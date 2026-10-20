package de.littletrotter.domain.shared.converters

import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceNumberConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InvoiceNumberConverterTest {
    private val conv = InvoiceNumberConverter()

    @Test
    fun `null stays null in both directions`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `a valid number round trips unchanged`() {
        val number = InvoiceNumber("RE-2026-04-00001")
        assertEquals("RE-2026-04-00001", conv.convertToDatabaseColumn(number))
        assertEquals(number, conv.convertToEntityAttribute("RE-2026-04-00001"))
    }

    @Test
    fun `a malformed stored number is rejected`() {
        assertThrows<InvoiceValidationException> { conv.convertToEntityAttribute("not a number") }
    }
}
