package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Hr
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.TripExpenseCommand
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.travel.port.inbound.HotelRatePrefillQueryPort
import de.littletrotter.application.travel.port.inbound.MileageRatePrefillQueryPort
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.i18n.TranslationProvider
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import de.littletrotter.ui.component.ActionIcons

private const val ARIA_LABEL = "aria-label"

/** [TripExpenseRowsEditor]'s two optional rate resolvers, bundled into one constructor parameter
 *  purely to keep that constructor under detekt's `LongParameterList` threshold; each is
 *  otherwise unrelated to the other, both only ever forwarded straight into [TripExpenseRowPrefill]. */
internal data class TripRateResolvers(
    val hotelRateResolver: HotelRatePrefillQueryPort?,
    val mileageRateResolver: MileageRatePrefillQueryPort?,
)

/**
 * The trip dialog's expense rows ("Posten"): [expenseRows] is the single source of truth
 * both the read-only [overviewGrid] ("Übersicht" tab) and the editable [container] ("Bearbeiten"
 * tab) render from, plus the per-kind "+" buttons ([addButtons]), the per-kind prefill
 * ([TripExpenseRowPrefill]) and the row reorder/delete/receipt-assignment controls. [TripDialog] owns
 * the header, per-diem block, footer and save; it reads [currentTotal]/[commands] back and is
 * told about every row mutation through [onChange] (marks the dialog dirty, refreshes the footer).
 */
internal class TripExpenseRowsEditor(
    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort,
    recipientQueryPort: RecipientQueryPort,
    rateResolvers: TripRateResolvers,
    private val invoice: InvoiceDetailDto,
    private val existingItem: ItemDto?,
    private val onChange: () -> Unit,
    // Appended after onChange (not before): keeping onChange the last declared parameter is
    // what lets its existing trailing-lambda call sites keep compiling. See
    // TripExpenseRowPrefill.today's own KDoc.
    today: LocalDate,
) : TranslationProvider {
    private val prefill = TripExpenseRowPrefill(recipientQueryPort, rateResolvers.hotelRateResolver, rateResolvers.mileageRateResolver, invoice, today)

    val container = VerticalLayout().apply { isPadding = false; isSpacing = true; setWidthFull() }
    override fun getComponent(): Component = container

    private val expenseRows = TripExpenseRowList(prefill, existingItem, today)
    private val grid: DataTable<TripExpenseRow> = buildOverviewGrid()
    private val rowEditor = TripExpenseRowEditor(
        rows = expenseRows,
        prefill = prefill,
        labelHost = container,
        formatCurrency = ::formatCurrency,
        onChange = onChange,
        rerender = ::render,
    )

    /** The read-only "Übersicht" grid. */
    val overviewGrid: Component get() = grid

    /** The "+ <kind>" buttons, in [TRIP_EXPENSE_KIND_DISPLAY_ORDER]. */
    fun addButtons(): List<Button> = TRIP_EXPENSE_KIND_DISPLAY_ORDER.map { addButton(it) }

    /** Net sum of all expense rows, the same [de.littletrotter.domain.invoice.ItemAmounts.calculate] formula
     *  [TripExpenseRow.netAmount] uses. */
    fun currentTotal(): Money = expenseRows.total()

    /** A row other than travel time without a description blocks save. */
    fun hasMissingDescription(): Boolean = expenseRows.hasMissingDescription()

    fun commands(): List<TripExpenseCommand> = expenseRows.commands()

    // Never made sortable (DataTable otherwise leaves Grid's own column-header sort UI
    // available): a click-driven re-sort here would silently diverge from expenseRows' own order,
    // the very thing the "Bearbeiten" row list edits — the spec's explicit requirement is that
    // order survives switching tabs, not that the grid can produce a second, competing order.
    private fun buildOverviewGrid(): DataTable<TripExpenseRow> =
        DataTable(TripExpenseRow::class.java, false).apply {
            setWidthFull()
            addComponentColumn { row -> kindCell(row.kind) }
                .setHeader(tr("invoiceDetail.trip.expenses.overview.kind")).asCompactColumn().isSortable = false
            addColumn { row -> row.description }
                .setHeader(tr("invoiceDetail.trip.expense.description")).asTextColumn(TextColumnWidth.MEDIUM).isSortable = false
            addColumn { row -> formatDate(row.travelDate) }
                .setHeader(tr("invoiceDetail.trip.expenses.overview.date")).asCompactColumn().isSortable = false
            addColumn { row -> formatDecimal(row.quantity) }
                .setHeader(tr("invoiceDetail.trip.expenses.overview.quantity")).setSortable(false).asNumericColumn()
            addColumn { row -> formatCurrency(row.unitPriceNet) }
                .setHeader(tr("invoiceDetail.trip.expenses.overview.unitPrice")).setSortable(false).asNumericColumn()
            addColumn { row -> formatCurrency(row.netAmount()) }
                .setHeader(tr("invoiceDetail.trip.expenses.overview.total")).setSortable(false).asNumericColumn()
            addComponentColumn { row -> receiptIndicator(row) }
                .setHeader(tr("invoiceDetail.trip.expenses.overview.receipt")).asCompactColumn().isSortable = false
        }

    private fun receiptIndicator(row: TripExpenseRow): Component =
        if (row.receiptId != null) {
            ActionIcons.ATTACHMENTS.create().apply { setSize("1rem"); element.setAttribute(ARIA_LABEL, tr("invoiceDetail.items.receipts.count", "1")) }
        } else {
            tripNoReceiptIndicator()
        }

    // ── Expenses ─────────────────────────────────────────────────────────────

    private fun addButton(kind: TripExpenseKind): Button =
        AppButtons.secondary("+ ${tr(tripExpenseKindLabelKey(kind))}") { addRow(kind) }.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
        }

    private fun addRow(kind: TripExpenseKind) {
        expenseRows.add(kind)
        onChange()
        render()
    }

    private fun tripReceipts(): List<ReceiptRowDto> = existingItem?.id?.let { invoiceReceiptQueryPort.findReceiptsForItem(it) }.orEmpty()

    /** Rebuilds both "Posten" views from [expenseRows] — the single source of truth both the
     *  read-only [grid] and the editable [container] render from, so
     *  switching tabs neither reorders nor drops an in-progress edit. Called after every
     *  mutation (add/delete/move/kind change/receipt change) instead of patching either view in
     *  place, the same "full re-render" idiom this dialog already used for add/delete/move. */
    fun render() {
        container.removeAll()
        if (expenseRows.isEmpty()) {
            container.add(Span(tr("invoiceDetail.trip.expenses.empty")).apply { addClassName("text-muted") })
        } else {
            val receipts = tripReceipts()
            expenseRows.snapshot().forEachIndexed { index, row ->
                if (index > 0) container.add(Hr())
                container.add(rowEditor.build(row, index, receipts))
            }
        }
        grid.setItems(expenseRows.snapshot())
    }

    private fun formatDate(date: LocalDate?): String =
        date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.GERMAN)) ?: "–"

    private fun formatDecimal(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private fun formatCurrency(money: Money): String = formatCurrency(money.amount, invoice.currencyEnum())
}

