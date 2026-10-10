package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.compliance.ValidationProfile
import de.littletrotter.domain.compliance.ValidationProfileState
import de.littletrotter.domain.repository.ValidationProfileRepository
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * [PanacheValidationProfileRepository] against a real Testcontainers Postgres. V2 seeds
 * one ACTIVE `XRechnung` row, so every fixture here uses its own random `profileId` to stay
 * independent of that row and of `uq_validation_profile_id_version` (profile_id, spec_version).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheValidationProfileRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: ValidationProfileRepository

    private val fixtureProfileId = "ZZCOV9-${UUID.randomUUID()}"

    private fun profile(specVersion: String, validFrom: LocalDate?): ValidationProfile =
        ValidationProfile().apply {
            this.profileId = fixtureProfileId
            this.specVersion = specVersion
            this.state = ValidationProfileState.ACTIVE.name
            this.validFrom = validFrom
        }.also { em.persist(it) }

    @Test
    @TestTransaction
    fun `happy path findCurrentByProfileId picks the latest validFrom, nulls last`() {
        val undated = profile("0.9", null)
        val older = profile("1.0", LocalDate.of(2025, 1, 1))
        val newer = profile("2.0", LocalDate.of(2026, 1, 1))
        em.flush()

        val current = repository.findCurrentByProfileId(fixtureProfileId)

        assertEquals(newer.id, current?.id, "the most recent validFrom wins")
        assertTrue(undated.id != null && older.id != null, "both other rows exist too")
    }

    @Test
    @TestTransaction
    fun `boundary findCurrentByProfileId returns null for an unregistered profile id`() {
        assertNull(repository.findCurrentByProfileId("zzcov9-unknown-${UUID.randomUUID()}"))
    }

    @Test
    @TestTransaction
    fun `boundary equal validFrom values are tie-broken by id descending`() {
        val sameDay = LocalDate.of(2026, 3, 1)
        val first = profile("1.0", sameDay)
        val second = profile("2.0", sameDay)
        em.flush()

        val current = repository.findCurrentByProfileId(fixtureProfileId)

        assertEquals(maxOf(first.id!!, second.id!!), current?.id, "the higher id wins on a validFrom tie")
    }

    @Test
    @TestTransaction
    fun `boundary saving a duplicate profileId-and-specVersion pair violates the DB constraint`() {
        profile("1.0", LocalDate.of(2025, 1, 1))
        em.flush()
        // GenerationType.IDENTITY forces Hibernate to execute the INSERT immediately on
        // em.persist() (it needs the generated id right away), not deferred to flush() — so the
        // constraint violation surfaces at persist(), and that call must be inside assertThrows.
        assertThrows(PersistenceException::class.java) {
            val duplicate = ValidationProfile().apply {
                this.profileId = fixtureProfileId
                this.specVersion = "1.0"
                this.state = ValidationProfileState.EXPERIMENTAL.name
            }
            em.persist(duplicate)
            em.flush()
        }
    }

    @Test
    @TestTransaction
    fun `happy path findAll and save resolve ordered rows and upsert by id`() {
        val a = profile("1.0", LocalDate.of(2025, 1, 1))
        val b = profile("2.0", LocalDate.of(2026, 1, 1))
        em.flush()

        val all = repository.findAll().filter { it.profileId == fixtureProfileId }
        assertEquals(listOf(a.id, b.id), all.map { it.id })

        a.state = ValidationProfileState.DEPRECATED.name
        val saved = repository.save(a)

        assertEquals(a.id, saved.id)
        assertEquals(ValidationProfileState.DEPRECATED.name, repository.findAll().first { it.id == a.id }.state)
    }
}
