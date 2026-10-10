package de.littletrotter.application.reference

import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.domain.reference.ValidationSetting
import de.littletrotter.domain.repository.ValidationSettingRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * T9 — the switch itself (test cases T9-01 and T9-09).
 *
 * The focus is on **fail-open**. That is the one property whose reversal would go unnoticed: a
 * check that silently fails because of a database problem produces no error, only a result with
 * no findings -- which looks exactly like "everything is fine".
 */
class ValidationSettingsGateTest {

    @Nested
    inner class FailOpen {

        /** T9-01 — no row in the database yet. */
        @Test
        fun `a capability without a stored row counts as enabled`() {
            val gate = ValidationSettingsGate(InMemoryValidationSettingRepository(), time = FIXED_TIME_SOURCE)
            assertTrue(gate.isEnabled(ValidationCapabilityId.OMLX_LLM_CHECK))
        }

        /** T9-09 — a new check whose seed migration has not run yet must not drag down the
         *  already-seeded ones. */
        @Test
        fun `an unseeded capability does not affect the seeded ones`() {
            val gate = ValidationSettingsGate(
                InMemoryValidationSettingRepository(mapOf(ValidationCapabilityId.OMLX_LLM_CHECK to false)), time = FIXED_TIME_SOURCE,
            )
            assertFalse(gate.isEnabled(ValidationCapabilityId.OMLX_LLM_CHECK))
            assertTrue(gate.isEnabled(ValidationCapabilityId.VERAPDF_CHECK), "ungeseedet heißt eingeschaltet")
        }

        @Test
        fun `a failing repository leaves the check enabled instead of silently dropping it`() {
            val gate = ValidationSettingsGate(FailingRepository(), time = FIXED_TIME_SOURCE)
            assertTrue(gate.isEnabled(ValidationCapabilityId.VERAPDF_CHECK))
        }

        /** A failure must not get cached for the cache's hold time -- otherwise a brief outage
         *  would stay in effect for ten seconds. */
        @Test
        fun `a repository that recovers is read again on the next call`() {
            val repo = FlakyRepository()
            val gate = ValidationSettingsGate(repo, time = FIXED_TIME_SOURCE)

            assertTrue(gate.isEnabled(ValidationCapabilityId.OMLX_LLM_CHECK), "erster Aufruf scheitert, fail-open")
            repo.healthy = true
            assertFalse(gate.isEnabled(ValidationCapabilityId.OMLX_LLM_CHECK), "danach gilt der gespeicherte Stand")
        }
    }

    @Nested
    inner class Switching {

        @Test
        fun `allSettings lists every known capability, seeded or not`() {
            val gate = ValidationSettingsGate(
                InMemoryValidationSettingRepository(mapOf(ValidationCapabilityId.VERAPDF_CHECK to false)), time = FIXED_TIME_SOURCE,
            )
            val all = gate.allSettings()
            assertEquals(ValidationCapabilityId.entries.toSet(), all.keys)
            assertEquals(false, all[ValidationCapabilityId.VERAPDF_CHECK])
            assertEquals(true, all[ValidationCapabilityId.OMLX_LLM_CHECK])
        }

        @Test
        fun `setEnabled takes effect immediately, without waiting for the cache to expire`() {
            val gate = ValidationSettingsGate(InMemoryValidationSettingRepository(), time = FIXED_TIME_SOURCE)
            assertTrue(gate.isEnabled(ValidationCapabilityId.OMLX_LLM_CHECK)) // populates the cache

            gate.setEnabled(ValidationCapabilityId.OMLX_LLM_CHECK, false)

            assertFalse(gate.isEnabled(ValidationCapabilityId.OMLX_LLM_CHECK))
        }

        @Test
        fun `setEnabled creates the row when none exists yet`() {
            val repo = InMemoryValidationSettingRepository()
            ValidationSettingsGate(repo, time = FIXED_TIME_SOURCE).setEnabled(ValidationCapabilityId.VERAPDF_CHECK, false)

            assertEquals(false, repo.findById(ValidationCapabilityId.VERAPDF_CHECK)?.enabled)
        }
    }

    @Nested
    inner class Entity {

        @Test
        fun `a row naming an unknown capability resolves to null instead of throwing`() {
            val row = ValidationSetting().apply { capabilityId = "ABGESCHAFFTE_PRUEFUNG" }
            assertEquals(null, row.capability)
        }

        @Test
        fun `the primary key is the constant name, not its ordinal`() {
            assertEquals("OMLX_LLM_CHECK", ValidationSetting.of(ValidationCapabilityId.OMLX_LLM_CHECK).capabilityId)
        }
    }

    private class FailingRepository : ValidationSettingRepository {
        override fun findAll(): List<ValidationSetting> = error("Datenbank nicht erreichbar")
        override fun findById(capability: ValidationCapabilityId) = error("Datenbank nicht erreichbar")
        override fun save(setting: ValidationSetting) = error("Datenbank nicht erreichbar")
    }

    private class FlakyRepository : ValidationSettingRepository {
        var healthy = false
        override fun findAll(): List<ValidationSetting> =
            if (healthy) listOf(ValidationSetting.of(ValidationCapabilityId.OMLX_LLM_CHECK, enabled = false))
            else error("Datenbank nicht erreichbar")
        override fun findById(capability: ValidationCapabilityId): ValidationSetting? = findAll().firstOrNull { it.capabilityId == capability.name }
        override fun save(setting: ValidationSetting) = setting
    }
}
