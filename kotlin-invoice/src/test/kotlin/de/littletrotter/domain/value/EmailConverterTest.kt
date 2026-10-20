package de.littletrotter.domain.value

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * COV — [EmailConverter] (the JPA `AttributeConverter` every `Email`-typed column round-trips
 * through) had zero direct coverage before this AP.
 */
class EmailConverterTest {

    private val converter = EmailConverter()

    @Test
    fun `convertToDatabaseColumn liefert den rohen E-Mail-Wert`() {
        assertEquals("billing@acme.example", converter.convertToDatabaseColumn(Email("billing@acme.example")))
    }

    @Test
    fun `convertToDatabaseColumn liefert null fuer ein fehlendes Attribut`() {
        assertNull(converter.convertToDatabaseColumn(null))
    }

    @Test
    fun `convertToEntityAttribute baut eine Email aus einer gueltigen Spalte`() {
        assertEquals(Email("billing@acme.example"), converter.convertToEntityAttribute("billing@acme.example"))
    }

    @Test
    fun `convertToEntityAttribute liefert null fuer eine leere oder fehlende Spalte`() {
        assertNull(converter.convertToEntityAttribute(null))
        assertNull(converter.convertToEntityAttribute(""))
        assertNull(converter.convertToEntityAttribute("   "))
    }
}
