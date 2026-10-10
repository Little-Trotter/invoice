package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.repository.B2bProfileFieldRepository
import de.littletrotter.shared.b2b.B2bTerm
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * real-Hibernate coverage for [PanacheB2bProfileFieldRepository]. No prior IT existed
 * for this class (baseline 87.5 %, 1/8 lines). Own `COV8-B2BF` profile-name token keeps cleanup
 * and assertions scoped to this test's own fixtures in the shared dev-services database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheB2bProfileFieldRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: B2bProfileFieldRepository

    @AfterEach
    @Transactional
    fun removeOwnRows() {
        em.createNativeQuery(
            "DELETE FROM b2b_profile_field WHERE profile_id IN (SELECT id FROM b2b_profile WHERE name LIKE 'COV8-B2BF%')",
        ).executeUpdate()
        em.createNativeQuery("DELETE FROM b2b_profile WHERE name LIKE 'COV8-B2BF%'").executeUpdate()
    }

    private fun newField(profile: B2bProfile, term: B2bTerm, sortOrder: Int): B2bProfileField =
        B2bProfileField().apply {
            this.profile = profile
            this.term = term
            businessDefinition = "COV8-B2BF definition"
            requirement = B2bFieldRequirement.OPTIONAL
            placement = B2bPlacement.INVOICE
            this.sortOrder = sortOrder
        }

    @Test
    @Transactional
    fun `happy path - save persists a field, it is found by id, and delete removes it`() {
        val profile = B2bProfile().apply { name = "COV8-B2BF Profile" }
        em.persist(profile)

        val saved = repository.save(newField(profile, B2bTerm.BT_11, 1))
        assertEquals(saved.id, repository.findById(saved.id!!)?.id)
        assertEquals(B2bTerm.BT_11, repository.findById(saved.id!!)?.term)

        repository.delete(saved.id!!)
        assertNull(repository.findById(saved.id!!))
    }

    @Test
    @Transactional
    fun `boundary - findByProfileId only returns the given profile's fields, ordered by sortOrder`() {
        val profile = B2bProfile().apply { name = "COV8-B2BF Ordered Profile" }
        val otherProfile = B2bProfile().apply { name = "COV8-B2BF Other Profile" }
        em.persist(profile)
        em.persist(otherProfile)

        val second = repository.save(newField(profile, B2bTerm.BT_13, 2))
        val first = repository.save(newField(profile, B2bTerm.BT_11, 1))
        repository.save(newField(otherProfile, B2bTerm.BT_11, 1))

        val fields = repository.findByProfileId(profile.id!!)
        assertEquals(listOf(first.id, second.id), fields.map { it.id })
    }

    @Test
    @Transactional
    fun `boundary - findByProfileId returns an empty list for a profile with no fields`() {
        val profile = B2bProfile().apply { name = "COV8-B2BF Empty Profile" }
        em.persist(profile)

        assertTrue(repository.findByProfileId(profile.id!!).isEmpty())
    }

    @Test
    @Transactional
    fun `boundary - findById returns null for an id that was never saved`() {
        assertNull(repository.findById(-1L))
    }
}
