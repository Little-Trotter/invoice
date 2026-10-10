package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.KositReportReader
import de.littletrotter.application.invoice.KositStatus
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.ui.component.blankToNull

/**
 * PO decision — a validation never reports "unknown": a technical abort, a
 * crashed/unreachable validator container, a missing or unreadable report, or a deliberately
 * skipped check are *reasons* for "nicht erfolgreich", never a status of their own.
 */
enum class ValidationFailureReason {
    FINDINGS,              // validator ran, result INVALID
    ABORTED,               // generation job FAILED before/without a validation result
    VALIDATOR_UNAVAILABLE, // validator/engine container not reachable or not running
    REPORT_MISSING,        // job COMPLETED but no report was stored
    REPORT_UNREADABLE,     // a report exists but cannot be parsed
    NOT_REQUESTED,         // validation was deliberately skipped (job option skipValidation)
}

/**
 * The outward, binary validation result — the only vocabulary the UI (chip, latest-job summary,
 * KoSIT dialog header, validation report) is allowed to show. [resolveValidationOutcome] is the
 * single place that derives it.
 */
sealed interface ValidationOutcome {
    data object Pending : ValidationOutcome
    data object Successful : ValidationOutcome
    data class NotSuccessful(
        val reason: ValidationFailureReason,
        /** free-text detail, e.g. the engineError of a failed stage; may be blank */
        val detail: String? = null,
    ) : ValidationOutcome
}

/**
 * Single source of truth for a job's validation result — the grid chip, the latest-job summary,
 * the KoSIT dialog header and the validation report all derive from this one function instead of
 * re-deriving their own reading of [KositStatus]. Derivation table (PO decision, in
 * this order):
 *
 * 1. `job.status` in PENDING/DELEGATED/RUNNING → [ValidationOutcome.Pending]
 * 2. `job.status == FAILED` → `NotSuccessful(ABORTED, ...)`
 * 3. validation deliberately skipped for this job → `NotSuccessful(NOT_REQUESTED)`
 * 4. COMPLETED, no report stored → `NotSuccessful(REPORT_MISSING)`
 * 5. report parses VALID → [ValidationOutcome.Successful]
 * 6. report parses INVALID → `NotSuccessful(FINDINGS)`
 * 7. report parses ERROR → `NotSuccessful(VALIDATOR_UNAVAILABLE, ...)`
 * 8. report unparseable ([KositStatus.UNKNOWN]) → `NotSuccessful(REPORT_UNREADABLE)`
 */
fun resolveValidationOutcome(job: GenerationJob): ValidationOutcome {
    if (job.status == GenerationJobStatus.PENDING ||
        job.status == GenerationJobStatus.DELEGATED ||
        job.status == GenerationJobStatus.RUNNING
    ) {
        return ValidationOutcome.Pending
    }
    if (job.status == GenerationJobStatus.FAILED) {
        val detail = parseGenerationJobResultRef(job.resultRefs, "error")
        return ValidationOutcome.NotSuccessful(ValidationFailureReason.ABORTED, detail)
    }

    val report = job.kositReport
    val result = KositReportReader.read(report)

    // 1.4 — a report whose stages are *all* skipped means validation was deliberately skipped
    // for this job, not that a report is missing (checked before the blank-report branch below,
    // which a skipped-only report would otherwise also match if it were somehow blank).
    if (!report.isNullOrBlank() && result.stages.isNotEmpty() && result.stages.all { it.skipped }) {
        return ValidationOutcome.NotSuccessful(ValidationFailureReason.NOT_REQUESTED)
    }
    if (report.isNullOrBlank()) {
        return ValidationOutcome.NotSuccessful(ValidationFailureReason.REPORT_MISSING)
    }
    return when (result.status) {
        KositStatus.VALID -> ValidationOutcome.Successful
        KositStatus.INVALID -> ValidationOutcome.NotSuccessful(ValidationFailureReason.FINDINGS)
        KositStatus.ERROR -> ValidationOutcome.NotSuccessful(
            ValidationFailureReason.VALIDATOR_UNAVAILABLE,
            result.stages.firstNotNullOfOrNull { it.engineError?.takeIf(String::isNotBlank) }
                ?: result.payloadSummary.blankToNull(),
        )
        KositStatus.UNKNOWN -> ValidationOutcome.NotSuccessful(ValidationFailureReason.REPORT_UNREADABLE)
    }
}

/** Maps a [ValidationFailureReason] to its i18n key (values in `messages.properties`,
 *  `validation.reason.*`) — shared by every Vaadin component that shows the reason. */
internal fun validationReasonKey(reason: ValidationFailureReason): String = when (reason) {
    ValidationFailureReason.FINDINGS -> "validation.reason.findings"
    ValidationFailureReason.ABORTED -> "validation.reason.aborted"
    ValidationFailureReason.VALIDATOR_UNAVAILABLE -> "validation.reason.validatorUnavailable"
    ValidationFailureReason.REPORT_MISSING -> "validation.reason.reportMissing"
    ValidationFailureReason.REPORT_UNREADABLE -> "validation.reason.reportUnreadable"
    ValidationFailureReason.NOT_REQUESTED -> "validation.reason.notRequested"
}
