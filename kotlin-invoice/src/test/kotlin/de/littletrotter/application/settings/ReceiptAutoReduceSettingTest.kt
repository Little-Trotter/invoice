package de.littletrotter.application.settings

import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.test.FIXED_TIME_SOURCE
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReceiptAutoReduceSettingTest {

    @Test
    fun `without a stored row large receipts are reduced automatically`() {
        val setting = ReceiptAutoReduceSetting(FeatureSettingsGate(InMemoryFeatureSettingRepository(), time = FIXED_TIME_SOURCE))

        assertTrue(setting.isAutoReduceEnabled())
        assertTrue(FeatureFlag.RECEIPT_AUTO_REDUCE.defaultEnabled)
    }

    @Test
    fun `switching the setting off is read back`() {
        val repo = InMemoryFeatureSettingRepository(mapOf(FeatureFlag.RECEIPT_AUTO_REDUCE to false))
        val setting = ReceiptAutoReduceSetting(FeatureSettingsGate(repo, time = FIXED_TIME_SOURCE))

        assertFalse(setting.isAutoReduceEnabled())
    }
}
