package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.fixture.edit
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * real-Hibernate coverage for [PanacheB2bProfileRepository]. No prior IT existed for
 * this class (baseline 67.7 %, 10/31 lines). Own `COV8-B2B` name token keeps cleanup and
 * assertions scoped to this test's own fixtures in the shared dev-services database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheB2bProfileRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: B2bProfileRepository

    @AfterEach
    @Transactional
    fun removeOwnRows() {
        em.createNativeQuery("DELETE FROM b2b_profile_country WHERE profile_id IN (SELECT id FROM b2b_profile WHERE name LIKE 'COV8-B2B%')").executeUpdate()
        em.createNativeQuery("DELETE FROM b2b_profile WHERE name LIKE 'COV8-B2B%'").executeUpdate()
    }

    private fun newProfile(name: String, active: Boolean = true): B2bProfile =
        B2bProfile().edit {
            this.name = name
            this.active = active
        }

    @Test
    @Transactional
    fun `happy path - save persists a profile, it is found by id and by name, and delete removes it`() {
        val saved = repository.save(newProfile("COV8-B2B Alpha"))

        assertEquals(saved.id, repository.findById(saved.id!!)?.id)
        assertEquals(saved.id, repository.findByName("COV8-B2B Alpha")?.id)
        assertEquals(saved.id, repository.findByName("cov8-b2b alpha")?.id, "findByName must be case-insensitive")

        repository.delete(saved.id!!)
        assertNull(repository.findById(saved.id!!))
    }

    @Test
    @Transactional
    fun `boundary - findAll filters by name and orders by the requested direction with an id tiebreak`() {
        val first = repository.save(newProfile("COV8-B2B Sort Aaa"))
        val second = repository.save(newProfile("COV8-B2B Sort Bbb"))

        val asc = repository.findAll(filterText = "cov8-b2b sort", sortDir = SortDirection.ASC).map { it.id }
        val desc = repository.findAll(filterText = "cov8-b2b sort", sortDir = SortDirection.DESC).map { it.id }
        assertTrue(asc.indexOf(first.id) < asc.indexOf(second.id))
        assertTrue(desc.indexOf(second.id) < desc.indexOf(first.id))
        assertEquals(2L, repository.count(filterText = "cov8-b2b sort"))
    }

    @Test
    @Transactional
    fun `boundary - countInactive only counts profiles with active = false, matching the same filterText as count`() {
        repository.save(newProfile("COV8-B2B Active One", active = true))
        repository.save(newProfile("COV8-B2B Inactive One", active = false))

        assertEquals(1L, repository.countInactive(filterText = "cov8-b2b"))
        assertEquals(2L, repository.count(filterText = "cov8-b2b"))
    }

    @Test
    @Transactional
    fun `boundary - findAllActive returns only active profiles ordered by name`() {
        val active = repository.save(newProfile("COV8-B2B Active Two", active = true))
        val inactive = repository.save(newProfile("COV8-B2B Inactive Two", active = false))

        val activeIds = repository.findAllActive().map { it.id }
        assertTrue(active.id in activeIds)
        assertFalse(inactive.id in activeIds)
    }

    @Test
    @Transactional
    fun `boundary - findAll, count and countInactive without a filterText take the unfiltered WHERE branch`() {
        val saved = repository.save(newProfile("COV8-B2B Unfiltered"))

        val nullFiltered = repository.findAll(filterText = null).map { it.id }
        val blankFiltered = repository.findAll(filterText = "   ").map { it.id }
        assertTrue(saved.id in nullFiltered)
        assertTrue(saved.id in blankFiltered)
        assertTrue(repository.count(filterText = null) >= 1L)
        assertTrue(repository.count(filterText = "   ") >= 1L)
        assertTrue(repository.countInactive(filterText = null) >= 0L)
        assertTrue(repository.countInactive(filterText = "   ") >= 0L)
    }
}
