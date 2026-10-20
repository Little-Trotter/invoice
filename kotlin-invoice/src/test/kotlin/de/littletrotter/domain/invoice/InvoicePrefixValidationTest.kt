package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class InvoicePrefixValidationTest {

    @ParameterizedTest
    @ValueSource(strings = ["RE-", "RE", "ADS005", "A", "abcdefghij", " RE- ", "Feld1-", "", "  ", "DSC-00001", "H272-0001-", "RR-CD-", "GANZ-ANDERS-", "abcdefghijklmnopqrst"])
    fun `accepts prefixes that yield valid invoice numbers or fall back to default`(prefix: String) {
        assertTrue(InvoiceNumber.isValidPrefix(prefix), prefix)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\${7*7}-", "\${7*7}", "../../etc/passwd", "Feld 1", "abcdefghijklmnopqrstu", "-", "-RE", "RE_1", "RE/1", "<b>"])
    fun `rejects hostile or malformed prefixes`(prefix: String) {
        assertFalse(InvoiceNumber.isValidPrefix(prefix), prefix)
    }

    @Test
    fun `an accepted prefix really produces a valid invoice number`() {
        val head = "RE-".trim().trimEnd('-')
        assertEquals("RE-2026-03-00001", InvoiceNumber("$head-2026-03-00001").value)
    }
}
