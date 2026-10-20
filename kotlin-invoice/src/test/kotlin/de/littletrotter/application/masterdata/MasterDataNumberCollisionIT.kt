package de.littletrotter.application.masterdata

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.NumberCollisionException
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.VatId
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Collision handling when saving customer/article numbers
 * (`MasterDataNumberService.saveWithNumberCollisionCheck`), against real PostgreSQL. Each test
 * uses its own, distinct name so its `NAME_INITIALS` prefix never collides with another test in
 * the same circle (`CUSTOMER`) — no cleanup needed between tests.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class MasterDataNumberCollisionIT {

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    @Inject
    lateinit var masterDataNumberService: MasterDataNumberService

    @Inject
    lateinit var articleManagementService: ArticleManagementService

    @Inject
    lateinit var articleRepository: ArticleRepository

    @Inject
    lateinit var recipientRepository: de.littletrotter.domain.repository.RecipientRepository

    private fun recipient(name: String, customerNumber: String = ""): Recipient = aRecipient {
        this.name = name
        this.customerNumber = CustomerNumber.ofNullable(customerNumber)
        email = Email.ofNullable("$name@example.invalid".replace(" ", "").lowercase())
        customerType = CustomerType.CONSUMER // avoids requiring VatId/IBAN in Recipient.validate()
    }

    // ─── two concurrent creations get two different numbers ───

    @Test
    fun `two concurrent customer creations both succeed with different numbers`() {
        val name = "Nebenlaeufig Zehn GmbH"
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<String> {
                    ready.countDown()
                    go.await(5, TimeUnit.SECONDS)
                    QuarkusTransaction.requiringNew().call {
                        customerManagementService.save(recipient(name)).customerNumber!!.value
                    }
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            go.countDown()
            val numbers = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(2, numbers.toSet().size, "beide Anlagen müssen unterschiedliche Nummern erhalten: $numbers")
            numbers.forEach { assertTrue(it.isNotBlank()) }
        } finally {
            pool.shutdown()
        }
    }

    // ─── a deliberately typed collision: error, no auto-retry ───

    @Test
    fun `a deliberately typed, already-taken number is rejected, not silently replaced`() {
        val name = "Elf Getippt GmbH"
        val existing = QuarkusTransaction.requiringNew().call { customerManagementService.save(recipient(name)) }
        val takenNumber = existing.customerNumber!!.value
        assertTrue(takenNumber.isNotBlank())

        // The user deliberately types the same value into a second, new record -- not a proposal.
        val collidingRecipient = recipient(name, customerNumber = takenNumber)

        val ex = assertThrows(NumberCollisionException::class.java) {
            QuarkusTransaction.requiringNew().call {
                customerManagementService.save(collidingRecipient, proposedCustomerNumber = null)
            }
        }
        assertEquals(takenNumber, ex.attemptedValue)
    }

    @Test
    fun `editing a recipient into a number another recipient holds is rejected without a database error`() {
        val first = QuarkusTransaction.requiringNew().call { customerManagementService.save(recipient("Dreizehn Erster GmbH")) }
        val second = QuarkusTransaction.requiringNew().call { customerManagementService.save(recipient("Vierzehn Zweiter GmbH")) }
        val taken = first.customerNumber!!.value

        val edited = QuarkusTransaction.requiringNew().call { recipientRepository.findById(second.id!!)!! }
        edited.edit { this.customerNumber = CustomerNumber.ofNullable(taken) }
        assertThrows(de.littletrotter.domain.exception.DuplicateCustomerNumberException::class.java) {
            QuarkusTransaction.requiringNew().call { customerManagementService.save(edited) }
        }

        val reloaded = QuarkusTransaction.requiringNew().call { recipientRepository.findById(second.id!!) }
        assertEquals(second.customerNumber!!.value, reloaded!!.customerNumber!!.value)
    }

    @Test
    fun `concurrent edits of different recipients to the same number let exactly one win`() {
        val threads = 6
        val ids = (1..threads).map { i ->
            QuarkusTransaction.requiringNew().call { customerManagementService.save(recipient("Nebenlaeufig Kunde $i GmbH")).id!! }
        }
        val shared = "RACE-${System.nanoTime()}"
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results = ids.map { id ->
                pool.submit<Throwable?> {
                    start.await()
                    try {
                        QuarkusTransaction.requiringNew().run {
                            val edited = recipientRepository.findById(id)!!
                            edited.edit { this.customerNumber = CustomerNumber.ofNullable(shared) }
                            customerManagementService.save(edited)
                        }
                        null
                    } catch (ex: Exception) {
                        ex
                    }
                }
            }
            start.countDown()
            val failures = results.map { it.get(60, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(threads - 1, failures.size, "exactly one edit may succeed")
            failures.forEach {
                assertTrue(
                    it is de.littletrotter.domain.exception.DuplicateCustomerNumberException,
                    "unexpected failure type: $it",
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a typed, already-taken article number is rejected and no row is added`() {
        val existing = QuarkusTransaction.requiringNew().call { articleManagementService.save(anArticle { name = "Artikel Fuenfzehn A" }) }
        val taken = existing.articleNumber!!.value
        val before = QuarkusTransaction.requiringNew().call { articleRepository.findAllArticleNumbers().size }

        val ex = assertThrows(NumberCollisionException::class.java) {
            QuarkusTransaction.requiringNew().call {
                articleManagementService.save(
                    anArticle { name = "Artikel Fuenfzehn B"; articleNumber = ArticleNumber(taken) },
                )
            }
        }

        assertEquals(taken, ex.attemptedValue)
        assertEquals(before, QuarkusTransaction.requiringNew().call { articleRepository.findAllArticleNumbers().size })
    }

    // ─── an unmodified proposal collides: retried automatically until free ───

    @Test
    fun `a colliding unmodified proposal is silently retried and succeeds`() {
        val name = "Zwoelf Vorschlag GmbH"

        // "Someone else" already got a number -- which one exactly is irrelevant; the counter is
        // global across all customers (CounterScope.GLOBAL), not per prefix, so this
        // deliberately does not compare against a separately pre-peeked value (that would be
        // fragile against other tests counting concurrently in the same run).
        val someoneElse = QuarkusTransaction.requiringNew().call { customerManagementService.save(recipient(name)) }
        val takenNumber = someoneElse.customerNumber!!.value
        assertTrue(takenNumber.isNotBlank())

        // User C had accepted EXACTLY THIS VALUE as an unmodified proposal -- whether it
        // actually came from a `peek()` is irrelevant to collision handling: only
        // currentValue == proposedValue matters (this is what distinguishes an accepted proposal from a
        // typed collision, where the user types it themselves and currentValue != proposedValue).
        val recipientC = recipient(name, customerNumber = takenNumber)
        val savedC = QuarkusTransaction.requiringNew().call {
            customerManagementService.save(recipientC, proposedCustomerNumber = takenNumber)
        }

        assertNotEquals(takenNumber, savedC.customerNumber?.value, "die Kollision musste zu einer neuen Nummer führen")
        assertTrue(savedC.customerNumber?.value.orEmpty().isNotBlank())
    }

    // ─── the counter lags behind bulk-loaded rows: auto numbers must skip past them ───

    /** Trailing counter digits of [number] bumped by [by], padding width preserved — composes
     *  "what the range will hand out [by] allocations from now" without re-deriving prefix or
     *  format from range configuration. */
    private fun bumpNumber(number: String, by: Long): String {
        val digits = number.takeLastWhile { it.isDigit() }
        check(digits.isNotEmpty()) { "number '$number' carries no trailing counter digits" }
        val bumped = (digits.toLong() + by).toString().padStart(digits.length, '0')
        return number.dropLast(digits.length) + bumped
    }

    /**
     * The live defect: rows written past the service (bulk import, datagen,
     * hand-typed numbers matching the pattern) never move the counter, so the next untouched
     * proposal collides — and a peeked proposal consumes nothing, so the FIRST reassignment
     * re-derives the very number that just collided. With three stale rows the old single-retry
     * surfaced a raw duplicate-key error; the skip-to-free loop must walk past all of them.
     */
    @Test
    fun `an article auto number skips past bulk-loaded rows the counter never saw`() {
        val article = { name: String -> anArticle { this.name = name } }
        val proposal = QuarkusTransaction.requiringNew().call {
            masterDataNumberService.proposeArticle(article("L4 Auto Artikel").category)
        }
        assertNotNull(proposal, "seeded CATALOGUE_DEFAULT range must be active")

        // Three consecutive numbers from the proposal onward, persisted straight through the
        // repository the way a bulk import writes them — the counter never moves.
        QuarkusTransaction.requiringNew().run {
            (0L..2L).forEach { offset ->
                articleRepository.saveAndFlush(
                    article("L4 Import $offset").edit { articleNumber = ArticleNumber(bumpNumber(proposal!!, offset)) },
                )
            }
        }

        // The untouched-proposal UI flow: field shows the peeked value, save passes it along.
        val saved = QuarkusTransaction.requiringNew().call {
            articleManagementService.save(
                article("L4 Auto Artikel").edit { articleNumber = ArticleNumber(proposal!!) },
                proposedArticleNumber = proposal,
            )
        }

        assertEquals(bumpNumber(proposal!!, 3), saved.articleNumber?.value)
    }

    /** L4 browser-flow shape: two consecutive auto-numbered article creations (one with the untouched
     *  proposal in the field, one with the field left blank) each get a fresh peek and must persist
     *  under distinct numbers, with no collision surfaced. */
    @Test
    fun `two consecutive auto-numbered article creations get distinct numbers`() {
        val article = { name: String -> anArticle { this.name = name } }
        val proposal1 = QuarkusTransaction.requiringNew().call {
            masterDataNumberService.proposeArticle(article("L4 Seq A").category)
        }
        assertNotNull(proposal1)
        val first = QuarkusTransaction.requiringNew().call {
            articleManagementService.save(
                article("L4 Seq A").edit { articleNumber = ArticleNumber(proposal1!!) },
                proposedArticleNumber = proposal1,
            )
        }
        val proposal2 = QuarkusTransaction.requiringNew().call {
            masterDataNumberService.proposeArticle(article("L4 Seq B").category)
        }
        val second = QuarkusTransaction.requiringNew().call {
            articleManagementService.save(article("L4 Seq B"), proposedArticleNumber = proposal2)
        }
        assertNotNull(first.articleNumber)
        assertNotNull(second.articleNumber)
        assertNotEquals(first.articleNumber?.value, second.articleNumber?.value)
        assertEquals(proposal1, first.articleNumber?.value)
    }

    /** Customer numbers ride the same [MasterDataNumberService.saveWithNumberCollisionCheck]
     *  machinery, so the same stale-rows drift must heal there too. */
    @Test
    fun `a customer auto number skips past bulk-loaded rows the counter never saw`() {
        val name = "Luecke Vier GmbH"
        val proposal = QuarkusTransaction.requiringNew().call {
            masterDataNumberService.proposeCustomer(name)
        }
        assertNotNull(proposal, "seeded CUSTOMER_DEFAULT range must be active")

        QuarkusTransaction.requiringNew().run {
            (0L..2L).forEach { offset ->
                // Only the colliding NUMBER matters; distinct names keep the fixture emails apart.
                recipientRepository.saveAndFlush(
                    recipient("Luecke Vier Import $offset", customerNumber = bumpNumber(proposal!!, offset)),
                )
            }
        }

        val saved = QuarkusTransaction.requiringNew().call {
            customerManagementService.save(
                recipient(name, customerNumber = proposal!!),
                proposedCustomerNumber = proposal,
            )
        }

        assertEquals(bumpNumber(proposal!!, 3), saved.customerNumber?.value)
    }

    // ─── a short-hash collision is rejected by the unique index ───

    @Test
    fun `two articles forced to the same shortHash are rejected by the unique index`() {
        val sharedHash = "SAMEHASH01"

        QuarkusTransaction.requiringNew().run {
            val first = anArticle { name = "Artikel Eins"; shortHash = sharedHash }
            articleRepository.saveAndFlush(first)
        }

        val ex = assertThrows(Exception::class.java) {
            QuarkusTransaction.requiringNew().run {
                val second = anArticle { name = "Artikel Zwei"; shortHash = sharedHash }
                articleRepository.saveAndFlush(second)
            }
        }
        // Postgres/Hibernate report the violation as a (nested) ConstraintViolationException;
        // this test only checks that it is rejected at all -- the exact exception class is not
        // a contract anyone programmatically catches here.
        assertNotNull(ex)
    }

    @Test
    fun `sequential customer creations with proposed customer number increment counter consecutively`() {
        val name1 = "Seq Kunde 1 GmbH"
        val name2 = "Seq Kunde 2 GmbH"
        val name3 = "Seq Kunde 3 GmbH"

        val prop1 = QuarkusTransaction.requiringNew().call { masterDataNumberService.proposeCustomer(name1) }
        assertNotNull(prop1)

        val saved1 = QuarkusTransaction.requiringNew().call {
            customerManagementService.save(recipient(name1, customerNumber = prop1!!), proposedCustomerNumber = prop1)
        }
        assertEquals(prop1, saved1.customerNumber?.value)

        val prop2 = QuarkusTransaction.requiringNew().call { masterDataNumberService.proposeCustomer(name2) }
        assertNotNull(prop2)
        assertEquals(bumpNumber(prop1!!, 1), prop2)

        val saved2 = QuarkusTransaction.requiringNew().call {
            customerManagementService.save(recipient(name2, customerNumber = prop2!!), proposedCustomerNumber = prop2)
        }
        assertEquals(prop2, saved2.customerNumber?.value)

        val prop3 = QuarkusTransaction.requiringNew().call { masterDataNumberService.proposeCustomer(name3) }
        assertNotNull(prop3)
        assertEquals(bumpNumber(prop1!!, 2), prop3)

        val saved3 = QuarkusTransaction.requiringNew().call {
            customerManagementService.save(recipient(name3, customerNumber = prop3!!), proposedCustomerNumber = prop3)
        }
        assertEquals(prop3, saved3.customerNumber?.value)
    }

    // ─── recipient.vat_id widened to VARCHAR(64) ───

    /**
     * `recipient.vat_id` was raised from `VARCHAR(30)` to `VARCHAR(64)` (`V1__baseline_schema
     * .sql`) so official foreign VAT-id formats are never clamped.
     * `VatId` accepts [VatIdValidator.Violation.UNKNOWN_PREFIX] (a syntactically complete
     * 2-letter prefix `VatIdValidator` does not recognise, e.g. a Japanese buyer's own tax id):
     * only a *known* prefix with the wrong shape, or a value over the column width, is rejected —
     * matching the pre-VatId behaviour, where this field was a bare `String?` with only a
     * `@Size(max = 64)` bound and no format check at all. This is the narrowed-but-still-valid
     * form of the original "unstructured value round-trips" test: a real round trip against
     * Postgres proves the column itself was widened, not merely the Kotlin entity.
     */
    @Test
    fun `an unrecognised-prefix VAT id well past 30 characters persists in full (unknown prefix accepted)`() {
        val longVatId = "JP" + "1".repeat(58) // 60 characters; JP has no recognised VatIdValidator format
        assertEquals(60, longVatId.length)
        val saved = QuarkusTransaction.requiringNew().call {
            customerManagementService.save(
                recipient("Foreign Recipient Wave E").edit { vatId = VatId(longVatId) },
            )
        }

        val reloaded = QuarkusTransaction.requiringNew().call { recipientRepository.findById(saved.id!!) }

        assertNotNull(reloaded)
        assertEquals(longVatId, reloaded!!.vatId?.value, "the full 60-character value must survive the round trip unclamped")
    }
}
