package de.littletrotter.ui.view.masterdata

import de.littletrotter.shared.catalogue.InvoiceItemCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class InvoiceItemCategoryLabelsTest {
    @Test
    fun `every category has a German translation key`() {
        assertEquals("item.category.service", InvoiceItemCategoryLabels.key(InvoiceItemCategory.SERVICE))
        assertEquals("item.category.product", InvoiceItemCategoryLabels.key(InvoiceItemCategory.PRODUCT))
        assertEquals("item.category.trip", InvoiceItemCategoryLabels.key(InvoiceItemCategory.TRIP))
        assertEquals("item.category.unitPrice", InvoiceItemCategoryLabels.key(InvoiceItemCategory.UNIT_PRICE))
    }
}
