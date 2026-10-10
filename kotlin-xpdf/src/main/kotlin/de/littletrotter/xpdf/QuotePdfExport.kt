package de.littletrotter.xpdf

import de.littletrotter.shared.documentjob.QuoteDocumentPayload

/** A stored offer PDF: where it lives, its checksum and the validation report as JSON. */
class QuotePdfResult(val objectKey: String, val sha256: String, val validationReportJson: String)

/** Renders, checks and stores the PDF of one offer job; the messaging boundary drives it without knowing the stage chain. */
fun interface QuotePdfExport {
    fun export(jobId: String, payload: QuoteDocumentPayload): QuotePdfResult
}
