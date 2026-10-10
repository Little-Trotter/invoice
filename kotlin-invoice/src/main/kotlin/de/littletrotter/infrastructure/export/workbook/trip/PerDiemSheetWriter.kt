package de.littletrotter.infrastructure.export.workbook.trip

import de.littletrotter.application.export.TripWorkbookView
import de.littletrotter.infrastructure.export.workbook.ColumnWidths
import de.littletrotter.infrastructure.export.workbook.SheetWriter
import de.littletrotter.infrastructure.export.workbook.WorkbookWriter
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemDayDescriptionInput
import de.littletrotter.shared.PerDiemMealDeductions
import de.littletrotter.shared.distinctPerDiemParticipants
import de.littletrotter.shared.formatPerDiemDescription
import java.math.BigDecimal
import java.text.MessageFormat

/** Dedicated per-diem sheet. It keeps the general Posten sheet focused on expense lines while
 * mirroring the trip dialog's per-diem overview columns and row descriptions. */
object PerDiemSheetWriter {
    const val SHEET_NAME = "Verpflegung"
    private val WIDTHS = listOf(
        ColumnWidths.NAME, ColumnWidths.TEXT, ColumnWidths.DATE, ColumnWidths.QUANTITY,
        ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.NAME,
    )

    fun write(writer: WorkbookWriter, labels: PerDiemSheetLabels, view: TripWorkbookView): SheetWriter {
        require(labels.columnHeaders.size == WIDTHS.size) { "per-diem sheet requires ${WIDTHS.size} columns" }
        val sheet = writer.sheet(SHEET_NAME)
        sheet.header(labels.columnHeaders, WIDTHS)
        val participants = distinctPerDiemParticipants(view.perDiemDays.map { it.participant })
        val includeParticipant = participants.size > 1
        view.perDiemDays.sortedWith(compareBy(nullsLast()) { TripExportDates.parse(it.date) }).forEach { day ->
            sheet.row()
                .text(labels.kindLabel)
                .text(
                    formatPerDiemDescription(
                        day = PerDiemDayDescriptionInput(
                            description = day.description,
                            isArrivalDeparture = day.isArrivalDeparture,
                            deductions = PerDiemMealDeductions(
                                breakfast = day.breakfastDeduction,
                                lunch = day.lunchDeduction,
                                dinner = day.dinnerDeduction,
                            ),
                            participant = day.participant,
                            includeParticipant = includeParticipant,
                        ),
                        labels = labels.descriptionLabels,
                    ),
                )
                .date(TripExportDates.parse(day.date))
                .quantity(BigDecimal.ONE)
                .money(day.baseRate, view.currency)
                .money(day.claimableAmount, view.currency)
                .blank()
        }
        val total = sheet.noteRow().blank().blank().text(labels.totalRowLabel).blank().blank()
        total.money(view.perDiemDays.fold(Money.ZERO) { sum, day -> sum + day.claimableAmount }, view.currency).blank()
        if (view.perDiemDays.isNotEmpty()) {
            val dayCount = view.perDiemDays.mapNotNull { TripExportDates.parse(it.date) }.distinct().size
            val scope = when (participants.size) {
                0 -> MessageFormat.format(labels.scopeDaysOnlyPattern, dayCount)
                1 -> MessageFormat.format(labels.scopeNamedPattern, dayCount, participants.single())
                else -> MessageFormat.format(labels.scopeCountedPattern, dayCount, participants.size)
            }
            val scopeRow = sheet.noteRow().text(scope)
            repeat(WIDTHS.size - 1) { scopeRow.blank() }
        }
        return sheet
    }
}
