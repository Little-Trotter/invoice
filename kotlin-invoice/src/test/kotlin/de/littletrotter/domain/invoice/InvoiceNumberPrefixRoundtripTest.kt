package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.masterdata.InvoicePrefixRule
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.shared.numbering.NumberFormatter as SharedNumberFormatter
import de.littletrotter.shared.numbering.NumberPattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

/**
 * Every prefix [InvoicePrefixRule] accepts must survive build (`NumberFormatter.compose`, with the
 * trailing hyphen stripped exactly as `InvoiceNumberService` does) and [InvoiceNumber] validation,
 * for all three number shapes. Hostile values stay rejected by both.
 */
class InvoiceNumberPrefixRoundtripTest {

    private val date = LocalDate.of(2026, 3, 7)

    companion object {
        @JvmStatic
        fun hostilePrefixes() = listOf(
            "\${7*7}", "../../etc/passwd", "Feld 1", "-RE", "RE_1", "RE/1", "A".repeat(21), "Ä-1", "RE;DROP",
        )

        @JvmStatic
        fun validPrefixes() = listOf(
            "RE", "RE-", "DSC", "RR-CD", "RR-CD-", "H272-0001", "H272-0001-", "A", "7-1",
            "A".repeat(20), "ABCDEFGHI-ABCDEFGHI", // 20 chars, inner hyphen
        )
    }

    private fun build(prefix: String, part: DatePart, seq: Long, width: Int): String =
        SharedNumberFormatter.compose(NumberPattern(prefix.trim().trimEnd('-'), part, width), date, seq)

    @ParameterizedTest
    @MethodSource("validPrefixes")
    fun `rule accepts the prefix and every number shape built from it validates`(prefix: String) {
        assertTrue(InvoicePrefixRule.isValid(prefix))
        assertTrue(InvoiceNumber.isValidPrefix(prefix))
        val stem = prefix.trimEnd('-')
        assertEquals("$stem-2026-0001", build(prefix, DatePart.YEAR, 1, 4).also { InvoiceNumber(it) })
        assertEquals("$stem-2026-03-00042", build(prefix, DatePart.YEAR_MONTH, 42, 5).also { InvoiceNumber(it) })
        assertEquals("$stem-2026-03-07-100000", build(prefix, DatePart.YEAR_MONTH_DAY, 100_000, 5).also { InvoiceNumber(it) })
    }

    @ParameterizedTest
    @MethodSource("validPrefixes")
    fun `unstripped trailing-hyphen prefix as persisted number text is accepted as the rule does`(prefix: String) {
        assertTrue(InvoiceNumber.isValidPrefix(prefix))
        InvoiceNumber("$prefix-2026-03-00001")
    }

    @ParameterizedTest
    @MethodSource("hostilePrefixes")
    fun `hostile or oversized prefixes are rejected by the rule and by number validation`(prefix: String) {
        assertFalse(InvoicePrefixRule.isValid(prefix))
        assertThrows(InvoiceValidationException::class.java) { InvoiceNumber("$prefix-2026-03-00001") }
        assertThrows(InvoiceValidationException::class.java) { InvoiceNumber("$prefix-2026-0001") }
        assertThrows(InvoiceValidationException::class.java) { InvoiceNumber("$prefix-2026-03-07-00001") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["RR-CD-2026-03", "RR-CD-2026", "RR-CD-26-03-00001", "RR-CD-2026-3-00001", "-2026-0001"])
    fun `malformed numeric tail is rejected even with a hyphenated prefix`(value: String) {
        assertThrows(InvoiceValidationException::class.java) { InvoiceNumber(value) }
    }
}
