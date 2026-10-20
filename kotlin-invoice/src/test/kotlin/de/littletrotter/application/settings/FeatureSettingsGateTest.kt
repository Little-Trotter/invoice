package de.littletrotter.application.settings

import de.littletrotter.domain.settings.FeatureFlag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * SFS — the feature-switch gate. Mirrors the validation-settings gate suite deliberately:
 * both carry the same fail-open contract, so the same six properties must hold here.
 */
class FeatureSettingsGateTest {

    @Nested
    inner class FailOpen {

        @Test
        fun `a flag without a stored row counts as its default`() {
            val gate = FeatureSettingsGate(InMemoryFeatureSettingRepository(), time = FIXED_TIME_SOURCE)
            assertTrue(gate.isEnabled(FeatureFlag.PER_DIEM_UI), "leere Tabelle heißt heute: alles an")
            assertTrue(gate.isEnabled(FeatureFlag.EMAIL_SEND))
        }

        @Test
        fun `a failing repository leaves the flags at their defaults instead of hiding features`() {
            val repo = InMemoryFeatureSettingRepository().apply { failReads = true }
            val gate = FeatureSettingsGate(repo, time = FIXED_TIME_SOURCE)
            assertTrue(gate.isEnabled(FeatureFlag.PER_DIEM_UI))
        }

        @Test
        fun `a repository that recovers is read again on the next call`() {
            val repo = InMemoryFeatureSettingRepository(mapOf(FeatureFlag.PER_DIEM_UI to false)).apply { failReads = true }
            val gate = FeatureSettingsGate(repo, time = FIXED_TIME_SOURCE)
            assertTrue(gate.isEnabled(FeatureFlag.PER_DIEM_UI), "erster Lesefehler, fail-open auf Default")
            repo.failReads = false
            assertFalse(gate.isEnabled(FeatureFlag.PER_DIEM_UI), "ein Ausfall darf den gespeicherten Zustand nicht überleben")
        }
    }

    @Nested
    inner class ReadWriteCache {

        @Test
        fun `setEnabled persists a missing row as insert and flips the read immediately`() {
            val repo = InMemoryFeatureSettingRepository()
            val gate = FeatureSettingsGate(repo, time = FIXED_TIME_SOURCE)

            gate.setEnabled(FeatureFlag.PER_DIEM_UI, false, "stefan")

            assertFalse(gate.isEnabled(FeatureFlag.PER_DIEM_UI), "invalidate() statt TTL abwarten")
            val row = repo.rows.getValue(FeatureFlag.PER_DIEM_UI.key)
            assertFalse(row.enabled)
            assertEquals("stefan", row.updatedBy, "updated_by für die 'zuletzt geändert'-Zeile der Ansicht")
        }

        @Test
        fun `re-enabling updates the existing row instead of duplicating`() {
            val repo = InMemoryFeatureSettingRepository(mapOf(FeatureFlag.EMAIL_SEND to false))
            val gate = FeatureSettingsGate(repo, time = FIXED_TIME_SOURCE)

            gate.setEnabled(FeatureFlag.EMAIL_SEND, true, "anjit")

            assertTrue(gate.isEnabled(FeatureFlag.EMAIL_SEND))
            assertEquals(1, repo.rows.size, "Upsert auf der String-PK, keine zweite Zeile")
            assertEquals("anjit", repo.rows.getValue(FeatureFlag.EMAIL_SEND.key).updatedBy)
        }

        @Test
        fun `allStates reports every catalog flag with its effective value`() {
            val gate = FeatureSettingsGate(InMemoryFeatureSettingRepository(mapOf(FeatureFlag.PER_DIEM_UI to false)), time = FIXED_TIME_SOURCE)
            val states = gate.allStates()
            assertEquals(FeatureFlag.entries.toSet(), states.keys)
            assertFalse(states.getValue(FeatureFlag.PER_DIEM_UI))
            assertTrue(states.getValue(FeatureFlag.EMAIL_SEND), "ohne Zeile gilt der Katalog-Default")
            assertFalse(states.getValue(FeatureFlag.EXTENDED_PROFILE), "ohne Zeile gilt der Katalog-Default (Opt-in, aus)")
        }

        @Test
        fun `lastChanged reads the stored row and survives a dead database`() {
            val repo = InMemoryFeatureSettingRepository()
            val gate = FeatureSettingsGate(repo, time = FIXED_TIME_SOURCE)
            assertNull(gate.lastChanged(FeatureFlag.PER_DIEM_UI), "nie geändert = keine Zeile")

            gate.setEnabled(FeatureFlag.PER_DIEM_UI, false, "stefan")
            assertNotNull(gate.lastChanged(FeatureFlag.PER_DIEM_UI)?.updatedBy)

            repo.failReads = true
            assertNull(gate.lastChanged(FeatureFlag.PER_DIEM_UI), "Meta-Zeile darf die Ansicht nicht zum Werfen bringen")
        }
    }
}
