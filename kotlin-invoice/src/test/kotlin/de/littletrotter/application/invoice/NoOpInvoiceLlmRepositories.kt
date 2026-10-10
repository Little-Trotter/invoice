package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.repository.InvoiceLlmAnalysisRepository
import de.littletrotter.domain.repository.InvoiceRepository

/**
 * No-op InvoiceRepository used by the secondary (test) constructor of [GenerationJobService].
 * Returns null for all lookups so that invoice validation is silently skipped in unit tests
 * that were written before the InvoiceRepository requirement was introduced.
 */
internal object NoOpInvoiceRepository : InvoiceRepository {
    override fun findById(id: Long): Invoice? = null
    override fun findByIdWithItems(id: Long): Invoice? = null
    override fun findByInvoiceNumber(number: String): Invoice? = null
    override fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice? = null
    override fun findAll(pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
    override fun findOverviewPage(pageIndex: Int, pageSize: Int, filterText: String?, status: InvoiceStatus?): List<Invoice> = emptyList()
    override fun countForOverview(filterText: String?, status: InvoiceStatus?): Long = 0
    override fun count(): Long = 0
    override fun countByStatus(status: InvoiceStatus): Long = 0
    override fun findByRecipientId(recipientId: Long, pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
    override fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
    override fun existsByBillingAddressId(addressId: Long): Boolean = false
    override fun existsByDeliveryAddressId(addressId: Long): Boolean = false
    override fun findInvoiceByItemId(itemId: Long): Invoice? = null
    override fun findAllForRecalculation(): List<Invoice> = emptyList()
    override fun save(invoice: Invoice): Invoice = throw UnsupportedOperationException("NoOpInvoiceRepository is read-only")
    override fun forceVersionIncrement(invoice: Invoice): Unit =
        throw UnsupportedOperationException("NoOpInvoiceRepository is read-only")
}

/**
 * V3 — no-op InvoiceLlmAnalysisRepository used by the secondary (test) constructors of
 * [GenerationJobService]. Silently drops writes and returns nothing, for unit tests written
 * before the LLM status-chain requirement existed.
 */
internal object NoOpLlmAnalysisRepository : InvoiceLlmAnalysisRepository {
    override fun findByInvoiceId(invoiceId: Long): List<InvoiceLlmAnalysis> = emptyList()
    override fun findByInvoiceIdAndModelName(invoiceId: Long, modelName: String): InvoiceLlmAnalysis? = null
    override fun save(analysis: InvoiceLlmAnalysis): InvoiceLlmAnalysis = analysis
}