/** `invoiceDetail.trip.kind.*` — one key per [TripExpenseKind], used by both the kind ComboBox and
 *  (prefixed with "+ ") the per-kind add buttons, so the two never carry a different label for the
 *  same kind (DRY). */
internal fun tripExpenseKindLabelKey(kind: TripExpenseKind): String = when (kind) {
    TripExpenseKind.HOTEL -> "invoiceDetail.trip.kind.hotel"
    TripExpenseKind.TRANSPORT -> "invoiceDetail.trip.kind.transport"
    TripExpenseKind.MILEAGE -> "invoiceDetail.trip.kind.mileage"
    TripExpenseKind.OTHER -> "invoiceDetail.trip.kind.other"
    TripExpenseKind.TRAVEL_TIME -> "invoiceDetail.trip.kind.travelTime"
}

/** One small leading icon per [TripExpenseKind], reused from the icon set already in use
 *  elsewhere in this dialog/its siblings (no new dependency): a column-width saver in
 *  [TripExpenseRowsEditor.overviewGrid] and a visual anchor in front of the "Bearbeiten" tab's
 *  kind [ComboBox] — never a replacement for the text label ([tripExpenseKindLabelKey] is always
 *  shown alongside it, screen readers still get the label). */
internal fun tripExpenseKindIcon(kind: TripExpenseKind): VaadinIcon = when (kind) {
    TripExpenseKind.HOTEL -> VaadinIcon.BED
    TripExpenseKind.TRANSPORT -> VaadinIcon.TICKET
    TripExpenseKind.MILEAGE -> VaadinIcon.CAR
    TripExpenseKind.OTHER -> VaadinIcon.ELLIPSIS_DOTS_H
    TripExpenseKind.TRAVEL_TIME -> VaadinIcon.CLOCK
}

/** Icon + label together, the "Art" column of [TripExpenseRowsEditor.overviewGrid] (that grid has
 *  no separate kind-name column — this cell carries both). */
private fun TripExpenseRowsEditor.kindCell(kind: TripExpenseKind): Component {
    val icon = tripExpenseKindIcon(kind).create().apply {
        setSize("1rem")
        element.setAttribute("aria-hidden", "true")
    }
    return HorizontalLayout(icon, Span(tr(tripExpenseKindLabelKey(kind)))).apply {
        isPadding = false; isSpacing = true; addClassName("lt-gap-035")
        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
    }
}
