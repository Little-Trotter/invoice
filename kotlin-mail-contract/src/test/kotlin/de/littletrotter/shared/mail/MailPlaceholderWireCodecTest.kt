package de.littletrotter.shared.mail

import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The one codec both contexts use for typed placeholder values. One test per value kind,
 * because the whole reason this moved into the contract is that two hand-kept-in-sync copies would
 * drift on the first kind whose text form is not simply `toString()`.
 */
class MailPlaceholderWireCodecTest {

    private val typed = linkedMapOf<String, MailPlaceholderValue>(
        "invoiceNumber" to MailPlaceholderValue.Text("RE-2026-0001"),
        "invoiceDate" to MailPlaceholderValue.Date(LocalDate.of(2026, 9, 7)),
        "grossTotal" to MailPlaceholderValue.Amount(Money.of(BigDecimal("1234.56"))),
    )

    // ── Round trip, per value type ──────────────────────────────────────────────

    @Test
    fun `encode-decode round-trips all three value types`() {
        assertEquals(typed, MailPlaceholderWireCodec.decode(MailPlaceholderWireCodec.encode(typed)))
    }

    @Test
    fun `STRING keeps the value verbatim`() {
        val value = MailPlaceholderValue.Text("Muster & Söhne GmbH")
        assertEquals(MailPlaceholderValueType.STRING, value.type)
        assertEquals("Muster & Söhne GmbH", value.wireValue)
        assertEquals(value, MailPlaceholderWireCodec.parse(MailPlaceholderValueType.STRING, value.wireValue))
    }

    @Test
    fun `DATE travels as ISO-8601, never as a German date`() {
        val value = MailPlaceholderValue.Date(LocalDate.of(2026, 1, 3))
        assertEquals("2026-01-03", value.wireValue)
        assertEquals(value, MailPlaceholderWireCodec.parse(MailPlaceholderValueType.DATE, "2026-01-03"))
    }

    @Test
    fun `MONEY travels as a plain decimal at Money's own scale`() {
        val value = MailPlaceholderValue.Amount(Money.of(BigDecimal("1234.50")))
        assertEquals("1234.50", value.wireValue)
        assertEquals(value, MailPlaceholderWireCodec.parse(MailPlaceholderValueType.MONEY, "1234.50"))
    }

    @Test
    fun `MONEY normalises an unrounded amount to two decimals on the wire`() {
        val encoded = MailPlaceholderValue.Amount(Money.exact(BigDecimal("33.3333"))).wireValue
        assertEquals("33.33", encoded)
        assertEquals(
            MailPlaceholderValue.Amount(Money.of(BigDecimal("33.33"))),
            MailPlaceholderWireCodec.parse(MailPlaceholderValueType.MONEY, encoded),
        )
    }

    @Test
    fun `an empty placeholder map round-trips as an empty JSON object`() {
        assertEquals("{}", MailPlaceholderWireCodec.encode(emptyMap()))
        assertEquals(emptyMap<String, MailPlaceholderValue>(), MailPlaceholderWireCodec.decode("{}"))
    }

    // ── The flat map shape the component's POST /api/send already takes ─────────

    @Test
    fun `flatten produces the component's flat string map, in order`() {
        assertEquals(
            listOf("invoiceNumber" to "RE-2026-0001", "invoiceDate" to "2026-09-07", "grossTotal" to "1234.56"),
            MailPlaceholderWireCodec.flatten(typed).toList(),
        )
    }

    @Test
    fun `expand is flatten's inverse given the types`() {
        val types = mapOf(
            "invoiceNumber" to MailPlaceholderValueType.STRING,
            "invoiceDate" to MailPlaceholderValueType.DATE,
            "grossTotal" to MailPlaceholderValueType.MONEY,
        )
        assertEquals(typed, MailPlaceholderWireCodec.expand(MailPlaceholderWireCodec.flatten(typed), types))
    }

    @Test
    fun `expand skips a key whose type this build does not know`() {
        val expanded = MailPlaceholderWireCodec.expand(
            mapOf("invoiceNumber" to "RE-1", "vatSummary" to "irrelevant"),
            mapOf("invoiceNumber" to MailPlaceholderValueType.STRING),
        )
        assertEquals(mapOf("invoiceNumber" to MailPlaceholderValue.Text("RE-1")), expanded)
    }

    // ── Loud failures instead of a plausible-looking wrong value ────────────────

    @Test
    fun `a malformed date is rejected, not kept as a raw string`() {
        val thrown = assertThrows<MailContractViolationException> {
            MailPlaceholderWireCodec.parse(MailPlaceholderValueType.DATE, "07.09.2026")
        }
        assertTrue(thrown.message!!.contains("DATE"))
    }

    @Test
    fun `a malformed amount is rejected, not kept as a raw string`() {
        assertThrows<MailContractViolationException> {
            MailPlaceholderWireCodec.parse(MailPlaceholderValueType.MONEY, "1.234,56 EUR")
        }
    }

    @Test
    fun `an entry without a type tag fails loudly`() {
        assertThrows<MailContractViolationException> {
            MailPlaceholderWireCodec.decode("""{"invoiceNumber":{"value":"RE-1"}}""")
        }
    }

    @Test
    fun `an entry without a value fails loudly rather than decoding to an empty string`() {
        assertThrows<MailContractViolationException> {
            MailPlaceholderWireCodec.decode("""{"invoiceNumber":{"type":"STRING"}}""")
        }
    }

    @Test
    fun `an entry with a blank value fails loudly`() {
        assertThrows<MailContractViolationException> {
            MailPlaceholderWireCodec.decode("""{"invoiceNumber":{"type":"STRING","value":"   "}}""")
        }
    }

    @Test
    fun `an unknown type tag fails loudly`() {
        assertThrows<MailContractViolationException> {
            MailPlaceholderWireCodec.decode("""{"grossTotal":{"type":"PERCENTAGE","value":"19"}}""")
        }
    }

    @Test
    fun `an entry that is not an object fails loudly`() {
        assertThrows<MailContractViolationException> {
            MailPlaceholderWireCodec.decode("""{"invoiceNumber":"RE-1"}""")
        }
    }

    @Test
    fun `extra keys inside an entry are ignored on read`() {
        val decoded = MailPlaceholderWireCodec.decode(
            """{"invoiceDate":{"type":"DATE","value":"2026-09-07","displayHint":"long","format":9}}"""
        )
        assertEquals(mapOf("invoiceDate" to MailPlaceholderValue.Date(LocalDate.of(2026, 9, 7))), decoded)
    }

    @Test
    fun `a non-object payload fails loudly`() {
        assertThrows<MailContractViolationException> { MailPlaceholderWireCodec.decode("[]") }
        assertThrows<MailContractViolationException> { MailPlaceholderWireCodec.decode("   ") }
        assertThrows<MailContractViolationException> { MailPlaceholderWireCodec.decode(null) }
        assertThrows<MailContractViolationException> { MailPlaceholderWireCodec.decode("{ not json") }
    }
}
