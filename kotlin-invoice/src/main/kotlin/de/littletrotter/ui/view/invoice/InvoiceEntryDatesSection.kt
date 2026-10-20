package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.ui.i18n.UiTranslations
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Column count of the dates form from 1100px on (invoice date, service period, due date, payment term). */
private const val WIDE_SCREEN_COLUMNS = 3

/**
 * Dates and payment-terms section of the entry pane's Tab 1 ("Rechnung"): invoice date, service
 * period, due date and the payment term that drives the live due-date recalculation.
 * Owns its widgets and their listeners; the binder bindings stay in
 * [InvoiceEntryPane.build]'s single bind step.
 */
internal class InvoiceEntryDatesSection(
    private val host: Component,
    private val paymentTermQueries: PaymentTermQueryPort,
    current: InvoiceDetailDto,
) {
    private fun tr(key: String, vararg params: Any): String = UiTranslations.tr(host, key, *params)

    private fun rangeTr(key: String, bound: String?): String = if (bound == null) tr(key) else tr(key, bound)

    // invoiceDate is mandatory from creation onward
    // (Invoice.invoiceDate is non-nullable), so this field is required and not clearable: no "x"
    // clear-button affordance, and manually blanking the typed value is a field validation error
    // on save, same idiom every other required field in this pane uses (asRequired).
    val fields = EntryDateFields(
        invoiceDateField = RangeMessageDatePicker(tr("invoiceDetail.invoiceDate"), ::rangeTr).apply { isClearButtonVisible = false },
        serviceFromField = RangeMessageDatePicker(tr("invoiceDetail.servicePeriodFrom"), ::rangeTr).apply { isClearButtonVisible = true },
        serviceToField = RangeMessageDatePicker(tr("invoiceDetail.servicePeriodTo"), ::rangeTr).apply { isClearButtonVisible = true },
        dueDateField = RangeMessageDatePicker(tr("invoiceDetail.dueDate"), ::rangeTr).apply { isClearButtonVisible = true },
    )
    val paymentTermBox: ComboBox<PaymentTermDto> = buildPaymentTermBox(current)

    /** Due-date recalculation plus the stale-error resets; [loading] is read live so the binder's
     *  first `readBean` never recomputes a persisted due date. */
    fun wireListeners(loading: () -> Boolean) {
        val dueDateWidgets = DueDateWidgets(fields.invoiceDateField, fields.dueDateField, paymentTermBox)
        paymentTermBox.addValueChangeListener { e ->
            handlePaymentTermDueDateRecalc(loading(), dueDateWidgets, e.value)
        }
        fields.invoiceDateField.addValueChangeListener { e ->
            handleInvoiceDateDueDateRecalc(loading(), dueDateWidgets, e.value)
        }
        // Errors flashed on save are stale as soon as the user touches the offending field again.
        fields.serviceFromField.addValueChangeListener { fields.serviceFromField.isInvalid = false }
        fields.serviceToField.addValueChangeListener { fields.serviceToField.isInvalid = false }
        fields.dueDateField.addValueChangeListener { fields.dueDateField.isInvalid = false }
    }

    fun buildSection(): Div = entrySection(
        tr("invoiceDetail.entry.section.dates"),
        FormLayout(fields.invoiceDateField, fields.serviceFromField, fields.serviceToField, fields.dueDateField, paymentTermBox).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("700px", 2), FormLayout.ResponsiveStep("1100px", WIDE_SCREEN_COLUMNS))
        },
    )

    private fun buildPaymentTermBox(current: InvoiceDetailDto): ComboBox<PaymentTermDto> =
        ComboBox<PaymentTermDto>(tr("invoiceDetail.paymentTerm")).apply {
            setItems(paymentTermQueries.findAllPaymentTerms())
            // The name shown here follows this invoice's own document language, same rule as everywhere else a payment term's name reaches a
            // document: an English invoice's picker already shows the English name.
            setItemLabelGenerator { it.nameFor(current.documentLanguage) ?: it.nameFor("de").orEmpty() }
            isClearButtonVisible = true; setWidthFull()
        }
}

