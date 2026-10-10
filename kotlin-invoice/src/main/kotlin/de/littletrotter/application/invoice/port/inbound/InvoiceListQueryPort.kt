package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.invoice.InvoiceOverviewSort
import de.littletrotter.domain.invoice.InvoiceStatus

/**
 * Input port of the "browse the invoice list" use case: everything the invoice list page
 * reads, and nothing else. [InvoiceExportFilter] is the list's filter snapshot (the XLS export
 * exports exactly the filtered list, so both share one type).
 */
interface InvoiceListQueryPort {
    /** One offset page of the filtered, sorted list. */
    fun findPage(filter: InvoiceExportFilter, page: InvoiceListPage): List<InvoiceOverviewDto>

    /** Number of invoices matching every field of [filter]. */
    fun count(filter: InvoiceExportFilter): Long

    /**
     * Matching invoices per [InvoiceStatus] for the quick-filter chip row. Honors every field of
     * [filter] except `status` itself, so a selected chip keeps its count and can be un-toggled.
     */
    fun countByStatus(filter: InvoiceExportFilter): Map<InvoiceStatus, Long>

    /** Distinct invoice years for the given seller/recipient (`null` = any), newest first. */
    fun availableYears(senderProfileId: Long?, recipientId: Long?): List<Int>

    /** Whether the invoice still exists — a list row may be stale after a concurrent delete. */
    fun exists(invoiceId: Long): Boolean
}

/** Offset page request of the invoice list. */
data class InvoiceListPage(
    val pageIndex: Int,
    val pageSize: Int,
    val sort: InvoiceOverviewSort,
)
