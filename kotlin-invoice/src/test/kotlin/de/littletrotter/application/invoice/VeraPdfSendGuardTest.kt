package de.littletrotter.application.invoice

import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class VeraPdfSendGuardTest {

    private val gate: ValidationSettingsGate = mock()
    private val guard = VeraPdfSendGuard(gate)

    private fun report(stage: ValidationStageResult): String {
        val run = ValidationRun(listOf(stage), KositOutcome.VALID, startedAt = "2026-10-12T10:00:00Z")
        return KositReportPayload(status = "VALID", validationRun = run).toJson()
    }

    private val pending = report(ValidationStageResult(ValidationStageResult.VERAPDF, KositOutcome.VALID, pending = true, advisory = true))

    private fun settingIs(enabled: Boolean) = whenever(gate.isEnabled(ValidationCapabilityId.VERAPDF_CHECK)).thenReturn(enabled)

    @Test
    fun `a pending verdict holds the send back while the check is on`() {
        settingIs(true)

        assertTrue(guard.mustWait(pending))
    }

    @Test
    fun `switching the check off releases a send that still waits`() {
        settingIs(false)

        assertFalse(guard.mustWait(pending))
    }

    @Test
    fun `a delivered verdict, findings included, never holds the send back`() {
        settingIs(true)
        val findings = report(ValidationStageResult(ValidationStageResult.VERAPDF, KositOutcome.INVALID, advisory = true))
        val unavailable = report(ValidationStageResult(ValidationStageResult.VERAPDF, KositOutcome.ERROR, advisory = true, engineError = "down"))
        val passed = report(ValidationStageResult(ValidationStageResult.VERAPDF, KositOutcome.VALID, advisory = true))

        assertFalse(guard.mustWait(findings))
        assertFalse(guard.mustWait(unavailable))
        assertFalse(guard.mustWait(passed))
    }

    @Test
    fun `a report without the stage never holds the send back`() {
        settingIs(true)

        assertFalse(guard.mustWait(null))
    }
}
