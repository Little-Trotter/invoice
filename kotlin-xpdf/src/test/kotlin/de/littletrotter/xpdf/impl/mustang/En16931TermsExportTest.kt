package de.littletrotter.xpdf.impl.mustang

import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.ShipToDto
import de.littletrotter.xpdf.b2bInvoiceDto
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mustangproject.Item
import java.math.BigDecimal
import java.sql.Date as SqlDate

/**
 * Business terms are verified on two levels: on the [MustangTransactionBuilder] object model
 * (exact and fast) and on the generated XML of [XmlExportServiceImpl.generate] (proof that
 * Mustang's writer actually emits the values — a setter the writer ignores looks correct on the
 * object model and is still missing from the document).
 *
 * Covers BT-3 (type code), BT-25/BT-26 (preceding invoice reference, BG-3), BT-72 (delivery
 * date), BT-73/BT-74 (BG-14), BT-134/BT-135 (BG-26), BT-153/BT-154 (item name and description).
 *
 * Whether these values are consistent with each other business-wise is **not** covered here but
 * in `PeriodConsistencyRules`/`UnitConsistencyRules` in kotlin-invoice; this test only checks
 * that what was set gets transported.
 */
class En16931TermsExportTest {

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun tx(invoice: InvoiceDto) =
        MustangTransactionBuilder(documentStore = null).build(MustangExportModelMapper.toModel(invoice))

    private fun items(invoice: InvoiceDto): List<Item> =
        tx(invoice).zfItems.map { it as Item }

    private fun day(iso: String): java.util.Date = SqlDate.valueOf(iso)

    /** Mustang indents its XML; a `contains` check on a nested sequence needs the version
     *  without whitespace between tags. */
    private fun compactXml(invoice: InvoiceDto): String =
        Regex(">\\s+<").replace(String(XmlExportServiceImpl().generate(invoice), Charsets.UTF_8), "><")

    /** The line-item part of the document — everything before the first header block. Needed
     *  because `BillingSpecifiedPeriod` has the same name on both levels. */
    private fun linePart(xml: String): String = xml.substringBefore("<ram:ApplicableHeaderTradeAgreement>")

    private fun headerPart(xml: String): String = xml.substringAfter("<ram:ApplicableHeaderTradeAgreement>")

    /** The buyer block alone — the seller carries its own `DefinedTradeContact` (BG-6); a
     *  `contains` check on the whole header could confuse it with BG-8. */
    private fun buyerParty(xml: String): String =
        xml.substringAfter("<ram:BuyerTradeParty>").substringBefore("</ram:BuyerTradeParty>")

    private fun dateElement(iso: String): String {
        val compact = iso.replace("-", "")
        return "<udt:DateTimeString format=\"102\">$compact</udt:DateTimeString>"
    }

    private fun line(
        description: String = "Beratung",
        itemDescription: String? = null,
        unitCode: String = "HUR",
        quantity: String = "8",
        unitPrice: String = "100.00",
        servicePeriodFrom: String? = null,
        servicePeriodTo: String? = null,
    ) = InvoiceItemDto(
        positionId = "1",
        description = description,
        itemDescription = itemDescription,
        quantity = BigDecimal(quantity),
        unitPriceNet = Money(BigDecimal(unitPrice)),
        unitCode = unitCode,
        taxRatePercent = BigDecimal("19"),
        taxCategory = "S",
        servicePeriodFrom = servicePeriodFrom,
        servicePeriodTo = servicePeriodTo,
        netAmount = Money(BigDecimal("800.00")),
        vatAmount = Money(BigDecimal("152.00")),
    )

    private fun invoice(
        typeCode: String? = null,
        originalInvoiceNumber: String? = null,
        originalInvoiceDate: String? = null,
        invoiceDate: String? = "2026-02-10",
        deliveryDate: String? = null,
        servicePeriodFrom: String? = null,
        servicePeriodTo: String? = null,
        items: List<InvoiceItemDto> = listOf(line()),
    ) = InvoiceDto(
        invoiceNumber = InvoiceNumberDto("RE-1"),
        dates = InvoiceDatesDto(
            invoiceDate = invoiceDate,
            deliveryDate = deliveryDate,
            servicePeriodFrom = servicePeriodFrom,
            servicePeriodTo = servicePeriodTo,
        ),
        typeCode = typeCode,
        references = InvoiceReferencesDto(
            originalInvoiceNumber = originalInvoiceNumber,
            originalInvoiceDate = originalInvoiceDate,
        ),
        senderProfile = PartyDto(name = "S", street = "A 1", postalCode = "10115", city = "Berlin", country = "DE", vatId = "DE123456789"),
        recipient = PartyDto(name = "R", street = "B 2", postalCode = "80331", city = "Muenchen", country = "DE"),
        items = items,
        totals = InvoiceTotalsDto(
            netAmount = Money(BigDecimal("800.00")),
            vatAmount = Money(BigDecimal("152.00")),
            grossAmount = Money(BigDecimal("952.00")),
        ),
    )

    // ─── BT-3 ────────────────────────────────────────────────────────────────

    @Nested
    inner class DocumentTypeCode {

        @Test
        fun `an invoice without a type code stays 380`() {
            assertEquals("380", tx(invoice()).documentCode)
        }

        @Test
        fun `a credit note is exported as 381`() {
            assertEquals("381", tx(invoice(typeCode = "381")).documentCode)
        }

        @Test
        fun `PO decision 1 (2026-09-02) - a storno-corrected invoice is exported as 384`() {
            assertEquals("384", tx(invoice(typeCode = "384")).documentCode)
        }

        @Test
        fun `a blank type code reads as 380 rather than as an empty element`() {
            assertEquals("380", tx(invoice(typeCode = "   ")).documentCode)
        }

        @Test
        fun `the type code reaches the ExchangedDocument in the generated xml`() {
            val exchanged = compactXml(invoice(typeCode = "381"))
                .substringAfter("<rsm:ExchangedDocument>")
                .substringBefore("</rsm:ExchangedDocument>")
            assertTrue(exchanged.contains("<ram:TypeCode>381</ram:TypeCode>"), exchanged)
        }
    }

