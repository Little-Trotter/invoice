package de.littletrotter.application.quote

import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.settings.FeatureFlag
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@DisplayName("QuoteWriteGuard (deployment AND flag)")
class QuoteWriteGuardTest {

    private fun guard(deployed: Boolean, flag: Boolean): QuoteWriteGuard {
        val gate = mock<FeatureSettingsGate>()
        whenever(gate.isEnabled(FeatureFlag.QUOTE)).thenReturn(flag)
        return QuoteWriteGuard(QuoteAvailability(deployed, gate))
    }

    @Test
    fun `passes when component is deployed and the flag is on`() {
        assertDoesNotThrow { guard(deployed = true, flag = true).requireEnabled() }
    }

    @Test
    fun `refuses with the disabled message key when the flag is off`() {
        val ex = assertThrows(QuoteModuleDisabledException::class.java) { guard(true, false).requireEnabled() }
        assertEquals("settings.flag.quote.disabled", ex.messageKey)
        assertEquals("settings.flag.quote.disabled", ex.message)
    }

    @Test
    fun `refuses when the component is not deployed`() {
        assertThrows(QuoteModuleDisabledException::class.java) { guard(false, true).requireEnabled() }
    }
}
