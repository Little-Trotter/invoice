package de.littletrotter.domain.shared.converters

import de.littletrotter.shared.document.DocumentLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DocumentLanguageConverterTest {
    private val conv = DocumentLanguageConverter()

    @Test
    fun `null stays null in both directions`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `every language round trips through its lower-case code`() {
        DocumentLanguage.entries.forEach {
            assertEquals(it.code, conv.convertToDatabaseColumn(it))
            assertEquals(it, conv.convertToEntityAttribute(it.code))
        }
    }

    @Test
    fun `an unknown stored code is rejected instead of falling back to German`() {
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute("fr") }
    }
}
