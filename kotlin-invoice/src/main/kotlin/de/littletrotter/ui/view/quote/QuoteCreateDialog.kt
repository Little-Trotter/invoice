package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.quote.port.inbound.CreateQuoteUseCase
import de.littletrotter.application.quote.port.inbound.QuoteCustomerLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteCustomerOption
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * "Neues Angebot": picks the customer from the master data the offer component replicated and
 * starts a draft for it. When the component knows no customer at all the dialog says why instead
 * of offering an empty list, because an empty picker would look like a defect.
 */
internal class QuoteCreateDialog(
    host: TranslationProvider,
    private val lookup: QuoteCustomerLookupQueryPort,
    private val create: CreateQuoteUseCase,
    private val onCreated: (String) -> Unit,
) : AppDialog(host.tr("quote.create.title"), Size.S) {

    internal val customerBox = ComboBox<QuoteCustomerOption>(host.tr("quote.create.customer"))
    internal val emptyState = Span(host.tr("quote.create.noCustomers"))
    internal val confirmButton = AppButtons.primary(host.tr("quote.create.confirm")) { createDraft() }

    init {
        customerBox.setWidthFull()
        customerBox.setItemLabelGenerator { labelOf(it) }
        customerBox.setItems { query ->
            val text = query.filter.orElse("")
            lookup.customers(text, query.offset + query.limit).drop(query.offset).stream()
        }
        customerBox.addValueChangeListener { refreshConfirm() }
        emptyState.addClassName("text-muted")
        add(customerBox, emptyState)
        footer(AppButtons.tertiary(host.tr("app.cancel")) { close() }, confirmButton)
    }

    /** Re-checks whether any customer is known before the dialog is shown. */
    fun show() {
        val anyCustomer = guarded { lookup.customers("", 1).isNotEmpty() } ?: false
        customerBox.isVisible = anyCustomer
        emptyState.isVisible = !anyCustomer
        customerBox.clear()
        refreshConfirm()
        open()
    }

    private fun refreshConfirm() {
        confirmButton.isEnabled = customerBox.value != null
    }

    private fun createDraft() {
        val customer = customerBox.value ?: return
        val id = guarded { create.create(customer.id) } ?: return
        close()
        onCreated(id)
    }

    private fun labelOf(item: QuoteCustomerOption): String {
        val detail = listOfNotNull(item.customerNumber, item.city).joinToString(", ")
        return if (detail.isEmpty()) item.name else "${item.name} ($detail)"
    }
}
