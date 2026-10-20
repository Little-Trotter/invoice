package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.repository.InvoiceNumberAssignment

/** Listings, counts and projections over all invoices, read only. */
interface InvoiceListingRepository {
    fun findAll(pageIndex: Int = 0, pageSize: Int = 60): List<Invoice>

    fun countForOverview(filterText: String? = null, status: InvoiceStatus? = null): Long

    fun count(): Long

    fun countByStatus(status: InvoiceStatus): Long

    /**
     * `(invoiceId, invoiceNumberSequence)` for each finalized invoice with a number. Basis for the
     * reconciliation check: orphaned journal entries, or invoice numbers without a journal entry.
     * Projects only the two columns instead of loading whole aggregates.
     */
    fun findFinalizedInvoiceNumberAssignments(): List<InvoiceNumberAssignment>

    /**
     * The **ids** of the invoices the recalculation sweep would load, for callers that sweep the
     * whole set and can tolerate individual rows being unreadable.
     *
     * Materialising an [Invoice] runs every `AttributeConverter` on the row, and
     * [de.littletrotter.domain.invoice.InvoiceNumberConverter] rejects a value that does not match
     * one of the three accepted formats. A single malformed `invoice_number` anywhere in the table
     * would otherwise make the sweep's own `SELECT` throw before the caller's per-invoice error
     * handling can see it. Selecting ids is a scalar projection: no entity is built, so no
     * converter runs, and the caller can load each invoice inside its own guard.
     */
    fun findAllIdsForRecalculation(): List<Long>
}
