package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The aggregation rule behind a multi-stage run: an ERROR on any
 *  active stage makes the run unverified (ERROR), not defective (INVALID); skipped stages
 *  never participate, so a skip can neither pass nor fail the run. */
class ValidationRunTest {

    @Test
    fun `overallOutcomeOf ERROR wins over INVALID`() {
        val stages = listOf(
            ValidationStageResult("STAGE1", KositOutcome.ERROR),
            ValidationStageResult("STAGE2", KositOutcome.INVALID)
        )
        assertEquals(KositOutcome.ERROR, ValidationRun.overallOutcomeOf(stages))
    }

    @Test
    fun `overallOutcomeOf VALID when all stages valid but one skipped`() {
        val stages = listOf(
            ValidationStageResult("STAGE1", KositOutcome.VALID),
            ValidationStageResult("STAGE2", KositOutcome.VALID, skipped = true, skipReason = "optional"),
            ValidationStageResult("STAGE3", KositOutcome.VALID)
        )
        val run = ValidationRun(stages, ValidationRun.overallOutcomeOf(stages), startedAt = "2026-01-01T00:00:00Z")
        assertEquals(KositOutcome.VALID, run.overall)
        val skippedStage = run.stages.find { it.skipped }
        assertEquals(true, skippedStage?.skipped)
        assertEquals("optional", skippedStage?.skipReason)
    }

    @Test
    fun `overallOutcomeOf INVALID when one stage invalid and others valid`() {
        val stages = listOf(
            ValidationStageResult("STAGE1", KositOutcome.VALID),
            ValidationStageResult("STAGE2", KositOutcome.INVALID),
            ValidationStageResult("STAGE3", KositOutcome.VALID)
        )
        assertEquals(KositOutcome.INVALID, ValidationRun.overallOutcomeOf(stages))
    }

    @Test
    fun `overallOutcomeOf skipped(ERROR) does not affect overall`() {
        val stages = listOf(
            ValidationStageResult("STAGE1", KositOutcome.ERROR, skipped = true, skipReason = "skipped"),
            ValidationStageResult("STAGE2", KositOutcome.VALID)
        )
        assertEquals(KositOutcome.VALID, ValidationRun.overallOutcomeOf(stages))
    }

    @Test
    fun `engineError defaults to null and can be set on an ERROR stage`() {
        val defaulted = ValidationStageResult("VERAPDF", KositOutcome.VALID)
        assertEquals(null, defaulted.engineError)

        val errored = ValidationStageResult("VERAPDF", KositOutcome.ERROR, engineError = "veraPDF-CLI nicht ausführbar")
        assertEquals("veraPDF-CLI nicht ausführbar", errored.engineError)
    }
}
