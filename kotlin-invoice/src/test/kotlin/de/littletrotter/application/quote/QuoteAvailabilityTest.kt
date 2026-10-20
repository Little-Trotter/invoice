package de.littletrotter.application.quote

import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.settings.FeatureFlag
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class QuoteAvailabilityTest {
    @Test
    fun `reports the configured switch`() {
        assertTrue(QuoteAvailability(true).enabled)
        assertFalse(QuoteAvailability(false).enabled)
    }

    @Test
    fun `offered needs the deployment and the flag, a missing gate reads as on`() {
        val gate = mock<FeatureSettingsGate>()
        whenever(gate.isEnabled(FeatureFlag.QUOTE)).thenReturn(false)
        assertFalse(QuoteAvailability(true, gate).offered)
        assertTrue(QuoteAvailability(true).offered)
        assertFalse(QuoteAvailability(false).offered)
    }
}
