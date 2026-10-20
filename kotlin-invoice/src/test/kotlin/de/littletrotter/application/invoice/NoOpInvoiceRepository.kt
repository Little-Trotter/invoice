package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.repository.InvoiceRepository

/**
 * No-op InvoiceRepository used by the secondary (test) constructor of [GenerationJobService].
 * Returns null for all lookups so that invoice validation is silently skipped in unit tests
 * that were written before the InvoiceRepository requirement was introduced.
 */
internal object NoOpInvoiceRepository : InvoiceRepository, de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository, de.littletrotter.application.invoice.port.outbound.InvoiceListingRepository {
    override fun findById(id: Long): Invoice? = null
    override fun findByIdWithItems(id: Long): Invoice? = null
    override fun findByInvoiceNumber(number: String): Invoice? = null
    override fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice? = null
    override fun findAll(pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
    override fun countForOverview(filterText: String?, status: InvoiceStatus?): Long = 0
    override fun count(): Long = 0
    override fun countByStatus(status: InvoiceStatus): Long = 0
    override fun findByRecipientId(recipientId: Long, pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
    override fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
    override fun existsByBillingAddressId(addressId: Long): Boolean = false
    override fun existsByDeliveryAddressId(addressId: Long): Boolean = false
    override fun findInvoiceByItemId(itemId: Long): Invoice? = null
    override fun findAllForRecalculation(): List<Invoice> = emptyList()
        override fun countOpenInvoicesForSellerVatCountry(senderProfileId: Long, country: de.littletrotter.shared.country.Country): Long = 0
        override fun existsByB2bProfileId(b2bProfileId: Long): Boolean = false
        override fun findAllIdsForRecalculation(): List<Long> = findAllForRecalculation().mapNotNull { it.id }
        override fun findByOriginalInvoiceNumber(number: String): List<de.littletrotter.domain.invoice.Invoice> =
            findAll(0, Int.MAX_VALUE).filter { it.originalInvoiceNumber?.value == number }
        override fun findFinalizedInvoiceNumberAssignments(): List<de.littletrotter.domain.repository.InvoiceNumberAssignment> = emptyList()
        override fun findItemPositions(itemIds: Collection<Long>): Map<Long, String> = emptyMap()
    override fun save(invoice: Invoice): Invoice = throw UnsupportedOperationException("NoOpInvoiceRepository is read-only")
    override fun forceVersionIncrement(invoice: Invoice): Unit =
        throw UnsupportedOperationException("NoOpInvoiceRepository is read-only")
}
