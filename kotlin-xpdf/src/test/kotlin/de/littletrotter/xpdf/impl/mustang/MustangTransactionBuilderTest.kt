package de.littletrotter.xpdf.impl.mustang

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.ReceiptUnavailableException
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.SchemedIdentifierDto
import de.littletrotter.shared.ShipToDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mustangproject.Contact
import org.mustangproject.TradeParty
import java.math.BigDecimal

/**
 * BT-34/BT-49 (electronicAddress) through the Mustang pipeline
 * ([MustangExportModelMapper] -> [MustangTransactionBuilder]). A Peppol participant ID is exactly
 * this Business Term with an ICD scheme (e.g. "9930", DE:VAT-based) instead of the "EM" email
 * scheme both sides already fall back to — no new type needed, just proof it isn't limited to
 * "EM".
 */
class MustangTransactionBuilderTest {

    private fun tx(invoice: InvoiceDto) = MustangTransactionBuilder(documentStore = null).build(MustangExportModelMapper.toModel(invoice))

    @Test
    fun `a Peppol participant scheme on the seller electronicAddress is exported unchanged`() {
        val t = tx(
            InvoiceDto(
                senderProfile = PartyDto(
                    name = "Seller",
                    electronicAddress = SchemedIdentifierDto("DE123456789", "9930"),
                ),
            ),
        )
        val sender = t.sender as TradeParty
        assertEquals("DE123456789", sender.uriUniversalCommunicationID)
        assertEquals("9930", sender.uriUniversalCommunicationIDScheme)
    }

    @Test
    fun `a Peppol participant scheme on the buyer electronicAddress is exported unchanged`() {
        val t = tx(
            InvoiceDto(
                recipient = PartyDto(
                    name = "Buyer",
                    electronicAddress = SchemedIdentifierDto("DE987654321", "9930"),
                ),
            ),
        )
        val recipient = t.recipient as TradeParty
        assertEquals("DE987654321", recipient.uriUniversalCommunicationID)
        assertEquals("9930", recipient.uriUniversalCommunicationIDScheme)
    }

    @Test
    fun `electronicAddress scheme is used over a bare email`() {
        val t = tx(
            InvoiceDto(
                recipient = PartyDto(
                    name = "Buyer",
                    email = "should-not-be-used@example.org",
                    electronicAddress = SchemedIdentifierDto("buyer@peppol.example", "EM"),
                ),
            ),
        )
        val recipient = t.recipient as TradeParty
        assertEquals("buyer@peppol.example", recipient.uriUniversalCommunicationID)
        assertEquals("EM", recipient.uriUniversalCommunicationIDScheme)
    }

    @Test
    fun `bare email is still used as a fallback when electronicAddress is absent`() {
        val t = tx(InvoiceDto(recipient = PartyDto(name = "Buyer", email = "buyer@example.org")))
        val recipient = t.recipient as TradeParty
        assertEquals("buyer@example.org", recipient.uriUniversalCommunicationID)
    }

    // ── BG-13, InvoiceDto.shipTo -> getDeliveryAddress() ──────────

    @Test
    fun `a shipTo maps to Mustang's delivery address, not the sender or recipient party`() {
        val t = tx(
            InvoiceDto(
                recipient = PartyDto(name = "Buyer", street = "Rechnungsweg 1", postalCode = "10115", city = "Berlin", country = "FR"),
                shipTo = ShipToDto(name = "Buyer", street = "Lieferweg 9", postalCode = "75010", city = "Paris", country = "FR"),
            ),
        )
        val shipTo = t.deliveryAddress as TradeParty
        assertEquals("Buyer", shipTo.name)
        assertEquals("Lieferweg 9", shipTo.getStreet())
        assertEquals("75010", shipTo.getZIP())
        assertEquals("Paris", shipTo.getLocation())
        assertEquals("FR", shipTo.getCountry())
        // The differing delivery address must not leak into the recipient itself.
        val recipient = t.recipient as TradeParty
        assertEquals("Rechnungsweg 1", recipient.getStreet())
    }

