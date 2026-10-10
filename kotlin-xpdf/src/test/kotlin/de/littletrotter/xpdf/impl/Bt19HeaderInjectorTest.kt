package de.littletrotter.xpdf.impl

import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EP6-F4b — unit-level pins for [Bt19HeaderInjector] itself, independent of either export route:
 * header element present when a value is resolvable, absent when it is not, the schema-correct
 * insertion position (immediately before `</ram:ApplicableHeaderTradeSettlement>`, never disturbing
 * sibling content), and idempotence on re-run. The route-level proof (both Mustang and the
 * FacturX-engine seam actually calling this, plus the embedded-PDF copy) lives in
 * `kotlin-e2e-tests`' `Bt19EndToEndE2EIT`.
 */
class Bt19HeaderInjectorTest {

    private val settlementXml =
        "<ram:ApplicableHeaderTradeSettlement>" +
            "<ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>" +
            "</ram:ApplicableHeaderTradeSettlement>"

    private fun invoiceWithBt19(value: String) = InvoiceDto(
        items = emptyList(),
        b2bValues = listOf(B2bValueDto(B2bTerm.BT_19, value)),
    )

    private fun invoiceWithCostCenter(value: String) = InvoiceDto(
        items = emptyList(),
        references = InvoiceReferencesDto(costCenter = value),
    )

    // ─── header element present when a value is resolvable ────────────────────

    @Test
    fun `injects the header element with the BT-19 value when present`() {
        val result = Bt19HeaderInjector.inject(settlementXml, invoiceWithBt19("KST-4711"))

        assertTrue(
            result.contains("<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-4711</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>"),
            result,
        )
    }

    @Test
    fun `falls back to costCenter when no BT-19 value was captured`() {
        val result = Bt19HeaderInjector.inject(settlementXml, invoiceWithCostCenter("KST-APP-1"))

        assertTrue(
            result.contains("<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-APP-1</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>"),
            result,
        )
    }

    @Test
    fun `BT-19 wins over costCenter when both are present`() {
        val invoice = invoiceWithBt19("KST-4711").copy(references = InvoiceReferencesDto(costCenter = "KST-APP-1"))
        val result = Bt19HeaderInjector.inject(settlementXml, invoice)

        assertTrue(result.contains("KST-4711"), result)
        assertFalse(result.contains("KST-APP-1"), result)
    }

    @Test
    fun `escapes XML metacharacters in the injected value`() {
        val result = Bt19HeaderInjector.inject(settlementXml, invoiceWithBt19("A & B < C > D"))

        assertTrue(result.contains("<ram:ID>A &amp; B &lt; C &gt; D</ram:ID>"), result)
        assertFalse(result.contains("<ram:ID>A & B < C > D</ram:ID>"), result)
    }

    // ─── header element absent when no value is resolvable ────────────────────

    @Test
    fun `does not inject anything when the invoice has no BT-19 or costCenter value`() {
        val result = Bt19HeaderInjector.inject(settlementXml, InvoiceDto(items = emptyList()))

        assertEquals(settlementXml, result)
        assertFalse(result.contains("ReceivableSpecifiedTradeAccountingAccount"), "no value must never mean an empty element")
    }

    @Test
    fun `does not inject anything for a blank BT-19 value`() {
        val result = Bt19HeaderInjector.inject(settlementXml, invoiceWithBt19("   "))

        assertEquals(settlementXml, result)
    }

    @Test
    fun `leaves xml unchanged when no ApplicableHeaderTradeSettlement block exists at all`() {
        val xml = "<ram:SomethingElse/>"
        val result = Bt19HeaderInjector.inject(xml, invoiceWithBt19("KST-4711"))

        assertEquals(xml, result)
    }

    @Test
    fun `leaves xml unchanged when the settlement block has an opening tag but no closing tag`() {
        val xml = "<ram:ApplicableHeaderTradeSettlement><ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>"
        val result = Bt19HeaderInjector.inject(xml, invoiceWithBt19("KST-4711"))

        assertEquals(xml, result)
    }

