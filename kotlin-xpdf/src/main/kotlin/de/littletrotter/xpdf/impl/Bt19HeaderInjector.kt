package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.xpdf.impl.mustang.MustangExportModelMapper

/**
 * Post-processing seam for BT-19 (buyer accounting reference) at the CII
 * header level (`ram:ApplicableHeaderTradeSettlement/ram:ReceivableSpecifiedTradeAccountingAccount`).
 * The e-invoice generator cannot write that element on its own:
 *
 * - **Mustang** ([XmlExportServiceImpl], 2.25.0 and, checked again for this finding, 2.26.0 — the
 *   newest release as of 2026-08-30) offers no
 *   header-level setter at all on `IExportableTransaction`, only the line-level BT-133 counterpart
 *   ([de.littletrotter.xpdf.impl.mustang.MustangExportModel]'s own class KDoc has the full
 *   reasoning, including why the BT-133 fanout stays even after this fix).
 *
 * Applied by [XmlExportServiceImpl.generate] to its own already-generated CII XML, immediately after generation and before the document
 * is stored or any validation stage runs — so KoSIT/[CiiXsdValidator] see the enriched document,
 * the same as every other post-processing fixup already applied at those seams
 * ([XmlExportServiceImpl.insertIntoExchangedDocument]).
 *
 * A raw string injection, not a DOM round-trip, matching that established idiom: the fragment is
 * inserted immediately before `</ram:ApplicableHeaderTradeSettlement>`'s closing tag — verified
 * schema-safe against the CII XSD sequence
 * (`CrossIndustryInvoice_ReusableAggregateBusinessInformationEntity_100pD16B.xsd`'s
 * `HeaderTradeSettlementType`): `ram:ReceivableSpecifiedTradeAccountingAccount` is followed only by
 * `Purchase-`/`SalesSpecifiedTradeAccountingAccount`, `SpecifiedTradeSettlementFinancialCard`,
 * `SpecifiedAdvancePayment` and `UltimatePayeeTradeParty` — none of which either route ever emits,
 * so "immediately before the closing tag" always lands in the schema-correct position in practice.
 * The engine's own wire format is CII D22B rather than D16B, but this BIE and its surrounding
 * sequence position are unchanged between those CII minor versions, and the same emitted-elements
 * argument applies there too.
 *
 * Idempotent: a no-op when the invoice carries no resolvable BT-19/cost-center value, or when the
 * settlement block already carries the element (defensive — neither route emits it today, but a
 * future Mustang/engine upgrade that starts writing it itself must not end up with two).
 *
 * Marker: remove once Mustang (or the external engine) writes BT-19 itself at the header level.
 */
object Bt19HeaderInjector {

    private const val SETTLEMENT_OPEN = "<ram:ApplicableHeaderTradeSettlement>"
    private const val SETTLEMENT_CLOSE = "</ram:ApplicableHeaderTradeSettlement>"
    private const val HEADER_ELEMENT_OPEN = "<ram:ReceivableSpecifiedTradeAccountingAccount>"

    /** Byte-array overload for the engine route, which hands back raw XML bytes. */
    fun inject(xml: ByteArray, invoice: InvoiceDto): ByteArray {
        val xmlStr = String(xml, Charsets.UTF_8)
        val result = inject(xmlStr, invoice)
        // Reference-identity check by design, not a value-equality bug: every early-return
        // branch in the String overload below hands back the exact same `xml` instance it
        // received, and the only branch that actually injects builds a brand-new String via
        // substring concatenation. So `result === xmlStr` is a reliable, O(1) "was this
        // touched at all" test. When untouched we return the original `xml` bytes directly
        // rather than re-encoding `xmlStr`, which also sidesteps any (unlikely but possible)
        // decode/encode round-trip artifacts on the untouched path.
        return if (result === xmlStr) xml else result.toByteArray(Charsets.UTF_8)
    }

    /** String overload for the Mustang route, which already works on a decoded XML string
     *  ([XmlExportServiceImpl.enrichInvoiceXml]'s other fixups take the same shape). */
    fun inject(xml: String, invoice: InvoiceDto): String {
        val value = MustangExportModelMapper.resolveAccountingReference(invoice) ?: return xml
        val openIdx = xml.indexOf(SETTLEMENT_OPEN)
        val closeIdx = xml.indexOf(SETTLEMENT_CLOSE)
        if (openIdx == -1 || closeIdx == -1 || closeIdx <= openIdx) return xml
        val settlementBlock = xml.substring(openIdx, closeIdx)
        if (settlementBlock.contains(HEADER_ELEMENT_OPEN)) return xml
        val fragment = "$HEADER_ELEMENT_OPEN<ram:ID>${escapeXmlText(value)}</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>"
        return xml.substring(0, closeIdx) + fragment + xml.substring(closeIdx)
    }

    /** Minimal element-text escaping — the value is user-captured free text (BT-19) or the
     *  app-level cost center, neither schema-restricted, so it can legally contain XML
     *  metacharacters that must not corrupt the surrounding document. */
    private fun escapeXmlText(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
