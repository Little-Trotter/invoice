package de.littletrotter.ui.view.masterdata

import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.catalogue.LeistungsverzeichnisRules
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ServiceCatalogSectionTest {
    @Test
    fun `every category has a unique tab with an allowed default unit`() {
        val expected = listOf(
            Triple(InvoiceItemCategory.PRODUCT, "serviceCatalog.category.material", InvoiceItemUnit.PIECE),
            Triple(InvoiceItemCategory.UNIT_PRICE, "serviceCatalog.category.unitPrice", InvoiceItemUnit.SQUARE_METER),
            Triple(InvoiceItemCategory.SERVICE, "serviceCatalog.category.service", InvoiceItemUnit.HOUR),
            Triple(InvoiceItemCategory.TRIP, "serviceCatalog.category.trip", InvoiceItemUnit.FLAT_RATE),
        )
        assertEquals(InvoiceItemCategory.entries.toSet(), expected.map { it.first }.toSet())
        assertEquals(expected.size, ServiceCatalogSection.entries.size)
        expected.forEachIndexed { index, (category, label, unit) ->
            val section = ServiceCatalogSection.forCategory(category)
            assertEquals(section, ServiceCatalogSection.entries[index])
            assertEquals(category, section.category)
            assertEquals(label, section.labelKey)
            assertEquals(unit, section.defaultUnit)
            assertTrue(LeistungsverzeichnisRules.isAllowed(category, section.defaultUnit))
        }
    }
}
