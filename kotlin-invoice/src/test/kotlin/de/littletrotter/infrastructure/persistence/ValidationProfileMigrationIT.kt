package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the V015 migration end to end against a real Postgres (Testcontainers, full
 * Flyway chain via [PostgresTestResource]/`quarkus.flyway.migrate-at-start`): the
 * `validation_profile` table and its seed exist as specified, and no pre-existing migration was
 * altered (this class running at all against the full chain is the proof for that last part).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ValidationProfileMigrationIT {

    @Inject
    lateinit var em: EntityManager

    private fun rowCount(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()

    @Test
    @Transactional
    fun `exactly one ACTIVE profile is seeded`() {
        assertEquals(1, rowCount("SELECT COUNT(*) FROM validation_profile WHERE state = 'ACTIVE'"))
    }

    @Test
    @Transactional
    fun `the seeded ACTIVE profile is the code-verifiable XRechnung 3_0_2 stand`() {
        val row = em.createNativeQuery(
            "SELECT profile_id, spec_version, validator_config_version, mustang_version FROM validation_profile WHERE state = 'ACTIVE'"
        ).singleResult as Array<Any?>
        assertEquals("XRechnung", row[0])
        assertEquals("3.0.2", row[1])
        assertEquals("2026-01-31", row[2])
        assertEquals("2.25.0", row[3])
    }

    // PO-Entscheid 28.09.2026: EXTENDED bleibt EXPERIMENTAL (kein eigener KoSIT-Nachweis,
    // nur die Factur-X-EXTENDED-Schematron-Stufe), also erhöht dieser Fall die ACTIVE-Zählung
    // in `exactly one ACTIVE profile is seeded` bewusst nicht.
    @Test
    @Transactional
    fun `the seeded EXTENDED profile is EXPERIMENTAL, never ACTIVE`() {
        val row = em.createNativeQuery(
            "SELECT spec_version, validator_config_version, mustang_version, state FROM validation_profile WHERE profile_id = 'EXTENDED'"
        ).singleResult as Array<Any?>
        assertEquals("Factur-X 1.09.2 EXTENDED", row[0])
        assertEquals("1.09.2", row[1])
        assertEquals("2.26.0", row[2])
        assertEquals("EXPERIMENTAL", row[3])
    }

    @Test
    @Transactional
    fun `an invalid state is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO validation_profile (profile_id, spec_version, state, created_at, version) VALUES ('X', '1.0', 'UNKNOWN', '2026-06-15T10:00:00Z', 0)"
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `valid_from after valid_to is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO validation_profile (profile_id, spec_version, state, valid_from, valid_to, created_at, version)
                   VALUES ('X', '1.0', 'EXPERIMENTAL', '2027-01-01', '2026-01-01', '2026-06-15T10:00:00Z', 0)"""
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `a duplicate profile_id and spec_version pair is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO validation_profile (profile_id, spec_version, state, created_at, version) VALUES ('XRechnung', '3.0.2', 'EXPERIMENTAL', '2026-06-15T10:00:00Z', 0)"
            ).executeUpdate()
            em.flush()
        }
    }
}
