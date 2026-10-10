package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemDayDescriptionInput
import de.littletrotter.shared.PerDiemDescriptionLabels
import de.littletrotter.shared.PerDiemMealDeductions
import de.littletrotter.shared.distinctPerDiemParticipants
import de.littletrotter.shared.formatPerDiemDescription
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.Section
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.i18n.TranslationProvider

/** The "Beleg" cell for a row that never carries a receipt — a per-diem day, and the trip
 *  expense grid's own "none assigned" case. */
internal fun tripNoReceiptIndicator(): Component = Span("–")

/**
 * The read-only per-diem block of [TripDialog]'s "Übersicht" tab: a caption line (the
 * block's own description, left; `N Tage · M Teilnehmer`, right) above a grid that mirrors the
 * trip expense grid column for column (Art · Beschreibung · Datum · Menge · Einzelpreis · Gesamt ·
 * Beleg), so the two tables read as one list split by section. The former separate
 * "Teilnehmer"/"Tagesart" columns joined the "Beschreibung" cell; "Menge" is always `1` (one row
 * is one day), "Einzelpreis" is [PerDiemOverviewRow.baseRate], "Beleg" is always empty. Reads
 * exclusively from [perDiemDaysEditor]; [refresh] rebinds everything after each editor change.
 */
internal class TripPerDiemOverview(
    private val perDiemDaysEditor: PerDiemDaysEditor,
    private val formatCurrency: (Money) -> String,
) : TranslationProvider {
    private val root = VerticalLayout().apply { isPadding = false; isSpacing = true; setWidthFull() }
    override fun getComponent(): Component = root

    /** Per-diem-as-a-Posten follow-up (PO 14.09.2026) — the participant joins the Beschreibung
     *  cell only while the trip has more than one distinct participant. Set by [refresh] before
     *  the grid's rows are (re)bound, so [descriptionCell] always reads the matching value. */
    private var multipleParticipants = false
    private lateinit var amountColumn: Grid.Column<PerDiemOverviewRow>

    /** The block's own optional description, visible read-only in Übersicht too. */
    private val descriptionCaption = Span().apply { addClassName("text-muted") }
    private val scopeCaption = Span().apply { addClassName("text-muted") }
    private val grid = buildGrid()

    /** The bordered [Section] [TripDialog] adds below the expense grid; its visibility is toggled
     *  by the dialog (hidden until the first per-diem entry exists). */
    val section: Section

    init {
        val captionRow = HorizontalLayout(descriptionCaption, scopeCaption).apply {
            isPadding = false; setWidthFull()
            justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
        }
        root.add(captionRow, grid)
        section = Section(tr("invoiceDetail.trip.perDiem.title"), "sp-section-bar--blue", root).apply {
            setWidthFull()
            addClassName("lt-border-box")
        }
    }

    fun refresh() {
        val rows = perDiemDaysEditor.overviewRows()
        multipleParticipants = distinctPerDiemParticipants(rows.map { it.participant }).size > 1
        grid.setItems(rows)
        refreshScope(rows)
        amountColumn.setFooter(formatCurrency(perDiemDaysEditor.currentTotal()))
        descriptionCaption.text = perDiemDaysEditor.descriptionField.value
    }

    private fun buildGrid(): DataTable<PerDiemOverviewRow> {
        val grid = DataTable(PerDiemOverviewRow::class.java, false).apply {
            setId(TripDialog.PER_DIEM_OVERVIEW_GRID_ID)
            setWidthFull()
        }
        grid.addComponentColumn { kindCell() }
            .setHeader(tr("invoiceDetail.trip.expenses.overview.kind")).asCompactColumn().isSortable = false
        grid.addComponentColumn { row -> descriptionCell(row) }
            .setHeader(tr("invoiceDetail.trip.expense.description")).asTextColumn(TextColumnWidth.MEDIUM).isSortable = false
        grid.addColumn { row -> DateFormatter.formatWithWeekday(row.date) }
            .setHeader(tr("invoiceDetail.trip.expenses.overview.date")).asCompactColumn().setSortable(false)
            .setFooter(tr("invoiceDetail.trip.perDiem.overview.footer.total"))
        grid.addColumn { "1" }
            .setHeader(tr("invoiceDetail.trip.expenses.overview.quantity")).setSortable(false).asNumericColumn()
        grid.addColumn { row -> formatCurrency(row.baseRate) }
            .setHeader(tr("invoiceDetail.trip.expenses.overview.unitPrice")).setSortable(false).asNumericColumn()
        amountColumn = grid.addColumn { row -> formatCurrency(row.amount) }
            .setHeader(tr("invoiceDetail.trip.expenses.overview.total")).setSortable(false).asNumericColumn()
        grid.addComponentColumn { tripNoReceiptIndicator() }
            .setHeader(tr("invoiceDetail.trip.expenses.overview.receipt")).asCompactColumn().isSortable = false
        return grid
    }

    /** The "Art" cell every per-diem row shares — a fixed kind (a per-diem day carries no trip
     *  expense kind of its own). */
    private fun kindCell(): Component {
        val label = tr("invoiceDetail.trip.perDiem.overview.kind")
        val icon = VaadinIcon.CUTLERY.create().apply {
            setSize("1rem")
            element.setAttribute("title", label)
            element.setAttribute("aria-label", label)
        }
        return HorizontalLayout(icon).apply {
            isPadding = false; isSpacing = false
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        }
    }

    /** The "Beschreibung" cell: shared description, day type and meal deduction markers joined
     *  by "·", plus the participant once [multipleParticipants] is true, plus the out-of-range
     *  warning icon. */
    private fun descriptionCell(row: PerDiemOverviewRow): Component {
        val text = formatPerDiemDescription(
            day = PerDiemDayDescriptionInput(
                description = perDiemDaysEditor.descriptionField.value,
                isArrivalDeparture = row.arrivalDeparture,
                deductions = PerDiemMealDeductions(
                    breakfast = row.breakfast,
                    lunch = row.lunch,
                    dinner = row.dinner,
                ),
                participant = row.participant,
                includeParticipant = multipleParticipants,
            ),
            labels = PerDiemDescriptionLabels(
                arrivalDeparture = tr("invoiceDetail.trip.perDiem.overview.arrivalDeparture"),
                fullDay = tr("invoiceDetail.trip.perDiem.overview.fullDay"),
                breakfastDeduction = tr("invoiceDetail.trip.perDiem.overview.deduction.breakfast"),
                lunchDeduction = tr("invoiceDetail.trip.perDiem.overview.deduction.lunch"),
                dinnerDeduction = tr("invoiceDetail.trip.perDiem.overview.deduction.dinner"),
            ),
        )
        val textSpan = Span(text)
        val warningIcon = if (row.outOfRange) outOfRangeIndicator() else null
        return HorizontalLayout(*listOfNotNull(textSpan, warningIcon).toTypedArray()).apply {
            isPadding = false; isSpacing = true; addClassName("lt-gap-035")
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        }
    }

    private fun outOfRangeIndicator(): Component =
        VaadinIcon.WARNING.create().apply {
            setSize("1rem")
            addClassName("lt-text-error-strong")
            element.setAttribute("title", tr("invoiceDetail.perDiem.entry.outOfRange"))
            element.setAttribute("aria-label", tr("invoiceDetail.perDiem.entry.outOfRange"))
        }

    /** Point 5/6 of the ergonomics follow-up: the caption's right-aligned scope. */
    private fun refreshScope(rows: List<PerDiemOverviewRow>) {
        val distinctDates = rows.mapNotNull { it.date }.distinct().size
        val distinctParticipants = distinctPerDiemParticipants(rows.map { it.participant })
        scopeCaption.text = when {
            rows.isEmpty() -> ""
            distinctParticipants.isEmpty() -> tr("invoiceDetail.trip.perDiem.overview.scope.daysOnly", distinctDates)
            distinctParticipants.size == 1 -> tr("invoiceDetail.trip.perDiem.overview.scope.named", distinctDates, distinctParticipants.first())
            else -> tr("invoiceDetail.trip.perDiem.overview.scope.counted", distinctDates, distinctParticipants.size)
        }
    }
}