    @Test
    fun `without a shipTo Mustang's own default (null) is kept, so no delivery address is exported`() {
        val t = tx(InvoiceDto(recipient = PartyDto(name = "Buyer")))
        assertNull(t.deliveryAddress)
    }

    @Test
    fun `a shipTo country of DE is resolved to the two-letter code, same convention as sender and recipient`() {
        val t = tx(
            InvoiceDto(
                shipTo = ShipToDto(name = "Buyer", street = "Lieferweg 2", postalCode = "10117", city = "Berlin", country = null),
            ),
        )
        val shipTo = t.deliveryAddress as TradeParty
        assertEquals("DE", shipTo.getCountry(), "PartyDto/ShipToDto tragen 'DE' als null; Mustangs CountryID ist Pflicht und muss trotzdem gefuellt sein")
    }

    // ── direct MustangExportModel construction for precise branch control ──────────────────

    private fun basePartyModel(name: String? = "Party") = MustangPartyModel(
        name = name,
        street = "Str. 1",
        postalCode = "12345",
        city = "City",
        country = "DE",
        vatId = null,
        taxNumber = null,
        globalIdSchemeId = null,
        globalIdValue = null,
        legalRegistrationSchemeId = null,
        legalRegistrationValue = null,
        electronicAddressSchemeId = null,
        electronicAddressValue = null,
        email = null,
        phone = null,
        includeContact = false,
    )

    private fun baseItemModel() = MustangItemModel(
        description = "Item",
        itemDescription = "",
        servicePeriodFrom = null,
        servicePeriodTo = null,
        unitCode = "C62",
        taxRatePercent = BigDecimal("19"),
        unitPriceNet = BigDecimal("100"),
        quantity = BigDecimal("1"),
        taxCategoryCode = "S",
        exemptionReasonCode = null,
        exemptionReasonText = null,
        productClassificationScheme = "ETIM",
        productClassificationCode = null,
    )

    private fun baseModel() = MustangExportModel(
        number = "INV-1",
        buyerReference = null,
        purchaseOrderReference = null,
        orderReference = null,
        orderReferenceDate = null,
        invoiceDate = "2026-02-10",
        deliveryDate = null,
        billingPeriodFrom = null,
        billingPeriodTo = null,
        documentTypeCode = "380",
        precedingInvoiceNumber = null,
        precedingInvoiceDate = null,
        currencyCode = "EUR",
        sender = basePartyModel("Seller"),
        recipient = basePartyModel("Buyer"),
        items = listOf(baseItemModel()),
        paymentTermsDays = 0,
        dueDate = null,
        senderIban = null,
    )

    @Test
    fun `a project reference produces the fixed project name Mustang's schema requires`() {
        val model = baseModel().copy(projectReference = "PROJ-1")
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertEquals("PROJ-1", t.specifiedProcuringProjectID)
        assertEquals("Project", t.specifiedProcuringProjectName)
    }

    @Test
    fun `no project reference means both procuring project getters stay null`() {
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel())

