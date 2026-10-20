package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.application.dto.ModelReport

/** Read side of the model analyses of an invoice, shaped for the detail view. */
fun interface InvoiceLlmAnalysisReadRepository {
    fun findReports(invoiceId: Long): List<ModelReport>
}