    // ─── BT-25 / BG-3 ────────────────────────────────────────────────────────

    @Nested
    inner class PrecedingInvoiceReference {

        @Test
        fun `a normal invoice references no preceding document`() {
            assertNull(tx(invoice()).invoiceReferencedDocuments)
        }

        @Test
        fun `a blank original number is treated as absent`() {
            assertNull(tx(invoice(originalInvoiceNumber = "  ")).invoiceReferencedDocuments)
        }

        @Test
        fun `a credit note carries the cancelled invoice number as BT-25`() {
            val refs = tx(invoice(typeCode = "381", originalInvoiceNumber = "RE-4711")).invoiceReferencedDocuments
            assertNotNull(refs)
            assertEquals(1, refs!!.size)
            assertEquals("RE-4711", refs[0].issuerAssignedID)
        }

        @Test
        fun `PO decision 1 (2026-09-02) - a storno-corrected invoice (384) also carries BG-3`() {
            val refs = tx(invoice(typeCode = "384", originalInvoiceNumber = "RE-4711")).invoiceReferencedDocuments
            assertNotNull(refs)
            assertEquals(1, refs!!.size)
            assertEquals("RE-4711", refs[0].issuerAssignedID)
        }

        @Test
        fun `the issue date of the preceding document is carried as BT-26`() {
            val refs = tx(
                invoice(typeCode = "381", originalInvoiceNumber = "RE-4711", originalInvoiceDate = "2026-01-31"),
            ).invoiceReferencedDocuments
            assertEquals(day("2026-01-31"), refs!![0].formattedIssueDateTime)
        }

        /** BT-26 is optional: without a stored date, the element is omitted rather than derived
         *  from the credit note's own date, which could accidentally be correct. */
        @Test
        fun `without a stored date BT-26 is omitted rather than derived`() {
            val refs = tx(invoice(typeCode = "381", originalInvoiceNumber = "RE-4711")).invoiceReferencedDocuments
            assertNull(refs!![0].formattedIssueDateTime)
        }

        @Test
        fun `the reference reaches the generated xml without a date`() {
            val xml = compactXml(invoice(typeCode = "381", originalInvoiceNumber = "RE-4711"))
            assertTrue(
                xml.contains("<ram:InvoiceReferencedDocument><ram:IssuerAssignedID>RE-4711</ram:IssuerAssignedID></ram:InvoiceReferencedDocument>"),
                xml,
            )
        }

        /** The date lives in `qdt:DateTimeString`, not `udt:` like every other date in the
         *  document — this exact deviation is required by the EN-16931 schema at this point, and
         *  it is invisible on the object model. */
        @Test
        fun `the reference reaches the generated xml with its date`() {
            val xml = compactXml(
                invoice(typeCode = "381", originalInvoiceNumber = "RE-4711", originalInvoiceDate = "2026-01-31"),
            )
            assertTrue(
                xml.contains(
                    "<ram:InvoiceReferencedDocument>" +
                        "<ram:IssuerAssignedID>RE-4711</ram:IssuerAssignedID>" +
                        "<ram:FormattedIssueDateTime>" +
                        "<qdt:DateTimeString format=\"102\">20260131</qdt:DateTimeString>" +
                        "</ram:FormattedIssueDateTime>" +
                        "</ram:InvoiceReferencedDocument>",
                ),
                xml,
            )
        }

        @Test
        fun `a normal invoice has no InvoiceReferencedDocument element at all`() {
            assertFalse(compactXml(invoice()).contains("<ram:InvoiceReferencedDocument>"))
        }
    }

    // ─── BT-73 / BT-74 (BG-14) ───────────────────────────────────────────────

    @Nested
    inner class HeaderBillingPeriod {

        @Test
        fun `both period ends are handed to mustang`() {
            val t = tx(invoice(servicePeriodFrom = "2026-01-01", servicePeriodTo = "2026-01-31"))
            assertEquals(day("2026-01-01"), t.detailedDeliveryPeriodFrom)
            assertEquals(day("2026-01-31"), t.detailedDeliveryPeriodTo)
        }

        @Test
        fun `an invoice without a period hands over nothing`() {
            val t = tx(invoice())
            assertNull(t.detailedDeliveryPeriodFrom)
            assertNull(t.detailedDeliveryPeriodTo)
        }

        @Test
        fun `the period reaches the header settlement in the generated xml`() {
            val header = headerPart(compactXml(invoice(servicePeriodFrom = "2026-01-01", servicePeriodTo = "2026-01-31")))
            assertTrue(
                header.contains(
                    "<ram:BillingSpecifiedPeriod>" +
                        "<ram:StartDateTime>${dateElement("2026-01-01")}</ram:StartDateTime>" +
                        "<ram:EndDateTime>${dateElement("2026-01-31")}</ram:EndDateTime>" +
                        "</ram:BillingSpecifiedPeriod>",
                ),
                header,
            )
        }

        /** BR-CO-19: BG-14 present means BT-73 **or** BT-74 is filled. Mustang omits the group
         *  entirely as long as no date is set, so the rule is satisfied structurally rather than
         *  by convention. */
        @Test
        fun `no period means no BillingSpecifiedPeriod group anywhere`() {
            assertFalse(compactXml(invoice()).contains("<ram:BillingSpecifiedPeriod>"))
        }

        @Test
        fun `a one-sided period still produces the group`() {
            val header = headerPart(compactXml(invoice(servicePeriodFrom = "2026-01-01")))
            assertTrue(header.contains("<ram:BillingSpecifiedPeriod>"), header)
            assertFalse(header.substringAfter("<ram:BillingSpecifiedPeriod>").substringBefore("</ram:BillingSpecifiedPeriod>").contains("EndDateTime"))
        }
    }

