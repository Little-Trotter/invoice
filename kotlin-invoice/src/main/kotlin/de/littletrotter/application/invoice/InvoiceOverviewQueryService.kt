package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceCursor
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Money
import java.time.LocalDate

/** Bundles [InvoiceOverviewSortField] and its [SortDirection] so the
 *  [InvoiceOverviewQueryService.findOverview] overload stays at 6 declared parameters, alongside
 *  [InvoiceOverviewDimensions] for the filter dimensions, under detekt's `LongParameterList`
 *  threshold. */
data class InvoiceOverviewSort(
    val field: InvoiceOverviewSortField,
    val dir: SortDirection,
)

/**
 * Read path for the invoice overview/list page: pagination (offset and cursor-based), counts and
 * simple filtered totals. Statistics-page queries (yearly/monthly/quarterly aggregates, rankings,
 * trends, distribution) live in [InvoiceStatisticsQueryPort] — split out in Z2 because this
 * class used to serve both audiences.
 */
interface InvoiceOverviewQueryService {
    fun findAllOverview(): List<InvoiceOverviewDto>
    /**
     * OFFSET-based paged query (0-based pageIndex). Use for UI DataProviders that need
     * random-access page navigation. Prefer [findOverviewAfterCursor] for sequential
     * scrolling when deep pages are expected.
     */
    fun findOverview(pageIndex: Int, pageSize: Int, filterText: String? = null, status: InvoiceStatus? = null, reverseCharge: Boolean? = null): List<InvoiceOverviewDto>

    fun findOverview(
        pageIndex: Int,
        pageSize: Int,
        filterText: String? = null,
        status: InvoiceStatus? = null,
        sortField: InvoiceOverviewSortField,
        sortDir: SortDirection,
    ): List<InvoiceOverviewDto> = findOverview(pageIndex, pageSize, filterText, status, sortField, sortDir, null)

    /** As above, additionally filtered on [Invoice.reverseCharge][de.littletrotter.domain.invoice.Invoice.reverseCharge]
     *  (`null` = no filter). A separate overload rather than a defaulted parameter on the method
     *  above, so existing callers/overrides are unaffected. */
    fun findOverview(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        status: InvoiceStatus?,
        sortField: InvoiceOverviewSortField,
        sortDir: SortDirection,
        reverseCharge: Boolean?,
    ): List<InvoiceOverviewDto> = findOverview(pageIndex, pageSize, filterText, status, sortField, sortDir)

    /** As above, additionally filtered on [de.littletrotter.domain.invoice.Invoice
     *  .validationStatus], [Invoice.recipient][de.littletrotter.domain.invoice.Invoice.recipient]
     *  ("Kunde"), the resolved seller (`selectedSeller` or `senderProfile`, "Anbieter"), and an
     *  invoice-date range (`dateFrom`/`dateTo`, both inclusive, either or both `null` for a
     *  half-open range) — all bundled into [dimensions] (each `null` field = no filter on
     *  that dimension) so this method stays under detekt's `LongParameterList` threshold. Backs the
     *  invoice-list quick-filter chip row (clicking a chip toggles [InvoiceOverviewDimensions
     *  .validationStatus] on top of whatever text/status/reverseCharge filter is already active)
     *  and the Kunde/Anbieter/Zeitraum filters. */
    fun findOverview(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        status: InvoiceStatus?,
        sort: InvoiceOverviewSort,
        dimensions: InvoiceOverviewDimensions,
    ): List<InvoiceOverviewDto> = findOverview(pageIndex, pageSize, filterText, status, sort.field, sort.dir, dimensions.reverseCharge)

    /**
     * Seek-based (keyset) pagination. Avoids the O(offset) scan cost of OFFSET queries.
     *
     * Pass `cursor = null` for the first page. To advance, build the next cursor from
     * the last element of the returned page via [InvoiceCursor.from]. Returns an empty
     * list when no more rows exist.
     *
     * Sort order is always `invoiceDate DESC, id DESC`.
     */
    fun findOverviewAfterCursor(
        cursor: InvoiceCursor?,
        pageSize: Int,
        filterText: String? = null,
        status: InvoiceStatus? = null,
        dimensions: InvoiceOverviewDimensions = InvoiceOverviewDimensions(),
    ): List<InvoiceOverviewDto>

    /**
     * Returns the total number of invoices matching optional filters. Implementations should
     * provide an efficient count (DB-level) when possible.
     */
    fun countOverview(filterText: String? = null, status: InvoiceStatus? = null): Long =
        countOverview(filterText, status, null)

    /** As above, additionally filtered on reverseCharge — see the [findOverview] overload above. */
    fun countOverview(filterText: String?, status: InvoiceStatus?, reverseCharge: Boolean?): Long =
        countOverview(filterText, status)

    /** As above, additionally filtered on validationStatus. */
    fun countOverview(filterText: String?, status: InvoiceStatus?, reverseCharge: Boolean?, validationStatus: ValidationStatus?): Long =
        countOverview(filterText, status, reverseCharge)

    /** As above, additionally filtered on recipient/seller/date-range via [dimensions]
     *  (see the [findOverview] overload above for parameter semantics). */
    fun countOverview(
        filterText: String?,
        status: InvoiceStatus?,
        dimensions: InvoiceOverviewDimensions,
    ): Long = countOverview(filterText, status, dimensions.reverseCharge, dimensions.validationStatus)

    /**
     * Aggregates net, vat and gross amounts across matching invoices.
     */
    fun getTotals(filterText: String? = null, status: InvoiceStatus? = null): Triple<Money, Money, Money>

    /**
     * One batched `GROUP BY validationStatus` count, honoring the same
     * text/status/reverseCharge filter as [findOverview]/[countOverview] but never
     * `validationStatus` itself (the quick-filter chip row needs every state's count under the
     * current filter, including the one the user might currently have selected, so a second
     * click can un-toggle it). Backs the invoice-list quick-filter chip row — a single query,
     * never one [countOverview] call per state (no N+1).
     */
    fun countOverviewGroupedByValidationStatus(
        filterText: String? = null,
        status: InvoiceStatus? = null,
        reverseCharge: Boolean? = null,
        recipientId: Long? = null,
        senderProfileId: Long? = null,
        dateFrom: LocalDate? = null,
        dateTo: LocalDate? = null,
    ): Map<ValidationStatus, Long>

    /**
     * One batched `GROUP BY status` count backing the invoice-list quick-filter chip row. Mirrors
     * [countOverviewGroupedByValidationStatus] exactly, with the two roles swapped: it honors
     * `validationStatus` (now an ordinary toolbar filter) but never `status` itself, so every
     * state's count stays present under the current filter — including a currently-selected one,
     * whose chip must survive for a second click to un-toggle it.
     */
    fun countOverviewGroupedByStatus(
        filterText: String? = null,
        validationStatus: ValidationStatus? = null,
        reverseCharge: Boolean? = null,
        recipientId: Long? = null,
        senderProfileId: Long? = null,
        dateFrom: LocalDate? = null,
        dateTo: LocalDate? = null,
    ): Map<InvoiceStatus, Long>
}
