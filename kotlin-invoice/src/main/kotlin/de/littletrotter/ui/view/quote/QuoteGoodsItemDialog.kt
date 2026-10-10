package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import de.littletrotter.application.quote.port.inbound.QuoteArticleLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.catalogue.LeistungsverzeichnisRules
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.view.invoice.InvoiceUnitLabels
import de.littletrotter.ui.view.masterdata.ServiceCatalogSection

/** The "Material" and "Einheitspreis" dialog: a free choice among the units the position kind allows. */
internal class QuoteGoodsItemDialog(
    host: TranslationProvider,
    articles: QuoteArticleLookupQueryPort,
    quoteId: String,
    override val category: InvoiceItemCategory,
    existing: QuoteItem? = null,
    onSave: (QuoteItemInput) -> Unit,
) : QuoteBaseItemDialog(host, articles, quoteId, existing, onSave) {

    private val allowedUnits = LeistungsverzeichnisRules.allowedUnits(category).sortedBy { it.ordinal }

    internal val unitBox = ComboBox<InvoiceItemUnit>(host.tr("invoiceDetail.itemDialog.unit")).apply {
        setItems(allowedUnits)
        setItemLabelGenerator { InvoiceUnitLabels.label(dialog, it) }
        isAllowCustomValue = false
        value = ServiceCatalogSection.forCategory(category).defaultUnit
        setWidthFull()
    }

    override fun titleKey(): String = "invoiceDetail.goods.dialog.title"

    override fun articleLabelKey(): String = "invoiceDetail.goods.selectArticle"

    override fun descriptionLabelKey(): String = "invoiceDetail.goods.itemName"

    override fun priceLabelKey(): String = "invoiceDetail.itemDialog.unitPriceNet"

    override fun unitFields(): List<Component> = listOf(quantity, unitBox)

    override fun selectedUnit(): InvoiceItemUnit = unitBox.value ?: allowedUnits.first()

    override fun selectUnit(unit: InvoiceItemUnit) {
        if (unit in allowedUnits) unitBox.value = unit
    }

    override fun wireUnitListeners(onChange: () -> Unit) = Unit
}
