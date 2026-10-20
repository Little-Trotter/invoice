package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.fixture.edit
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
 * Profile fields are defined and removed through [B2bProfile] and read back through the profile
 * repository, against real Hibernate. Own `COV8-B2BF` profile-name token keeps cleanup and
 * assertions scoped to this test's own fixtures in the shared dev-services database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class B2bProfileFieldPersistenceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: B2bProfileRepository

    @AfterEach
    @Transactional
    fun removeOwnRows() {
        em.createNativeQuery(
            "DELETE FROM b2b_profile_field WHERE profile_id IN (SELECT id FROM b2b_profile WHERE name LIKE 'COV8-B2BF%')",
        ).executeUpdate()
        em.createNativeQuery("DELETE FROM b2b_profile WHERE name LIKE 'COV8-B2BF%'").executeUpdate()
    }

    private fun profile(name: String): B2bProfile = repository.save(B2bProfile().edit { this.name = name })

    private fun newField(profile: B2bProfile, term: B2bTerm, sortOrder: Int): B2bProfileField =
        B2bProfileField().edit {
            this.profile = profile
            this.term = term
            businessDefinition = "COV8-B2BF definition"
            requirement = B2bFieldRequirement.OPTIONAL
            placement = B2bPlacement.INVOICE
            this.sortOrder = sortOrder
        }

    @Test
    @Transactional
    fun `happy path - a defined field is found by id and by owner, and removing it deletes it`() {
        val profile = profile("COV8-B2BF Profile")
        val saved = profile.defineField(newField(profile, B2bTerm.BT_11, 1))
        repository.saveAndFlush(profile)

        assertEquals(B2bTerm.BT_11, repository.findFieldById(saved.id!!)?.term)
        assertEquals(profile.id, repository.findByFieldId(saved.id!!)?.id)

        assertTrue(profile.removeField(saved.id!!))
        repository.saveAndFlush(profile)
        em.clear()
        assertNull(repository.findFieldById(saved.id!!))
    }

    @Test
    @Transactional
    fun `boundary - findFieldsByProfileIds groups per profile and orders by sortOrder`() {
        val profile = profile("COV8-B2BF Ordered Profile")
        val otherProfile = profile("COV8-B2BF Other Profile")
        val second = profile.defineField(newField(profile, B2bTerm.BT_13, 2))
        val first = profile.defineField(newField(profile, B2bTerm.BT_11, 1))
        otherProfile.defineField(newField(otherProfile, B2bTerm.BT_11, 1))
        repository.saveAndFlush(profile)
        repository.saveAndFlush(otherProfile)

        val byProfile = repository.findFieldsByProfileIds(listOf(profile.id!!, otherProfile.id!!))

        assertEquals(listOf(first.id, second.id), byProfile.getValue(profile.id!!).map { it.id })
        assertEquals(1, byProfile.getValue(otherProfile.id!!).size)
    }

    @Test
    @Transactional
    fun `boundary - a profile without fields has no entry and an empty id list reads nothing`() {
        val profile = profile("COV8-B2BF Empty Profile")

        assertTrue(repository.findFieldsByProfileIds(listOf(profile.id!!)).isEmpty())
        assertTrue(repository.findFieldsByProfileIds(emptyList()).isEmpty())
    }

    @Test
    @Transactional
    fun `boundary - an unknown field id yields no field and no owner`() {
        assertNull(repository.findFieldById(-1L))
        assertNull(repository.findByFieldId(-1L))
    }
}
