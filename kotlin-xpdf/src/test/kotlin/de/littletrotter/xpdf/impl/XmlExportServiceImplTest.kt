package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.FacturXSchematronValidator
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceCashDiscountDto
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.ReceiptDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mustangproject.TradeParty
import org.mustangproject.ZUGFeRD.IExportableTransaction
import org.mustangproject.ZUGFeRD.Profiles
import java.math.BigDecimal

class XmlExportServiceImplTest {

    // ─── resolveProfile ──────────────────────────────────────────────────────

    @Test
    fun `resolve profile uses XRechnung when sender vat id present`() {
        val service = XmlExportServiceImpl()
        val invoice = InvoiceDto(
            senderProfile = PartyDto(vatId = "DE123"),
            recipient = PartyDto(vatId = null),
        )
        assertEquals("XRechnung", service.resolveProfile(invoice))
    }

    @Test
    fun `resolve profile uses XRechnung when recipient vat id present`() {
        val service = XmlExportServiceImpl()
        val invoice = InvoiceDto(
            senderProfile = PartyDto(vatId = null),
            recipient = PartyDto(vatId = "DE999"),
        )
        assertEquals("XRechnung", service.resolveProfile(invoice))
    }

    @Test
    fun `resolve profile falls back to EN16931 without vat ids`() {
        val service = XmlExportServiceImpl()
        val invoice = InvoiceDto(senderProfile = PartyDto(), recipient = PartyDto())
        assertEquals("EN16931", service.resolveProfile(invoice))
    }

    @Test
    fun `resolve profile falls back to EN16931 when both parties null`() {
        val service = XmlExportServiceImpl()
        assertEquals("EN16931", service.resolveProfile(InvoiceDto()))
    }

    @Test
    fun `resolve profile falls back to EN16931 when both vat ids are blank strings, not null`() {
        val service = XmlExportServiceImpl()
        val invoice = InvoiceDto(senderProfile = PartyDto(vatId = "  "), recipient = PartyDto(vatId = ""))
        assertEquals("EN16931", service.resolveProfile(invoice))
    }

    // ─── generate ────────────────────────────────────────────────────────────

