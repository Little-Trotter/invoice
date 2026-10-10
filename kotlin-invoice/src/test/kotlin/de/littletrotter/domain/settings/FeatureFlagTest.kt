package de.littletrotter.domain.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeatureFlagTest {

    @Test
    fun `keys match pattern and are unique`() {
        val keys = FeatureFlag.entries.map { it.key }
        assertTrue(keys.all { it.matches(Regex("^[a-z0-9][a-z0-9._-]{1,63}$")) })
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `fromKey round-trips for known keys`() {
        FeatureFlag.entries.forEach { flag ->
            assertEquals(flag, FeatureFlag.fromKey(flag.key))
        }
    }

    @Test
    fun `fromKey returns null for unknown key`() {
        assertNull(FeatureFlag.fromKey("unknown.flag.key"))
    }

    @Test
    fun `defaultEnabled matches each flag's documented rollout state`() {
        // PO decision 2026-09-28 — EXTENDED_PROFILE is the one deliberate exception: the KoSIT
        // validator cannot validate EXTENDED, so that escalation stays off until an admin opts in.
        assertTrue(FeatureFlag.PER_DIEM_UI.defaultEnabled)
        assertTrue(FeatureFlag.EMAIL_SEND.defaultEnabled)
        assertTrue(FeatureFlag.QUOTE.defaultEnabled)
        assertFalse(FeatureFlag.EXTENDED_PROFILE.defaultEnabled)
    }

    @Test
    fun `labelKey and descriptionKey use the settings flag namespace`() {
        FeatureFlag.entries.forEach { flag ->
            assertTrue(flag.labelKey.startsWith("settings.flag."))
            assertTrue(flag.descriptionKey.startsWith("settings.flag."))
        }
    }

    @Test
    fun `labelKey and descriptionKey are non-empty`() {
        FeatureFlag.entries.forEach { flag ->
            assertNotNull(flag.labelKey)
            assertNotNull(flag.descriptionKey)
            assertFalse(flag.labelKey.isEmpty())
            assertFalse(flag.descriptionKey.isEmpty())
        }
    }
}
