package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.ui.component.ActionIcons

/** The row's own value widgets — everything [TripExpenseRow] itself carries a field for. Split
 *  out from [ExpenseRowControlFields] purely to keep [ExpenseRowFields]'s own constructor (and
 *  each of these two) under detekt's `LongParameterList` threshold. */
private class ExpenseRowValueFields(
    val kindBox: ComboBox<TripExpenseKind>,
    val descriptionField: TextField,
    val dateField: DatePicker,
    val quantityField: NumberField,
    val unitBox: ComboBox<InvoiceItemUnit>,
    val priceField: NumberField,
    val netSpan: Span,
)

/** The row's lookup/reorder/delete controls — [TripExpenseRow.vehicleType] plus the widgets that
 *  act on the row rather than holding one of its own values. [receiptBox] is `null` when the trip
 *  item has no receipts attached yet — hidden entirely rather than shown disabled, see
 *  [TripExpenseRowEditor.createExpenseRowFields]. */
private class ExpenseRowControlFields(
    val vehicleTypeBox: ComboBox<VehicleType>,
    val receiptBox: ComboBox<ReceiptRowDto>?,
    val upButton: Button,
    val downButton: Button,
    val deleteButton: Button,
)

/** The widgets [TripExpenseRowEditor.build] lays out for one [TripExpenseRow], split from
 *  the value-change wiring in [TripExpenseRowEditor.wireExpenseRowFields] so neither half of that function
 *  carries both concerns. */
private class ExpenseRowFields(
    val values: ExpenseRowValueFields,
    val controls: ExpenseRowControlFields,
)

/**
 * Builds and wires one editable "Bearbeiten" line of [TripExpenseRowsEditor]: the row's
 * value widgets write straight onto the [TripExpenseRow] (reporting through [onChange]), while every
 * structural mutation (kind switch, receipt assignment, move, delete) goes through [rows] and then
 * asks the owning editor to [rerender] both views.
 */
