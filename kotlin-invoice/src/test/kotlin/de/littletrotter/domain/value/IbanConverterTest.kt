package de.littletrotter.domain.value

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * COV — [IbanConverter] (the JPA `AttributeConverter` every `Iban`-typed column round-trips
 * through) had zero direct coverage before this AP.
 */
class IbanConverterTest {

    private val converter = IbanConverter()

    @Test
    fun `convertToDatabaseColumn liefert den rohen IBAN-Wert`() {
        assertEquals("DE89370400440532013000", converter.convertToDatabaseColumn(Iban("DE89370400440532013000")))
    }

    @Test
    fun `convertToDatabaseColumn liefert null fuer ein fehlendes Attribut`() {
        assertNull(converter.convertToDatabaseColumn(null))
    }

    @Test
    fun `convertToEntityAttribute baut eine Iban aus einer gueltigen Spalte`() {
        assertEquals(Iban("DE89370400440532013000"), converter.convertToEntityAttribute("DE89370400440532013000"))
    }

    @Test
    fun `convertToEntityAttribute liefert null fuer eine leere oder fehlende Spalte`() {
        assertNull(converter.convertToEntityAttribute(null))
        assertNull(converter.convertToEntityAttribute(""))
        assertNull(converter.convertToEntityAttribute("   "))
    }
}
