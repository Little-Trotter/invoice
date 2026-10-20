package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage

/**
 * Appends the verdict of the asynchronous veraPDF check to the document it was requested for. The
 * verdict is committed before this call returns; a send that waited for it continues afterwards.
 */
interface RecordPdfCheckResultUseCase : CommandEntryPoint {

    fun record(verdict: PdfCheckVerdict)
}

/**
 * The conformance service's answer for the document of generation job [jobId]. [outcome] is
 * `ERROR` when the check could not be performed, then [engineError] names why; it says nothing
 * about the document.
 */
data class PdfCheckVerdict(
    val jobId: Long,
    val outcome: KositOutcome,
    val validator: String,
    val findings: List<ValidationMessage>,
    val engineError: String?,
)
