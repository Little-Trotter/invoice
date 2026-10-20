package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Branch coverage tests for ReportFormatHelpers utility functions.
 * Tests isPresent, orEmpty, and amountUnit formatting functions.
 */
class ReportFormatHelpersTest {

    @Test
    fun `isPresent returns true for non-null non-blank strings`() {
        assertTrue(isPresent("hello"), "Non-blank string should be present")
        assertTrue(isPresent("123"), "Non-blank string should be present")
        assertTrue(isPresent(" text "), "Whitespace-padded string should be present")
    }

    @Test
    fun `isPresent returns false for null`() {
        assertFalse(isPresent(null), "Null should not be present")
    }

    @Test
    fun `isPresent returns false for blank string`() {
        assertFalse(isPresent(""), "Empty string should not be present")
        assertFalse(isPresent("   "), "Whitespace-only string should not be present")
    }

    @Test
    fun `isPresent returns true for non-string non-null values`() {
        assertTrue(isPresent(123), "Non-string non-null should be present")
        assertTrue(isPresent(0), "Zero should be present")
        assertTrue(isPresent(emptyList<String>()), "Empty list should be present")
    }

    @Test
    fun `orEmpty returns string value as-is`() {
        assertEquals("hello", orEmpty("hello"))
        assertEquals("123", orEmpty("123"))
    }

    @Test
    fun `orEmpty returns empty string for null`() {
        assertEquals("", orEmpty(null))
    }

    @Test
    fun `orEmpty converts non-string to empty string`() {
        assertEquals("", orEmpty(123))
        assertEquals("", orEmpty(0))
        assertEquals("", orEmpty(emptyList<String>()))
    }

    @Test
    fun `amountUnit with value and unit`() {
        val result1 = amountUnit("100", "EUR")
        assertTrue(result1.contains("100"))
        assertTrue(result1.contains("EUR"))

        val result2 = amountUnit("50.50", "m")
        assertTrue(result2.contains("50.50"))
        assertTrue(result2.contains("m"))
    }

    @Test
    fun `amountUnit with blank value returns empty string`() {
        assertEquals("", amountUnit("", "EUR"))
        assertEquals("", amountUnit("   ", "EUR"))
        assertEquals("", amountUnit(null, "EUR"))
    }

    @Test
    fun `amountUnit with non-string value`() {
        assertEquals("", amountUnit(123, "EUR"), "Non-string should be treated as empty")
    }

    @Test
    fun `amount is alias for amountUnit`() {
        assertEquals(amountUnit("100", "EUR"), amount("100", "EUR"))
        assertEquals(amountUnit("", "EUR"), amount("", "EUR"))
    }

    // ── boundary tests ──────────────────────────────────────────────────────────────────────

    @Test
    fun `boundary - printedCurrencyUnit renders the compact euro symbol for EUR`() {
        assertEquals("€", printedCurrencyUnit("EUR"))
    }

    @Test
    fun `boundary - printedCurrencyUnit keeps the ISO code for a non-EUR currency`() {
        assertEquals("USD", printedCurrencyUnit("USD"))
        assertEquals("CHF", printedCurrencyUnit("CHF"))
    }

    @Test
    fun `amount with non-string value returns empty string`() {
        assertEquals("", amount(42, "EUR"))
    }
}