    // ─── BT-134 / BT-135 (BG-26) ─────────────────────────────────────────────

    @Nested
    inner class LineBillingPeriod {

        @Test
        fun `a line period is handed to the mustang item`() {
            val item = items(invoice(items = listOf(line(servicePeriodFrom = "2026-01-05", servicePeriodTo = "2026-01-20")))).single()
            assertEquals(day("2026-01-05"), item.detailedDeliveryPeriodFrom)
            assertEquals(day("2026-01-20"), item.detailedDeliveryPeriodTo)
        }

        @Test
        fun `a line without a period hands over nothing`() {
            val item = items(invoice()).single()
            assertNull(item.detailedDeliveryPeriodFrom)
            assertNull(item.detailedDeliveryPeriodTo)
        }

        @Test
        fun `the line period reaches the line settlement in the generated xml`() {
            val lines = linePart(compactXml(invoice(items = listOf(line(servicePeriodFrom = "2026-01-05", servicePeriodTo = "2026-01-20")))))
            assertTrue(
                lines.contains(
                    "<ram:BillingSpecifiedPeriod>" +
                        "<ram:StartDateTime>${dateElement("2026-01-05")}</ram:StartDateTime>" +
                        "<ram:EndDateTime>${dateElement("2026-01-20")}</ram:EndDateTime>" +
                        "</ram:BillingSpecifiedPeriod>",
                ),
                lines,
            )
        }

        /** The header period must not accidentally leak into the line item and vice versa — both
         *  groups share the same element name, which makes the mix-up easy. */
        @Test
        fun `header period and line period stay in their own blocks`() {
            val xml = compactXml(
                invoice(
                    servicePeriodFrom = "2026-01-01",
                    servicePeriodTo = "2026-01-31",
                    items = listOf(line(servicePeriodFrom = "2026-01-05", servicePeriodTo = "2026-01-20")),
                ),
            )
            assertTrue(linePart(xml).contains(dateElement("2026-01-05")))
            assertFalse(linePart(xml).contains(dateElement("2026-01-01")))
            assertTrue(headerPart(xml).contains(dateElement("2026-01-01")))
            assertFalse(headerPart(xml).contains(dateElement("2026-01-05")))
        }

        @Test
        fun `a line period without a header period is exported unchanged`() {
            val xml = compactXml(invoice(items = listOf(line(servicePeriodFrom = "2026-01-05", servicePeriodTo = "2026-01-20"))))
            assertTrue(linePart(xml).contains("<ram:BillingSpecifiedPeriod>"))
            assertFalse(headerPart(xml).contains("<ram:BillingSpecifiedPeriod>"))
        }
    }

    // ─── BT-72 ───────────────────────────────────────────────────────────────

    @Nested
    inner class ActualDeliveryDate {

        @Test
        fun `an explicit delivery date wins over the period end`() {
            val t = tx(invoice(deliveryDate = "2026-01-20", servicePeriodTo = "2026-01-31"))
            assertEquals(day("2026-01-20"), t.deliveryDate)
        }

        /** Fallback for documents where BT-72 was previously derived from the period end — the
         *  export must stay byte-identical for those. */
        @Test
        fun `without a delivery date the period end is used`() {
            assertEquals(day("2026-01-31"), tx(invoice(servicePeriodTo = "2026-01-31")).deliveryDate)
        }

        @Test
        fun `without both the issue date is used`() {
            assertEquals(day("2026-02-10"), tx(invoice()).deliveryDate)
        }

        @Test
        fun `the delivery date reaches the ActualDeliverySupplyChainEvent`() {
            val header = headerPart(compactXml(invoice(deliveryDate = "2026-01-20", servicePeriodTo = "2026-01-31")))
            assertTrue(
                header.contains(
                    "<ram:ActualDeliverySupplyChainEvent><ram:OccurrenceDateTime>${dateElement("2026-01-20")}</ram:OccurrenceDateTime></ram:ActualDeliverySupplyChainEvent>",
                ),
                header,
            )
        }
    }

    // ─── BT-153 / BT-154 ─────────────────────────────────────────────────────

    @Nested
    inner class ItemNameAndDescription {

        private fun productBlock(xml: String): String =
            xml.substringAfter("<ram:SpecifiedTradeProduct>").substringBefore("</ram:SpecifiedTradeProduct>")

        @Test
        fun `name and description are two separate product fields`() {
            val item = items(invoice(items = listOf(line(description = "Beratung", itemDescription = "Analyse der Altdaten")))).single()
            assertEquals("Beratung", item.product.name)
            assertEquals("Analyse der Altdaten", item.product.description)
        }

        /** A missing BT-154 is **not** filled from BT-153: sending the same text twice is not a
         *  description. */
        @Test
        fun `a missing description is not filled from the name`() {
            assertEquals("", items(invoice()).single().product.description)
        }

        @Test
        fun `a blank description is normalized to empty so the element is omitted`() {
            assertEquals("", items(invoice(items = listOf(line(itemDescription = "   ")))).single().product.description)
            assertFalse(productBlock(compactXml(invoice(items = listOf(line(itemDescription = "   "))))).contains("<ram:Description>"))
        }

        @Test
        fun `both terms reach the generated xml`() {
            val product = productBlock(compactXml(invoice(items = listOf(line(description = "Beratung", itemDescription = "Analyse der Altdaten")))))
            assertTrue(product.contains("<ram:Name>Beratung</ram:Name>"), product)
            assertTrue(product.contains("<ram:Description>Analyse der Altdaten</ram:Description>"), product)
        }

        @Test
        fun `a line without BT-154 has no empty Description element`() {
            assertFalse(productBlock(compactXml(invoice())).contains("<ram:Description>"))
        }
    }

    // ─── BT-129 / BT-130 ─────────────────────────────────────────────────────