    @Test
    fun `generate returns non-empty xml bytes for minimal invoice`() {
        val service = XmlExportServiceImpl()
        val invoice = InvoiceDto(
            invoiceNumber = InvoiceNumberDto("GEN-001"),
            senderProfile = PartyDto(name = "Sender", street = "Str 1", postalCode = "10115", city = "Berlin", country = "DE"),
            recipient = PartyDto(name = "Client", street = "Str 2", postalCode = "80331", city = "Munich", country = "DE"),
        )
        val bytes = service.generate(invoice)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())
        val xml = String(bytes, Charsets.UTF_8)
        assertTrue(xml.contains("?xml") || xml.isNotBlank())
    }

    @Test
    fun `generate returns xml bytes for fully filled invoice`() {
        val service = XmlExportServiceImpl()
        val invoice = fullInvoice()
        val bytes = service.generate(invoice)
        assertTrue(bytes.isNotEmpty())
    }

    // ─── PO decision 2026-09-28 — EXTENDED profile: structured Skonto discount terms ─────────

    /**
     * The real `InvoiceDto -> generate()` path, not a hand-built fixture: proves the EXTENDED
     * profile carries the structured `ram:ApplicableTradePaymentDiscountTerms` alongside the
     * BR-DE-18 `#SKONTO#` text line, that the same invoice under EN16931 does not, and that the
     * EXTENDED output stays schema-valid (F3 stage 1) and Schematron-clean (Factur-X EXTENDED
     * route) — not just "well-formed".
     */
    @Test
    fun `EXTENDED profile emits structured discount terms - EN16931 stays text-only, and the EXTENDED xml is schema and Schematron valid`() {
        val service = XmlExportServiceImpl()

        fun invoice(profileId: String) = InvoiceDto(
            invoiceNumber = InvoiceNumberDto("EXT-001"),
            meta = InvoiceMetaDto(profileId = profileId),
            dates = InvoiceDatesDto(invoiceDate = "2026-04-01", dueDate = "2026-04-15", paymentTermsDays = 14),
            senderProfile = PartyDto(
                name = "Sender GmbH", street = "Hauptstr. 1", postalCode = "10115", city = "Berlin",
                country = "DE", vatId = "DE123456789", iban = "DE02120300000000202051", bic = "BYLADEM1001",
            ),
            recipient = PartyDto(name = "Empfaenger AG", street = "Neue Str. 2", postalCode = "80331", city = "Munich", country = "DE"),
            items = listOf(
                InvoiceItemDto(
                    description = "Consulting", quantity = BigDecimal("1"),
                    taxRatePercent = BigDecimal("19"), unitPriceNet = Money(BigDecimal("100.00")),
                    netAmount = Money(BigDecimal("100.00")),
                ),
            ),
            cashDiscount = InvoiceCashDiscountDto(
                percent = BigDecimal("2"),
                days = 10,
                deadline = "2026-04-11",
                amount = Money(BigDecimal("2.38")),
                payableAmount = Money(BigDecimal("116.62")),
            ),
        )

        val extendedXml = String(service.generate(invoice("EXTENDED")), Charsets.UTF_8)
        assertTrue(
            extendedXml.contains("urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:extended"),
            "expected the EXTENDED guideline URN, got: $extendedXml",
        )
        assertTrue(
            extendedXml.contains("<ram:ApplicableTradePaymentDiscountTerms>"),
            "expected the structured discount element, got: $extendedXml",
        )
        assertTrue(
            extendedXml.contains("<ram:CalculationPercent>2.00</ram:CalculationPercent>"),
            "expected CalculationPercent 2.00, got: $extendedXml",
        )
        // BasisAmount is Mustang's own computed grand total (ZUGFeRD2PullProvider's writer,
        // not anything MustangPaymentDiscountTerms controls) - 1 x 100.00 net at 19% VAT, no
        // allowances/charges, so the gross total is exactly 119.00.
        assertTrue(
            extendedXml.contains("<ram:BasisAmount currencyID=\"EUR\">119.00</ram:BasisAmount>"),
            "expected BasisAmount to be the invoice's gross total (119.00), got: $extendedXml",
        )
        assertTrue(extendedXml.contains("#SKONTO#TAGE=10#PROZENT=2.00#"), "expected the BT-20 text line too, got: $extendedXml")

        val xsdResult = CiiXsdValidator().apply { init() }.validate(extendedXml)
        assertEquals(KositOutcome.VALID, xsdResult.outcome, "XSD findings: ${xsdResult.findings}")

        val schematronStage = FacturXSchematronValidator().apply { init() }.validate(extendedXml.toByteArray(Charsets.UTF_8))
        assertFalse(schematronStage.skipped, "expected the EXTENDED route to actually run, not pass through")
        assertTrue(schematronStage.findings.isEmpty(), "Schematron findings: ${schematronStage.findings}")
        assertEquals(KositOutcome.VALID, schematronStage.outcome)

        val en16931Xml = String(service.generate(invoice("EN16931")), Charsets.UTF_8)
        assertFalse(
            en16931Xml.contains("ApplicableTradePaymentDiscountTerms"),
            "EN16931 must never carry the structured discount element, got: $en16931Xml",
        )
        assertTrue(en16931Xml.contains("#SKONTO#TAGE=10#PROZENT=2.00#"), "EN16931 must still carry the text line, got: $en16931Xml")
    }

    // ─── buildTransaction branches ────────────────────────────────────────────

    private fun tx(invoice: InvoiceDto): IExportableTransaction {
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("buildTransaction", InvoiceDto::class.java)
        m.isAccessible = true
        return m.invoke(XmlExportServiceImpl(), invoice) as IExportableTransaction
    }

    private fun tx(invoice: InvoiceDto, service: XmlExportServiceImpl): IExportableTransaction {
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("buildTransaction", InvoiceDto::class.java)
        m.isAccessible = true
        return m.invoke(service, invoice) as IExportableTransaction
    }

    /** Minimal in-memory [DocumentStore] fake for the receipt-embedding tests below. */
    private class FakeDocumentStore(private val objects: Map<String, ByteArray>) : DocumentStore {
        var failWith: Exception? = null
        override fun put(objectKey: String, data: ByteArray, contentType: String) {}
        override fun get(objectKey: String): ByteArray =
            failWith?.let { throw it } ?: objects[objectKey] ?: throw NoSuchElementException(objectKey)
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) {}
    }

    @Test
    fun `getNumber returns UNBEKANNT for blank invoice number`() {
        val t = tx(InvoiceDto(invoiceNumber = InvoiceNumberDto("")))
        assertEquals("UNBEKANNT", t.number)
    }

    @Test
    fun `getNumber returns actual invoice number when set`() {
        val t = tx(InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-99")))
        assertEquals("INV-99", t.number)
    }

    @Test
    fun `getReferenceNumber uses buyerReference not a party vatId`() {
        // BT-10: must never come from the same generic field as a party's vatId
        // (Compliance Matrix negative example).
        val t = tx(
            InvoiceDto(
                references = InvoiceReferencesDto(buyerReference = "BUYER-REF-42"),
                recipient = PartyDto(vatId = "DE-RCP"),
                senderProfile = PartyDto(vatId = "DE-SND"),
            ),
        )
        assertEquals("BUYER-REF-42", t.referenceNumber)
    }

    @Test
    fun `getBuyerOrderReferencedDocument returns the purchase order reference when set`() {
        val t = tx(InvoiceDto(references = InvoiceReferencesDto(purchaseOrderReference = "PROJ-2026-VWAB-043")))
        assertEquals("PROJ-2026-VWAB-043", t.buyerOrderReferencedDocument?.issuerAssignedID)
    }

    /**
     * BT-13 gets its value from BT-13's own field and from nowhere else. This is the regression
     * guard for the defect the split removed: `buyerReference` (BT-10) used to feed
     * `getBuyerOrderReferencedDocument()` too, so a public buyer's Leitweg-ID was exported as a
     * purchase order number.
     */
    @Test
    fun `getBuyerOrderReferencedDocument is null when only the BT-10 buyer reference is set`() {
        val t = tx(InvoiceDto(references = InvoiceReferencesDto(buyerReference = "04011000-1234512345-06")))
        assertNull(t.buyerOrderReferencedDocument)
        assertEquals("04011000-1234512345-06", t.referenceNumber, "BT-10 itself must be unaffected")
    }

    @Test
    fun `getSellerOrderReferencedDocument returns order reference when set`() {
        val t = tx(InvoiceDto(references = InvoiceReferencesDto(orderReference = "ANG-2026-0007")))
        assertEquals("ANG-2026-0007", t.sellerOrderReferencedDocument?.issuerAssignedID)
    }

    @Test
    fun `getSellerOrderReferencedDocument returns null when order reference blank`() {
        val t = tx(InvoiceDto(references = InvoiceReferencesDto(orderReference = null)))
        assertNull(t.sellerOrderReferencedDocument)
    }

    // ─── Task O: InvoiceCurrencyCode ─────────────────────────────────────────

    @Test
    fun `getCurrency returns EUR by default when currency field blank`() {
        val t = tx(InvoiceDto())
        assertEquals("EUR", t.currency)
    }

    @Test
    fun `getCurrency returns the invoice currency when set`() {
        val t = tx(InvoiceDto(totals = InvoiceTotalsDto(currency = de.littletrotter.shared.Currency.CHF)))
        assertEquals("CHF", t.currency)
    }

    @Test
    fun `generated xml emits EUR InvoiceCurrencyCode for default invoice`() {
        val service = XmlExportServiceImpl()
        val xml = String(service.generate(fullInvoice()), Charsets.UTF_8)
        assertTrue(xml.contains("<ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>"),
            "expected EUR InvoiceCurrencyCode in:\n$xml")
    }

    @Test
    fun `generated xml emits CHF InvoiceCurrencyCode for CHF invoice`() {
        val service = XmlExportServiceImpl()
        val chfInvoice = fullInvoice().let { it.copy(totals = it.totals.copy(currency = de.littletrotter.shared.Currency.CHF)) }
        val xml = String(service.generate(chfInvoice), Charsets.UTF_8)
        assertTrue(xml.contains("<ram:InvoiceCurrencyCode>CHF</ram:InvoiceCurrencyCode>"),
            "expected CHF InvoiceCurrencyCode in:\n$xml")
    }

    @Test
    fun `generated xml emits NOK and SEK InvoiceCurrencyCode for nordic invoices`() {
        val service = XmlExportServiceImpl()
        val nokInvoice = fullInvoice().let { it.copy(totals = it.totals.copy(currency = de.littletrotter.shared.Currency.NOK)) }
        val sekInvoice = fullInvoice().let { it.copy(totals = it.totals.copy(currency = de.littletrotter.shared.Currency.SEK)) }
        val nokXml = String(service.generate(nokInvoice), Charsets.UTF_8)
        val sekXml = String(service.generate(sekInvoice), Charsets.UTF_8)
        assertTrue(nokXml.contains("<ram:InvoiceCurrencyCode>NOK</ram:InvoiceCurrencyCode>"),
            "expected NOK InvoiceCurrencyCode in:\n$nokXml")
        assertTrue(sekXml.contains("<ram:InvoiceCurrencyCode>SEK</ram:InvoiceCurrencyCode>"),
            "expected SEK InvoiceCurrencyCode in:\n$sekXml")
    }

    @Test
    fun `generated xml contains SellerOrderReferencedDocument for order reference`() {
        val service = XmlExportServiceImpl()
        val base = fullInvoice()
        val invoice = base.copy(references = base.references.copy(orderReference = "ANG-2026-0007"))
        val xml = String(service.generate(invoice), Charsets.UTF_8)
        assertTrue(xml.contains("<ram:SellerOrderReferencedDocument>"))
        assertTrue(xml.contains("<ram:IssuerAssignedID>ANG-2026-0007</ram:IssuerAssignedID>"))
    }

    // An empty string here would make Mustang write a literal
    // <ram:BuyerReference></ram:BuyerReference> (its writer guards on `!= null` only), which is
    // exactly what PEPPOL-EN16931-R008 rejects. null omits the element instead.
    @Test
    fun `V1-T01 - getReferenceNumber returns null when buyerReference is blank so no empty element is written`() {
        val t = tx(InvoiceDto(recipient = PartyDto(vatId = "DE-RCP"), senderProfile = PartyDto(vatId = "DE-SND")))
        assertNull(t.referenceNumber)
    }

    @Test
    fun `V1-T01 - getReferenceNumber returns null when both vatIds and buyerReference are null`() {
        val t = tx(InvoiceDto())
        assertNull(t.referenceNumber)
    }

    @Test
    fun `V1-T02 - getReferenceNumber passes a set buyerReference through unchanged`() {
        val t = tx(InvoiceDto(references = InvoiceReferencesDto(buyerReference = "04011000-1234512345-06")))
        assertEquals("04011000-1234512345-06", t.referenceNumber)
    }

    @Test
    fun `getIssueDate uses invoiceDate string when set`() {
        val t = tx(InvoiceDto(dates = InvoiceDatesDto(invoiceDate = "2026-03-01")))
        assertNotNull(t.issueDate)
    }

    @Test
    fun `getIssueDate returns current date when invoiceDate is null`() {
        val t = tx(InvoiceDto(dates = InvoiceDatesDto(invoiceDate = null)))
        assertNotNull(t.issueDate)
    }

    @Test
    fun `getDeliveryDate uses servicePeriodTo when set`() {
        val t = tx(InvoiceDto(dates = InvoiceDatesDto(servicePeriodTo = "2026-03-31")))
        assertNotNull(t.deliveryDate)
    }

    @Test
    fun `getDeliveryDate falls back to issueDate when servicePeriodTo is null`() {
        val t = tx(InvoiceDto(dates = InvoiceDatesDto(invoiceDate = "2026-01-01", servicePeriodTo = null)))
        assertNotNull(t.deliveryDate)
    }

    @Test
    fun `getSender returns null when senderProfile is null`() {
        val t = tx(InvoiceDto())
        assertNull(t.sender)
    }

    @Test
    fun `getSender returns party without vatId when vatId null`() {
        val t = tx(InvoiceDto(senderProfile = PartyDto(name = "Me", vatId = null)))
        assertNotNull(t.sender)
    }

    @Test
    fun `getSender returns party with vatId when vatId set`() {
        val t = tx(InvoiceDto(senderProfile = PartyDto(name = "Me", vatId = "DE123")))
        assertNotNull(t.sender)
    }

    @Test
    fun `getRecipient returns null when recipient is null`() {
        val t = tx(InvoiceDto())
        assertNull(t.recipient)
    }

    @Test
    fun `getRecipient returns party when recipient present`() {
        val t = tx(InvoiceDto(recipient = PartyDto(name = "Client", city = "Berlin")))
        assertNotNull(t.recipient)
    }

    @Test
    fun `sender and recipient electronic addresses are mapped from email`() {
        val t = tx(
            InvoiceDto(
                senderProfile = PartyDto(name = "Seller", email = "seller@example.org"),
                recipient = PartyDto(name = "Buyer", email = "buyer@example.org"),
            ),
        )

        val sender = t.sender as TradeParty
        val recipient = t.recipient as TradeParty

        assertEquals("seller@example.org", sender.uriUniversalCommunicationID)
        assertEquals("buyer@example.org", recipient.uriUniversalCommunicationID)
    }

    @Test
    fun `sender global id is mapped as ISO 6523 ICD 0088 (GLN) when provided`() {
        // BR-CL-10/ISO 6523 ICD: the legacy bare globalId field has no scheme of its own, so a
        // GS1 GLN is implied — its ICD code is "0088", not the literal string "GLN".
        val t = tx(InvoiceDto(senderProfile = PartyDto(name = "Seller", globalId = "4012345678901")))
        val sender = t.sender as TradeParty
        assertEquals("4012345678901", sender.globalID)
        assertEquals("0088", sender.globalIDScheme)
    }

    @Test
    fun `sender contact is mapped for BR-DE-2`() {
        val t = tx(
            InvoiceDto(
                senderProfile = PartyDto(name = "Seller", email = "seller@example.org", phone = "+49-123"),
            ),
        )

        val sender = t.sender as TradeParty
        val contact = sender.contact

        assertNotNull(contact)
        assertEquals("Seller", contact?.name)
        assertEquals("+49-123", contact?.phone)
        assertEquals("seller@example.org", contact?.eMail)
    }

    @Test
    fun `blank address fields are omitted instead of emitted as empty elements`() {
        val t = tx(
            InvoiceDto(
                senderProfile = PartyDto(name = "Seller", street = "", postalCode = "", city = "", country = "DE"),
            ),
        )

        val sender = t.sender as TradeParty
        assertNull(sender.street)
        assertNull(sender.getZIP())
        assertNull(sender.location)
        assertEquals("DE", sender.country)
    }

    @Test
    fun `getZFItems returns empty array when no items`() {
        val t = tx(InvoiceDto())
        assertEquals(0, t.zFItems.size)
    }

    @Test
    fun `getZFItems maps items without receipts`() {
        val t = tx(InvoiceDto(items = listOf(
            InvoiceItemDto(description = "Work", quantity = BigDecimal.ONE, taxRatePercent = BigDecimal("19"),
                unitPriceNet = Money(BigDecimal("100")), receipts = emptyList())
        )))
        assertEquals(1, t.zFItems.size)
    }

    @Test
    fun `getZFItems maps items with receipts and adds no line-level reference or note`() {
        // BR-CL-07/CII-SR-039/040/113 — receipts must not surface anything under
        // SpecifiedLineTradeAgreement any more; see MustangTransactionBuilder.buildItem.
        val t = tx(InvoiceDto(items = listOf(
            InvoiceItemDto(description = "Work", quantity = BigDecimal.ONE, taxRatePercent = BigDecimal("19"),
                unitPriceNet = Money(BigDecimal("100")),
                receipts = listOf(ReceiptDto(fileName = "r.pdf")))
        )))
        assertEquals(1, t.zFItems.size)
        // Mustang's Item returns null (not an empty array) when nothing was ever added.
        assertEquals(0, t.zFItems[0].referencedDocuments?.size ?: 0)
    }

    @Test
    fun `additionalReferencedDocuments is null when no documentStore is wired`() {
        // The no-arg constructor's documentStore is null (see the KDoc on XmlExportServiceImpl) —
        // must not throw, and a receipt without a readable object store yields no BG-24 entry.
        val t = tx(InvoiceDto(items = listOf(
            InvoiceItemDto(description = "Work", quantity = BigDecimal.ONE, taxRatePercent = BigDecimal("19"),
                unitPriceNet = Money(BigDecimal("100")),
                receipts = listOf(ReceiptDto(fileName = "r.pdf", objectKey = "receipt-pool/abc.pdf")))
        )))
        assertNull(t.additionalReferencedDocuments)
    }

    @Test
    fun `additionalReferencedDocuments embeds the receipt PDF as a document-level BG-24 entry`() {
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34)
        val store = FakeDocumentStore(mapOf("receipt-pool/abc.pdf" to pdfBytes))
        val service = XmlExportServiceImpl(store)

        val t = tx(InvoiceDto(items = listOf(
            InvoiceItemDto(description = "Work", quantity = BigDecimal.ONE, taxRatePercent = BigDecimal("19"),
                unitPriceNet = Money(BigDecimal("100")),
                receipts = listOf(ReceiptDto(fileName = "r.pdf", objectKey = "receipt-pool/abc.pdf")))
        )), service)

        // Nothing at line level any more — see the test above; the entry now lives here.
        assertEquals(0, t.zFItems[0].referencedDocuments?.size ?: 0)

        val docs = t.additionalReferencedDocuments
        assertNotNull(docs)
        assertEquals(1, docs!!.size)
        assertEquals("r.pdf", docs[0].filename)
        assertEquals("application/pdf", docs[0].mimetype)
        assertTrue(pdfBytes.contentEquals(docs[0].data))
    }

    @Test
    fun `additionalReferencedDocuments dedupes a receipt referenced from both the invoice and an item`() {
        // Task spec dedup rule: one BG-24 entry per unique receipt object key, even when the same
        // receipt hangs off the invoice-level list AND an item.
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        val store = FakeDocumentStore(mapOf("receipt-pool/shared.pdf" to pdfBytes))
        val service = XmlExportServiceImpl(store)

        val t = tx(InvoiceDto(
            receipts = listOf(ReceiptDto(fileName = "shared.pdf", objectKey = "receipt-pool/shared.pdf")),
            items = listOf(
                InvoiceItemDto(description = "Work", quantity = BigDecimal.ONE, taxRatePercent = BigDecimal("19"),
                    unitPriceNet = Money(BigDecimal("100")),
                    receipts = listOf(ReceiptDto(fileName = "shared.pdf", objectKey = "receipt-pool/shared.pdf"))),
            ),
        ), service)

        val docs = t.additionalReferencedDocuments
        assertNotNull(docs)
        assertEquals(1, docs!!.size, "the same receipt on the invoice and an item must collapse to one BG-24 entry")
    }

    @Test
    fun `additionalReferencedDocuments skips gracefully when the receipt object cannot be loaded`() {
        val store = FakeDocumentStore(emptyMap())
        val service = XmlExportServiceImpl(store)

        val t = tx(InvoiceDto(items = listOf(
            InvoiceItemDto(description = "Work", quantity = BigDecimal.ONE, taxRatePercent = BigDecimal("19"),
                unitPriceNet = Money(BigDecimal("100")),
                receipts = listOf(ReceiptDto(fileName = "missing.pdf", objectKey = "receipt-pool/missing.pdf")))
        )), service)

        // No exception, no entry — best-effort, matching the old line-level embedding's behaviour.
        assertNull(t.additionalReferencedDocuments)
    }

    @Test
    fun `generate produces well-formed XML when a receipt is embedded at document level`() {
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34)
        val store = FakeDocumentStore(mapOf("receipt-pool/abc.pdf" to pdfBytes))
        val service = XmlExportServiceImpl(store)

        val baseItem = fullInvoice().items[0]
        val invoice = fullInvoice().copy(items = listOf(baseItem.copy(receipts = listOf(ReceiptDto(fileName = "timesheet.pdf", objectKey = "receipt-pool/abc.pdf")))))

        val xml = String(service.generate(invoice), Charsets.UTF_8)

        // Well-formed: parseable by a stock JDK parser (getXML() does the same with dom4j).
        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.parse(xml.byteInputStream())

        // The embedded attachment is present, and the file name survives via IssuerAssignedID
        // (BT-122) and the attachment's filename attribute — both under
        // ApplicableHeaderTradeAgreement, not any line.
        assertTrue(xml.contains("<ram:TypeCode>916</ram:TypeCode>"))
        assertTrue(xml.contains("<ram:IssuerAssignedID>timesheet.pdf</ram:IssuerAssignedID>"))
        assertTrue(xml.contains("filename=\"timesheet.pdf\""))
        assertTrue(xml.contains(java.util.Base64.getEncoder().encodeToString(pdfBytes)))
    }

    @Test
    fun `getZFItems preserves explicit unit code`() {
        val t = tx(InvoiceDto(items = listOf(
            InvoiceItemDto(description = "Service", quantity = BigDecimal.ONE, taxRatePercent = BigDecimal("19"),
                unitPriceNet = Money(BigDecimal("100")), unitCode = "H87")
        )))
        val item = t.zFItems[0]
        assertEquals("H87", item.product.unit)
    }

    @Test
    fun `generated xml carries a valid unit code at BilledQuantity and BasisQuantity for every InvoiceItemUnit`() {
        // Task F / BR-CL-23: mirrors every UN/ECE Rec 20 (+ Rec 21) code assigned in
        // de.littletrotter.shared.catalogue.InvoiceItemUnit (kotlin-invoice). Kept as a
        // literal list here since kotlin-xpdf does not depend on kotlin-invoice's domain model —
        // if a new InvoiceItemUnit value is added there, add its uneceCode here too.
        val allUneceCodes = listOf(
            "HUR", "DAY", "WEE", "MON", "H87", "MTR", "MTK", "MTQ", "KMT", "LTR", "KGM", "C62",
            // Pinning finding: HECTARE's uneceCode was "HAR" (not an official UN/ECE Rec 20
            // code); corrected to "HA" in InvoiceItemUnit, mirrored here.
            "GRM", "HA", "TNE",
        )
        val service = XmlExportServiceImpl()

        for (code in allUneceCodes) {
            val invoice = fullInvoice().copy(
                items = listOf(
                    InvoiceItemDto(description = "Item $code", quantity = BigDecimal("2"), taxRatePercent = BigDecimal("19"),
                        unitPriceNet = Money(BigDecimal("10")), unitCode = code),
                )
            )
            val xml = String(service.generate(invoice), Charsets.UTF_8)
            val occurrences = Regex("unitCode=\"${Regex.escape(code)}\"").findAll(xml).count()
            assertTrue(occurrences >= 2, "expected unitCode=\"$code\" at both BilledQuantity and BasisQuantity, found $occurrences occurrence(s) in:\n$xml")
        }
    }

    @Test
    fun `getZFItems falls back to C62 not the invalid ST code when unit code is blank`() {
        // Task F: "ST" is not a valid UN/ECE Rec 20 code and fails BR-CL-23 on every invoice.
        val t = tx(InvoiceDto(items = listOf(
            InvoiceItemDto(description = "Work", quantity = BigDecimal.ONE, taxRatePercent = BigDecimal("19"),
                unitPriceNet = Money(BigDecimal("100")), unitCode = null)
        )))
        val item = t.zFItems[0]
        assertEquals("C62", item.product.unit)
    }

    @Test
    fun `getPaymentTermDescription names the due date for 0 days with a due date, not immediate payment`() {
        // Precedence fix — 0 days + a due date is exactly what InvoiceLifecycleService.create
        // stamps on every ordinary invoice regardless of day count, so this is the COMMON shape,
        // not an edge case: claiming "payable immediately" here (the old, wrong behaviour) would
        // be wrong on most invoices, not just a rare one. The due date wins over the day count,
        // same precedence getPaymentTerms() and the PDF's buildPaymentDueText already use.
        val t = tx(InvoiceDto(dates = InvoiceDatesDto(paymentTermsDays = 0, dueDate = "2026-03-01")))
        assertEquals("Zahlbar bis 01.03.2026", t.paymentTermDescription)
    }

    @Test
    fun `getPaymentTermDescription returns term string for positive days`() {
        val t = tx(InvoiceDto(dates = InvoiceDatesDto(paymentTermsDays = 30)))
        assertTrue(t.paymentTermDescription.contains("30"))
    }

    @Test
    fun `getPaymentTermDescription returns null when there is genuinely nothing to describe`() {
        // PO decision 2 (2026-09-02) — the exact shape a storno document carries: 0 days AND no
        // due date at all (InvoiceStornoService never derives one). Regression coverage for the
        // bug where this override unconditionally fell back to "Zahlbar sofort", which Mustang's
        // writer (ZUGFeRD2PullProvider.generateXML) then wrote into a SpecifiedTradePaymentTerms
        // group on a document that must carry no payment-terms text at all.
        val t = tx(InvoiceDto(dates = InvoiceDatesDto(paymentTermsDays = 0, dueDate = null)))
        assertNull(t.paymentTermDescription)
    }

    // ── BT-20 follows the frozen document language, both branches, plus the storno null
    //    case in English too (border cases from testmatrix.md: de/en × (days | immediate | storno)).

    @Test
    fun `getPaymentTermDescription is English for an English document with positive days`() {
        val t = tx(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = "en"), dates = InvoiceDatesDto(paymentTermsDays = 30)))
        assertEquals("Payable within 30 days net", t.paymentTermDescription)
    }

    @Test
    fun `getPaymentTermDescription names the due date in English for 0 days and a due date, not immediate payment`() {
        val t = tx(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = "en"), dates = InvoiceDatesDto(paymentTermsDays = 0, dueDate = "2026-03-01")))
        assertEquals("Payable by 1 Mar 2026", t.paymentTermDescription)
    }

    @Test
    fun `getPaymentTermDescription storno null case stays null in English too`() {
        val t = tx(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = "en"), dates = InvoiceDatesDto(paymentTermsDays = 0, dueDate = null)))
        assertNull(t.paymentTermDescription, "the storno shape must resolve to null regardless of document language")
    }

    // ── Contract: BT-9 (structured due date) and BT-20 (payment-terms text) must agree ─────────
    //
    // The assertion whose absence let the original defect live (fix-bt20-stale-payment-term-days
    // spec): a stale `paymentTermsDays = 14` on an invoice whose real due date is derived from a
    // different term must not make BT-20 name "14" while BT-9 carries a different date next to it.

    @Test
    fun `BT-9 and BT-20 name the same due date in the exported CII, even with a stale 14-day default`() {
        val invoice = InvoiceDto(
            invoiceNumber = InvoiceNumberDto("BT9-BT20-1"),
            dates = InvoiceDatesDto(invoiceDate = "2026-09-06", dueDate = "2026-10-06", paymentTermsDays = 14),
            senderProfile = PartyDto(name = "Sender", street = "Str 1", postalCode = "10115", city = "Berlin", country = "DE", vatId = "DE123456789"),
            recipient = PartyDto(name = "Recipient", street = "Str 2", postalCode = "80331", city = "Munich", country = "DE", vatId = "DE987654321"),
            items = listOf(
                InvoiceItemDto(
                    description = "Service", quantity = BigDecimal.ONE, taxRatePercent = BigDecimal("19"),
                    unitPriceNet = Money(BigDecimal("100")), netAmount = Money(BigDecimal("100")),
                ),
            ),
        )
        val xml = String(XmlExportServiceImpl().generate(invoice), Charsets.UTF_8)

        val bt9 = Regex(
            "<ram:DueDateDateTime>.*?<udt:DateTimeString[^>]*>(\\d{8})</udt:DateTimeString>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)?.groupValues?.get(1)
        val paymentTermsBlock = Regex(
            "<ram:SpecifiedTradePaymentTerms>.*?</ram:SpecifiedTradePaymentTerms>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)?.value
        val bt20 = paymentTermsBlock?.let { Regex("<ram:Description>([^<]*)</ram:Description>").find(it)?.groupValues?.get(1) }

        assertEquals("20261006", bt9, "BT-9 due date, xml: $xml")
        assertEquals("Zahlbar bis 06.10.2026", bt20, "BT-20 must name the same due date as BT-9 (not the stale 14-day default), xml: $xml")
    }

    @Test
    fun `getTradeSettlement returns fallback TypeCode-42 settlement for BR-DE-1 when senderProfile null`() {
        val t = tx(InvoiceDto())
        assertNotNull(t.tradeSettlement)
        assertEquals(1, t.tradeSettlement!!.size)
        val xml = t.tradeSettlement!![0].getSettlementXML(Profiles.getByName("EN16931"))
        assertTrue(xml.contains("<ram:TypeCode>42</ram:TypeCode>"))
    }

    @Test
    fun `getTradeSettlement returns fallback TypeCode-42 settlement for BR-DE-1 when iban blank`() {
        val t = tx(InvoiceDto(senderProfile = PartyDto(iban = "")))
        assertNotNull(t.tradeSettlement)
        assertEquals(1, t.tradeSettlement!!.size)
        val xml = t.tradeSettlement!![0].getSettlementXML(Profiles.getByName("EN16931"))
        assertTrue(xml.contains("<ram:TypeCode>42</ram:TypeCode>"))
    }

    @Test
    fun `getTradeSettlement returns SEPA bank details when iban set`() {
        val t = tx(InvoiceDto(senderProfile = PartyDto(iban = "DE02120300000000202051")))
        assertNotNull(t.tradeSettlement)
        assertEquals(1, t.tradeSettlement!!.size)
    }

    // ─── fixXmlDeclaration ────────────────────────────────────────────────────

    @Test
    fun `fix xml declaration adds missing quotes`() {
        val service = XmlExportServiceImpl()
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("fixXmlDeclaration", String::class.java)
        m.isAccessible = true
        val fixed = m.invoke(service, "<?xml version=1.0 encoding=UTF-8?><Invoice/>") as String
        assertTrue(fixed.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
    }

    @Test
    fun `invoice xml enrichment inserts language id and document status reason code`() {
        val service = XmlExportServiceImpl()
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("enrichInvoiceXml", String::class.java, InvoiceDto::class.java)
        m.isAccessible = true
        val xml = "<ram:ExchangedDocument><ram:ID>INV-1</ram:ID><ram:TypeCode>380</ram:TypeCode></ram:ExchangedDocument>"
        val result = m.invoke(service, xml, InvoiceDto(meta = InvoiceMetaDto(languageId = "DE", documentStatusReasonCode = "01"))) as String
        assertTrue(result.contains("<ram:LanguageID>DE</ram:LanguageID>"))
        assertTrue(result.contains("<ram:DocumentStatusReasonCode>01</ram:DocumentStatusReasonCode>"))
    }

    @Test
    fun `invoice xml enrichment skips insertion when the LanguageID tag is already present`() {
        val service = XmlExportServiceImpl()
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("enrichInvoiceXml", String::class.java, InvoiceDto::class.java)
        m.isAccessible = true
        val xml = "<ram:ExchangedDocument><ram:ID>INV-1</ram:ID><ram:LanguageID>FR</ram:LanguageID><ram:TypeCode>380</ram:TypeCode></ram:ExchangedDocument>"
        val result = m.invoke(service, xml, InvoiceDto(meta = InvoiceMetaDto(languageId = "DE"))) as String

        assertEquals(1, Regex("<ram:LanguageID>").findAll(result).count(), "must not insert a second LanguageID element")
        assertTrue(result.contains("<ram:LanguageID>FR</ram:LanguageID>"), "the existing value must not be overwritten")
    }

    @Test
    fun `invoice xml enrichment skips insertion when the DocumentStatusReasonCode tag is already present`() {
        val service = XmlExportServiceImpl()
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("enrichInvoiceXml", String::class.java, InvoiceDto::class.java)
        m.isAccessible = true
        val xml = "<ram:ExchangedDocument><ram:ID>INV-1</ram:ID><ram:DocumentStatusReasonCode>02</ram:DocumentStatusReasonCode><ram:TypeCode>380</ram:TypeCode></ram:ExchangedDocument>"
        val result = m.invoke(service, xml, InvoiceDto(meta = InvoiceMetaDto(documentStatusReasonCode = "01"))) as String

        assertEquals(1, Regex("<ram:DocumentStatusReasonCode>").findAll(result).count())
        assertTrue(result.contains("<ram:DocumentStatusReasonCode>02</ram:DocumentStatusReasonCode>"))
    }

    @Test
    fun `invoice xml enrichment is a no-op for a blank languageId`() {
        val service = XmlExportServiceImpl()
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("enrichInvoiceXml", String::class.java, InvoiceDto::class.java)
        m.isAccessible = true
        val xml = "<ram:ExchangedDocument><ram:ID>INV-1</ram:ID><ram:TypeCode>380</ram:TypeCode></ram:ExchangedDocument>"
        val result = m.invoke(service, xml, InvoiceDto(meta = InvoiceMetaDto(languageId = "   "))) as String

        assertEquals(xml, result)
    }

    @Test
    fun `invoice xml enrichment is a no-op for a blank documentStatusReasonCode`() {
        val service = XmlExportServiceImpl()
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("enrichInvoiceXml", String::class.java, InvoiceDto::class.java)
        m.isAccessible = true
        val xml = "<ram:ExchangedDocument><ram:ID>INV-1</ram:ID><ram:TypeCode>380</ram:TypeCode></ram:ExchangedDocument>"
        val result = m.invoke(service, xml, InvoiceDto(meta = InvoiceMetaDto(documentStatusReasonCode = "   "))) as String

        assertEquals(xml, result)
    }

    @Test
    fun `invoice xml enrichment is a no-op when neither languageId nor documentStatusReasonCode is set`() {
        val service = XmlExportServiceImpl()
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("enrichInvoiceXml", String::class.java, InvoiceDto::class.java)
        m.isAccessible = true
        val xml = "<ram:ExchangedDocument><ram:ID>INV-1</ram:ID><ram:TypeCode>380</ram:TypeCode></ram:ExchangedDocument>"
        val result = m.invoke(service, xml, InvoiceDto(meta = InvoiceMetaDto())) as String

        assertEquals(xml, result)
    }

    @Test
    fun `insertIntoExchangedDocument falls back to appending right after the opening tag when no TypeCode is found`() {
        val service = XmlExportServiceImpl()
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("enrichInvoiceXml", String::class.java, InvoiceDto::class.java)
        m.isAccessible = true
        // No </ram:TypeCode> at all -> the primary regex can't match, exercising the fallback path.
        val xml = "<ram:ExchangedDocument><ram:ID>INV-1</ram:ID></ram:ExchangedDocument>"
        val result = m.invoke(service, xml, InvoiceDto(meta = InvoiceMetaDto(languageId = "DE"))) as String

        assertEquals("<ram:ExchangedDocument><ram:LanguageID>DE</ram:LanguageID><ram:ID>INV-1</ram:ID></ram:ExchangedDocument>", result)
    }

    @Test
    fun `fix xml declaration returns input unchanged when no xml declaration present`() {
        val service = XmlExportServiceImpl()
        val m = XmlExportServiceImpl::class.java.getDeclaredMethod("fixXmlDeclaration", String::class.java)
        m.isAccessible = true
        val input = "<Invoice/>"
        val result = m.invoke(service, input) as String
        assertEquals(input, result)
    }

    // ─── BT-29/46, BT-30/47, BT-34/49 from the tax rule's schemed identifiers ──

    @Test
    fun `R8-T01 identifier scheme is used over the legacy globalId when both present`() {
        val t = tx(
            InvoiceDto(
                senderProfile = PartyDto(
                    name = "Seller",
                    globalId = "SHOULD-BE-IGNORED",
                    identifier = de.littletrotter.shared.SchemedIdentifierDto("162", "0088"),
                ),
            ),
        )
        val sender = t.sender as TradeParty
        assertEquals("162", sender.globalID)
        assertEquals("0088", sender.globalIDScheme)
    }

    @Test
    fun `R8-T02 legalRegistration maps to LegalOrganisation with the party name as trading name`() {
        val t = tx(
            InvoiceDto(
                senderProfile = PartyDto(
                    name = "Sender GmbH",
                    legalRegistration = de.littletrotter.shared.SchemedIdentifierDto("HRB 12345", "0002"),
                ),
            ),
        )
        val sender = t.sender as TradeParty
        val legalOrg = sender.legalOrganisation
        assertNotNull(legalOrg)
        assertEquals("HRB 12345", legalOrg!!.schemedID.getID())
        assertEquals("0002", legalOrg.schemedID.scheme)
        assertEquals("Sender GmbH", legalOrg.tradingBusinessName)
    }

    @Test
    fun `R8-T03 electronicAddress scheme is used over a bare email`() {
        val t = tx(
            InvoiceDto(
                recipient = PartyDto(
                    name = "Buyer",
                    email = "should-not-be-used@example.org",
                    electronicAddress = de.littletrotter.shared.SchemedIdentifierDto("buyer@peppol.example", "EM"),
                ),
            ),
        )
        val recipient = t.recipient as TradeParty
        assertEquals("buyer@peppol.example", recipient.uriUniversalCommunicationID)
        assertEquals("EM", recipient.uriUniversalCommunicationIDScheme)
    }

    @Test
    fun `R8-T04 bare email is still used as a fallback when electronicAddress is absent`() {
        val t = tx(InvoiceDto(recipient = PartyDto(name = "Buyer", email = "buyer@example.org")))
        val recipient = t.recipient as TradeParty
        assertEquals("buyer@example.org", recipient.uriUniversalCommunicationID)
    }

    // ─── per-position tax category / exemption mapped onto Product ─────────────

    @Test
    fun `R8-T05 reverse charge item sets category AE and the reverse charge flag`() {
        val t = tx(
            InvoiceDto(
                items = listOf(
                    InvoiceItemDto(
                        description = "Consulting", quantity = BigDecimal.ONE,
                        unitPriceNet = Money(BigDecimal("100")),
                        taxCategory = "AE", taxRatePercent = BigDecimal.ZERO,
                        exemptionReasonCode = "VATEX-EU-AE",
                        exemptionReasonText = "Steuerschuldnerschaft des Leistungsempfängers",
                    ),
                ),
            ),
        )
        val product = t.zFItems[0].product
        assertEquals("AE", product.taxCategoryCode)
        assertTrue(product.isReverseCharge)
        assertEquals("VATEX-EU-AE", product.taxExemptionReasonCode)
        assertEquals("Steuerschuldnerschaft des Leistungsempfängers", product.taxExemptionReason)
    }

    @Test
    fun `R8-T06 exempt-service item sets category E without the reverse charge flag`() {
        val t = tx(
            InvoiceDto(
                items = listOf(
                    InvoiceItemDto(
                        description = "Heilbehandlung", quantity = BigDecimal.ONE,
                        unitPriceNet = Money(BigDecimal("100")),
                        taxCategory = "E", taxRatePercent = BigDecimal.ZERO,
                        exemptionReasonText = "Steuerfrei nach § 4 Nr. 14 UStG",
                    ),
                ),
            ),
        )
        val product = t.zFItems[0].product
        assertEquals("E", product.taxCategoryCode)
        assertFalse(product.isReverseCharge)
        assertEquals("Steuerfrei nach § 4 Nr. 14 UStG", product.taxExemptionReason)
    }

    @Test
    fun `R8-T07 standard-rate item still carries a plain category S and the rate itself`() {
        val t = tx(
            InvoiceDto(
                items = listOf(
                    InvoiceItemDto(
                        description = "Beratung", quantity = BigDecimal.ONE,
                        unitPriceNet = Money(BigDecimal("100")),
                        taxCategory = "S", taxRatePercent = BigDecimal("19"),
                    ),
                ),
            ),
        )
        val product = t.zFItems[0].product
        assertEquals("S", product.taxCategoryCode)
        assertFalse(product.isReverseCharge)
        assertEquals(0, BigDecimal("19").compareTo(product.getVATPercent()))
    }

    @Test
    fun `R8-T09 item with no taxCategory snapshot keeps Mustang's own default category`() {
        // Mustang's Product already defaults taxCategoryCode to "S" internally; an item with no
        // taxCategory snapshot is not explicitly overridden here, so it stays on that library
        // default rather than us guessing AE/E/O from a bare rate.
        val t = tx(
            InvoiceDto(
                items = listOf(
                    InvoiceItemDto(
                        description = "Legacy item", quantity = BigDecimal.ONE,
                        unitPriceNet = Money(BigDecimal("100")), taxRatePercent = BigDecimal("19"),
                    ),
                ),
            ),
        )
        val product = t.zFItems[0].product
        assertEquals("S", product.taxCategoryCode)
        assertFalse(product.isReverseCharge)
    }

    // ─── profile id read from the register when the producer already resolved it ──

    @Test
    fun `R8-T10 resolveProfile prefers the DTO-carried profileId over the local heuristic`() {
        val service = XmlExportServiceImpl()
        val invoice = InvoiceDto(meta = InvoiceMetaDto(profileId = "EN16931"), senderProfile = PartyDto(vatId = "DE123"))
        assertEquals("EN16931", service.resolveProfile(invoice))
    }

    @Test
    fun `R8-T11 resolveProfile falls back to the local heuristic when profileId is blank`() {
        val service = XmlExportServiceImpl()
        val invoice = InvoiceDto(meta = InvoiceMetaDto(profileId = ""), senderProfile = PartyDto(vatId = "DE123"))
        assertEquals("XRechnung", service.resolveProfile(invoice))
    }

    @Test
    fun `resolveProfile falls back to the local heuristic for a whitespace-only profileId`() {
        val service = XmlExportServiceImpl()
        val invoice = InvoiceDto(meta = InvoiceMetaDto(profileId = "   "), senderProfile = PartyDto(vatId = "DE123"))
        assertEquals("XRechnung", service.resolveProfile(invoice))
    }

    @Test
    fun `resolveProfile trims surrounding whitespace off a captured profileId`() {
        val service = XmlExportServiceImpl()
        val invoice = InvoiceDto(meta = InvoiceMetaDto(profileId = "  EN16931  "))
        assertEquals("EN16931", service.resolveProfile(invoice))
    }

    // ─── existing integration test (kept) ─────────────────────────────────────

    @Test
    fun `build transaction exposes invoice metadata and referenced receipts`() {
        val service = XmlExportServiceImpl()
        val invoice = InvoiceDto(
            invoiceNumber = InvoiceNumberDto("INV-2026-03-00123"),
            dates = InvoiceDatesDto(invoiceDate = "2026-03-01", servicePeriodTo = "2026-03-05", paymentTermsDays = 14),
            references = InvoiceReferencesDto(buyerReference = "BUYER-REF-99"),
            senderProfile = PartyDto(name = "Sender", vatId = "DE123", iban = "DE02120300000000202051"),
            recipient = PartyDto(name = "Recipient", vatId = "DE999"),
            items = listOf(
                InvoiceItemDto(
                    description = "Service",
                    quantity = BigDecimal("2.0"),
                    taxRatePercent = BigDecimal("19"),
                    unitPriceNet = Money(BigDecimal("50.00")),
                    netAmount = Money(BigDecimal("100.00")),
                    receipts = listOf(ReceiptDto(fileName = "receipt-a.pdf")),
                ),
            ),
        )
        val t = tx(invoice)
        assertEquals("INV-2026-03-00123", t.number)
        assertEquals("BUYER-REF-99", t.referenceNumber)
        assertEquals("Zahlbar innerhalb 14 Tagen netto", t.paymentTermDescription) // IN-4, PO decision 3 (06.09.2026): no "von"
        assertEquals(1, t.zFItems.size)
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun fullInvoice() = InvoiceDto(
        invoiceNumber = InvoiceNumberDto("FULL-001"),
        dates = InvoiceDatesDto(invoiceDate = "2026-04-01", servicePeriodTo = "2026-04-30", paymentTermsDays = 14),
        senderProfile = PartyDto(name = "Sender GmbH", street = "Hauptstr. 1", postalCode = "10115", city = "Berlin",
            country = "DE", vatId = "DE123456789", iban = "DE02120300000000202051", bic = "BYLADEM1001"),
        recipient = PartyDto(name = "Empfaenger AG", street = "Neue Str. 2", postalCode = "80331", city = "Munich",
            country = "DE", vatId = "DE987654321"),
        items = listOf(
            InvoiceItemDto(description = "Consulting", quantity = BigDecimal("8"),
                taxRatePercent = BigDecimal("19"), unitPriceNet = Money(BigDecimal("120")),
                netAmount = Money(BigDecimal("960")), receipts = listOf(ReceiptDto("timesheet.pdf")))
        )
    )
}
