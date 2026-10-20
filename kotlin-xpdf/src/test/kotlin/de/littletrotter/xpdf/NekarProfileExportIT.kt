package de.littletrotter.xpdf

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The NekarProfile proof at export level: that each of the eight documented fields (three
 * mandatory, three optional, two conditional on BT-13) correctly reaches the CII document via
 * the real path `InvoiceDto` -> `MustangTransactionBuilder` -> `XmlExportServiceImpl.generate`,
 * and that each of the three combinations from [nekarProfileInvoiceDto] stays KoSIT-`VALID`.
 *
 * Complements, does not replace: the Mustang mechanics per individual field are already proven by
 * [de.littletrotter.xpdf.impl.mustang.En16931TermsExportTest], mandatory-field **enforcement** at
 * invoice finalization by `NekarProfileConfigIT` (kotlin-invoice). This class is the missing
 * third corner: the named end-to-end case (NekarProfile), once with all eight fields, once with
 * only the mandatory floor.
 */
class NekarProfileExportIT {

    private val validator = EmbeddedKositValidator().apply { init() }

    private fun exportedXml(invoice: InvoiceDto): String =
        String(XmlExportServiceImpl().generate(invoice), Charsets.UTF_8)

    private fun compact(xml: String): String = Regex(">\\s+<").replace(xml, "><")

