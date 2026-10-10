package de.littletrotter.domain.repository

import de.littletrotter.domain.invoice.InvoiceLlmAnalysis

interface InvoiceLlmAnalysisRepository {
    fun findByInvoiceId(invoiceId: Long): List<InvoiceLlmAnalysis>
    fun findByInvoiceIdAndModelName(invoiceId: Long, modelName: String): InvoiceLlmAnalysis?
    fun save(analysis: InvoiceLlmAnalysis): InvoiceLlmAnalysis
}