        assertNull(t.specifiedProcuringProjectID)
        assertNull(t.specifiedProcuringProjectName)
    }

    // ── getAdditionalReferencedDocuments() — receipt objectKey null/blank/valid ─────────────

    @Test
    fun `a receipt with a null objectKey fails instead of being skipped`() {
        val store: DocumentStore = mock()
        val model = baseModel().copy(receipts = listOf(MustangReceiptRefModel(fileName = "a.pdf", objectKey = null)))

        val ex = assertThrows(ReceiptUnavailableException::class.java) {
            MustangTransactionBuilder(store).build(model).additionalReferencedDocuments
        }
        assertTrue(ex.userMessage.contains("a.pdf"))
    }

    @Test
    fun `a receipt with a blank objectKey fails`() {
        val store: DocumentStore = mock()
        val model = baseModel().copy(receipts = listOf(MustangReceiptRefModel(fileName = "a.pdf", objectKey = "   ")))

        assertThrows(ReceiptUnavailableException::class.java) {
            MustangTransactionBuilder(store).build(model).additionalReferencedDocuments
        }
    }

    @Test
    fun `a receipt the store cannot load fails with a user-facing message`() {
        val store: DocumentStore = mock()
        whenever(store.get(eq("r/a.pdf"))).thenThrow(IllegalStateException("gone"))
        val model = baseModel().copy(receipts = listOf(MustangReceiptRefModel(fileName = "a.pdf", objectKey = "r/a.pdf")))

        val ex = assertThrows(ReceiptUnavailableException::class.java) {
            MustangTransactionBuilder(store).build(model).additionalReferencedDocuments
        }
        assertTrue(ex.userMessage.contains("Der Export wurde abgebrochen"), ex.userMessage)
    }

    @Test
    fun `BT-123 is the description, or the file name when there is none`() {
        val store: DocumentStore = mock()
        whenever(store.get(any())).thenReturn(byteArrayOf(1))
        val model = baseModel().copy(
            receipts = listOf(
                MustangReceiptRefModel("a.pdf", "r/a.pdf", description = "Taxi Berlin"),
                MustangReceiptRefModel("b.pdf", "r/b.pdf", description = "  "),
                MustangReceiptRefModel("c.pdf", "r/c.pdf"),
            ),
        )

        val docs = MustangTransactionBuilder(store).build(model).additionalReferencedDocuments!!

        assertEquals(listOf("Taxi Berlin", "b.pdf", "c.pdf"), docs.map { it.description })
        assertEquals(listOf("a.pdf", "b.pdf", "c.pdf"), docs.map { it.filename })
    }

    @Test
    fun `a receipt with a valid objectKey is fetched and included`() {
        val store: DocumentStore = mock()
        whenever(store.get(eq("r/a.pdf"))).thenReturn(byteArrayOf(1, 2, 3))
        val model = baseModel().copy(receipts = listOf(MustangReceiptRefModel(fileName = "a.pdf", objectKey = "r/a.pdf")))

        val t = MustangTransactionBuilder(store).build(model)

        assertEquals(1, t.additionalReferencedDocuments!!.size)
    }

    // ── getPaymentTerms() — dueDate / paymentTermsDays combinations ────────────────────────

    @Test
    fun `no due date and no payment term days means no payment terms at all`() {
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel())
        assertNull(t.paymentTerms)
    }

    @Test
    fun `an explicit due date produces payment terms even with zero payment term days`() {
        val model = baseModel().copy(dueDate = "2026-03-01")
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertNotNull(t.paymentTerms)
        assertNotNull(t.paymentTerms!!.dueDate)
    }

    @Test
    fun `payment term days without an explicit due date derives one from the invoice date`() {
        val model = baseModel().copy(paymentTermsDays = 14)
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertNotNull(t.paymentTerms)
        assertNotNull(t.paymentTerms!!.dueDate)
    }

    @Test
    fun `payment term days without any date at all still produces payment terms, but no due date`() {
        // The invoice-date fallback (`model.invoiceDate?.takeIf { paymentTermsDays > 0 }?.let { ... }`)
        // needs an invoice date to derive a due date from; without one, `dueDate` stays null even
        // though `paymentTermsDays > 0` still makes `getPaymentTerms()` return a non-null object.
        val model = baseModel().copy(paymentTermsDays = 14, invoiceDate = null)
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertNotNull(t.paymentTerms)
        assertNull(t.paymentTerms!!.dueDate)
    }

    // ── getPaymentTermDescription() — the no-payment-term-at-all fallback's own precedence ──
    //
    // Fix — this override used to branch on the stale
    // `paymentTermsDays` entity default first, and its `dueDate != null` branch said "immediate
    // payment" instead of naming the date. Matrix below: (due date | days) x (present | absent),
    // plus the trap (a stale non-14 default alongside a real due date) and the cancellation null case
    // this precedence change must not touch.

    @Test
    fun `neither due date nor payment term days produces the cancellation null, not immediate-payment text`() {
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel())
        assertNull(t.paymentTermDescription)
    }

    @Test
    fun `positive days without a due date name the day count`() {
        val model = baseModel().copy(paymentTermsDays = 30, dueDate = null)
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertEquals("Zahlbar innerhalb 30 Tagen netto", t.paymentTermDescription)
    }

    @Test
    fun `a due date with zero payment term days names the date, not immediate payment`() {
        // The second defect from the fix spec: this exact shape used to return "Zahlbar sofort".
        val model = baseModel().copy(paymentTermsDays = 0, dueDate = "2026-10-06")
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertEquals("Zahlbar bis 06.10.2026", t.paymentTermDescription)
    }

    @Test
    fun `a due date wins over a stale positive payment term days value`() {
        // The primary defect: `paymentTermsDays` is a create()-time-only default (14) never
        // written again once a real payment term is chosen (InvoiceLifecycleService's own KDoc).
        // A due date derived from an actually-chosen, non-14-day term must win over that stale
        // default, not the other way round.
        val model = baseModel().copy(paymentTermsDays = 14, dueDate = "2026-10-06")
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertEquals("Zahlbar bis 06.10.2026", t.paymentTermDescription)
    }

    @Test
    fun `both branches present still resolve through the due date, never through paymentTermName being absent`() {
        // paymentTermName short-circuits everything below it whenever a term was actually
        // chosen; this pins the fallback ladder's own precedence for when it is null.
        val model = baseModel().copy(paymentTermName = null, paymentTermsDays = 30, dueDate = "2026-11-15")
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertEquals("Zahlbar bis 15.11.2026", t.paymentTermDescription)
    }

    @Test
    fun `the cancellation shape - zero days and no due date - still resolves to null in English too`() {
        val model = baseModel().copy(paymentTermsDays = 0, dueDate = null, documentLanguage = "en")
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertNull(t.paymentTermDescription, "the storno shape must resolve to null regardless of document language")
    }

    @Test
    fun `paymentTermName short-circuits the fallback ladder entirely, even with a due date and days set`() {
        val model = baseModel().copy(paymentTermName = "30 Tage netto", paymentTermsDays = 14, dueDate = "2026-10-06")
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertEquals("30 Tage netto", t.paymentTermDescription)
    }

    // ── getPaymentTermDescription() — BR-DE-18 Skonto line appended to BT-20 ───────────────

    @Test
    fun `a cash discount appends the BR-DE-18 Skonto line after the term name`() {
        val model = baseModel().copy(
            paymentTermName = "30 Tage netto",
            cashDiscountDays = 7,
            cashDiscountPercent = BigDecimal("2"),
        )
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertEquals("30 Tage netto\n#SKONTO#TAGE=7#PROZENT=2.00#\n", t.paymentTermDescription)
    }

    @Test
    fun `a fractional discount percentage is formatted with two decimals`() {
        val model = baseModel().copy(
            paymentTermName = "30 Tage netto",
            cashDiscountDays = 10,
            cashDiscountPercent = BigDecimal("2.5"),
        )
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertEquals("30 Tage netto\n#SKONTO#TAGE=10#PROZENT=2.50#\n", t.paymentTermDescription)
    }

    @Test
    fun `a cash discount appends the Skonto line after the composed due-date fallback too`() {
        val model = baseModel().copy(
            paymentTermName = null,
            paymentTermsDays = 0,
            dueDate = "2026-10-06",
            cashDiscountDays = 7,
            cashDiscountPercent = BigDecimal("2"),
        )
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertEquals("Zahlbar bis 06.10.2026\n#SKONTO#TAGE=7#PROZENT=2.00#\n", t.paymentTermDescription)
    }

    @Test
    fun `no cash discount leaves the payment term description unchanged`() {
        val model = baseModel().copy(paymentTermName = "30 Tage netto")
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertEquals("30 Tage netto", t.paymentTermDescription)
    }

    @Test
    fun `the cancellation shape stays null even when the model somehow carries discount fields`() {
        // Cancellation never has a payment term, so InvoiceDtoMapper never fills cashDiscount for it -
        // this only pins that a discount can never resurrect text for a document that must carry
        // none, however the fields got set.
        val model = baseModel().copy(
            paymentTermsDays = 0,
            dueDate = null,
            cashDiscountDays = 7,
            cashDiscountPercent = BigDecimal("2"),
        )
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertNull(t.paymentTermDescription)
    }

    // ── getDiscountTerms() — structured Skonto, EXTENDED profile only ──────────────────────

    @Test
    fun `EXTENDED with a cash discount produces structured discount terms rounded to two decimals`() {
        val model = baseModel().copy(
            exportProfile = "EXTENDED",
            dueDate = "2026-10-06",
            cashDiscountDays = 10,
            cashDiscountPercent = BigDecimal("2.5"),
        )
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        val discountTerms = t.paymentTerms!!.discountTerms
        assertNotNull(discountTerms)
        assertEquals(BigDecimal("2.50"), discountTerms!!.calculationPercentage)
        assertNull(discountTerms.baseDate)
        assertEquals(10, discountTerms.basePeriodMeasure)
        assertEquals("DAY", discountTerms.basePeriodUnitCode)
    }

    @Test
    fun `EXTENDED is matched case-insensitively`() {
        val model = baseModel().copy(
            exportProfile = "extended",
            dueDate = "2026-10-06",
            cashDiscountDays = 7,
            cashDiscountPercent = BigDecimal("2"),
        )
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertNotNull(t.paymentTerms!!.discountTerms)
    }

    @Test
    fun `EN16931 never carries structured discount terms, even with a cash discount`() {
        val model = baseModel().copy(
            exportProfile = "EN16931",
            dueDate = "2026-10-06",
            cashDiscountDays = 7,
            cashDiscountPercent = BigDecimal("2"),
        )
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertNull(t.paymentTerms!!.discountTerms)
    }

    @Test
    fun `XRechnung never carries structured discount terms, even with a cash discount`() {
        val model = baseModel().copy(
            exportProfile = "XRechnung",
            dueDate = "2026-10-06",
            cashDiscountDays = 7,
            cashDiscountPercent = BigDecimal("2"),
        )
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertNull(t.paymentTerms!!.discountTerms)
    }

    @Test
    fun `EXTENDED without a cash discount carries no discount terms`() {
        val model = baseModel().copy(exportProfile = "EXTENDED", paymentTermsDays = 14)
        val t = MustangTransactionBuilder(documentStore = null).build(model)

        assertNull(t.paymentTerms!!.discountTerms)
    }

    // ── buildParty() — every optional field's null branch ──────────────────────────────────

    @Test
    fun `a party with only a country set carries no name, street, zip, location, vat or tax id`() {
        val model = baseModel().copy(
            sender = MustangPartyModel(
                name = null, street = null, postalCode = null, city = null, country = "DE",
                vatId = null, taxNumber = null,
                globalIdSchemeId = null, globalIdValue = null,
                legalRegistrationSchemeId = null, legalRegistrationValue = null,
                electronicAddressSchemeId = null, electronicAddressValue = null,
                email = null, phone = null, includeContact = false,
            ),
        )
        val t = MustangTransactionBuilder(documentStore = null).build(model)
        val sender = t.sender as TradeParty

        assertNull(sender.getName())
        assertNull(sender.getStreet())
        assertNull(sender.getZIP())
        assertNull(sender.getLocation())
        assertEquals("DE", sender.getCountry())
    }

    @Test
    fun `globalId is only set when both scheme and value are present, not when only one is`() {
        val schemeOnly = basePartyModel().copy(globalIdSchemeId = "0088", globalIdValue = null)
        val valueOnly = basePartyModel().copy(globalIdSchemeId = null, globalIdValue = "4000001")
        val both = basePartyModel().copy(globalIdSchemeId = "0088", globalIdValue = "4000001")

        listOf(schemeOnly, valueOnly).forEach { party ->
            val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(sender = party))
            assertNull((t.sender as TradeParty).getGlobalID(), "no globalId expected for $party")
        }
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(sender = both))
        assertEquals("4000001", (t.sender as TradeParty).getGlobalID())
    }

    @Test
    fun `legalRegistration is only set when both scheme and value are present`() {
        val schemeOnly = basePartyModel().copy(legalRegistrationSchemeId = "0002", legalRegistrationValue = null)
        val valueOnly = basePartyModel().copy(legalRegistrationSchemeId = null, legalRegistrationValue = "552100554")
        val both = basePartyModel().copy(legalRegistrationSchemeId = "0002", legalRegistrationValue = "552100554")

        listOf(schemeOnly, valueOnly).forEach { party ->
            val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(sender = party))
            assertNull((t.sender as TradeParty).getLegalOrganisation(), "no legalOrganisation expected for $party")
        }
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(sender = both))
        assertNotNull((t.sender as TradeParty).getLegalOrganisation())
    }

    @Test
    fun `electronicAddress is only used when both scheme and value are present, otherwise email is the fallback`() {
        val schemeOnly = basePartyModel().copy(electronicAddressSchemeId = "EM", electronicAddressValue = null, email = "fallback@example.com")
        val valueOnly = basePartyModel().copy(electronicAddressSchemeId = null, electronicAddressValue = "unused@example.com", email = "fallback@example.com")

        listOf(schemeOnly, valueOnly).forEach { party ->
            val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(sender = party))
            assertEquals("fallback@example.com", (t.sender as TradeParty).getEmail(), "expected the email fallback for $party")
        }
    }

    @Test
    fun `includeContact with all three contact fields null builds no Contact block`() {
        val party = basePartyModel(name = null).copy(includeContact = true, phone = null, email = null)
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(sender = party))

        assertNull((t.sender as TradeParty).getContact())
    }

    @Test
    fun `includeContact with only a phone number still builds a Contact block with just the phone`() {
        val party = basePartyModel(name = null).copy(includeContact = true, phone = "+49 30 1234", email = null)
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(sender = party))

        val contact = (t.sender as TradeParty).getContact() as Contact
        assertNull(contact.getName())
        assertEquals("+49 30 1234", contact.getPhone())
    }

    @Test
    fun `includeContact with only an email still builds a Contact block with just the email`() {
        // The only combination of the three `contactName != null || contactPhone != null ||
        // contactEmail != null` operands not already covered above: name and phone both null,
        // forcing evaluation through to the third (email) operand.
        val party = basePartyModel(name = null).copy(includeContact = true, phone = null, email = "seller@example.com")
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(sender = party))

        val contact = (t.sender as TradeParty).getContact() as Contact
        assertNull(contact.getName())
        assertNull(contact.getPhone())
        assertEquals("seller@example.com", contact.getEMail())
    }

    @Test
    fun `without includeContact, a buyer contact point built only from BT-56 name (no email)`() {
        val party = basePartyModel().copy(includeContact = false, contactPointName = "Ansprechpartner", contactPointEmail = null)
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(recipient = party))

        val contact = (t.recipient as TradeParty).getContact() as Contact
        assertEquals("Ansprechpartner", contact.getName())
    }

    @Test
    fun `without includeContact, a buyer contact point built only from BT-58 email (no name)`() {
        val party = basePartyModel().copy(includeContact = false, contactPointName = null, contactPointEmail = "contact@example.com")
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(recipient = party))

        val contact = (t.recipient as TradeParty).getContact() as Contact
        assertNull(contact.getName())
    }

    @Test
    fun `without includeContact and no contact point values, no Contact block at all`() {
        val party = basePartyModel().copy(includeContact = false, contactPointName = null, contactPointEmail = null)
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(recipient = party))

        assertNull((t.recipient as TradeParty).getContact())
    }

    // ── buildShipToParty() — every field's null branch ─────────────────────────────────────

    @Test
    fun `a shipTo with only a country set carries no name, street, zip or location`() {
        val model = baseModel().copy(
            shipTo = MustangShipToModel(name = null, street = null, postalCode = null, city = null, country = "FR"),
        )
        val t = MustangTransactionBuilder(documentStore = null).build(model)
        val shipTo = t.deliveryAddress as TradeParty

        assertNull(shipTo.getName())
        assertNull(shipTo.getStreet())
        assertNull(shipTo.getZIP())
        assertNull(shipTo.getLocation())
        assertEquals("FR", shipTo.getCountry())
    }

    // ── buildItem() — product classification and per-line service period ──────────────────

    @Test
    fun `a product classification code adds a classification entry`() {
        val item = baseItemModel().copy(productClassificationCode = "EC002714")
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(items = listOf(item)))

        val product = t.getZFItems().single().getProduct()
        assertTrue(product.getClassifications().isNotEmpty(), "expected a classification entry to be added")
    }

    @Test
    fun `a line with only servicePeriodFrom still sets a detailed delivery period`() {
        val item = baseItemModel().copy(servicePeriodFrom = "2026-01-01", servicePeriodTo = null)
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(items = listOf(item)))

        assertNotNull(t.getZFItems().single().getDetailedDeliveryPeriodFrom())
        assertNull(t.getZFItems().single().getDetailedDeliveryPeriodTo())
    }

    @Test
    fun `a line with only servicePeriodTo still sets a detailed delivery period`() {
        val item = baseItemModel().copy(servicePeriodFrom = null, servicePeriodTo = "2026-01-31")
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel().copy(items = listOf(item)))

        assertNull(t.getZFItems().single().getDetailedDeliveryPeriodFrom())
        assertNotNull(t.getZFItems().single().getDetailedDeliveryPeriodTo())
    }

    @Test
    fun `a line with neither service period date sets no detailed delivery period`() {
        val t = MustangTransactionBuilder(documentStore = null).build(baseModel())

        assertNull(t.getZFItems().single().getDetailedDeliveryPeriodFrom())
        assertNull(t.getZFItems().single().getDetailedDeliveryPeriodTo())
    }

    // ── BT-120 composition on the wire (BT-121/exemptionReasonCode stays untouched) ──────────

    private fun aeItemModel(exemptionReasonText: String?, exemptionReasonCode: String? = "VATEX-EU-AE") = baseItemModel().copy(
        taxCategoryCode = "AE", exemptionReasonCode = exemptionReasonCode, exemptionReasonText = exemptionReasonText,
    )

    @Test
    fun `IN-6 - a German document's BT-120 is the master-data text unchanged - golden, byte-identical`() {
        val model = baseModel().copy(documentLanguage = "de", items = listOf(aeItemModel("Steuerschuldnerschaft aus § 3a Abs. 2 UStG.")))
        val product = MustangTransactionBuilder(documentStore = null).build(model).getZFItems().single().getProduct()

        assertEquals("Steuerschuldnerschaft aus § 3a Abs. 2 UStG.", product.taxExemptionReason)
        assertEquals("VATEX-EU-AE", product.taxExemptionReasonCode)
    }

    @Test
    fun `IN-6 - an English document's BT-120 keeps the German original and appends the English standard text after an em dash`() {
        val model = baseModel().copy(documentLanguage = "en", items = listOf(aeItemModel("Steuerschuldnerschaft aus § 3a Abs. 2 UStG.")))
        val product = MustangTransactionBuilder(documentStore = null).build(model).getZFItems().single().getProduct()

        assertEquals(
            "Steuerschuldnerschaft aus § 3a Abs. 2 UStG. — Reverse charge: the recipient is liable for VAT (Art. 196 VAT Directive).",
            product.taxExemptionReason,
        )
        // BT-121 stays untouched — still the plain machine-readable code.
        assertEquals("VATEX-EU-AE", product.taxExemptionReasonCode)
    }

    @Test
    fun `IN-6 - an English document with a blank master-data text carries the English standard text alone on the wire`() {
        val model = baseModel().copy(documentLanguage = "en", items = listOf(aeItemModel(null)))
        val product = MustangTransactionBuilder(documentStore = null).build(model).getZFItems().single().getProduct()

        assertEquals("Reverse charge: the recipient is liable for VAT (Art. 196 VAT Directive).", product.taxExemptionReason)
    }

    @Test
    fun `IN-6 - a standard-rate line carries no exemption reason at all on the wire, in either language`() {
        val deModel = baseModel().copy(documentLanguage = "de", items = listOf(baseItemModel()))
        val enModel = baseModel().copy(documentLanguage = "en", items = listOf(baseItemModel()))

        assertNull(MustangTransactionBuilder(documentStore = null).build(deModel).getZFItems().single().getProduct().taxExemptionReason)
        assertNull(MustangTransactionBuilder(documentStore = null).build(enModel).getZFItems().single().getProduct().taxExemptionReason)
    }
}
