package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.application.quote.port.inbound.QuoteArticleLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.view.invoice.InvoiceUnitLabels

private val SERVICE_UNITS = listOf(InvoiceItemUnit.HOUR, InvoiceItemUnit.DAY, InvoiceItemUnit.FLAT_RATE, InvoiceItemUnit.APPROACH)

/**
 * The "Dienstleistung" dialog: the unit is picked as a radio group, and the price label follows it
 * (hourly rate, daily rate, flat amount). A flat rate is one piece by nature, so its quantity
 * input is hidden and the line always carries a quantity of 1.
 */
internal class QuoteServiceItemDialog(
    host: TranslationProvider,
    articles: QuoteArticleLookupQueryPort,
    quoteId: String,
    existing: QuoteItem? = null,
    onSave: (QuoteItemInput) -> Unit,
) : QuoteBaseItemDialog(host, articles, quoteId, existing, onSave) {

    override val category = InvoiceItemCategory.SERVICE

    internal val unitGroup = RadioButtonGroup<InvoiceItemUnit>(host.tr("invoiceDetail.itemDialog.unit")).apply {
        setItems(SERVICE_UNITS)
        setItemLabelGenerator { InvoiceUnitLabels.label(dialog, it) }
        value = InvoiceItemUnit.HOUR
    }

    override fun titleKey(): String = "invoiceDetail.service.dialog.title"

    override fun articleLabelKey(): String = "invoiceDetail.service.selectArticle"

    override fun descriptionLabelKey(): String = "invoiceDetail.service.activity"

    override fun priceLabelKey(): String = rateLabelKey(selectedUnit())

    override fun unitFields(): List<Component> = listOf(unitGroup, quantity)

    override fun selectedUnit(): InvoiceItemUnit = unitGroup.value ?: InvoiceItemUnit.HOUR

    override fun selectUnit(unit: InvoiceItemUnit) {
        if (unit in SERVICE_UNITS) unitGroup.value = unit
        syncUnitDependentFields()
    }

    override fun effectiveQuantity(): Double? = if (selectedUnit() == InvoiceItemUnit.FLAT_RATE) 1.0 else quantity.value

    override fun wireUnitListeners(onChange: () -> Unit) {
        syncUnitDependentFields()
        unitGroup.addValueChangeListener {
            syncUnitDependentFields()
            onChange()
        }
    }

    /** The price label and the quantity input follow the unit, also for a unit that was set before this listener existed. */
    private fun syncUnitDependentFields() {
        price.label = host.tr(rateLabelKey(selectedUnit()))
        quantity.isVisible = selectedUnit() != InvoiceItemUnit.FLAT_RATE
    }

    private fun rateLabelKey(unit: InvoiceItemUnit): String = when (unit) {
        InvoiceItemUnit.DAY -> "invoiceDetail.service.rateLabel.day"
        InvoiceItemUnit.FLAT_RATE -> "invoiceDetail.service.rateLabel.flat"
        InvoiceItemUnit.APPROACH -> "quote.items.dialog.rateLabel.approach"
        else -> "invoiceDetail.service.rateLabel.hour"
    }
}
