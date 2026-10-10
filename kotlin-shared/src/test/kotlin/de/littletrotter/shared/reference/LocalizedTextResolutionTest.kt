package de.littletrotter.shared.reference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The single resolution rule: exact match on the document language → fallback language →
 * `null`. Never composed, never a partial region-subtag match.
 */
class LocalizedTextResolutionTest {

    @Test
    fun `exact match on the document language wins`() {
        val texts = mapOf("de" to "Deutscher Text", "en" to "English text")
        assertEquals("English text", resolveLocalizedText(texts, documentLanguage = "en", fallbackLanguage = "de"))
    }

    @Test
    fun `falls back to the fallback language when the document language has no entry`() {
        val texts = mapOf("de" to "Deutscher Text")
        assertEquals("Deutscher Text", resolveLocalizedText(texts, documentLanguage = "en", fallbackLanguage = "de"))
    }

    @Test
    fun `neither language present resolves to null`() {
        val texts = mapOf<String, String>()
        assertNull(resolveLocalizedText(texts, documentLanguage = "en", fallbackLanguage = "de"))
    }

    @Test
    fun `a blank stored value is treated as absent, defensively`() {
        val texts = mapOf("en" to "   ", "de" to "Deutscher Text")
        assertEquals("Deutscher Text", resolveLocalizedText(texts, documentLanguage = "en", fallbackLanguage = "de"))
    }

    @Test
    fun `an unknown language resolves via the fallback`() {
        val texts = mapOf("de" to "Deutscher Text")
        assertEquals("Deutscher Text", resolveLocalizedText(texts, documentLanguage = "fr", fallbackLanguage = "de"))
    }

    @Test
    fun `de and en both present - de is not touched when the document language is de`() {
        val texts = mapOf("de" to "Deutscher Text", "en" to "English text")
        assertEquals("Deutscher Text", resolveLocalizedText(texts, documentLanguage = "de", fallbackLanguage = "de"))
    }

    @Test
    fun `a region subtag does not silently match its base language - en-GB does not match en`() {
        val texts = mapOf("en" to "English text")
        assertNull(resolveLocalizedText(texts, documentLanguage = "en-GB", fallbackLanguage = "de"))
    }

    @Test
    fun `never composed - result is exactly one language's text, not a concatenation`() {
        val texts = mapOf("de" to "Deutscher Text", "en" to "English text")
        val result = resolveLocalizedText(texts, documentLanguage = "en", fallbackLanguage = "de")
        assertEquals("English text", result)
        assert(result?.contains("Deutscher Text") != true)
    }
}
