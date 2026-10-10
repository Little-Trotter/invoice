package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PositionNumberFormatterTest {

    @Test
    fun `single-digit position pads to three digits`() {
        assertEquals("003", PositionNumberFormatter.format("3"))
    }

    @Test
    fun `two-digit position pads to three digits`() {
        assertEquals("012", PositionNumberFormatter.format("12"))
    }

    @Test
    fun `three-digit position remains unchanged`() {
        assertEquals("120", PositionNumberFormatter.format("120"))
    }

    @Test
    fun `four-digit position is not truncated`() {
        assertEquals("1234", PositionNumberFormatter.format("1234"))
    }

    @Test
    fun `single-digit position with sub-index one appends unpadded sub-index`() {
        assertEquals("003.1", PositionNumberFormatter.format("3", 1))
    }

    @Test
    fun `single-digit position with two-digit sub-index appends unpadded sub-index`() {
        assertEquals("003.12", PositionNumberFormatter.format("3", 12))
    }

    @Test
    fun `three-digit position with two-digit sub-index handles 100-position border case`() {
        assertEquals("003.100", PositionNumberFormatter.format("3", 100))
    }

    @Test
    fun `non-numeric position ID extracts digits and pads`() {
        assertEquals("001", PositionNumberFormatter.format("POS-001"))
    }

    @Test
    fun `mixed alphanumeric position extracts digits`() {
        assertEquals("042", PositionNumberFormatter.format("P42Q"))
    }

    @Test
    fun `position ID with no digits uses fallback index`() {
        assertEquals("005", PositionNumberFormatter.format("ABC", fallbackIndex = 5))
    }

    @Test
    fun `position ID with no digits uses zero fallback by default`() {
        assertEquals("000", PositionNumberFormatter.format("XYZ"))
    }

    @Test
    fun `non-numeric position with fallback and sub-index appends sub-index to padded fallback`() {
        assertEquals("007.1", PositionNumberFormatter.format("ABC", 1, fallbackIndex = 7))
    }

    @Test
    fun `empty position ID uses fallback`() {
        assertEquals("010", PositionNumberFormatter.format("", fallbackIndex = 10))
    }

    @Test
    fun `null-like string falls back to zero`() {
        assertEquals("000", PositionNumberFormatter.format("null"))
    }
}
