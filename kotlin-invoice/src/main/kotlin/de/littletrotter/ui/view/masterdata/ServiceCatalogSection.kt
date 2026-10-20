package de.littletrotter.ui.view.masterdata

import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit

/** Shared position labels and defaults; trips remain available only on invoices. */
internal enum class ServiceCatalogSection(
    val category: InvoiceItemCategory,
    val labelKey: String,
    val defaultUnit: InvoiceItemUnit,
) {
    MATERIAL(InvoiceItemCategory.PRODUCT, "serviceCatalog.category.material", InvoiceItemUnit.PIECE),
    UNIT_PRICE(InvoiceItemCategory.UNIT_PRICE, "serviceCatalog.category.unitPrice", InvoiceItemUnit.SQUARE_METER),
    SERVICE(InvoiceItemCategory.SERVICE, "serviceCatalog.category.service", InvoiceItemUnit.HOUR),
    TRIP(InvoiceItemCategory.TRIP, "serviceCatalog.category.trip", InvoiceItemUnit.FLAT_RATE);

    companion object {
        val catalogueEntries: List<ServiceCatalogSection> = listOf(MATERIAL, UNIT_PRICE, SERVICE)

        fun forCategory(category: InvoiceItemCategory): ServiceCatalogSection = when (category) {
            InvoiceItemCategory.PRODUCT -> MATERIAL
            InvoiceItemCategory.UNIT_PRICE -> UNIT_PRICE
            InvoiceItemCategory.SERVICE -> SERVICE
            InvoiceItemCategory.TRIP -> TRIP
        }
    }
}
