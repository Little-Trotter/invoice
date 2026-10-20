package de.littletrotter.ui.view.masterdata

import de.littletrotter.shared.catalogue.InvoiceItemCategory

/** Single label-key source for every category ComboBox and filter in the UI. */
internal object InvoiceItemCategoryLabels {
    fun key(category: InvoiceItemCategory): String = when (category) {
        InvoiceItemCategory.SERVICE -> "item.category.service"
        InvoiceItemCategory.PRODUCT -> "item.category.product"
        InvoiceItemCategory.TRIP -> "item.category.trip"
        InvoiceItemCategory.UNIT_PRICE -> "item.category.unitPrice"
    }
}