internal class TripExpenseRowEditor(
    private val rows: TripExpenseRowList,
    private val prefill: TripExpenseRowPrefill,
    private val labelHost: Component,
    private val formatCurrency: (Money) -> String,
    private val onChange: () -> Unit,
    private val rerender: () -> Unit,
) : TranslationProvider {
    override fun getComponent(): Component = labelHost

    /** The "Bearbeiten" editor line for [row] at [index]; [allReceipts] are the trip item's receipts. */
    fun build(row: TripExpenseRow, index: Int, allReceipts: List<ReceiptRowDto>): Component {
        val fields = createExpenseRowFields(row, index, allReceipts)
        wireExpenseRowFields(fields, row, index)
        val v = fields.values
        val c = fields.controls
        // A small leading icon (never a replacement for the ComboBox's own text label,
        // which already names the kind).
        val kindIcon = tripExpenseKindIcon(row.kind).create().apply { setSize("1rem"); element.setAttribute("aria-hidden", "true") }

        return HorizontalLayout(
            *listOfNotNull(
                kindIcon, v.kindBox, v.descriptionField, v.dateField, v.quantityField, v.unitBox,
                v.priceField, v.netSpan, c.vehicleTypeBox, c.receiptBox,
                c.upButton, c.downButton, c.deleteButton,
            ).toTypedArray(),
        ).apply {
            setWidthFull(); isPadding = false; isSpacing = true; addClassName("toolbar-wrap")
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        }
    }

    private fun createExpenseRowFields(row: TripExpenseRow, index: Int, allReceipts: List<ReceiptRowDto>): ExpenseRowFields =
        ExpenseRowFields(
            values = createExpenseRowValueFields(row),
            controls = createExpenseRowControlFields(row, index, allReceipts),
        )

    private fun createExpenseRowValueFields(row: TripExpenseRow): ExpenseRowValueFields {
        val kindBox = ComboBox<TripExpenseKind>().apply {
            setItems(TRIP_EXPENSE_KIND_DISPLAY_ORDER)
            setItemLabelGenerator { tr(tripExpenseKindLabelKey(it)) }
            value = row.kind
            width = "9rem"
        }
        val descriptionField = TextField().apply {
            placeholder = tr("invoiceDetail.trip.expense.description"); value = row.description; setWidthFull()
        }
        val dateField = DatePicker().apply { value = row.travelDate; width = "9.5rem" }
        val quantityField = NumberField().apply { value = row.quantity.toDouble(); min = 0.0; width = "6rem" }
        val unitBox = ComboBox<InvoiceItemUnit>().apply {
            setItems(TRIP_EXPENSE_UNITS)
            setItemLabelGenerator { InvoiceUnitLabels.label(labelHost, it) }
            value = row.unit; width = "7rem"
        }
        val priceField = NumberField().apply {
            suffixComponent = Span("€"); value = if (row.rateProvided) row.unitPriceNet.amount.toDouble() else null; width = "8rem"
        }
        val netSpan = Span(formatCurrency(row.netAmount())).apply { addClassNames("text-muted", "lt-min-w-5") }
        return ExpenseRowValueFields(kindBox, descriptionField, dateField, quantityField, unitBox, priceField, netSpan)
    }

    private fun createExpenseRowControlFields(row: TripExpenseRow, index: Int, allReceipts: List<ReceiptRowDto>): ExpenseRowControlFields {
        val vehicleTypeBox = ComboBox<VehicleType>().apply {
            setItems(VehicleType.CAR, VehicleType.OTHER_MOTOR_VEHICLE)
            setItemLabelGenerator {
                when (it) {
                    VehicleType.CAR -> tr("invoiceDetail.travel.mileage.vehicleType.car")
                    VehicleType.OTHER_MOTOR_VEHICLE -> tr("invoiceDetail.travel.mileage.vehicleType.other")
                }
            }
            value = row.vehicleType
            width = "12rem"
            isVisible = row.kind == TripExpenseKind.MILEAGE
        }
        // Hidden entirely (not a disabled, empty-looking ComboBox) while the trip has no
        // receipts at all; once it has any, a sibling row's own current selection is excluded
        // (recomputed here on every render). "No receipt" is the ComboBox's own cleared (`null`)
        // value, not a selectable sentinel item — the clear button and the placeholder text both
        // read "kein Beleg" for it, so it stays as discoverable as a real item was.
        val receiptBox = if (allReceipts.isEmpty()) {
            null
        } else {
            val assignedElsewhere = rows.receiptIdsAssignedElsewhere(index)
            val available = allReceipts.filter { it.id !in assignedElsewhere }
            ComboBox<ReceiptRowDto>().apply {
                setItems(available)
                setItemLabelGenerator { it.fileName }
                placeholder = tr("invoiceDetail.items.receipts.noneOption")
                isClearButtonVisible = true
                value = available.find { it.id == row.receiptId }
                width = "10rem"
            }
        }
        // The click handlers are attached in wireExpenseRowFields once the row's fields exist.
        val upButton = AppButtons.icon(VaadinIcon.ANGLE_UP, tr("invoiceDetail.trip.expense.moveUp")) {}.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
            addClassName("trip-expense-reorder")
            isEnabled = index > 0
        }
        val downButton = AppButtons.icon(VaadinIcon.ANGLE_DOWN, tr("invoiceDetail.trip.expense.moveDown")) {}.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
            addClassName("trip-expense-reorder")
            isEnabled = index < rows.size - 1
        }
        val deleteButton = AppButtons.icon(ActionIcons.DELETE, tr("invoiceDetail.trip.expense.delete")) {}.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
        }
        return ExpenseRowControlFields(vehicleTypeBox, receiptBox, upButton, downButton, deleteButton)
    }

    private fun wireExpenseRowFields(fields: ExpenseRowFields, row: TripExpenseRow, index: Int) {
        val v = fields.values
        val c = fields.controls

        fun recalcNet() {
            row.quantity = BigDecimal.valueOf(v.quantityField.value ?: 0.0)
            row.unitPriceNet = Money.of(BigDecimal.valueOf(v.priceField.value ?: 0.0))
            row.rateProvided = v.priceField.value != null
            v.netSpan.text = formatCurrency(row.netAmount())
            onChange()
        }

        // Re-runs the same TripExpenseRowPrefill an "+" button uses (unit, quantity, price,
        // vehicle-type visibility) instead of only toggling vehicleTypeBox's visibility; a full
        // re-render picks up every downstream widget (icon included) consistently.
        v.kindBox.addValueChangeListener { event ->
            rows.changeKind(row, event.value ?: row.kind)
            onChange()
            rerender()
        }
        v.descriptionField.addValueChangeListener { row.description = it.value; onChange() }
        v.dateField.addValueChangeListener { event ->
            row.travelDate = event.value
            if (row.kind == TripExpenseKind.MILEAGE) resolveMileageRate(c.vehicleTypeBox.value, event.value, v.priceField)
            onChange()
        }
        v.quantityField.addValueChangeListener { recalcNet() }
        v.unitBox.addValueChangeListener { event -> row.unit = event.value ?: row.unit; onChange() }
        v.priceField.addValueChangeListener { recalcNet() }
        c.vehicleTypeBox.addValueChangeListener { event ->
            row.vehicleType = event.value ?: row.vehicleType
            resolveMileageRate(row.vehicleType, row.travelDate, v.priceField)
            onChange()
        }
        // A full re-render (not just `row.receiptId = …`) so every sibling row's own
        // exclusion set (createExpenseRowFields) picks up this row's new assignment immediately.
        c.receiptBox?.addValueChangeListener { event ->
            row.receiptId = event.value?.id
            onChange()
            rerender()
        }

        c.upButton.addClickListener { moveExpenseRow(index, index - 1) }
        c.downButton.addClickListener { moveExpenseRow(index, index + 1) }
        c.deleteButton.addClickListener {
            rows.removeAt(index)
            onChange()
            rerender()
        }
    }

    private fun resolveMileageRate(vehicleType: VehicleType, date: LocalDate?, priceField: NumberField) {
        val resolved = prefill.mileageRatePerKm(vehicleType, date) ?: return
        priceField.value = resolved.toDouble()
    }

    private fun moveExpenseRow(from: Int, to: Int) {
        if (!rows.move(from, to)) return
        onChange()
        rerender()
    }
}
