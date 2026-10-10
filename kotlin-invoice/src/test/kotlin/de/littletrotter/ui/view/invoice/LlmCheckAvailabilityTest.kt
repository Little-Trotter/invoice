package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.LlmAnalysisFeatureConfig
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.reference.ValidationCapabilityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** [LlmCheckAvailability.isAvailable] folds the LLM-Check checkbox's three independent
 *  availability reasons into one status; see the class's own KDoc for the precedence rule. */
class LlmCheckAvailabilityTest {

    private fun gate(enabled: Boolean): ValidationSettingsGate =
        mock<ValidationSettingsGate>().apply { whenever(isEnabled(ValidationCapabilityId.OMLX_LLM_CHECK)).thenReturn(enabled) }

    @Test
    fun `happy path - all three checks pass and the checkbox counts as available`() {
        val availability = LlmCheckAvailability(gate(true), LlmAnalysisFeatureConfig(enabled = true)) { true }
        assertEquals(LlmCheckStatus.Available, availability.isAvailable())
    }

    @Test
    fun `boundary - the business switch off wins over every other reason`() {
        val availability = LlmCheckAvailability(gate(false), LlmAnalysisFeatureConfig(enabled = false)) { false }
        assertEquals(LlmCheckStatus.SwitchedOff, availability.isAvailable())
    }

    @Test
    fun `boundary - the deployment flag off wins over the oMLX configuration when the switch is on`() {
        val availability = LlmCheckAvailability(gate(true), LlmAnalysisFeatureConfig(enabled = false)) { false }
        assertEquals(LlmCheckStatus.DisabledByDeployment, availability.isAvailable())
    }

    @Test
    fun `boundary - an unconfigured oMLX endpoint is reported only once switch and deployment both allow it`() {
        val availability = LlmCheckAvailability(gate(true), LlmAnalysisFeatureConfig(enabled = true)) { false }
        assertEquals(LlmCheckStatus.NotConfigured, availability.isAvailable())
    }

    @Test
    fun `boundary - null validationSettingsGate and null llmAnalysisFeatureConfig both count as switched on`() {
        val availability = LlmCheckAvailability(null, null) { true }
        assertEquals(LlmCheckStatus.Available, availability.isAvailable())
    }

    @Test
    fun `the zero-argument constructor defaults to the real environment reader and the two required beans absent`() {
        // No assertion on the actual env-dependent outcome (the test host's own environment
        // decides it) — this only proves the zero-arg overload constructs and evaluates without
        // throwing, exercising the constructor delegation chain itself.
        LlmCheckAvailability().isAvailable()
    }
}