    @Test
    fun `leaves xml unchanged when the closing tag appears before the opening tag`() {
        // A malformed/reversed document — the guard must not insert at whatever position
        // `indexOf(SETTLEMENT_CLOSE)` happens to find when it precedes the opening tag.
        val xml = "</ram:ApplicableHeaderTradeSettlement><ram:ApplicableHeaderTradeSettlement>"
        val result = Bt19HeaderInjector.inject(xml, invoiceWithBt19("KST-4711"))

        assertEquals(xml, result)
    }

    // ─── schema-correct position: immediately before the closing tag, siblings untouched ──────

    @Test
    fun `inserts immediately before the closing settlement tag, after existing sibling content`() {
        val xml =
            "<ram:ApplicableHeaderTradeSettlement>" +
                "<ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>" +
                "<ram:SpecifiedTradeSettlementHeaderMonetarySummation><ram:GrandTotalAmount>119.00</ram:GrandTotalAmount></ram:SpecifiedTradeSettlementHeaderMonetarySummation>" +
                "</ram:ApplicableHeaderTradeSettlement>"

        val result = Bt19HeaderInjector.inject(xml, invoiceWithBt19("KST-4711"))

        val expected =
            "<ram:ApplicableHeaderTradeSettlement>" +
                "<ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>" +
                "<ram:SpecifiedTradeSettlementHeaderMonetarySummation><ram:GrandTotalAmount>119.00</ram:GrandTotalAmount></ram:SpecifiedTradeSettlementHeaderMonetarySummation>" +
                "<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-4711</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>" +
                "</ram:ApplicableHeaderTradeSettlement>"
        assertEquals(expected, result)
    }

    @Test
    fun `never touches a line-level ReceivableSpecifiedTradeAccountingAccount (BT-133) outside the settlement block`() {
        val xml =
            "<ram:IncludedSupplyChainTradeLineItem>" +
                "<ram:SpecifiedLineTradeSettlement>" +
                "<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-4711</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>" +
                "</ram:SpecifiedLineTradeSettlement>" +
                "</ram:IncludedSupplyChainTradeLineItem>" +
                settlementXml

        val result = Bt19HeaderInjector.inject(xml, invoiceWithBt19("KST-4711"))

        // Exactly two occurrences afterward: the pre-existing line one (untouched) plus the one
        // new header injection — never a third from double-processing the line-level occurrence.
        val occurrences = Regex("<ram:ReceivableSpecifiedTradeAccountingAccount>").findAll(result).count()
        assertEquals(2, occurrences, result)
    }

    // ─── idempotence ────────────────────────────────────────────────────────

    @Test
    fun `re-running the injector over already-injected xml is a pure no-op`() {
        val onceInjected = Bt19HeaderInjector.inject(settlementXml, invoiceWithBt19("KST-4711"))
        val twiceInjected = Bt19HeaderInjector.inject(onceInjected, invoiceWithBt19("KST-4711"))

        assertEquals(onceInjected, twiceInjected)
        assertEquals(1, Regex("<ram:ReceivableSpecifiedTradeAccountingAccount>").findAll(twiceInjected).count(), twiceInjected)
    }

    // ─── byte-array overload (the engine route's own shape) ────────────────────

    @Test
    fun `byte array overload round-trips through UTF-8 and injects the same way as the string overload`() {
        val bytes = settlementXml.toByteArray(Charsets.UTF_8)
        val result = Bt19HeaderInjector.inject(bytes, invoiceWithBt19("KST-4711"))

        assertTrue(String(result, Charsets.UTF_8).contains("KST-4711"))
    }

    @Test
    fun `byte array overload returns the identical instance when no injection happens`() {
        val bytes = settlementXml.toByteArray(Charsets.UTF_8)
        val result = Bt19HeaderInjector.inject(bytes, InvoiceDto(items = emptyList()))

        assertSame(bytes, result, "a no-op must not even re-encode the bytes")
    }
}
