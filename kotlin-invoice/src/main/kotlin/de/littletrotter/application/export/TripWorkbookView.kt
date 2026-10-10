package de.littletrotter.application.export

import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemDayExportDto
import de.littletrotter.shared.TripExpenseExportDto
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The trip workbook's own POI-free view, assembled by
 * [de.littletrotter.infrastructure.export.workbook.trip.TripWorkbookExportService] from the mapped
 * [de.littletrotter.shared.InvoiceItemDto]/[de.littletrotter.shared.TripExportDto] (never the
 * UI-facing `ItemDto`/`TripDto`), mirroring [InvoicePositionExportRow]'s own "one row/view type per
 * sheet family" idiom: the two sheet writers in `infrastructure.export.workbook.trip` take this
 * plain data, never the DTOs or [de.littletrotter.application.invoice.InvoiceDtoMapper] itself,
 * keeping the onion-architecture boundary intact.
 *
 * [expenses]/[perDiemDays] are kept as the export DTO's own row types rather than flattened further
 * — [de.littletrotter.infrastructure.export.workbook.trip.TripExpenseSheetWriter] is the one place
 * that turns them into sheet rows, and it does so directly off the wire shape already defined.
 */
data class TripWorkbookView(
    val positionId: String,
    /** BT-153 ([de.littletrotter.shared.InvoiceItemDto.description]). */
    val title: String,
    /** BT-154 ([de.littletrotter.shared.InvoiceItemDto.itemDescription]). */
    val purpose: String?,
    val periodFrom: LocalDate?,
    val periodTo: LocalDate?,
    val invoiceNumber: String,
    val invoiceDate: LocalDate?,
    val customerName: String?,
    val costCenter: String?,
    /** [de.littletrotter.shared.InvoiceItemDto.taxRatePercent] — never absent on the wire. */
    val taxRatePercent: BigDecimal?,
    val calculatedNet: Money,
    val fixedNetPrice: Money?,
    val receiptsCount: Int,
    val currency: Currency,
    val expenses: List<TripExpenseExportDto>,
    val perDiemDays: List<PerDiemDayExportDto>,
)
