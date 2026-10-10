package de.littletrotter.ui.view.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [resolveValidationOutcome] is the single source for the derivation
 * table the PO fixed on 2026-08-24; every row is exercised here directly against a [GenerationJob] fixture.
 */
class ValidationOutcomeTest {

    private fun job(status: GenerationJobStatus, kositReport: String? = null, resultRefs: String? = null): GenerationJob =
        GenerationJob().apply {
            this.status = status
            this.kositReport = kositReport
            this.resultRefs = resultRefs
        }

    private fun validReport(): String = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
    private fun invalidReport(): String = KositReportPayload.encode(
        KositValidationResult(status = KositOutcome.INVALID, details = listOf(ValidationMessage("ERROR", "BR-01 missing")))
    )
    private fun errorReport(engineError: String? = "veraPDF-CLI nicht ausführbar"): String {
        val stages = listOf(ValidationStageResult("VERAPDF", KositOutcome.ERROR, engineError = engineError))
        return KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.ERROR,
                summary = "Engine error: timeout",
                validationRun = ValidationRun(stages, KositOutcome.ERROR, startedAt = "2026-08-24T10:00:00Z"),
            )
        )
    }
    private fun allSkippedReport(): String {
        val stages = listOf(
            ValidationStageResult("SYNTAX", KositOutcome.VALID, skipped = true, skipReason = "skipValidation"),
            ValidationStageResult("KOSIT", KositOutcome.VALID, skipped = true, skipReason = "skipValidation"),
        )
        return KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.VALID,
                validationRun = ValidationRun(stages, KositOutcome.VALID, startedAt = "2026-08-24T10:00:00Z"),
            )
        )
    }

    // ── 1. lifecycle in flight → Pending ────────────────────────────────────

    @Test
    fun `PENDING DELEGATED RUNNING are all Pending`() {
        assertEquals(ValidationOutcome.Pending, resolveValidationOutcome(job(GenerationJobStatus.PENDING)))
        assertEquals(ValidationOutcome.Pending, resolveValidationOutcome(job(GenerationJobStatus.DELEGATED)))
        assertEquals(ValidationOutcome.Pending, resolveValidationOutcome(job(GenerationJobStatus.RUNNING)))
    }

    // ── 2. FAILED → ABORTED, detail from resultRefs error ───────────────────

    @Test
    fun `FAILED job is ABORTED with the resultRefs error as detail`() {
        val outcome = resolveValidationOutcome(
            job(GenerationJobStatus.FAILED, resultRefs = """{"error":"engine unreachable"}"""),
        )
        assertTrue(outcome is ValidationOutcome.NotSuccessful)
        outcome as ValidationOutcome.NotSuccessful
        assertEquals(ValidationFailureReason.ABORTED, outcome.reason)
        assertEquals("engine unreachable", outcome.detail)
    }

    @Test
    fun `FAILED job without a resultRefs error still yields ABORTED with a null detail`() {
        val outcome = resolveValidationOutcome(job(GenerationJobStatus.FAILED, resultRefs = null))
        assertTrue(outcome is ValidationOutcome.NotSuccessful)
        assertEquals(ValidationFailureReason.ABORTED, (outcome as ValidationOutcome.NotSuccessful).reason)
        assertNull(outcome.detail)
    }

    // ── 3. skipped validation → NOT_REQUESTED (§1.4) ────────────────────────

    @Test
    fun `a report whose stages are all skipped is NOT_REQUESTED`() {
        val outcome = resolveValidationOutcome(job(GenerationJobStatus.COMPLETED, kositReport = allSkippedReport()))
        assertTrue(outcome is ValidationOutcome.NotSuccessful)
        assertEquals(ValidationFailureReason.NOT_REQUESTED, (outcome as ValidationOutcome.NotSuccessful).reason)
    }

    // ── 4. COMPLETED, blank report → REPORT_MISSING ─────────────────────────

    @Test
    fun `COMPLETED with a blank report is REPORT_MISSING`() {
        val outcome = resolveValidationOutcome(job(GenerationJobStatus.COMPLETED, kositReport = null))
        assertTrue(outcome is ValidationOutcome.NotSuccessful)
        assertEquals(ValidationFailureReason.REPORT_MISSING, (outcome as ValidationOutcome.NotSuccessful).reason)
    }

    // ── 5. VALID report → Successful ────────────────────────────────────────

    @Test
    fun `a VALID report is Successful`() {
        assertEquals(
            ValidationOutcome.Successful,
            resolveValidationOutcome(job(GenerationJobStatus.COMPLETED, kositReport = validReport())),
        )
    }

    // ── 6. INVALID report → FINDINGS ────────────────────────────────────────

    @Test
    fun `an INVALID report is FINDINGS`() {
        val outcome = resolveValidationOutcome(job(GenerationJobStatus.COMPLETED, kositReport = invalidReport()))
        assertTrue(outcome is ValidationOutcome.NotSuccessful)
        assertEquals(ValidationFailureReason.FINDINGS, (outcome as ValidationOutcome.NotSuccessful).reason)
    }

    // ── 7. ERROR report → VALIDATOR_UNAVAILABLE, detail from stage engineError / summary ──

    @Test
    fun `an ERROR report is VALIDATOR_UNAVAILABLE with the stage engineError as detail`() {
        val outcome = resolveValidationOutcome(job(GenerationJobStatus.COMPLETED, kositReport = errorReport()))
        assertTrue(outcome is ValidationOutcome.NotSuccessful)
        outcome as ValidationOutcome.NotSuccessful
        assertEquals(ValidationFailureReason.VALIDATOR_UNAVAILABLE, outcome.reason)
        assertEquals("veraPDF-CLI nicht ausführbar", outcome.detail)
    }

    @Test
    fun `an ERROR report without a stage engineError falls back to the payload summary`() {
        val outcome = resolveValidationOutcome(
            job(GenerationJobStatus.COMPLETED, kositReport = errorReport(engineError = null)),
        )
        assertTrue(outcome is ValidationOutcome.NotSuccessful)
        outcome as ValidationOutcome.NotSuccessful
        assertEquals(ValidationFailureReason.VALIDATOR_UNAVAILABLE, outcome.reason)
        assertEquals("Engine error: timeout", outcome.detail)
    }

    // ── 8. unparseable report → REPORT_UNREADABLE ───────────────────────────

    @Test
    fun `an unparseable report is REPORT_UNREADABLE`() {
        val outcome = resolveValidationOutcome(job(GenerationJobStatus.COMPLETED, kositReport = "<not-well-formed"))
        assertTrue(outcome is ValidationOutcome.NotSuccessful)
        assertEquals(ValidationFailureReason.REPORT_UNREADABLE, (outcome as ValidationOutcome.NotSuccessful).reason)
    }

    // ── validationReasonKey: every reason maps to its own i18n key ──────────────

    @Test
    fun `validationReasonKey maps every ValidationFailureReason to a distinct i18n key`() {
        val expected = mapOf(
            ValidationFailureReason.FINDINGS to "validation.reason.findings",
            ValidationFailureReason.ABORTED to "validation.reason.aborted",
            ValidationFailureReason.VALIDATOR_UNAVAILABLE to "validation.reason.validatorUnavailable",
            ValidationFailureReason.REPORT_MISSING to "validation.reason.reportMissing",
            ValidationFailureReason.REPORT_UNREADABLE to "validation.reason.reportUnreadable",
            ValidationFailureReason.NOT_REQUESTED to "validation.reason.notRequested",
        )
        expected.forEach { (reason, key) -> assertEquals(key, validationReasonKey(reason)) }
        assertEquals(ValidationFailureReason.entries.toSet(), expected.keys, "every enum value must be covered above")
    }
}