    /**
     * Transport only: the line's unit lands unchanged on `BilledQuantity`. Whether a unit fits
     * the quantity (Euro with quantity 1, integer piece counts, …) is decided by
     * `UnitConsistencyRules` in kotlin-invoice, not by the export.
     */
    @Nested
    inner class QuantityAndUnit {

        @Test
        fun `the focus units are carried through unchanged`() {
            listOf("HUR" to "8", "DAY" to "3", "H87" to "5", "MTK" to "12.50", "C62" to "1").forEach { (code, qty) ->
                val xml = compactXml(invoice(items = listOf(line(unitCode = code, quantity = qty))))
                assertTrue(
                    linePart(xml).contains("<ram:BilledQuantity unitCode=\"$code\">"),
                    "unitCode $code fehlt: ${linePart(xml)}",
                )
            }
        }

        @Test
        fun `a line without a unit code falls back to C62`() {
            val item = items(InvoiceDto(items = listOf(InvoiceItemDto(description = "Pauschale", taxRatePercent = BigDecimal.ZERO)))).single()
            assertEquals("C62", item.product.unit)
        }

        /** The domain still stores a rebate line's sign on the price (B1 comment on
         *  `InvoiceItem.validate`), but the export normalizes it onto the quantity: EN 16931
         *  BR-27/BR-28 and the Nekar policy both require BT-146 (unit price) non-negative. */
        @Test
        fun `a rebate line exports with a negative quantity and non-negative price`() {
            val item = items(invoice(items = listOf(line(unitPrice = "-50.00", quantity = "1")))).single()
            assertEquals(BigDecimal("50.00"), item.price)
            assertEquals(BigDecimal("-1"), item.quantity)
        }

        /** Sign matrix — (qty 1, price -50) exports as BT-129 = -1, BT-146 = 50,
         *  BT-131 (line net) = -50; a normal (positive-price) line is untouched. */
        @Test
        fun `the sign normalization leaves a normal line byte-identical`() {
            val xml = compactXml(invoice(items = listOf(line(unitPrice = "100.00", quantity = "8"))))
            assertTrue(linePart(xml).contains("<ram:BilledQuantity unitCode=\"HUR\">8.0000</ram:BilledQuantity>"), linePart(xml))
            assertTrue(linePart(xml).contains("<ram:ChargeAmount>100.0000</ram:ChargeAmount>"), linePart(xml))
            assertTrue(linePart(xml).contains("<ram:LineTotalAmount>800.00</ram:LineTotalAmount>"), linePart(xml))
        }

        @Test
        fun `the sign normalization matrix flips both quantity and price for a negative line`() {
            val xml = compactXml(invoice(items = listOf(line(unitPrice = "-50.00", quantity = "1"))))
            val line = linePart(xml)
            assertTrue(line.contains("<ram:BilledQuantity unitCode=\"HUR\">-1.0000</ram:BilledQuantity>"), line)
            assertTrue(line.contains("<ram:ChargeAmount>50.0000</ram:ChargeAmount>"), line)
            assertTrue(line.contains("<ram:LineTotalAmount>-50.00</ram:LineTotalAmount>"), line)
        }
    }

    // ─── The captured B2B free-text fields in the export ──────────────────────

