package de.littletrotter.domain.reference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * "Synchronize number counters to existing data" admin action (L4 follow-up, 2026-09-02+) —
 * pure suffix-parsing rule ([NumberSuffixParser.suffixOf]) and the "never lowers" aggregation
 * ([NumberSuffixParser.maxSuffix]) that feeds
 * [de.littletrotter.application.reference.NumberRangeService.syncCounterTo].
 */
class NumberSuffixParserTest {

    // ── suffixOf ──────────────────────────────────────────────────────────────

    @Test
    fun `plain zero-padded number parses its trailing digits`() {
        assertEquals(42L, NumberSuffixParser.suffixOf("00042"))
    }

    @Test
    fun `prefixed and dated number is read prefix-agnostically - only the trailing run counts`() {
        assertEquals(17L, NumberSuffixParser.suffixOf("ART-2026-08-00017"))
    }

    @Test
    fun `leading zeros in the suffix do not change its numeric value`() {
        assertEquals(1L, NumberSuffixParser.suffixOf("KUN-00001"))
    }

    @Test
    fun `a number with digits only in the prefix and none trailing has no suffix`() {
        assertNull(NumberSuffixParser.suffixOf("2026-ABC"))
    }

    @Test
    fun `a purely alphabetic hand-typed number has no suffix`() {
        assertNull(NumberSuffixParser.suffixOf("MEINE-EIGENE-NUMMER"))
    }

    @Test
    fun `blank and null both parse to no suffix`() {
        assertNull(NumberSuffixParser.suffixOf(""))
        assertNull(NumberSuffixParser.suffixOf("   "))
        assertNull(NumberSuffixParser.suffixOf(null))
    }

    @Test
    fun `a suffix past Int range still parses as a Long`() {
        assertEquals(10_000_000_000L, NumberSuffixParser.suffixOf("SEQ-10000000000"))
    }

    // ── maxSuffix ─────────────────────────────────────────────────────────────

    @Test
    fun `max suffix ignores entries with no parseable digits and non-parseable never lowers the result`() {
        val numbers = listOf("KUN-00003", "MEINE-EIGENE-NUMMER", "KUN-00017", "", null, "KUN-00009")
        assertEquals(17L, NumberSuffixParser.maxSuffix(numbers))
    }

    @Test
    fun `max suffix of an empty or all-unparseable collection is null - nothing to raise the counter to`() {
        assertNull(NumberSuffixParser.maxSuffix(emptyList()))
        assertNull(NumberSuffixParser.maxSuffix(listOf(null, "", "NO-DIGITS-HERE")))
    }

    @Test
    fun `max suffix picks the numerically highest suffix, not the lexicographically last string`() {
        // "9" > "10" lexicographically but not numerically -- a bug here would report the wrong
        // "already covered" value and could wrongly leave the counter behind.
        assertEquals(10L, NumberSuffixParser.maxSuffix(listOf("KUN-00009", "KUN-00010")))
    }
}
