package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ArticleNumberTest {

    @Test
    fun `happy path accepts a real article number`() {
        val number = ArticleNumber("ART-00042")
        assertEquals("ART-00042", number.value)
        assertEquals("ART-00042", number.toString())
    }

    @Test
    fun `boundary a blank, non-null value is rejected`() {
        assertThrows(InvalidParameterException::class.java) { ArticleNumber("   ") }
    }

    @Test
    fun `boundary an empty value is rejected`() {
        assertThrows(InvalidParameterException::class.java) { ArticleNumber("") }
    }

    // ── column-length invariant (article_number VARCHAR(60)) ──────────────────

    @Test
    fun `boundary a 59-character value is accepted`() {
        val number = ArticleNumber("A".repeat(59))
        assertEquals(59, number.value.length)
    }

    @Test
    fun `boundary a 60-character value is accepted`() {
        val number = ArticleNumber("A".repeat(60))
        assertEquals(60, number.value.length)
    }

    @Test
    fun `boundary a 61-character value is rejected`() {
        val ex = assertThrows(InvalidParameterException::class.java) { ArticleNumber("A".repeat(61)) }
        assertEquals("Artikelnummer darf höchstens 60 Zeichen haben.", ex.message)
    }

    @Test
    fun `ofNullable returns null for a null input`() {
        assertNull(ArticleNumber.ofNullable(null))
    }

    @Test
    fun `ofNullable returns null for a blank input instead of throwing`() {
        assertNull(ArticleNumber.ofNullable("   "))
    }

    @Test
    fun `ofNullable wraps a real value`() {
        assertEquals(ArticleNumber("ART-1"), ArticleNumber.ofNullable("ART-1"))
    }

    @Test
    fun `converter round-trips a value through the database column type`() {
        val converter = ArticleNumberConverter()
        val number = ArticleNumber("ART-7")
        assertEquals("ART-7", converter.convertToDatabaseColumn(number))
        assertEquals(number, converter.convertToEntityAttribute("ART-7"))
    }

    @Test
    fun `converter is null-safe in both directions`() {
        val converter = ArticleNumberConverter()
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }

    @Test
    fun `converter treats a blank database value the same as null`() {
        val converter = ArticleNumberConverter()
        assertNull(converter.convertToEntityAttribute("   "))
    }
}
