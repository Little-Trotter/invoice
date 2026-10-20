package de.littletrotter.domain.shared.converters

import de.littletrotter.shared.tax.VatCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VatCategoryConverterTest {
    private val conv = VatCategoryConverter()

    @Test
    fun `null stays null in both directions`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `every category round trips through its code`() {
        VatCategory.entries.forEach {
            assertEquals(it.code, conv.convertToDatabaseColumn(it))
            assertEquals(it, conv.convertToEntityAttribute(it.code))
        }
    }

    @Test
    fun `an unknown stored code is rejected instead of dropped`() {
        val e = assertThrows<IllegalStateException> { conv.convertToEntityAttribute("X") }
        assertEquals("Unknown VAT category code 'X' in stored invoice item", e.message)
    }
}
