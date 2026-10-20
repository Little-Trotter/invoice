package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.fixture.edit
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AP-IMP-6 — [PanacheNumberRangeRepository] itself had no direct test before this class:
 * [NumberCounterIT]/[PostgresNumberCounterIT] cover the counter column
 * ([PostgresNumberCounterRepository]) and the range's own CRUD/filter query builder
 * ([PanacheNumberRangeRepository.findAll]/`count`/`countInactive`/`where`) went unexercised.
 * V2__baseline_seed.sql always seeds exactly one active default range per subject (CUSTOMER,
 * ARTICLE, SELLER, INVOICE) — this fixture never fights that seed data: it only ever asserts on
 * its own distinctively-named `ZZ-AP-IMP-6-*` rows via the [filterText] parameter, the same
 * scoping convention [PanacheRepositoryCriteriaOverviewIT] and [RecipientB2bFieldValueRepositoryIT]
 * use for the same shared, uncleaned dev-services database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheNumberRangeRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: NumberRangeRepository

    private fun newRange(code: String, displayName: String, active: Boolean) = NumberRange().edit {
        this.code = code
        this.displayName = displayName
        this.subject = NumberRangeSubject.CUSTOMER
        this.startValue = 1
        this.prefixSource = PrefixSource.NONE
        this.counterWidth = 5
        this.continuity = Continuity.UNIQUE_ONLY
        this.isActive = active
        this.isDefault = false
    }

    private fun persist(range: NumberRange): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            em.persist(range)
            em.flush()
            range.id!!
        }

    @Test
    fun `findActive returns the seeded active default range for a subject`() {
        val active = repository.findActive(NumberRangeSubject.CUSTOMER)
        assertNotNull(active, "V2 seeds exactly one active CUSTOMER_DEFAULT range")
        assertTrue(active!!.isActive)
        assertEquals(NumberRangeSubject.CUSTOMER, active.subject)
    }

    @Test
    fun `findBySubject returns every range of that subject ordered by id, active or not`() {
        val inactiveId = persist(newRange("ZZ_IMP6_QUOTE", "ZZ-AP-IMP-6 quote", active = false).edit { subject = NumberRangeSubject.QUOTE })

        val quotes = repository.findBySubject(NumberRangeSubject.QUOTE)

        assertTrue(quotes.all { it.subject == NumberRangeSubject.QUOTE })
        assertTrue(quotes.any { it.code == "QUOTE_DEFAULT" }, "the seeded default quote range")
        assertTrue(quotes.any { it.id == inactiveId && !it.isActive }, "inactive ranges are included")
        assertEquals(quotes.map { it.id }.sortedBy { it }, quotes.map { it.id })
        assertTrue(repository.findBySubject(NumberRangeSubject.INVOICE).none { it.subject == NumberRangeSubject.QUOTE })
    }

    @Test
    fun `findById returns the persisted range and null for an unknown id`() {
        val id = persist(newRange("ZZ_IMP6_FINDBYID", "ZZ-AP-IMP-6 findById", active = false))

        val found = repository.findById(id)
        assertNotNull(found)
        assertEquals("ZZ-AP-IMP-6 findById", found!!.displayName)

        assertNull(repository.findById(-1L))
    }

    @Test
    fun `findAll and count apply the free-text filter across code and displayName, countInactive limits to inactive rows`() {
        persist(newRange("ZZ_IMP6_FILTER", "ZZ-AP-IMP-6 Filter Match", active = false))

        val unfiltered = repository.findAll(0, 100, null)
        assertTrue(unfiltered.size >= 5, "at least the four seeded ranges plus this fixture's own row")

        val byDisplayName = repository.findAll(0, 10, "zz-ap-imp-6 filter")
        assertEquals(listOf("ZZ_IMP6_FILTER"), byDisplayName.map { it.code })
        assertEquals(1L, repository.count("zz-ap-imp-6 filter"))

        val byCode = repository.findAll(0, 10, "ZZ_IMP6_FILTER")
        assertEquals(1, byCode.size)

        assertTrue(repository.findAll(0, 10, "no-such-range-marker").isEmpty())
        assertEquals(0L, repository.count("no-such-range-marker"))

        assertTrue(repository.countInactive(null) >= 1, "this fixture's own row is inactive")
        assertEquals(1L, repository.countInactive("zz-ap-imp-6 filter"))
        // The seeded default ranges are all active, so a filter that only ever matches an active
        // row must count zero inactive ones — proves countInactive's WHERE is-active clause, not
        // just its filter text, actually narrows the result.
        assertEquals(0L, repository.countInactive("customer_default"))
    }

    @Test
    fun `boundary - a blank (non-null) filterText takes the same unfiltered branch as null`() {
        assertEquals(repository.count(null), repository.count("   "))
        assertEquals(repository.findAll(0, 100, null).size, repository.findAll(0, 100, "   ").size)
        assertEquals(repository.countInactive(null), repository.countInactive("   "))
    }

    @Test
    fun `boundary - delete of an unknown id is a no-op`() {
        QuarkusTransaction.requiringNew().run { repository.delete(-987654321L) }
    }

    @Test
    fun `save inserts a new range then updates it via merge, delete removes it`() {
        val range = newRange("ZZ_IMP6_SAVE", "ZZ-AP-IMP-6 Save v1", active = false)

        val saved = QuarkusTransaction.requiringNew().call<NumberRange> { repository.save(range) }
        assertNotNull(saved.id)

        saved.edit { displayName = "ZZ-AP-IMP-6 Save v2" }
        QuarkusTransaction.requiringNew().run { repository.save(saved) }

        val reloaded = repository.findById(saved.id!!)
        assertEquals("ZZ-AP-IMP-6 Save v2", reloaded?.displayName)

        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
        // Fresh persistence context, not the ambient one `reloaded` above already cached this row
        // in: em.find() answers straight from the first-level cache for an id it has already
        // seen, without a DB round-trip, which would hide the delete entirely.
        em.clear()
        assertNull(repository.findById(saved.id!!))
    }
}