    /**
     * B2B catalog values reach the CII export as [de.littletrotter.shared.B2bValueDto] (header
     * `InvoiceDto.b2bValues`, line `InvoiceItemDto.b2bValues`); each term is verified against the
     * exact CII element the EN-16931 binding expects it in, not just "the value appears somewhere
     * in the document".
     *
     * The XPaths stored in the catalog (`b2b_field_catalog.xpath`) are never evaluated here:
     * mapping is typed in the mapper/builder, because inserting an element via XPath after the
     * fact would violate CII's schema-relevant element ordering. See the KDoc of
     * [MustangExportModel].
     */
    @Nested
    inner class B2bProfileValues {

        private fun withHeader(vararg values: Pair<String, String>) =
            invoice().copy(b2bValues = values.map { B2bValueDto(B2bTerm.fromOfficialCode(it.first), it.second) })

        private fun withLine(vararg values: Pair<String, String>) =
            invoice(items = listOf(line().copy(b2bValues = values.map { B2bValueDto(B2bTerm.fromOfficialCode(it.first), it.second) })))

        // ── BT-12 — contract reference ────────────────────────────────────────

        @Test
        fun `BT-12 reaches the ContractReferencedDocument of the trade agreement`() {
            val header = headerPart(compactXml(withHeader("BT-12" to "V-2026-77")))
            assertTrue(
                header.contains(
                    "<ram:ContractReferencedDocument><ram:IssuerAssignedID>V-2026-77</ram:IssuerAssignedID></ram:ContractReferencedDocument>",
                ),
                header,
            )
        }

        // ── BT-18 — document object identifier ───────────────────────────────

        /** BT-18 has no element of its own in CII; it's an `AdditionalReferencedDocument` with
         *  TypeCode 130, set by Mustang's writer itself for this slot. BT-18-1
         *  (ReferenceTypeCode) is set to "IL". */
        @Test
        fun `BT-18 reaches an AdditionalReferencedDocument with TypeCode 130 and ReferenceTypeCode IL`() {
            val header = headerPart(compactXml(withHeader("BT-18" to "OBJ-4711")))
            assertTrue(
                header.contains(
                    "<ram:AdditionalReferencedDocument>" +
                        "<ram:IssuerAssignedID>OBJ-4711</ram:IssuerAssignedID>" +
                        "<ram:TypeCode>130</ram:TypeCode>" +
                        "<ram:ReferenceTypeCode>IL</ram:ReferenceTypeCode>" +
                        "</ram:AdditionalReferencedDocument>",
                ),
                header,
            )
        }

        /** Without BT-18 the AdditionalReferencedDocument is not created — specifically no empty
         *  ReferenceTypeCode element. */
        @Test
        fun `without BT-18 there is no AdditionalReferencedDocument and no ReferenceTypeCode`() {
            val header = headerPart(compactXml(invoice()))
            assertFalse(header.contains("<ram:AdditionalReferencedDocument>"), header)
            assertFalse(header.contains("<ram:ReferenceTypeCode>"), header)
        }

        // ── BT-19 / BT-133 — the cost center, exported through the line ──────

        /**
         * EP6-F4b — Mustang itself still offers no header setter for BT-19 (see the class KDoc of
         * [MustangExportModel]), but [XmlExportServiceImpl.generate] injects the header element
         * as a post-processing step ([de.littletrotter.xpdf.impl.Bt19HeaderInjector]), so the
         * captured BT-19 value reaches the document on **both** levels: the header element
         * itself, and BT-133 on every line (the pre-existing fanout).
         */
        @Test
        fun `BT-19 reaches both the header element and every line as BT-133`() {
            val xml = compactXml(withHeader("BT-19" to "KST-4711"))

            assertTrue(
                headerPart(xml).contains("<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-4711</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>"),
                headerPart(xml),
            )
            assertTrue(
                linePart(xml).contains("<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-4711</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>"),
                linePart(xml),
            )
        }

        @Test
        fun `BT-133 is fanned out to every line of a multi-line invoice`() {
            val xml = compactXml(
                withHeader("BT-19" to "KST-4711").copy(items = listOf(line(), line(description = "Zweite Position"))),
            )
            val occurrences = Regex(
                "<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-4711</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>",
            ).findAll(linePart(xml)).count()
            assertEquals(2, occurrences, linePart(xml))
        }

        /** Without a captured cost center at all, the element must not appear anywhere — no
         *  guessed or empty accounting account. */
        @Test
        fun `without a cost center BT-133 is absent from every line`() {
            assertFalse(compactXml(invoice()).contains("ReceivableSpecifiedTradeAccountingAccount"))
        }

        /** The app-level `Invoice.references.costCenter` is the fallback, used only when no BT-19
         *  FREE value was captured — see the precedence rule in [MustangExportModelMapper]. Both
         *  levels (the header injection and the pre-existing BT-133 line fanout) share the same
         *  resolved value ([MustangExportModelMapper.resolveAccountingReference]). */
        @Test
        fun `costCenter is used as BT-133 and as the header element when no BT-19 value was captured`() {
            val inv = invoice()
            val xml = compactXml(inv.copy(references = inv.references.copy(costCenter = "KST-APP-1")))
            assertTrue(
                linePart(xml).contains("<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-APP-1</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>"),
                linePart(xml),
            )
            assertTrue(
                headerPart(xml).contains("<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-APP-1</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>"),
                headerPart(xml),
            )
        }

        /** BT-19 (the captured B2B value) wins over `costCenter` when both are present — the
         *  precedence rule this fixes, applied at both levels. */
        @Test
        fun `BT-19 wins over costCenter when both are present, at both levels`() {
            val inv = withHeader("BT-19" to "KST-4711")
            val xml = compactXml(inv.copy(references = inv.references.copy(costCenter = "KST-APP-1")))
            assertTrue(linePart(xml).contains("KST-4711"), linePart(xml))
            assertFalse(linePart(xml).contains("KST-APP-1"), linePart(xml))
            assertTrue(headerPart(xml).contains("KST-4711"), headerPart(xml))
            assertFalse(headerPart(xml).contains("KST-APP-1"), headerPart(xml))
        }

        // ── BT-56 / BT-58 — buyer contact point (BG-8) ────────────────────────

        @Test
        fun `BT-56 and BT-58 reach the DefinedTradeContact of the buyer`() {
            val buyer = buyerParty(compactXml(withHeader("BT-56" to "Frau Meier", "BT-58" to "meier@acme.example")))
            assertTrue(
                buyer.contains(
                    "<ram:DefinedTradeContact>" +
                        "<ram:PersonName>Frau Meier</ram:PersonName>" +
                        "<ram:EmailURIUniversalCommunication><ram:URIID>meier@acme.example</ram:URIID></ram:EmailURIUniversalCommunication>" +
                        "</ram:DefinedTradeContact>",
                ),
                buyer,
            )
        }

        /** BG-8 is the contact person **at the buyer**, not the buyer itself. Without a captured
         *  value no contact block is created — certainly not one built from the recipient's
         *  name/email. */
        @Test
        fun `without BT-56 and BT-58 the buyer has no contact block at all`() {
            val buyer = buyerParty(compactXml(invoice()))
            assertFalse(buyer.contains("<ram:DefinedTradeContact>"), buyer)
        }

        @Test
        fun `the buyer contact does not leak into the seller party`() {
            val xml = compactXml(withHeader("BT-56" to "Frau Meier"))
            val seller = xml.substringAfter("<ram:SellerTradeParty>").substringBefore("</ram:SellerTradeParty>")
            assertFalse(seller.contains("Frau Meier"), seller)
            assertTrue(buyerParty(xml).contains("Frau Meier"), buyerParty(xml))
        }

        /** The five header terms are no longer printed on the paper (T6) — the e-invoice XML is
         *  where they live, all five at once from one invoice. */
        @Test
        fun `all five header terms BT-12 BT-18 BT-19 BT-56 and BT-58 reach the XML together`() {
            val xml = compactXml(
                withHeader(
                    "BT-12" to "V-2026-77",
                    "BT-18" to "OBJ-4711",
                    "BT-19" to "KST-4711",
                    "BT-56" to "Frau Meier",
                    "BT-58" to "meier@acme.example",
                ),
            )
            val header = headerPart(xml)
            assertTrue(header.contains("<ram:ContractReferencedDocument><ram:IssuerAssignedID>V-2026-77</ram:IssuerAssignedID>"), header)
            assertTrue(header.contains("<ram:IssuerAssignedID>OBJ-4711</ram:IssuerAssignedID><ram:TypeCode>130</ram:TypeCode>"), header)
            assertTrue(xml.contains("<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-4711</ram:ID>"), xml)
            val buyer = buyerParty(xml)
            assertTrue(buyer.contains("<ram:PersonName>Frau Meier</ram:PersonName>"), buyer)
            assertTrue(buyer.contains("<ram:URIID>meier@acme.example</ram:URIID>"), buyer)
        }

        // ── BT-155 / BT-156 — line item identifiers ───────────────────────────

        @Test
        fun `BT-155 and BT-156 reach the SpecifiedTradeProduct of the line`() {
            val product = compactXml(withLine("BT-155" to "ART-4711", "BT-156" to "KUNDE-0815"))
                .substringAfter("<ram:SpecifiedTradeProduct>")
                .substringBefore("</ram:SpecifiedTradeProduct>")
            assertTrue(
                product.startsWith(
                    "<ram:SellerAssignedID>ART-4711</ram:SellerAssignedID>" +
                        "<ram:BuyerAssignedID>KUNDE-0815</ram:BuyerAssignedID>" +
                        "<ram:Name>",
                ),
                product,
            )
        }

        @Test
        fun `the line identifiers are handed to the mustang product`() {
            val item = items(withLine("BT-155" to "ART-4711", "BT-156" to "KUNDE-0815")).single()
            assertEquals("ART-4711", item.product.sellerAssignedID)
            assertEquals("KUNDE-0815", item.product.buyerAssignedID)
        }

        @Test
        fun `one of the two identifiers alone is exported alone`() {
            val product = compactXml(withLine("BT-156" to "KUNDE-0815"))
                .substringAfter("<ram:SpecifiedTradeProduct>")
                .substringBefore("</ram:SpecifiedTradeProduct>")
            assertFalse(product.contains("SellerAssignedID"), product)
            assertTrue(product.contains("<ram:BuyerAssignedID>KUNDE-0815</ram:BuyerAssignedID>"), product)
        }

        // ── The counter-case ──────────────────────────────────────────────────

        /** Without captured values nothing about the document may change — the golden file below
         *  pins this down byte-for-byte. */
        @Test
        fun `an invoice without B2B values is unchanged by the mapping`() {
            val bare = String(XmlExportServiceImpl().generate(invoice()), Charsets.UTF_8)
            val emptyLists = String(
                XmlExportServiceImpl().generate(invoice().copy(b2bValues = emptyList())),
                Charsets.UTF_8,
            )
            assertEquals(bare, emptyLists)
            assertFalse(bare.contains("<ram:ContractReferencedDocument>"), bare)
            assertFalse(bare.contains("<ram:AdditionalReferencedDocument>"), bare)
            assertFalse(bare.contains("SellerAssignedID"), bare)
        }

        /** A blank value must not produce an empty node (PEPPOL-EN16931-R008: documents must not
         *  contain empty elements). There is no case here for an unwired or unknown term: all
         *  seven FREE catalog terms (BT-12/18/19/56/58/155/156) are wired
         *  ([de.littletrotter.xpdf.impl.mustang.MustangExportModelMapper]), and an unknown BT
         *  code cannot be constructed at all — [B2bValueDto.term] is always a real [B2bTerm]. */
        @Test
        fun `a blank term produces no element`() {
            val xml = compactXml(withHeader("BT-12" to "   "))
            assertFalse(xml.contains("<ram:ContractReferencedDocument>"), xml)
        }

        /**
         * The counter-proof: it would mean little for the B2B values to arrive if the
         * already-wired terms of the same invoice did not — BT-73/BT-74 and BT-134/BT-135.
         */
        @Test
        fun `the terms that were already wired reach the same document`() {
            val xml = compactXml(
                invoice(
                    servicePeriodFrom = "2026-01-01",
                    servicePeriodTo = "2026-01-31",
                    items = listOf(line(servicePeriodFrom = "2026-01-05", servicePeriodTo = "2026-01-20")),
                ),
            )

            assertTrue(headerPart(xml).contains(dateElement("2026-01-01")), "BT-73 fehlt: ${headerPart(xml)}")
            assertTrue(linePart(xml).contains(dateElement("2026-01-05")), "BT-134 fehlt: ${linePart(xml)}")
        }
    }

