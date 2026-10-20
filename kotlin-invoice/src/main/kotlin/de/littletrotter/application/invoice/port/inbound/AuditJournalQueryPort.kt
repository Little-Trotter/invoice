package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.application.dto.JournalGlobalFilter
import de.littletrotter.application.dto.InvoiceAuditActionLine

/** Input port of the change journal views: one invoice's history and the global journal, one line per business action. */
interface AuditJournalQueryPort {
    /** One invoice's history, one line per business action, paged and ordered by [direction] (newest first by default). */
    fun findActionsByInvoice(
        invoiceId: Long,
        pageIndex: Int,
        pageSize: Int,
        direction: SortDirection = SortDirection.DESC,
    ): List<InvoiceAuditActionLine>

    /** Action count for [findActionsByInvoice], driving the pagination control. */
    fun countActionsByInvoice(invoiceId: Long): Long

    /** All actions matching [filter], paged and ordered by [direction] (newest first by default). */
    fun findGlobalActions(
        filter: JournalGlobalFilter,
        pageIndex: Int,
        pageSize: Int,
        direction: SortDirection = SortDirection.DESC,
    ): List<InvoiceAuditActionLine>

    /** Action count for [filter], driving the pagination control. */
    fun countGlobalActions(filter: JournalGlobalFilter): Long

    /** Display names of the recipients the given page of [lines] refers to, keyed by recipient id. */
    fun recipientNames(lines: List<InvoiceAuditActionLine>): Map<Long, String>

    /** File names of the receipts the given page of [lines] refers to, keyed by receipt id. */
    fun receiptFileNames(lines: List<InvoiceAuditActionLine>): Map<Long, String>

    /** Position labels of the items the given page of [lines] refers to, keyed by item id. */
    fun itemPositions(lines: List<InvoiceAuditActionLine>): Map<Long, String>
}
