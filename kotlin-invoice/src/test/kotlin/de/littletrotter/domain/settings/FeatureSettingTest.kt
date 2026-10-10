package de.littletrotter.domain.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FeatureSettingTest {

    @Test
    fun `of() factory creates a FeatureSetting with flag key and enabled status`() {
        val setting = FeatureSetting.of(FeatureFlag.PER_DIEM_UI, true)

        assertEquals(FeatureFlag.PER_DIEM_UI.key, setting.key)
        assertEquals(true, setting.enabled)
    }

    @Test
    fun `of() factory can create both enabled and disabled settings`() {
        val enabled = FeatureSetting.of(FeatureFlag.PER_DIEM_UI, true)
        val disabled = FeatureSetting.of(FeatureFlag.PER_DIEM_UI, false)

        assertEquals(true, enabled.enabled)
        assertEquals(false, disabled.enabled)
    }

    @Test
    fun `flag property returns the corresponding FeatureFlag when key matches`() {
        val setting = FeatureSetting.of(FeatureFlag.PER_DIEM_UI, true)
        assertEquals(FeatureFlag.PER_DIEM_UI, setting.flag)
    }

    @Test
    fun `flag property returns null when key does not match any flag`() {
        val setting = FeatureSetting().apply { key = "UNKNOWN_FLAG_KEY" }
        assertNull(setting.flag)
    }

    @Test
    fun `a setting can have a description`() {
        val setting = FeatureSetting().apply {
            key = FeatureFlag.PER_DIEM_UI.key
            description = "Per diem UI feature"
        }
        assertEquals("Per diem UI feature", setting.description)
    }
}