    // ─── BT-22 — Invoice.freeText as one header note ─────────────────────────

    /**
     * `Invoice.freeText` (`InvoiceMetaDto.freeText`) as one header-level
     * `ram:IncludedNote` (BT-22).
     */
    @Nested
    inner class HeaderNote {

        private fun withFreeText(freeText: String?): InvoiceDto {
            val inv = invoice()
            return inv.copy(meta = inv.meta.copy(freeText = freeText))
        }

        /** `rsm:ExchangedDocument` — where Mustang writes header-level `ram:IncludedNote`s, ahead
         *  of the line items and `ram:ApplicableHeaderTradeAgreement`, so neither [headerPart] nor
         *  [linePart] is the right cut here. */
        private fun exchangedDocument(xml: String): String =
            xml.substringAfter("<rsm:ExchangedDocument>").substringBefore("<rsm:SupplyChainTradeTransaction>")

        @Test
        fun `a non-blank freeText becomes one header IncludedNote`() {
            val xml = exchangedDocument(compactXml(withFreeText("Bitte den Zahlungsverweis angeben.")))
            assertTrue(
                xml.contains("<ram:IncludedNote><ram:Content>Bitte den Zahlungsverweis angeben.</ram:Content></ram:IncludedNote>"),
                xml,
            )
        }

        @Test
        fun `a blank freeText produces no IncludedNote at all`() {
            assertFalse(compactXml(withFreeText("   ")).contains("<ram:IncludedNote>"))
        }

        @Test
        fun `no freeText produces no IncludedNote at all`() {
            assertFalse(compactXml(invoice()).contains("<ram:IncludedNote>"))
        }

        private fun withNotes(freeText: String?, consumerNote: String?, profileId: String): InvoiceDto {
            val inv = invoice()
            return inv.copy(meta = inv.meta.copy(freeText = freeText, consumerNote = consumerNote, profileId = profileId))
        }

        private fun noteCount(xml: String): Int = Regex("<ram:IncludedNote>").findAll(exchangedDocument(xml)).count()

        @Test
        fun `a consumer note without free text is the only IncludedNote in both profiles`() {
            listOf("EN16931", "XRechnung").forEach { profile ->
                val xml = compactXml(withNotes(null, "Hinweis fuer Privatkunden", profile))
                assertEquals(1, noteCount(xml), profile)
                assertTrue(xml.contains("<ram:IncludedNote><ram:Content>Hinweis fuer Privatkunden</ram:Content></ram:IncludedNote>"), profile)
            }
        }

        @Test
        fun `free text and consumer note become two IncludedNotes, free text first, in both profiles`() {
            listOf("EN16931", "XRechnung").forEach { profile ->
                val xml = compactXml(withNotes("Freitext", "Hinweis fuer Privatkunden", profile))
                assertEquals(2, noteCount(xml), profile)
                assertTrue(
                    exchangedDocument(xml).contains(
                        "<ram:IncludedNote><ram:Content>Freitext</ram:Content></ram:IncludedNote>" +
                            "<ram:IncludedNote><ram:Content>Hinweis fuer Privatkunden</ram:Content></ram:IncludedNote>",
                    ),
                    profile,
                )
            }
        }

        @Test
        fun `a blank consumer note adds no IncludedNote`() {
            assertEquals(0, noteCount(compactXml(withNotes(null, "  ", "EN16931"))))
        }
    }

