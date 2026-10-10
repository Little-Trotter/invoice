package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.exception.DefaultPaymentTermAlreadySetException
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.domain.value.Email
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.math.BigDecimal
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * AP- [PanachePaymentTermRepository] had almost no direct test before this class
 * (10 % branch coverage). V2__baseline_seed.sql seeds four active rows, one of them
 * `is_default = TRUE` ("14 Tage netto") — a partial unique index
 * (`uq_payment_term_single_default`) allows only one default row, so this fixture never creates
 * its own default, only asserts against the seeded one. It only otherwise asserts on its own
 * distinctively-marked `ZZ-AP--*` rows, the same scoping convention
 * [PanacheNumberRangeRepositoryIT] uses for the same shared, uncleaned database. Unlike
 * [PanacheMileageRateRepositoryIT], the free-text filter here is on `name`/`description`
 * (both `String`), not the `Country`-converted-to-Integer column, so it does not hit the
 * `lower()`-on-Integer Hibernate bug documented there.
 *
 * `PaymentTerm.name` is now [PaymentTerm.nameGroup]
 * (`payment_term.name_group_id -> localized_text_group -> localized_text`), resolved for the
 * fallback (German) language by [PanachePaymentTermRepository]'s own native-SQL join
 * (`NAME_JOIN_SQL`); this class's fixtures/cleanup go through the real `language` row instead of
 * a plain column write.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanachePaymentTermRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: PaymentTermRepository

    private fun germanLanguage(): Language = em.find(Language::class.java, 1)!!
    private fun englishLanguage(): Language = em.find(Language::class.java, 2)!!

    private fun newTerm(
        name: String,
        description: String?,
        dueInDays: Int,
        active: Boolean,
    ) = PaymentTerm().apply {
        setName(germanLanguage(), name)
        this.description = description
        this.dueInDays = dueInDays
        this.discountPercent = DiscountPercent(BigDecimal.ZERO)
        this.isDefault = false
        this.isActive = active
    }

    private fun persist(term: PaymentTerm): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            em.persist(term)
            em.flush()
            term.id!!
        }

    /**
     * Removes this class's own rows again. Not optional politeness: sibling ITs assert *global*
     * invariants over this same shared, never-cleaned table (see TravelRateMigrationIT /
     * PaymentTermMigrationIT), so a row left behind here fails one of those instead of this
     * class. Scoped strictly to the ZZ-AP- marker so no other fixture's data is touched.
     *
     * native SQL, not a JPQL `DELETE FROM PaymentTerm`: `e.name` is no longer an entity
     * attribute path. `payment_term` rows are deleted first (nothing else references them from
     * this fixture), then the now-orphaned `localized_text_group` rows — `ON DELETE CASCADE`
     * takes their `localized_text` rows with them. `orphanRemoval`/`cascade = ALL` on
     * [PaymentTerm.nameGroup] only fires through the JPA entity lifecycle, never for a raw native
     * `DELETE`, so this cleanup step is not optional the way it would be for `em.remove(entity)`.
     */
    @AfterEach
    fun removeOwnRows() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM sender_profile WHERE invoice_prefix = 'ZZAPCOV4'").executeUpdate()
            val groupIds = em.createNativeQuery(
                "SELECT DISTINCT p.name_group_id FROM payment_term p " +
                    "JOIN localized_text lt ON lt.group_id = p.name_group_id " +
                    "WHERE lt.value LIKE 'ZZ-AP-COV-4%'",
            ).resultList.map { (it as Number).toLong() }
            if (groupIds.isNotEmpty()) {
                em.createNativeQuery("DELETE FROM payment_term WHERE name_group_id IN :ids").setParameter("ids", groupIds).executeUpdate()
                em.createNativeQuery("DELETE FROM localized_text_group WHERE id IN :ids").setParameter("ids", groupIds).executeUpdate()
            }
        }
    }

    @Test
    fun `findAll and count filter across name and description, resolveSortExpr falls back for an unknown sort field`() {
        val id = persist(
            newTerm("ZZ-AP-COV-4 Filterzahlungsziel", "Beschreibung fuer AP-COV-4", dueInDays = 45, active = true),
        )

        val unfiltered = repository.findAll(filterText = null)
        assertTrue(unfiltered.size >= 5, "the four seeded terms plus this fixture's own row")

        val byName = repository.findAll(0, 10, "zz-ap-cov-4 filterzahlungsziel")
        assertEquals(listOf(id), byName.map { it.id })
        assertEquals(1L, repository.count("zz-ap-cov-4 filterzahlungsziel"))

        // The same row also matches via description — proves the OR clause's second operand,
        // not just the first, actually participates in the filter.
        val byDescription = repository.findAll(0, 10, "beschreibung fuer ap-cov-4")
        assertEquals(listOf(id), byDescription.map { it.id })

        assertTrue(repository.findAll(0, 10, "no-such-payment-term-marker").isEmpty())
        assertEquals(0L, repository.count("no-such-payment-term-marker"))
        assertTrue(repository.count(null) >= 1L, "count(null) must take the unfiltered WHERE branch")
        // A blank (non-null) filterText must take the same unfiltered branch as null — a
        // separate sub-branch of `isNullOrBlank()` from the null check above.
        assertEquals(repository.count(null), repository.count("   "))
        assertEquals(repository.findAll(0, 100, null).size, repository.findAll(0, 100, "   ").size)
        assertEquals(repository.countInactive(null), repository.countInactive("   "))

        // sortField = "name" is in the allowlist, an unknown one falls back to the default —
        // both sides of resolveSortExpr's `allowed[sortField] ?: default` elvis. Filter on the
        // full, test-unique marker (not just the shared "zz-ap-cov-4" prefix other tests in this
        // class also use), so this stays exactly 1 row regardless of JUnit execution order.
        val knownSort = repository.findAll(0, 10, "zz-ap-cov-4 filterzahlungsziel", sortField = "dueInDays", sortDir = SortDirection.DESC)
        assertEquals(1, knownSort.size)
        val unknownSort = repository.findAll(0, 10, "zz-ap-cov-4 filterzahlungsziel", sortField = "no-such-field", sortDir = SortDirection.ASC)
        assertEquals(1, unknownSort.size)
    }

    @Test
    fun `sorting by name orders on the resolved fallback-language text`() {
        persist(newTerm("ZZ-AP-COV-4-SORT B-Ziel", null, dueInDays = 1, active = true))
        persist(newTerm("ZZ-AP-COV-4-SORT A-Ziel", null, dueInDays = 2, active = true))

        val ascending = repository.findAll(0, 10, "zz-ap-cov-4-sort", sortField = "name", sortDir = SortDirection.ASC)
        assertEquals(listOf("ZZ-AP-COV-4-SORT A-Ziel", "ZZ-AP-COV-4-SORT B-Ziel"), ascending.map { it.nameFor("de") })

        val descending = repository.findAll(0, 10, "zz-ap-cov-4-sort", sortField = "name", sortDir = SortDirection.DESC)
        assertEquals(listOf("ZZ-AP-COV-4-SORT B-Ziel", "ZZ-AP-COV-4-SORT A-Ziel"), descending.map { it.nameFor("de") })
    }

    @Test
    fun `countInactive limits to inactive rows and combines with the filter`() {
        val before = repository.countInactive(null)
        persist(newTerm("ZZ-AP-COV-4 Inaktives Ziel", null, dueInDays = 10, active = false))

        assertEquals(before + 1, repository.countInactive(null))
        assertEquals(1L, repository.countInactive("zz-ap-cov-4 inaktives"))
        // The seeded default term is active, so a filter that only ever matches an active row
        // must count zero inactive ones.
        assertEquals(0L, repository.countInactive("14 tage netto"))
    }

    @Test
    fun `findDefault and findDefaults return the single seeded default row`() {
        val default = repository.findDefault()
        assertNotNull(default, "V2 seeds exactly one is_default = TRUE row")
        assertTrue(default!!.isDefault)

        val defaults = repository.findDefaults()
        assertEquals(1, defaults.size)
        assertEquals(default.id, defaults[0].id)
    }

    @Test
    fun `isInUse is false and usageCounts omits an unreferenced fixture row`() {
        val id = persist(newTerm("ZZ-AP-COV-4 Unbenutzt", null, dueInDays = 20, active = true))

        assertTrue(!repository.isInUse(id))
        assertTrue(!repository.usageCounts().containsKey(id))
    }

    @Test
    fun `boundary - isInUse is true and usageCounts includes a term referenced as a sender's default`() {
        val id = persist(newTerm("ZZ-AP-COV-4 Referenziert", null, dueInDays = 14, active = true))

        QuarkusTransaction.requiringNew().run {
            val sender = SenderProfile().apply {
                name = "ZZ-AP-COV-4 Sender"
                invoicePrefix = "ZZAPCOV4"
                taxNumber = TaxNumber("11/616/35840")
                vatId = VatId("DE123456789")
                email = Email.ofNullable("zz-ap-cov-4@example.org")
                defaultPaymentTerm = em.find(PaymentTerm::class.java, id)
            }
            em.persist(sender)
        }

        assertTrue(repository.isInUse(id))
        assertEquals(1L, repository.usageCounts()[id])
    }

    @Test
    fun `save inserts a new term then updates it via merge, delete removes it`() {
        val term = newTerm("ZZ-AP-COV-4 Save v1", null, dueInDays = 5, active = false)

        val saved = QuarkusTransaction.requiringNew().call<PaymentTerm> { repository.save(term) }
        assertNotNull(saved.id)

        QuarkusTransaction.requiringNew().run {
            val managed = repository.findById(saved.id!!)!!
            managed.setName(germanLanguage(), "ZZ-AP-COV-4 Save v2")
            repository.save(managed)
        }

        val reloaded = repository.findById(saved.id!!)
        assertEquals("ZZ-AP-COV-4 Save v2", reloaded?.nameFor("de"))

        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
        em.clear()
        assertNull(repository.findById(saved.id!!))

        // Deleting an id that is already gone must be a no-op, not an error — exercises the
        // `em.find(...)?.let { ... }` null-safety branch's "not found" side.
        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
    }

    @Test
    fun `boundary - saving a second isDefault term translates the partial unique index violation into DefaultPaymentTermAlreadySetException`() {
        // V2 seeds exactly one is_default = TRUE row ("14 Tage netto") — a second one collides
        // with uq_payment_term_single_default, the whole point of ST-B4.
        val secondDefault = newTerm("ZZ-AP-COV-4 Zweiter Standard", null, dueInDays = 30, active = true).apply { isDefault = true }

        val thrown = assertThrows(DefaultPaymentTermAlreadySetException::class.java) {
            QuarkusTransaction.requiringNew().run { repository.save(secondDefault) }
        }
        assertNotNull(thrown.cause, "the unique-constraint violation must be chained as the cause")
    }

    @Test
    fun `boundary - the same duplicate-default conflict falls back to the raw id, then the literal question mark, when no German name was ever set`() {
        // nameFor("de") null (only an English entry exists) forces the exception message's own
        // `term.nameFor("de") ?: term.id?.toString() ?: "?"` fallback chain past its first arm.
        // PaymentTerm.id is @GeneratedValue(IDENTITY): it is only ever assigned by a successful
        // INSERT, so a term whose insert fails on the unique index still carries a null id here —
        // the chain's second arm is unreachable through this path and always resolves to "?".
        val secondDefault = PaymentTerm().apply {
            setName(englishLanguage(), "ZZ-AP-COV-4 No German Name")
            dueInDays = 30
            discountPercent = DiscountPercent(BigDecimal.ZERO)
            isDefault = true
            isActive = true
        }

        val thrown = assertThrows(DefaultPaymentTermAlreadySetException::class.java) {
            QuarkusTransaction.requiringNew().run { repository.save(secondDefault) }
        }
        assertNotNull(thrown.cause)
    }

    private fun defaultCount(): Long = QuarkusTransaction.requiringNew().call<Long> {
        (em.createNativeQuery("SELECT COUNT(*) FROM payment_term WHERE is_default = TRUE").singleResult as Number).toLong()
    }

    private fun restoreSeededDefault(seededId: Long) = QuarkusTransaction.requiringNew().run {
        em.createNativeQuery("UPDATE payment_term SET is_default = FALSE WHERE is_default = TRUE").executeUpdate()
        em.createNativeQuery("UPDATE payment_term SET is_default = TRUE WHERE id = :id").setParameter("id", seededId).executeUpdate()
    }

    @Test
    fun `releaseDefaultsExcept lets a new default be saved without violating the single-default index`() {
        val seededId = repository.findDefault()!!.id!!
        val newDefault = newTerm("ZZ-AP-COV-4 Neuer Standard", null, dueInDays = 45, active = true).apply { isDefault = true }
        try {
            QuarkusTransaction.requiringNew().run {
                repository.releaseDefaultsExcept(newDefault.id)
                repository.save(newDefault)
            }
            assertEquals(1L, defaultCount())
            assertEquals(newDefault.id, repository.findDefault()!!.id)
        } finally {
            restoreSeededDefault(seededId)
        }
        assertEquals(seededId, repository.findDefault()!!.id)
    }

    @Test
    fun `releaseDefaultsExcept keeps the given term as default`() {
        val seededId = repository.findDefault()!!.id!!
        QuarkusTransaction.requiringNew().run { repository.releaseDefaultsExcept(seededId) }
        assertEquals(seededId, repository.findDefault()!!.id)
        assertEquals(1L, defaultCount())
    }

    @Test
    fun `concurrent promotions of two different default terms leave exactly one default`() {
        val seededId = repository.findDefault()!!.id!!
        val terms = listOf("A", "B").map { newTerm("ZZ-AP-COV-4 Parallel $it", null, dueInDays = 20, active = true).apply { isDefault = true } }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = terms.map { term ->
                pool.submit {
                    start.await()
                    QuarkusTransaction.requiringNew().run {
                        repository.releaseDefaultsExcept(term.id)
                        repository.save(term)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1L, defaultCount())
        } finally {
            pool.shutdownNow()
            restoreSeededDefault(seededId)
        }
    }
}
