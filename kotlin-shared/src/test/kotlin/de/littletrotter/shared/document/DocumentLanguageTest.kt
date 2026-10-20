package de.littletrotter.shared.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DocumentLanguageTest {

    @Test
    fun `country derives the default language`() {
        listOf("DE", "AT", "CH", "LI", "de").forEach { assertEquals(DocumentLanguage.DE, DocumentLanguage.fromCountry(it), it) }
        listOf("FR", "US", "GB").forEach { assertEquals(DocumentLanguage.EN, DocumentLanguage.fromCountry(it), it) }
    }

    @Test
    fun `a missing or blank country reads as German`() {
        assertEquals(DocumentLanguage.DE, DocumentLanguage.fromCountry(null))
        assertEquals(DocumentLanguage.DE, DocumentLanguage.fromCountry("  "))
    }

    @Test
    fun `only the code en reads as English`() {
        assertEquals(DocumentLanguage.EN, DocumentLanguage.fromCode("en"))
        assertEquals(DocumentLanguage.DE, DocumentLanguage.fromCode("de"))
        assertEquals(DocumentLanguage.DE, DocumentLanguage.fromCode(null))
        assertEquals(DocumentLanguage.DE, DocumentLanguage.fromCode(""))
        assertEquals(DocumentLanguage.DE, DocumentLanguage.fromCode("fr"))
    }

    @Test
    fun `render code normalises to de or en`() {
        assertEquals("en", DocumentLanguage.renderCode("en"))
        assertEquals("de", DocumentLanguage.renderCode("xx"))
        assertEquals("de", DocumentLanguage.renderCode(null))
    }
}
