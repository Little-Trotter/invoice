package de.littletrotter.shared.mail

import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [MailPlaceholderCatalogue] is what a host builds a compose request against and what a test on
 * either side checks a mapper's output against — the completeness checks below are what keep a
 * typo in a key or a mismatched type from ever reaching that point.
 */
class MailPlaceholderCatalogueTest {

    // ── entries() ────────────────────────────────────────────────────────────

    @Test
    fun `INVOICE_DISPATCH carries exactly the ten invoice keys`() {
        assertEquals(
            listOf(
                "invoiceNumber", "invoiceDate", "dueDate", "grossTotal", "recipientName", "senderName",
                "senderPhone", "senderEmail", "senderWebsite", "iban",
            ),
            MailPlaceholderCatalogue.entries(MailDocumentKind.INVOICE_DISPATCH).map { it.key },
        )
    }

    @Test
    fun `QUOTE_DISPATCH carries exactly the nine quote keys with their given types`() {
        val entries = MailPlaceholderCatalogue.entries(MailDocumentKind.QUOTE_DISPATCH)
        assertEquals(
            listOf(
                "recipientName", "quoteNumber", "quoteDate", "validUntil", "totalGross", "senderName",
                "senderPhone", "senderEmail", "senderWebsite",
            ),
            entries.map { it.key },
        )
        assertEquals(
            listOf(
                MailPlaceholderValueType.STRING,
                MailPlaceholderValueType.STRING,
                MailPlaceholderValueType.DATE,
                MailPlaceholderValueType.DATE,
                MailPlaceholderValueType.MONEY,
                MailPlaceholderValueType.STRING,
                MailPlaceholderValueType.STRING,
                MailPlaceholderValueType.STRING,
                MailPlaceholderValueType.STRING,
            ),
            entries.map { it.type },
        )
    }

    // ── Completeness across kinds ────────────────────────────────────────────

    @Test
    fun `every catalogue key of every kind is a legal placeholder identifier`() {
        val identifier = Regex("\\w+")
        MailDocumentKind.entries.forEach { kind ->
            MailPlaceholderCatalogue.entries(kind).forEach { entry ->
                assertTrue(identifier.matches(entry.key), "'${entry.key}' ($kind) is not a valid {{key}} identifier")
            }
        }
    }

    @Test
    fun `a key shared by more than one kind carries the same type in every kind`() {
        val byKey = MailDocumentKind.entries
            .flatMap { kind -> MailPlaceholderCatalogue.entries(kind).map { it.key to it.type } }
            .groupBy({ it.first }, { it.second })
        byKey.forEach { (key, types) ->
            assertEquals(1, types.toSet().size, "'$key' has conflicting types across kinds: ${types.toSet()}")
        }
    }

    // ── validate() ───────────────────────────────────────────────────────────

    @Test
    fun `validate is empty for a complete, correctly typed INVOICE_DISPATCH map`() {
        val values = mapOf(
            "invoiceNumber" to MailPlaceholderValue.Text("RE-2026-0001"),
            "invoiceDate" to MailPlaceholderValue.Date(LocalDate.of(2026, 9, 7)),
            "dueDate" to MailPlaceholderValue.Date(LocalDate.of(2026, 9, 21)),
            "grossTotal" to MailPlaceholderValue.Amount(Money.of(BigDecimal("1234.56"))),
            "recipientName" to MailPlaceholderValue.Text("Muster GmbH"),
            "senderName" to MailPlaceholderValue.Text("Little Trotter"),
            "iban" to MailPlaceholderValue.Text("DE02120300000000202051"),
        )
        assertEquals(emptyList<String>(), MailPlaceholderCatalogue.validate(MailDocumentKind.INVOICE_DISPATCH, values))
    }

    @Test
    fun `validate is empty when only the required keys of INVOICE_DISPATCH are present`() {
        val values = mapOf(
            "invoiceNumber" to MailPlaceholderValue.Text("RE-2026-0001"),
            "invoiceDate" to MailPlaceholderValue.Date(LocalDate.of(2026, 9, 7)),
            "grossTotal" to MailPlaceholderValue.Amount(Money.of(BigDecimal("1234.56"))),
            "recipientName" to MailPlaceholderValue.Text("Muster GmbH"),
        )
        assertEquals(emptyList<String>(), MailPlaceholderCatalogue.validate(MailDocumentKind.INVOICE_DISPATCH, values))
    }

    @Test
    fun `validate is empty for a complete QUOTE_DISPATCH map`() {
        val values = mapOf(
            "recipientName" to MailPlaceholderValue.Text("Muster GmbH"),
            "quoteNumber" to MailPlaceholderValue.Text("AN-2026-0001"),
            "quoteDate" to MailPlaceholderValue.Date(LocalDate.of(2026, 9, 7)),
            "validUntil" to MailPlaceholderValue.Date(LocalDate.of(2026, 10, 7)),
            "totalGross" to MailPlaceholderValue.Amount(Money.of(BigDecimal("999.00"))),
            "senderName" to MailPlaceholderValue.Text("Little Trotter"),
        )
        assertEquals(emptyList<String>(), MailPlaceholderCatalogue.validate(MailDocumentKind.QUOTE_DISPATCH, values))
    }

    @Test
    fun `validate reports an unknown key`() {
        val violations = MailPlaceholderCatalogue.validate(
            MailDocumentKind.INVOICE_DISPATCH,
            mapOf("totallyUnknown" to MailPlaceholderValue.Text("x")),
        )
        assertTrue(violations.any { it.contains("unknown") && it.contains("totallyUnknown") })
    }

    @Test
    fun `validate reports a wrong-typed value for a known key`() {
        val violations = MailPlaceholderCatalogue.validate(
            MailDocumentKind.INVOICE_DISPATCH,
            mapOf("grossTotal" to MailPlaceholderValue.Text("1234.56")),
        )
        assertTrue(violations.any { it.contains("grossTotal") && it.contains("MONEY") })
    }

    @Test
    fun `validate reports every missing required key, not just the first`() {
        val violations = MailPlaceholderCatalogue.validate(MailDocumentKind.INVOICE_DISPATCH, emptyMap())
        assertTrue(violations.any { it.contains("invoiceNumber") })
        assertTrue(violations.any { it.contains("invoiceDate") })
        assertTrue(violations.any { it.contains("grossTotal") })
        assertTrue(violations.any { it.contains("recipientName") })
        // dueDate/senderName/iban are optional — their absence must not be reported.
        assertTrue(violations.none { it.contains("dueDate") })
        assertTrue(violations.none { it.contains("senderName") })
        assertTrue(violations.none { it.contains("iban") })
    }

    @Test
    fun `an unknown key and a missing required key are both reported at once`() {
        val violations = MailPlaceholderCatalogue.validate(
            MailDocumentKind.QUOTE_DISPATCH,
            mapOf("notAQuoteField" to MailPlaceholderValue.Text("x")),
        )
        assertTrue(violations.any { it.contains("notAQuoteField") })
        assertTrue(violations.any { it.contains("quoteNumber") })
    }
}