/**
 * Date picker that tells the user why a typed date is refused. Without its own texts the picker
 * reports a range violation or unparsable input with an empty message, so the field is marked
 * but the user never learns the reason. The earliest/latest day is only known once the
 * surrounding dates are set, so the texts naming the bound are rebuilt whenever it changes.
 */
internal class RangeMessageDatePicker(
    label: String,
    private val translate: (String, String?) -> String,
) : DatePicker(label) {
    private val boundFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    init {
        updateI18n { it.badInputErrorMessage = translate("invoiceDetail.entry.validation.date.badInput", null) }
    }

    override fun setMin(min: LocalDate?) {
        super.setMin(min)
        updateI18n { i18n ->
            i18n.minErrorMessage = min?.let { translate("invoiceDetail.entry.validation.date.beforeMin", boundFormat.format(it)) }
        }
    }

    override fun setMax(max: LocalDate?) {
        super.setMax(max)
        updateI18n { i18n ->
            i18n.maxErrorMessage = max?.let { translate("invoiceDetail.entry.validation.date.afterMax", boundFormat.format(it)) }
        }
    }

    private fun updateI18n(change: (DatePickerI18n) -> Unit) {
        i18n = (i18n ?: DatePickerI18n()).also(change)
    }
}

/** The always-visible date/period fields of Tab 1 ("Rechnung"). */
internal class EntryDateFields(
    val invoiceDateField: DatePicker,
    val serviceFromField: DatePicker,
    val serviceToField: DatePicker,
    val dueDateField: DatePicker,
)

// ─── Live due-date recalculation ───

/** The three widgets a due-date recalculation reads/writes — bundled so
 *  [handlePaymentTermDueDateRecalc]/[handleInvoiceDateDueDateRecalc] each take one parameter
 *  for them instead of three (LongParameterList). */
private data class DueDateWidgets(
    val invoiceDateField: DatePicker,
    val dueDateField: DatePicker,
    val paymentTermBox: ComboBox<PaymentTermDto>,
)

private fun handlePaymentTermDueDateRecalc(loading: Boolean, widgets: DueDateWidgets, newTerm: PaymentTermDto?) {
    widgets.paymentTermBox.isInvalid = false
    if (loading) return
    val newDue = computeDueDate(widgets.invoiceDateField.value, newTerm?.dueInDays ?: return)
    if (newDue != null) widgets.dueDateField.value = newDue
}

private fun handleInvoiceDateDueDateRecalc(loading: Boolean, widgets: DueDateWidgets, newInvoiceDate: LocalDate?) {
    widgets.invoiceDateField.isInvalid = false
    if (loading) return
    val newDue = computeDueDate(newInvoiceDate, widgets.paymentTermBox.value?.dueInDays ?: return)
    if (newDue != null) widgets.dueDateField.value = newDue
}

internal fun resolvePaymentTerm(invoiceTerm: PaymentTermDto?, defaultTerm: PaymentTermDto?): PaymentTermDto? =
    invoiceTerm ?: defaultTerm

internal fun computeDueDate(invoiceDate: LocalDate?, dueInDays: Int): LocalDate? =
    invoiceDate?.plusDays(dueInDays.toLong())

/**
 * Save-time date guard of the entry pane. Only the service-period order blocks a save (the domain's
 * own `SERVICE_PERIOD_END_BEFORE_START` hard error); a due date before the invoice date or the
 * service end is only a non-blocking warning ([de.littletrotter.domain.compliance.DueDateRules])
 * and must never refuse the save here.
 */
internal fun validateDateGuard(serviceFrom: LocalDate?, serviceTo: LocalDate?): String? {
    if (serviceFrom != null && serviceTo != null && serviceFrom.isAfter(serviceTo))
        return "invoiceDetail.validation.servicePeriod.order"
    return null
}
