package de.littletrotter.application.dto

import java.time.LocalDate

/**
 * Opaque cursor for seek-based (keyset) pagination of the invoice overview.
 *
 * Encodes the last row of the previous page using the stable sort key
 * `(invoiceDate DESC, id DESC)`. Pass `null` to start from the first page.
 *
 * This avoids the O(offset) scan cost of OFFSET-based pagination, which
 * degrades linearly as the requested page number grows.
 *
 * Usage:
 * ```
 * var cursor: InvoiceCursor? = null
 * do {
 *     val page = service.findOverviewAfterCursor(cursor, pageSize = 20)
 *     // ... process page ...
 *     cursor = InvoiceCursor.from(page) // null when page is empty or last
 * } while (cursor != null)
 * ```
 */
data class InvoiceCursor(
    val lastDate: LocalDate,
    val lastId: Long,
) {
    companion object {
        /**
         * Builds a cursor from the last element of a page.
         * Returns null if the page is empty (signals end of results).
         */
        fun from(page: List<InvoiceOverviewDto>): InvoiceCursor? {
            val last = page.lastOrNull() ?: return null
            val date = last.invoiceDate ?: return null
            val id = last.id ?: return null
            return InvoiceCursor(date, id)
        }
    }
}
