package de.littletrotter.domain.invoice

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VeraPdfCheckStateTest {

    private fun report(vararg veraPdf: ValidationStageResult): String {
        val stages = listOf(ValidationStageResult("PDFA", KositOutcome.VALID)) + veraPdf
        val run = ValidationRun(stages, ValidationRun.overallOutcomeOf(stages), startedAt = "2026-10-12T10:00:00Z")
        return KositReportPayload(status = run.overall.name, validationRun = run).toJson()
    }

    private fun vera(outcome: KositOutcome = KositOutcome.VALID, pending: Boolean = false, skipped: Boolean = false, findings: List<ValidationMessage> = emptyList()) =
        ValidationStageResult(ValidationStageResult.VERAPDF, outcome, findings, skipped = skipped, pending = pending, advisory = true)

    @Test
    fun `no report, no run and no stage mean the check was not enabled`() {
        assertEquals(VeraPdfCheckState.NOT_ENABLED, veraPdfCheckStateOf(null))
        assertEquals(VeraPdfCheckState.NOT_ENABLED, veraPdfCheckStateOf("not json"))
        assertEquals(VeraPdfCheckState.NOT_ENABLED, veraPdfCheckStateOf(KositReportPayload(status = "VALID").toJson()))
        assertEquals(VeraPdfCheckState.NOT_ENABLED, veraPdfCheckStateOf(report()))
    }

    @Test
    fun `a skipped stage is not enabled`() {
        assertEquals(VeraPdfCheckState.NOT_ENABLED, veraPdfCheckStateOf(report(vera(skipped = true))))
    }

    @Test
    fun `a pending stage is pending`() {
        assertEquals(VeraPdfCheckState.PENDING, veraPdfCheckStateOf(report(vera(pending = true))))
    }

    @Test
    fun `the verdicts map to passed, findings and unavailable`() {
        val finding = ValidationMessage("WARNING", "rule", stage = "VERAPDF")
        assertEquals(VeraPdfCheckState.PASSED, veraPdfCheckStateOf(report(vera(KositOutcome.VALID))))
        assertEquals(VeraPdfCheckState.FINDINGS, veraPdfCheckStateOf(report(vera(KositOutcome.INVALID, findings = listOf(finding)))))
        assertEquals(VeraPdfCheckState.UNAVAILABLE, veraPdfCheckStateOf(report(vera(KositOutcome.ERROR))))
    }

    @Test
    fun `sending waits only while the verdict is outstanding and the check is switched on`() {
        VeraPdfCheckState.entries.forEach { state ->
            assertEquals(state == VeraPdfCheckState.PENDING, state.holdsBackSending(checkEnabled = true), "enabled, $state")
            assertFalse(state.holdsBackSending(checkEnabled = false), "disabled, $state")
        }
    }

    @Test
    fun `switching the check off releases a pending document`() {
        val pending = veraPdfCheckStateOf(report(vera(pending = true)))

        assertTrue(pending.holdsBackSending(checkEnabled = true))
        assertFalse(pending.holdsBackSending(checkEnabled = false))
    }
}
