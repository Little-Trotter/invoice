package de.littletrotter.ui.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StringExtensionsTest {
    @Test
    fun `orPlaceholder returns placeholder when value is null`() {
        val result: String? = null
        assertEquals("-", result.orPlaceholder())
    }

    @Test
    fun `orPlaceholder returns placeholder when value is blank`() {
        val result = "".orPlaceholder()
        assertEquals("-", result)
    }

    @Test
    fun `orPlaceholder returns placeholder when value is whitespace-only`() {
        val result = "   ".orPlaceholder()
        assertEquals("-", result)
    }

    @Test
    fun `orPlaceholder returns the value unchanged when not blank`() {
        assertEquals("test", "test".orPlaceholder())
    }

    /**
     * The helper deliberately does NOT trim: the call sites it replaced did not trim either, and
     * moving a trim into the shared helper would silently change what those views render. Blank
     * and whitespace-only values still reach the placeholder, because `isNotBlank()` already
     * covers them — trimming would only affect surrounding whitespace on an otherwise real value.
     */
    @Test
    fun `orPlaceholder keeps surrounding whitespace on a non-blank value`() {
        assertEquals("  test  ", "  test  ".orPlaceholder())
    }

    @Test
    fun `orPlaceholder returns custom placeholder when value is null`() {
        val result: String? = null
        assertEquals("N/A", result.orPlaceholder("N/A"))
    }

    @Test
    fun `orPlaceholder returns custom placeholder when value is blank`() {
        val result = "".orPlaceholder("EMPTY")
        assertEquals("EMPTY", result)
    }

    @Test
    fun `orPlaceholder keeps surrounding whitespace with a custom placeholder`() {
        assertEquals("  content  ", "  content  ".orPlaceholder("N/A"))
    }

    @Test
    fun `blankToNull returns null when value is null`() {
        val result: String? = null
        assertEquals(null, result.blankToNull())
    }

    @Test
    fun `blankToNull returns null when value is empty`() {
        assertEquals(null, "".blankToNull())
    }

    @Test
    fun `blankToNull returns null when value is whitespace-only`() {
        assertEquals(null, "   ".blankToNull())
    }

    @Test
    fun `blankToNull returns the value unchanged when not blank`() {
        assertEquals("test", "test".blankToNull())
    }

    @Test
    fun `blankToNull does not trim an ordinary value`() {
        assertEquals("  test  ", "  test  ".blankToNull())
    }
}
