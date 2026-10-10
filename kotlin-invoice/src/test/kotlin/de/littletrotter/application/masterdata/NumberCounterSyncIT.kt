package de.littletrotter.application.masterdata

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.reference.NumberRangeService
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.NumberRangeSyncNotSupportedException
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

/**
 * "Synchronize number counters to existing data" admin maintenance action against real PostgreSQL: seeds rows past the counter directly through the repository (the same bulk-import/datagen shape `MasterDataNumberCollisionIT`'s counter-lag tests use), runs the sync, asserts the counter lands exactly on the highest persisted suffix, and then proves a real service save allocates the very next number without colliding — the sync's whole point is not needing the per-save skip-to-free retry to walk there one number at a time afterward.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class NumberCounterSyncIT {

    @Inject
    lateinit var masterDataNumberService: MasterDataNumberService

    @Inject
    lateinit var numberRangeService: NumberRangeService

    @Inject
    lateinit var numberCounters: NumberCounterRepository

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var articleManagementService: ArticleManagementService

    @Inject
    lateinit var articleRepository: ArticleRepository

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var senderProfileRepository: SenderProfileRepository

    /** Trailing counter digits of [number] bumped by [by], padding width preserved — same helper
     *  as `MasterDataNumberCollisionIT.bumpNumber`, duplicated locally per that file's own
     *  precedent (no shared test util for this exists yet). */
    private fun bumpNumber(number: String, by: Long): String {
        val digits = number.takeLastWhile { it.isDigit() }
        check(digits.isNotEmpty()) { "number '$number' carries no trailing counter digits" }
        val bumped = (digits.toLong() + by).toString().padStart(digits.length, '0')
        return number.dropLast(digits.length) + bumped
    }

    /** The value the production [de.littletrotter.domain.reference.NumberSuffixParser] would
     *  also parse out of [number] — computed independently here (not by calling the parser) so
     *  this test doesn't validate the parser against itself; the parser has its own unit test. */
    private fun expectedSuffixOf(number: String): Long = number.takeLastWhile { it.isDigit() }.toLong()

    private fun recipient(name: String, customerNumber: String = ""): Recipient = Recipient().apply {
        this.name = name
        this.customerNumber = CustomerNumber.ofNullable(customerNumber)
        email = Email.ofNullable("$name@example.invalid".replace(" ", "").lowercase())
        customerType = CustomerType.CONSUMER
    }

    /** `invoice_prefix` carries its own unique index (`uq_sender_profile_invoice_prefix`,
     *  independent of `seller_number`) AND a 10-character column limit
     *  (`@Size(max = 10)`/`@Column(length = 10)` on [SenderProfile.invoicePrefix]) -- every seller
     *  profile created in this test needs its own short, distinct [invoicePrefix]. */
    private fun seller(name: String, invoicePrefix: String, sellerNumber: String? = null): SenderProfile = SenderProfile().apply {
        this.name = name
        this.invoicePrefix = invoicePrefix
        this.sellerNumber = sellerNumber
        email = Email.ofNullable("${name.replace(" ", "").lowercase()}@example.invalid")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban.ofNullable("DE89370400440532013000")
    }

    /**
     * Shared-DB isolation for the exact-value assertions below: every IT class in the
     * failsafe run writes into the same PostgreSQL container, and classes running earlier in the
     * alphabetical class order leave rows with arbitrary trailing-digit numbers behind, persisted
     * straight through the repositories without ever moving a counter — `StatisticsFilterIT`'s
     * `CUST-YRT52`/`CUST-YRO52`/`CUST-ATK52` recipients were the concrete rows that broke the
     * customer test (expected 33, sync landed on their 52). The sync under
     * test maxes over ALL persisted numbers, so without a baseline this test's "my three bulk
     * rows are the maximum" premise silently depends on run order. Aligning the counter with the pre-existing global
     * max first guarantees the freshly peeked proposal — and therefore the rows seeded from it —
     * really is the maximum, turning every assertion into a pure delta check against this test's
     * own rows. The production code under test is not validated against itself by this: a sync
     * or parser that mis-computed the max would shift the second, asserted sync's result off the
     * independently computed `expectedSuffixOf` value and still fail the test.
     */
    private fun baselineCounterToPreexistingRows(sync: () -> Unit) {
        QuarkusTransaction.requiringNew().run { sync() }
    }

    // ─── ARTICLE ────────────────────────────────────────────────────────────────

    @Test
    fun `sync raises the article counter to the max of bulk-loaded rows and a save then allocates collision-free`() {
        baselineCounterToPreexistingRows { masterDataNumberService.syncArticleCounter() }
        val proposal = QuarkusTransaction.requiringNew().call {
            masterDataNumberService.proposeArticle(Article().apply { name = "Sync Artikel Vorschlag" }.category)
        }
        assertNotNull(proposal, "seeded CATALOGUE_DEFAULT range must be active")

        // Three consecutive numbers persisted straight through the repository -- the counter
        // never moves, exactly like a bulk import / datagen run.
        QuarkusTransaction.requiringNew().run {
            (0L..2L).forEach { offset ->
                articleRepository.saveAndFlush(
                    Article().apply {
                        name = "Sync Artikel Import $offset"
                        articleNumber = ArticleNumber(bumpNumber(proposal!!, offset))
                    },
                )
            }
        }
        val expectedMax = expectedSuffixOf(bumpNumber(proposal!!, 2))

        val result = QuarkusTransaction.requiringNew().call { masterDataNumberService.syncArticleCounter() }

        assertTrue(result.changed)
        assertEquals(expectedMax, result.newValue)
        assertEquals(expectedMax, numberCounters.currentValue(NumberRangeSubject.CATALOGUE))

        // A real save, with no explicit number, must now allocate straight past the imported
        // rows -- no skip-to-free retry needed because the counter already reflects them.
        val saved = QuarkusTransaction.requiringNew().call {
            articleManagementService.save(Article().apply { name = "Sync Artikel Nach Sync" })
        }
        assertEquals(bumpNumber(proposal, 3), saved.articleNumber?.value)

        // Re-running the sync afterward changes nothing further -- "bereits synchron".
        val secondRun = QuarkusTransaction.requiringNew().call { masterDataNumberService.syncArticleCounter() }
        assertFalse(secondRun.changed)
        assertEquals(expectedSuffixOf(saved.articleNumber!!.value), numberCounters.currentValue(NumberRangeSubject.CATALOGUE))
    }

    // ─── CUSTOMER ───────────────────────────────────────────────────────────────

    @Test
    fun `sync raises the customer counter to the max of bulk-loaded rows and a save then allocates collision-free`() {
        baselineCounterToPreexistingRows { masterDataNumberService.syncCustomerCounter() }
        val proposal = QuarkusTransaction.requiringNew().call {
            masterDataNumberService.proposeCustomer("Sync Kunde Vorschlag")
        }
        assertNotNull(proposal, "seeded CUSTOMER_DEFAULT range must be active")

        QuarkusTransaction.requiringNew().run {
            (0L..2L).forEach { offset ->
                recipientRepository.saveAndFlush(
                    recipient("Sync Kunde Import $offset", customerNumber = bumpNumber(proposal!!, offset)),
                )
            }
        }
        val expectedMax = expectedSuffixOf(bumpNumber(proposal!!, 2))

        val result = QuarkusTransaction.requiringNew().call { masterDataNumberService.syncCustomerCounter() }

        assertTrue(result.changed)
        assertEquals(expectedMax, result.newValue)
        assertEquals(expectedMax, numberCounters.currentValue(NumberRangeSubject.CUSTOMER))

        val saved = QuarkusTransaction.requiringNew().call {
            customerManagementService.save(recipient("Sync Kunde Nach Sync"))
        }
        assertEquals(bumpNumber(proposal, 3), saved.customerNumber?.value)
    }

    // ─── SELLER ─────────────────────────────────────────────────────────────────

    @Test
    fun `sync raises the seller counter to the max of bulk-loaded rows and a save then allocates collision-free`() {
        baselineCounterToPreexistingRows { masterDataNumberService.syncSellerCounter() }
        val proposal = QuarkusTransaction.requiringNew().call {
            masterDataNumberService.proposeSeller(seller("Sync Anbieter Vorschlag", invoicePrefix = "SYNCV-").name)
        }
        assertNotNull(proposal, "seeded SELLER_DEFAULT range must be active")

        QuarkusTransaction.requiringNew().run {
            (0L..1L).forEach { offset ->
                senderProfileRepository.saveAndFlush(
                    seller(
                        "Sync Anbieter Import $offset",
                        invoicePrefix = "SYNCI$offset-",
                        sellerNumber = bumpNumber(proposal!!, offset),
                    ),
                )
            }
        }
        val expectedMax = expectedSuffixOf(bumpNumber(proposal!!, 1))

        val result = QuarkusTransaction.requiringNew().call { masterDataNumberService.syncSellerCounter() }

        assertTrue(result.changed)
        assertEquals(expectedMax, result.newValue)
        assertEquals(expectedMax, numberCounters.currentValue(NumberRangeSubject.SELLER))

        val saved = QuarkusTransaction.requiringNew().call {
            sellerProfileService.save(seller("Sync Anbieter Nach Sync", invoicePrefix = "SYNCN-"), defaults = SellerDefaultSelections())
        }
        assertEquals(bumpNumber(proposal, 2), saved.sellerNumber)
    }

    // ─── refuses the GAPLESS invoice range ───────────────────────────────────────

    /**
     * [MasterDataNumberService] never exposes an INVOICE wrapper (invoice numbers allocate in the
     * same transaction as the document, so they cannot drift the way customer/article/seller
     * numbers can — see [syncCustomerCounter]/[syncArticleCounter]/[syncSellerCounter]'s KDoc).
     * This exercises the underlying [NumberRangeService.syncCounterTo] directly, against the real
     * seeded `INVOICE_GAPLESS` range, to prove the refusal holds at the layer that owns it, not
     * only in the pure unit test against fakes.
     */
    @Test
    fun `sync refuses the seeded GAPLESS invoice range instead of silently doing nothing`() {
        assertThrows(NumberRangeSyncNotSupportedException::class.java) {
            QuarkusTransaction.requiringNew().call {
                numberRangeService.syncCounterTo(NumberRangeSubject.INVOICE, 99L)
            }
        }
    }
}
