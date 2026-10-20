package de.littletrotter.application.settings

import de.littletrotter.shared.wording.WordingProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Mirrors `FeatureSettingsGateTest`: env default, DB override, cache, fail-open, plus the
 * unknown-env-value border case that has no equivalent on the boolean gates.
 */
class WordingSettingsGateTest {

    @Nested
    inner class EnvDefault {

        @Test
        fun `no row and a HANDWERK env default resolve to HANDWERK`() {
            val gate = WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE)
            assertEquals(WordingProfile.HANDWERK, gate.currentProfile())
        }

        @Test
        fun `no row and a BERATUNG env default resolve to BERATUNG`() {
            val gate = WordingSettingsGate(InMemorySystemSettingRepository(), "BERATUNG", time = FIXED_TIME_SOURCE)
            assertEquals(WordingProfile.BERATUNG, gate.currentProfile())
        }

        @Test
        fun `an unknown env value falls back to the HANDWERK default`() {
            val gate = WordingSettingsGate(InMemorySystemSettingRepository(), "bogus-profile", time = FIXED_TIME_SOURCE)
            assertEquals(WordingProfile.DEFAULT, gate.currentProfile())
            assertEquals(WordingProfile.HANDWERK, gate.currentProfile())
        }

        @Test
        fun `the env default is case-insensitive`() {
            val gate = WordingSettingsGate(InMemorySystemSettingRepository(), "beratung", time = FIXED_TIME_SOURCE)
            assertEquals(WordingProfile.BERATUNG, gate.currentProfile())
        }
    }

    @Nested
    inner class DbOverride {

        @Test
        fun `a stored row overrides the env default`() {
            val repo = InMemorySystemSettingRepository(mapOf(WordingSettingsGate.KEY to "BERATUNG"))
            val gate = WordingSettingsGate(repo, "HANDWERK", time = FIXED_TIME_SOURCE)
            assertEquals(WordingProfile.BERATUNG, gate.currentProfile())
        }

        @Test
        fun `setProfile persists and invalidates immediately`() {
            val repo = InMemorySystemSettingRepository()
            val gate = WordingSettingsGate(repo, "HANDWERK", time = FIXED_TIME_SOURCE)

            gate.setProfile(WordingProfile.BERATUNG, "stefan")

            assertEquals(WordingProfile.BERATUNG, gate.currentProfile(), "invalidate() statt TTL abwarten")
            assertEquals("BERATUNG", repo.rows.getValue(WordingSettingsGate.KEY).value)
            assertEquals("stefan", repo.rows.getValue(WordingSettingsGate.KEY).updatedBy)
        }

        @Test
        fun `switching back updates the existing row instead of duplicating`() {
            val repo = InMemorySystemSettingRepository(mapOf(WordingSettingsGate.KEY to "BERATUNG"))
            val gate = WordingSettingsGate(repo, "HANDWERK", time = FIXED_TIME_SOURCE)

            gate.setProfile(WordingProfile.HANDWERK, "anjit")

            assertEquals(WordingProfile.HANDWERK, gate.currentProfile())
            assertEquals(1, repo.rows.size)
            assertEquals("anjit", repo.rows.getValue(WordingSettingsGate.KEY).updatedBy)
        }
    }

    @Nested
    inner class FailOpen {

        @Test
        fun `a failing repository falls back to the env default instead of throwing`() {
            val repo = InMemorySystemSettingRepository().apply { failReads = true }
            val gate = WordingSettingsGate(repo, "HANDWERK", time = FIXED_TIME_SOURCE)
            assertEquals(WordingProfile.HANDWERK, gate.currentProfile())
        }

        @Test
        fun `a repository that recovers is read again on the next call`() {
            val repo = InMemorySystemSettingRepository(mapOf(WordingSettingsGate.KEY to "BERATUNG")).apply { failReads = true }
            val gate = WordingSettingsGate(repo, "HANDWERK", time = FIXED_TIME_SOURCE)
            assertEquals(WordingProfile.HANDWERK, gate.currentProfile(), "erster Lesefehler, fail-open auf Env-Default")
            repo.failReads = false
            assertEquals(WordingProfile.BERATUNG, gate.currentProfile(), "ein Ausfall darf den gespeicherten Zustand nicht überleben")
        }

        @Test
        fun `lastChanged survives a dead database`() {
            val repo = InMemorySystemSettingRepository()
            val gate = WordingSettingsGate(repo, "HANDWERK", time = FIXED_TIME_SOURCE)
            gate.setProfile(WordingProfile.BERATUNG, "stefan")
            repo.failReads = true
            assertEquals(null, gate.lastChanged())
        }
    }
}
