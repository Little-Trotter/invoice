package de.littletrotter.domain.shared.converters

import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InvoiceItemCategoryConverterTest {
    private val conv = InvoiceItemCategoryConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `RK-1 - the three surviving codes round-trip`() {
        assertEquals(0, conv.convertToDatabaseColumn(InvoiceItemCategory.SERVICE))
        assertEquals(InvoiceItemCategory.SERVICE, conv.convertToEntityAttribute(0))
        assertEquals(1, conv.convertToDatabaseColumn(InvoiceItemCategory.PRODUCT))
        assertEquals(InvoiceItemCategory.PRODUCT, conv.convertToEntityAttribute(1))
        assertEquals(5, conv.convertToDatabaseColumn(InvoiceItemCategory.TRIP))
        assertEquals(InvoiceItemCategory.TRIP, conv.convertToEntityAttribute(5))
    }

    @Test
    fun `RK-1 - the gap left by the deleted TRAVEL_EXPENSE-EXPENSE-PER_DIEM codes decodes to null, not the neighbouring category`() {
        assertNull(conv.convertToEntityAttribute(2))
        assertNull(conv.convertToEntityAttribute(3))
        assertNull(conv.convertToEntityAttribute(4))
    }

    @Test
    fun `unknown code rejected`() {
        assertNull(conv.convertToEntityAttribute(999))
    }

    @Test
    fun `all categories have unique codes`() {
        val codes = InvoiceItemCategory.entries.map { conv.convertToDatabaseColumn(it) }
        assertEquals(codes.distinct().size, codes.size, "Doppelte Codes gefunden")
    }
}
