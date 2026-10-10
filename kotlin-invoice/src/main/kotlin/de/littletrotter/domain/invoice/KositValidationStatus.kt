package de.littletrotter.domain.invoice

import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.KositReportPayload

/**
 * PO decision 2026-09-13 — the one place a persisted [GenerationJob.kositReport] blob is mapped
 * onto the domain's own [ValidationStatus]. Shared by [de.littletrotter.application.invoice
 * .DocumentGeneratedReactionHandler]'s validation-status-resolving reaction and
 * [de.littletrotter.application.invoice.GenerationJobService.resolveSendOutcome] — both derive the
 * identical outcome from the identical report, so this exists once instead of twice (a possible
 * double *write* of the same value between the two callers is a known, harmless overlap, never a
 * diverging one — see `resolveSendOutcome`'s own KDoc).
 *
 * Returns `null` only when [kositReport] itself does not decode at all (missing/malformed) — the
 * caller decides what "no report" means for its own purpose (e.g.
 * DocumentGeneratedReactionHandler falls through to an older job's report before finally
 * defaulting to [ValidationStatus.UNAVAILABLE]). A report that DOES decode but carries an
 * unrecognized/"ERROR" outcome resolves to [ValidationStatus.UNAVAILABLE] (non-null) — a genuine
 * resolved-but-erroneous result, not "no report".
 */
fun resolveValidationStatusFromKositReport(kositReport: String?): ValidationStatus? =
    KositReportPayload.decode(kositReport)?.status?.let { status ->
        when (status) {
            "VALID" -> ValidationStatus.VALID
            "INVALID" -> ValidationStatus.INVALID
            else -> ValidationStatus.UNAVAILABLE
        }
    }
