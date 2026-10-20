package de.littletrotter.infrastructure.export.workbook.trip

import java.time.LocalDate

/**
 * The export DTOs ([de.littletrotter.shared.TripExpenseExportDto.travelDate],
 * [de.littletrotter.shared.PerDiemDayExportDto.date]) carry ISO dates as plain `String?` (the wire
 * format every DTO field in this family uses, see [de.littletrotter.shared.InvoiceItemDto
 * .servicePeriodFrom]'s own KDoc), but a sheet cell wants a genuine typed
 * [de.littletrotter.infrastructure.export.workbook.RowWriter.date] value, not text — this one-line
 * parse is shared by [TripWorkbookExportService] (the trip's own top-level dates) and
 * [TripExpenseSheetWriter] (each expense/per-diem row's own date) rather than repeated at both call
 * sites.
 */
internal object TripExportDates {
    fun parse(iso: String?): LocalDate? = iso?.let(LocalDate::parse)
}