    private fun assertKositValid(xml: String) {
        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "Befunde: " + result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )
    }

    /**
     * The most complex case: all eight fields together, spread across three XML sections (header
     * settlement for BT-19/BT-12/BT-18, buyer party for BT-58, line item for BT-155/BT-156), plus
     * the IBAN (BT-84) in the payment section. Each fragment is named individually so a regression
     * points straight at the affected field instead of just "something is missing".
     */
    @Test
    fun `NekarProfile Pflicht- und Optionalfelder zusammen ergeben ein gueltiges, vollstaendiges Dokument`() {
        val invoice = nekarProfileInvoiceDto()
        val xml = exportedXml(invoice)
        val compact = compact(xml)

        assertKositValid(xml)

        // BT-84 — IBAN in the payment section (SEPA credit transfer instead of the TypeCode-42
        // fallback).
        assertTrue(
            compact.contains("<ram:SpecifiedTradeSettlementPaymentMeans>") &&
                compact.contains("DE02120300000000202051"),
            "BT-84 (IBAN) fehlt im Zahlungsteil: $compact",
        )

        // BT-19 — ram:ReceivableSpecifiedTradeAccountingAccount, inserted at the header by
        // Bt19HeaderInjector (Mustang 2.25.0 has no setter of its own for it) and additionally as
        // BT-133 on every line.
        assertTrue(
            compact.contains(
                "<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-NEKAR-001</ram:ID>" +
                    "</ram:ReceivableSpecifiedTradeAccountingAccount>",
            ),
            "BT-19/BT-133 fehlt: $compact",
        )

        // BT-58 — ram:BuyerTradeParty/ram:DefinedTradeContact/ram:EmailURIUniversalCommunication.
        // The NekarProfile fixture deliberately sets no BT-56 (contact name), unlike
        // b2bInvoiceDto — the underlying profile only knows BT-58, not BT-56.
        val buyer = compact.substringAfter("<ram:BuyerTradeParty>").substringBefore("</ram:BuyerTradeParty>")
        assertTrue(
            buyer.contains(
                "<ram:EmailURIUniversalCommunication><ram:URIID>rechnung@nekar-supermarkt.de</ram:URIID>" +
                    "</ram:EmailURIUniversalCommunication>",
            ),
            "BT-58 fehlt am Käufer: $buyer",
        )

        // BT-13 — ram:BuyerOrderReferencedDocument (header, ApplicableHeaderTradeAgreement).
        assertTrue(
            compact.contains(
                "<ram:BuyerOrderReferencedDocument><ram:IssuerAssignedID>2000325012</ram:IssuerAssignedID>" +
                    "</ram:BuyerOrderReferencedDocument>",
            ),
            "BT-13 fehlt: $compact",
        )

        // BT-12 — ram:ContractReferencedDocument.
        assertTrue(
            compact.contains(
                "<ram:ContractReferencedDocument><ram:IssuerAssignedID>VERTRAG-2026-01</ram:IssuerAssignedID>" +
                    "</ram:ContractReferencedDocument>",
            ),
            "BT-12 fehlt: $compact",
        )

        // BT-18 — ram:AdditionalReferencedDocument with TypeCode 130 and ReferenceTypeCode "IL"
        // (the same mechanism; the value here is NekarProfile's own).
        assertTrue(
            compact.contains(
                "<ram:AdditionalReferencedDocument><ram:IssuerAssignedID>IA-2026-01</ram:IssuerAssignedID>" +
                    "<ram:TypeCode>130</ram:TypeCode><ram:ReferenceTypeCode>IL</ram:ReferenceTypeCode>" +
                    "</ram:AdditionalReferencedDocument>",
            ),
            "BT-18 fehlt: $compact",
        )

        // BT-155/BT-156 — ram:SpecifiedTradeProduct of the (only) line item, because BT-13 is
        // set.
        val product = compact.substringAfter("<ram:SpecifiedTradeProduct>").substringBefore("</ram:SpecifiedTradeProduct>")
        assertTrue(product.contains("<ram:SellerAssignedID>Nekar-Artikelbezeichnung 4711</ram:SellerAssignedID>"), "BT-155 fehlt: $product")
        assertTrue(product.contains("<ram:BuyerAssignedID>NEKAR-ART-4711</ram:BuyerAssignedID>"), "BT-156 fehlt: $product")
    }

    /**
     * Only the mandatory floor (BT-84/BT-19/BT-58): the document stays valid, and none of the
     * five optional/conditional fields produces an empty or orphaned element.
     */
    @Test
    fun `NekarProfile Pflichtfelder allein ergeben ein gueltiges Dokument`() {
        val invoice = nekarProfileInvoiceDto(mandatoryOnly = true)
        val xml = exportedXml(invoice)
        val compact = compact(xml)

        assertKositValid(xml)

        // BT-84 — IBAN in the payment section.
        assertTrue(
            compact.contains("<ram:SpecifiedTradeSettlementPaymentMeans>") &&
                compact.contains("DE02120300000000202051"),
            "BT-84 (IBAN) fehlt im Zahlungsteil: $compact",
        )

        // BT-19 — ram:ReceivableSpecifiedTradeAccountingAccount.
        assertTrue(
            compact.contains(
                "<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-NEKAR-001</ram:ID>" +
                    "</ram:ReceivableSpecifiedTradeAccountingAccount>",
            ),
            "BT-19/BT-133 fehlt: $compact",
        )

        // BT-58 — ram:BuyerTradeParty/ram:DefinedTradeContact/ram:EmailURIUniversalCommunication.
        val buyer = compact.substringAfter("<ram:BuyerTradeParty>").substringBefore("</ram:BuyerTradeParty>")
        assertTrue(
            buyer.contains(
                "<ram:EmailURIUniversalCommunication><ram:URIID>rechnung@nekar-supermarkt.de</ram:URIID>" +
                    "</ram:EmailURIUniversalCommunication>",
            ),
            "BT-58 fehlt am Käufer: $buyer",
        )

        // BT-13 — must NOT be present.
        assertFalse(
            compact.contains("<ram:BuyerOrderReferencedDocument>"),
            "BT-13 sollte bei mandatoryOnly nicht vorhanden sein",
        )

        // BT-12 — must NOT be present.
        assertFalse(
            compact.contains("<ram:ContractReferencedDocument>"),
            "BT-12 sollte bei mandatoryOnly nicht vorhanden sein",
        )

        // BT-18 — must NOT be present.
        assertFalse(
            compact.contains("<ram:AdditionalReferencedDocument>"),
            "BT-18 sollte bei mandatoryOnly nicht vorhanden sein",
        )

        // BT-155 — must NOT be present.
        assertFalse(
            compact.contains("<ram:SellerAssignedID>"),
            "BT-155 sollte bei mandatoryOnly nicht vorhanden sein",
        )

        // BT-156 — must NOT be present.
        assertFalse(
            compact.contains("<ram:BuyerAssignedID>"),
            "BT-156 sollte bei mandatoryOnly nicht vorhanden sein",
        )
    }

    /**
     * Edge case: without BT-13, BT-155/BT-156 must not appear (the profile makes them
     * conditional on BT-13); the document stays valid regardless.
     */
    @Test
    fun `NekarProfile ohne Bestellnummer bleiben BT-155-BT-156 aus, das Dokument bleibt gueltig`() {
        val baseInvoice = nekarProfileInvoiceDto()
        val invoice = baseInvoice.copy(
            references = baseInvoice.references.copy(purchaseOrderReference = null),
            items = baseInvoice.items.mapIndexed { index, item ->
                if (index == 0) item.copy(b2bValues = emptyList()) else item
            },
        )
        val xml = exportedXml(invoice)
        val compact = compact(xml)

        assertKositValid(xml)

        // BT-13 — must NOT be present.
        assertFalse(
            compact.contains("<ram:BuyerOrderReferencedDocument>"),
            "BT-13 sollte ohne purchaseOrderReference nicht vorhanden sein",
        )

        // BT-155 — must NOT be present.
        assertFalse(
            compact.contains("<ram:SellerAssignedID>"),
            "BT-155 sollte ohne BT-13 nicht vorhanden sein",
        )

        // BT-156 — must NOT be present.
        assertFalse(
            compact.contains("<ram:BuyerAssignedID>"),
            "BT-156 sollte ohne BT-13 nicht vorhanden sein",
        )

        // BT-19 — must still be present.
        assertTrue(
            compact.contains(
                "<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-NEKAR-001</ram:ID>" +
                    "</ram:ReceivableSpecifiedTradeAccountingAccount>",
            ),
            "BT-19/BT-133 sollte noch vorhanden sein: $compact",
        )

        // BT-58 — must still be present.
        val buyer = compact.substringAfter("<ram:BuyerTradeParty>").substringBefore("</ram:BuyerTradeParty>")
        assertTrue(
            buyer.contains(
                "<ram:EmailURIUniversalCommunication><ram:URIID>rechnung@nekar-supermarkt.de</ram:URIID>" +
                    "</ram:EmailURIUniversalCommunication>",
            ),
            "BT-58 sollte noch vorhanden sein: $buyer",
        )
    }
}
