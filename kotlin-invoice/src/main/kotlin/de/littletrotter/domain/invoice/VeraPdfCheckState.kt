package de.littletrotter.domain.invoice

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationStageResult

/**
 * Where the asynchronous veraPDF conformance check of a stored document stands. The check runs
 * after the document is stored, so its stage is first pending and is replaced by the verdict once
 * the conformance service has answered.
 */
enum class VeraPdfCheckState {
    /** The check was not requested for this document (switched off, or validation skipped). */
    NOT_ENABLED,

    /** Requested; the verdict has not arrived yet. */
    PENDING,

    /** The document conforms. */
    PASSED,

    /** The document violates the profile; findings warn and never block. */
    FINDINGS,

    /** The check could not be performed (service unreachable, timeout); sending is not held back. */
    UNAVAILABLE,
    ;

    /**
     * Sending (IN_REVIEW to sent) waits exactly while the verdict is outstanding and the check is
     * switched on. Reading the current switch rather than the one at request time lets switching
     * the check off release documents that are still waiting. Findings and an impossible check
     * never block: the invoice itself was validated by the mandatory stages.
     */
    fun holdsBackSending(checkEnabled: Boolean): Boolean = this == PENDING && checkEnabled
}

/** The veraPDF state recorded in [kositReport] (the stored validation report JSON). */
fun veraPdfCheckStateOf(kositReport: String?): VeraPdfCheckState {
    val stage = KositReportPayload.decode(kositReport)?.validationRun?.stages
        ?.firstOrNull { it.stage == ValidationStageResult.VERAPDF }
        ?: return VeraPdfCheckState.NOT_ENABLED
    return when {
        stage.pending -> VeraPdfCheckState.PENDING
        stage.skipped -> VeraPdfCheckState.NOT_ENABLED
        stage.outcome == KositOutcome.ERROR -> VeraPdfCheckState.UNAVAILABLE
        stage.outcome == KositOutcome.INVALID -> VeraPdfCheckState.FINDINGS
        else -> VeraPdfCheckState.PASSED
    }
}