    // ─── BT-11 — Project reference ────────────────────────────────────────────

    /**
     * BT-11 is `BOUND` in the B2B catalog (`bound_property = 'projectNumber'`), so it
     * is read straight off `InvoiceReferencesDto.projectNumber`, never from `b2bValues` (a BOUND
     * term never gets a `b2b_field_value` row — see [MustangExportModel.projectReference]). It
     * reaches `ram:ApplicableHeaderTradeAgreement/ram:SpecifiedProcuringProject/ram:ID` via
     * Mustang's own header hook (`getSpecifiedProcuringProjectID/Name()`,
     * `IExportableTransaction` 2.25.0) — no `enrichInvoiceXml` fixup needed.
     */
    @Nested
    inner class ProjectReference {

        private fun withProjectNumber(value: String?): InvoiceDto {
            val inv = invoice()
            return inv.copy(references = inv.references.copy(projectNumber = value))
        }

        @Test
        fun `BT-11 reaches SpecifiedProcuringProject inside the trade agreement`() {
            val header = headerPart(compactXml(withProjectNumber("PRJ-2026-0099")))
            assertTrue(
                header.contains(
                    "<ram:SpecifiedProcuringProject><ram:ID>PRJ-2026-0099</ram:ID>" +
                        "<ram:Name>Project</ram:Name></ram:SpecifiedProcuringProject>",
                ),
                header,
            )
        }

        @Test
        fun `without a captured project reference there is no SpecifiedProcuringProject element`() {
            assertFalse(headerPart(compactXml(invoice())).contains("<ram:SpecifiedProcuringProject>"))
        }

        /** PEPPOL-EN16931-R008 — a blank value must not produce an element either. */
        @Test
        fun `a blank project reference produces no element`() {
            assertFalse(headerPart(compactXml(withProjectNumber("   "))).contains("<ram:SpecifiedProcuringProject>"))
        }
    }

    // ─── BT-10 / BT-13 — the split, on the generated document ─────────────────

    /**
     * BT-10 (`ram:BuyerReference`) and BT-13
     * (`ram:BuyerOrderReferencedDocument/ram:IssuerAssignedID`) used to be fed from a single
     * field. For a public-sector buyer that meant the Leitweg-ID ended up in the document as the
     * purchase order number.
     *
     * The three cases here are deliberately cut so that none proves anything alone: both terms
     * arriving would mean nothing if they still carried the same value; BT-13 being absent would
     * mean nothing if BT-10 disappeared along with it.
     */
    @Nested
    inner class Bt10AndBt13AreSeparateTerms {

        private fun withReferences(buyerReference: String?, purchaseOrderReference: String?): String {
            val inv = invoice()
            return compactXml(inv.copy(references = inv.references.copy(buyerReference = buyerReference, purchaseOrderReference = purchaseOrderReference)))
        }

        @Test
        fun `each term reaches its own element, carrying its own value`() {
            val xml = withReferences("04011000-1234512345-06", "BST-2026-0042")

            assertTrue(
                headerPart(xml).contains("<ram:BuyerReference>04011000-1234512345-06</ram:BuyerReference>"),
                "BT-10 fehlt: ${headerPart(xml)}",
            )
            assertTrue(
                headerPart(xml).contains("<ram:BuyerOrderReferencedDocument><ram:IssuerAssignedID>BST-2026-0042</ram:IssuerAssignedID></ram:BuyerOrderReferencedDocument>"),
                "BT-13 fehlt: ${headerPart(xml)}",
            )
        }

        /** The actual bug that the split fixes. */
        @Test
        fun `a Leitweg-ID alone no longer claims to be a purchase order number`() {
            val xml = withReferences("04011000-1234512345-06", null)

            assertTrue(
                xml.contains("<ram:BuyerReference>04011000-1234512345-06</ram:BuyerReference>"),
                "BT-10 muss unverändert übermittelt werden: $xml",
            )
            assertFalse(xml.contains("<ram:BuyerOrderReferencedDocument>"), xml)
            assertFalse(xml.contains("<ram:IssuerAssignedID>04011000-1234512345-06</ram:IssuerAssignedID>"), xml)
        }

        /** PEPPOL-EN16931-R008 — a blank field must not produce an empty node. */
        @Test
        fun `a blank purchase order reference produces no element`() {
            assertFalse(withReferences("BR-1", "   ").contains("<ram:BuyerOrderReferencedDocument>"))
        }

        /** BT-15 stays where it was — the split does not touch the third reference term. */
        @Test
        fun `the seller's own order reference stays on its own element`() {
            val inv = invoice()
            val xml = compactXml(
                inv.copy(references = inv.references.copy(buyerReference = "BR-1", purchaseOrderReference = "BST-1", orderReference = "ANG-2026-0007")),
            )

            assertTrue(xml.contains("<ram:SellerOrderReferencedDocument><ram:IssuerAssignedID>ANG-2026-0007</ram:IssuerAssignedID></ram:SellerOrderReferencedDocument>"), xml)
            assertTrue(xml.contains("<ram:BuyerOrderReferencedDocument><ram:IssuerAssignedID>BST-1</ram:IssuerAssignedID></ram:BuyerOrderReferencedDocument>"), xml)
        }
    }

    // ─── BG-13, ram:ShipToTradeParty ──────────────────────────

    @Nested
    inner class ShipTo {

        private fun shipToBlock(xml: String): String =
            headerPart(xml).substringAfter("<ram:ShipToTradeParty>").substringBefore("</ram:ShipToTradeParty>")

        @Test
        fun `a shipTo reaches ram_ShipToTradeParty inside ApplicableHeaderTradeDelivery`() {
            val xml = compactXml(
                invoice().copy(
                    shipTo = ShipToDto(
                        name = "R", street = "Lieferweg 9", postalCode = "75010", city = "Paris", country = "FR",
                    ),
                ),
            )
            assertTrue(headerPart(xml).contains("<ram:ShipToTradeParty>"), headerPart(xml))
            val shipTo = shipToBlock(xml)
            assertTrue(shipTo.contains("<ram:Name>R</ram:Name>"), shipTo)
            assertTrue(shipTo.contains("<ram:LineOne>Lieferweg 9</ram:LineOne>"), shipTo)
            assertTrue(shipTo.contains("<ram:PostcodeCode>75010</ram:PostcodeCode>"), shipTo)
            assertTrue(shipTo.contains("<ram:CityName>Paris</ram:CityName>"), shipTo)
            assertTrue(shipTo.contains("<ram:CountryID>FR</ram:CountryID>"), shipTo)
        }

        /** Without a shipTo the document stays exactly what [CommunityGoldenFile] pins down
         *  byte-for-byte — no empty or partially filled `ram:ShipToTradeParty` anywhere in the
         *  header. */
        @Test
        fun `without a shipTo the xml carries no ShipToTradeParty at all`() {
            assertFalse(compactXml(invoice()).contains("<ram:ShipToTradeParty>"))
        }

        @Test
        fun `a shipTo does not change the BuyerTradeParty itself`() {
            val xml = compactXml(
                invoice().copy(
                    shipTo = ShipToDto(
                        name = "R", street = "Lieferweg 9", postalCode = "75010", city = "Paris", country = "FR",
                    ),
                ),
            )
            val buyer = buyerParty(xml)
            assertTrue(buyer.contains("<ram:LineOne>B 2</ram:LineOne>"), buyer)
            assertFalse(buyer.contains("Lieferweg 9"), buyer)
        }
    }

    // ─── Golden file for the community case ────────────────────────────────────

    /**
     * A byte-for-byte comparison of the document from [b2bInvoiceDto] without B2B values against
     * `src/test/resources/golden/community-invoice-cii.xml`.
     *
     * What this comparison catches that the individual cases above do not: **every** change to
     * the generated document, even ones nobody wrote a case for — a Mustang update that renames
     * an element, a mapping change that drags a field along incidentally. It is deliberately
     * strict; a deliberate change is confirmed by rewriting the file, not by loosening the
     * comparison.
     */
    @Nested
    inner class CommunityGoldenFile {

        @Test
        fun `the exported xml of an invoice without B2B values is byte-identical to the golden file`() {
            val expected = checkNotNull(
                En16931TermsExportTest::class.java.getResourceAsStream("/golden/community-invoice-cii.xml"),
            ) { "Golden File fehlt" }.use { String(it.readAllBytes(), Charsets.UTF_8) }

            val actual = String(
                XmlExportServiceImpl().generate(b2bInvoiceDto(b2bValues = false)),
                Charsets.UTF_8,
            )

            assertEquals(
                expected.replace("\r\n", "\n").trimEnd(),
                actual.replace("\r\n", "\n").trimEnd(),
                "Der erzeugte Beleg weicht vom festgehaltenen Stand ab. Ist die Änderung gewollt, " +
                    "wird src/test/resources/golden/community-invoice-cii.xml neu geschrieben — " +
                    "und die Abweichung im Commit begründet.",
            )
        }

        /**
         * The one deliberate change to the golden file, named rather than just carried along
         * silently: the fixture carries a buyer reference (`PO-B2B-1`) but **no** purchase order
         * number. Before the BT-10/BT-13 split the same value appeared twice in the document —
         * once as BT-10, once as BT-13. Now it appears only where it belongs.
         *
         * Without this case, the disappearance of `ram:BuyerOrderReferencedDocument` would just
         * be one fewer line in the comparison; with it, it is an assertion that breaks again if
         * the two terms ever collapse back into one.
         */
        @Test
        fun `the golden file keeps BT-10 and no longer carries a BT-13 element`() {
            val golden = checkNotNull(
                En16931TermsExportTest::class.java.getResourceAsStream("/golden/community-invoice-cii.xml"),
            ) { "Golden File fehlt" }.use { String(it.readAllBytes(), Charsets.UTF_8) }

            assertTrue(golden.contains("<ram:BuyerReference>PO-B2B-1</ram:BuyerReference>"), golden)
            assertFalse(golden.contains("<ram:BuyerOrderReferencedDocument>"), golden)
        }
    }
}
