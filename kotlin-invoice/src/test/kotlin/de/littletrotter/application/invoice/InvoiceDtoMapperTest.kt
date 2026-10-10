package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.domain.masterdata.LegalRegistrationIdentifier
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.InvoiceDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * Z4 — the `Invoice` → [InvoiceDto] mapping tests, moved off `DocumentJobPublisherTest` to target
 * [InvoiceDtoMapper] directly (no NATS/JetStream fixture needed anymore). `DocumentJobPublisherTest`
 * keeps only the envelope/send-path tests and uses a fake mapper there.
 */
class InvoiceDtoMapperTest {

    private val jsonMapper = jacksonObjectMapper()

    @Test
    fun `payload maps sender invoice billing and recipient fallback billing with electronic addresses`() {
        val sender = SenderProfile().apply {
            name = "Seller GmbH"
            email = Email.ofNullable("seller@example.org")
            setBillingAddress(Address.forSender(this, Address.AddressType.BILLING, "Senderweg 1", "50667", "Koeln", Country.DE, true))
        }
        val recipient = Recipient().apply {
            name = "Buyer AG"
            customerNumber = CustomerNumber("KD-1")
            email = Email.ofNullable("buyer@example.org")
            addDeliveryAddress(Address.forRecipient(this, Address.AddressType.DELIVERY, "Lieferweg 9", "10115", "Berlin", Country.DE, true))
        }
        val invoice = Invoice().apply {
            id = 123L
            senderProfile = sender
            invoiceNote = "Footer note"
            this.recipient = recipient
            // invoice.billingAddress must take precedence for sender mapping
            billingAddress = Address.forSender(sender, Address.AddressType.BILLING, "Rechnungsweg 7", "80331", "Muenchen", Country.DE, true)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("seller@example.org", dto.senderProfile?.email)
        assertEquals("Rechnungsweg 7", dto.senderProfile?.street)
        assertEquals("buyer@example.org", dto.recipient?.email)
        // recipient falls back to delivery when billing is absent
        assertEquals("Lieferweg 9", dto.recipient?.street)
    }

    @Test
    fun `mapToDto includes EN16931 fields in invoice payload`() {
        val sender = SenderProfile().apply {
            name = "Seller GmbH"
            email = Email.ofNullable("seller@example.org")
            taxNumber = TaxNumber("1234567890")
            globalId = "4012345678901"
            setBillingAddress(Address.forSender(this, Address.AddressType.BILLING, "Senderweg 1", "50667", "Koeln", Country.DE, true))
        }
        val recipient = Recipient().apply {
            name = "Buyer AG"
            customerNumber = CustomerNumber("KD-1")
            email = Email.ofNullable("buyer@example.org")
            globalId = "4012345678902"
            addDeliveryAddress(Address.forRecipient(this, Address.AddressType.DELIVERY, "Lieferweg 9", "10115", "Berlin", Country.DE, true))
        }
        val item = InvoiceItem().apply {
            positionId = "1"
            description = "Test position"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100.00"))
            // unit defaults to HOUR, whose derived unitCode (Task F) is "HUR" — matches the assertion below.
            productClassificationScheme = "UNSPSC"
            productClassificationCode = "12345678"
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal.ZERO) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100.00"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal("19.00"))
            grossAmount = de.littletrotter.shared.Money(BigDecimal("119.00"))
        }
        val invoice = Invoice().apply {
            id = 123L
            senderProfile = sender
            this.recipient = recipient
            references.buyerReference = "PO-2026-0001"
            references.orderReference = "ANG-2026-0007"
            references.orderReferenceDate = java.time.LocalDate.of(2026, 5, 20)
            currency = de.littletrotter.shared.Currency.CHF
            languageId = "de"
            documentStatusReasonCode = "01"
            itemList.asMutableForTest().add(item)
            totals.netAmount = de.littletrotter.shared.Money(java.math.BigDecimal("100.00"))
            totals.vatAmount = de.littletrotter.shared.Money(java.math.BigDecimal("19.00"))
            totals.grossAmount = de.littletrotter.shared.Money(java.math.BigDecimal("119.00"))
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("PO-2026-0001", dto.references.buyerReference)
        assertEquals("ANG-2026-0007", dto.references.orderReference)
        assertEquals("2026-05-20", dto.references.orderReferenceDate)
        assertEquals(de.littletrotter.shared.Currency.CHF, dto.totals.currency)
        assertEquals("de", dto.meta.languageId)
        assertEquals("01", dto.meta.documentStatusReasonCode)
        assertEquals("4012345678901", dto.senderProfile?.globalId)
        assertEquals("4012345678902", dto.recipient?.globalId)
        val itemDto = dto.items.first()
        assertEquals("HUR", itemDto.unitCode)
        assertEquals("UNSPSC", itemDto.productClassificationScheme)
        assertEquals("12345678", itemDto.productClassificationCode)
    }

    /**
     * Round-trip proof for AR2: the mapper output serializes and deserializes back into
     * [InvoiceDto] — the exact type kotlin-xpdf reads — with every field intact. Guards against a
     * future change reintroducing an untyped Map/Any payload where a renamed field would silently
     * arrive at the worker as a default instead of failing to compile.
     */
    @Test
    fun `mapper output round-trips through InvoiceDto losslessly`() {
        val sender = SenderProfile().apply {
            name = "Seller GmbH"
            email = Email.ofNullable("seller@example.org")
            phone = "+49 221 1234567"
            vatId = VatId("DE123456789")
            iban = de.littletrotter.domain.value.Iban.ofNullable("DE02120300000000202051")
            bic = Bic("BYLADEM1001")
            taxNumber = TaxNumber("12/345/67890")
            globalId = "4012345678901"
            bankName = "Sparkasse Koeln"
            managingDirector = "Erika Musterfrau"
            courtOfJurisdiction = "AG Koeln"
            commercialRegisterNumber = "HRB 12345"
            // footerNote on the wire is the resolved invoiceNote for the document
            // language; this invoice's documentLanguage defaults to "de", so a German entry
            // reproduces the exact wire value the old plain-string field used to.
            setInvoiceNote(de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }, "Footer note")
            setBillingAddress(Address.forSender(this, Address.AddressType.BILLING, "Senderweg 1", "50667", "Koeln", Country.DE, true))
        }
        val recipient = Recipient().apply {
            name = "Buyer AG"
            customerNumber = CustomerNumber("KD-1")
            email = Email.ofNullable("buyer@example.org")
            phone = "+49 30 7654321"
            vatId = VatId("DE987654321")
            globalId = "4012345678902"
            addDeliveryAddress(Address.forRecipient(this, Address.AddressType.DELIVERY, "Lieferweg 9", "10115", "Berlin", Country.DE, true))
        }
        val item = InvoiceItem().apply {
            positionId = "1"
            description = "Consulting"
            quantity = BigDecimal("2.5")
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100.00"))
            productClassificationScheme = "UNSPSC"
            productClassificationCode = "12345678"
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            taxRateSnapshot = TaxRatePercent(BigDecimal("19.00"))
            netAmount = de.littletrotter.shared.Money(BigDecimal("250.00"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal("47.50"))
            grossAmount = de.littletrotter.shared.Money(BigDecimal("297.50"))
        }
        val invoice = Invoice().apply {
            id = 321L
            senderProfile = sender
            invoiceNote = "Footer note"
            this.recipient = recipient
            references.buyerReference = "PO-2026-0002"
            references.orderReference = "ANG-2026-0009"
            references.orderReferenceDate = java.time.LocalDate.of(2026, 6, 1)
            currency = de.littletrotter.shared.Currency.GBP
            languageId = "en"
            documentStatusReasonCode = "02"
            itemList.asMutableForTest().add(item)
            totals.netAmount = de.littletrotter.shared.Money(BigDecimal("250.00"))
            totals.vatAmount = de.littletrotter.shared.Money(BigDecimal("47.50"))
            totals.grossAmount = de.littletrotter.shared.Money(BigDecimal("297.50"))
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)
        val roundTripped: InvoiceDto = jsonMapper.readValue(jsonMapper.writeValueAsString(dto))

        assertEquals(dto, roundTripped)
        assertEquals(321L, roundTripped.id)
        assertEquals("PO-2026-0002", roundTripped.references.buyerReference)
        assertEquals("ANG-2026-0009", roundTripped.references.orderReference)
        assertEquals("2026-06-01", roundTripped.references.orderReferenceDate)
        assertEquals(de.littletrotter.shared.Currency.GBP, roundTripped.totals.currency)
        assertEquals("en", roundTripped.meta.languageId)
        assertEquals("02", roundTripped.meta.documentStatusReasonCode)
        assertEquals(0, BigDecimal("250.00").compareTo(roundTripped.totals.netAmount.amount))
        assertEquals(0, BigDecimal("47.50").compareTo(roundTripped.totals.vatAmount.amount))
        assertEquals(0, BigDecimal("297.50").compareTo(roundTripped.totals.grossAmount.amount))

        val sp = requireNotNull(roundTripped.senderProfile)
        assertEquals("Seller GmbH", sp.name)
        assertEquals("Senderweg 1", sp.street)
        assertEquals("50667", sp.postalCode)
        assertEquals("Koeln", sp.city)
        // Deliberately null for Germany (DIN-5008 domestic mail convention), not a bug;
        // see the KDoc on PartyDto.country.
        assertNull(sp.country)
        assertEquals("seller@example.org", sp.email)
        assertEquals("+49 221 1234567", sp.phone)
        assertEquals("DE123456789", sp.vatId)
        assertEquals("DE02120300000000202051", sp.iban)
        assertEquals("BYLADEM1001", sp.bic)
        assertEquals("12/345/67890", sp.taxNumber)
        assertEquals("4012345678901", sp.globalId)
        assertEquals("Sparkasse Koeln", sp.bankName)
        assertEquals("Erika Musterfrau", sp.managingDirector)
        assertEquals("AG Koeln", sp.courtOfJurisdiction)
        assertEquals("HRB 12345", sp.commercialRegisterNumber)
        assertEquals("Footer note", sp.footerNote)

        val rc = requireNotNull(roundTripped.recipient)
        assertEquals("Buyer AG", rc.name)
        assertEquals("Lieferweg 9", rc.street)
        assertEquals("10115", rc.postalCode)
        assertEquals("Berlin", rc.city)
        assertEquals("buyer@example.org", rc.email)
        assertEquals("+49 30 7654321", rc.phone)
        assertEquals("DE987654321", rc.vatId)
        assertEquals("4012345678902", rc.globalId)
        assertEquals("KD-1", rc.customerNumber)

        assertEquals(1, roundTripped.items.size)
        val itemDto = roundTripped.items.first()
        assertEquals("1", itemDto.positionId)
        assertEquals("Consulting", itemDto.description)
        assertEquals(0, BigDecimal("2.5").compareTo(itemDto.quantity))
        assertEquals(0, BigDecimal("100.00").compareTo(itemDto.unitPriceNet.amount))
        assertEquals(0, BigDecimal("19.00").compareTo(itemDto.taxRatePercent))
        assertEquals("UNSPSC", itemDto.productClassificationScheme)
        assertEquals("12345678", itemDto.productClassificationCode)
        assertEquals(0, BigDecimal("250.00").compareTo(itemDto.netAmount.amount))
        assertEquals(0, BigDecimal("47.50").compareTo(itemDto.vatAmount.amount))
    }

    @Test
    fun `R6-T11 - a non-German sender country is populated, not nulled`() {
        val sender = SenderProfile().apply {
            name = "French Seller SARL"
            email = Email.ofNullable("seller@example.fr")
            taxNumber = TaxNumber("1234567890")
            setBillingAddress(Address.forSender(this, Address.AddressType.BILLING, "Rue de la Paix 1", "75002", "Paris", Country.FR, true))
        }
        val recipient = Recipient().apply { name = "Buyer AG"; customerNumber = CustomerNumber("KD-1") }
        val invoice = Invoice().apply {
            id = 500L
            senderProfile = sender
            this.recipient = recipient
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("FR", dto.senderProfile?.country)
    }

    // reverseCharge on wire derived from item categories

    private fun itemWithCategory(category: String, rate: String): InvoiceItem = InvoiceItem().apply {
        positionId = "1"; description = "Beratung"
        quantity = BigDecimal("1")
        unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
        netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
        vatAmount = de.littletrotter.shared.Money(BigDecimal.ZERO)
        grossAmount = de.littletrotter.shared.Money(BigDecimal("100"))
        applyTaxRule(TaxRate().apply {
            code = "TEST"; this.category = category; this.rate = TaxRatePercent(BigDecimal(rate))
            if (category == "AE") exemptionReasonCode = "VATEX-EU-AE"
        })
    }

    @Test
    fun `R6-T09 - reverseCharge on the wire matches an AE item even when the legacy boolean says false`() {
        val invoice = Invoice().apply {
            id = 501L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            reverseCharge = false
            itemList.asMutableForTest().add(itemWithCategory("AE", "0.00"))
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertTrue(dto.totals.reverseCharge)
    }

    @Test
    fun `R6-T10 - a legacy boolean disagreeing with the category loses -- the category wins`() {
        val invoice = Invoice().apply {
            id = 502L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            reverseCharge = true // legacy field says reverse charge...
            itemList.asMutableForTest().add(itemWithCategory("S", "19.00")) // ...but the snapshot says regular S
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals(false, dto.totals.reverseCharge)
    }

    @Test
    fun `a draft item without a snapshot carries its referenced tax rate`() {
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = BigDecimal("1")
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("7.00")) }
        }
        val invoice = Invoice().apply {
            id = 504L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(item)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals(0, BigDecimal("7.00").compareTo(dto.items[0].taxRatePercent))
    }

    @Test
    fun `R8-T17 - changing the reference tax rule after finalization does not change the mapped DTO`() {
        val rule = TaxRate().apply { code = "DE_STANDARD_19"; category = "S"; rate = TaxRatePercent(BigDecimal("19.00")) }
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = BigDecimal("1")
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal("19"))
            grossAmount = de.littletrotter.shared.Money(BigDecimal("119"))
            applyTaxRule(rule)
        }
        val invoice = Invoice().apply {
            id = 503L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(item)
        }

        // The reference rule changes after the invoice's snapshot was taken (e.g. a later rate
        // correction) — the already-finalized item must not follow it.
        rule.rate = TaxRatePercent(BigDecimal("7.00"))
        rule.category = "AE"

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("S", dto.items[0].taxCategory, "the snapshot category must stay frozen")
        assertEquals(0, BigDecimal("19.00").compareTo(dto.items[0].taxRatePercent), "the snapshot rate must stay frozen")
    }

    // ── V1 — BT-10 derived on the read path (kotlin-invoice's Invoice model) ──────────────
    // The print/export job runs for any invoice status, so the SENT-only gate in
    // InvoiceLifecycleService never covers this path — the mapper itself must not put a blank
    // BT-10 on the wire, or kotlin-xpdf writes an empty <ram:BuyerReference/> (R008).

    private fun invoiceFor(buyerReference: String?, routingId: String?): Invoice = Invoice().apply {
        id = 900L
        senderProfile = SenderProfile().apply {
            name = "Seller GmbH"
            email = Email.ofNullable("seller@example.org")
            setBillingAddress(Address.forSender(this, Address.AddressType.BILLING, "Senderweg 1", "50667", "Koeln", Country.DE, true))
        }
        recipient = Recipient().apply {
            name = "Buyer AG"
            customerNumber = CustomerNumber("KD-1")
            email = Email.ofNullable("buyer@example.org")
            this.routingId = routingId
        }
        this.references.buyerReference = buyerReference
        totals.netAmount = de.littletrotter.shared.Money.ZERO
        totals.vatAmount = de.littletrotter.shared.Money.ZERO
        totals.grossAmount = de.littletrotter.shared.Money.ZERO
    }

    @Test
    fun `V1-T03 - a blank buyerReference falls back to the recipient's Leitweg-ID`() {
        val dto = InvoiceDtoMapper().mapToDto(invoiceFor(null, "04011000-1234512345-06"))
        assertEquals("04011000-1234512345-06", dto.references.buyerReference)
    }

    @Test
    fun `V1-T04 - with neither buyerReference nor Leitweg-ID the wire value stays null, never an empty string`() {
        val dto = InvoiceDtoMapper().mapToDto(invoiceFor("   ", null))
        assertNull(dto.references.buyerReference)
    }

    @Test
    fun `V1-T05 - an explicit buyerReference wins over a differing Leitweg-ID`() {
        val dto = InvoiceDtoMapper().mapToDto(invoiceFor("PO-2026-4711", "04011000-1234512345-06"))
        assertEquals("PO-2026-4711", dto.references.buyerReference)
    }

    // ── BT-13 — the same read path, deliberately *without* the Leitweg-ID fallback ─────────

    @Test
    fun `BT-13 - a captured purchase order reference reaches the wire in its own field`() {
        val invoice = invoiceFor("04011000-1234512345-06", "04011000-1234512345-06")
            .apply { references.purchaseOrderReference = "BST-2026-0042" }
        val dto = InvoiceDtoMapper().mapToDto(invoice)
        assertEquals("BST-2026-0042", dto.references.purchaseOrderReference)
        assertEquals("04011000-1234512345-06", dto.references.buyerReference, "BT-10 remains in its own field")
    }

    // BT-13: same read path, deliberately without Leitweg-ID fallback

    /**
     * BT-10 fallback (Leitweg-ID) is wrong here: a Leitweg-ID is routing address, not order
     * number, and BT-13 is optional in EN 16931. Nothing captured means nothing on wire—this
     * defect is why BT-10 and BT-13 are separate.
     */
    @Test
    fun `BT-13 - without a captured value the wire stays null, never the Leitweg-ID`() {
        assertNull(InvoiceDtoMapper().mapToDto(invoiceFor(null, "04011000-1234512345-06")).references.purchaseOrderReference)
        assertNull(
            InvoiceDtoMapper().mapToDto(invoiceFor("PO-2026-4711", null).apply { references.purchaseOrderReference = "  " })
                .references.purchaseOrderReference,
        )
    }

    // BT-11 (project reference) to wire

    /**
     * InvoiceDtoMapper left references.projectNumber unmapped entirely—field reached PDF context
     * but never InvoiceDto, so XML export never saw it. This fix ensures the DTO boundary that
     * kotlin-xpdf deserializes against includes it.
     */
    @Test
    fun `BT-11 - a captured project reference reaches the wire`() {
        val invoice = invoiceFor("04011000-1234512345-06", null).apply { references.projectNumber = "PRJ-2026-0099" }
        val dto = InvoiceDtoMapper().mapToDto(invoice)
        assertEquals("PRJ-2026-0099", dto.references.projectNumber)
    }

    @Test
    fun `BT-11 - a blank project reference reaches the wire as null, never as an empty string`() {
        val invoice = invoiceFor("04011000-1234512345-06", null).apply { references.projectNumber = "   " }
        val dto = InvoiceDtoMapper().mapToDto(invoice)
        assertNull(dto.references.projectNumber)
    }

    // ── the document publishing pipeline stays out of scope of the mapper; covered on DocumentJobPublisherTest ──

    @Test
    fun `Anbieter- und Empfaenger-Kennungen sowie die elektronische Adresse werden auf den Draht uebertragen`() {
        val sender = SenderProfile().apply {
            name = "Seller GmbH"
            sellerIdentifier = PartyIdentifier.of("DE123456789", "9930")
            electronicAddress = ElectronicAddress.of("DE123456789", "9930")
            legalRegistrationSchemeId = "0002"
            commercialRegisterNumber = "HRB 12345"
        }
        val recipient = Recipient().apply {
            name = "Buyer AG"
            customerNumber = CustomerNumber("KD-1")
            buyerIdentifier = PartyIdentifier.of("DE987654321", "9930")
            legalRegistrationIdentifier = LegalRegistrationIdentifier.of("HRB 99999", "0002")
            electronicAddress = ElectronicAddress.of("DE987654321", "9930")
            customerType = CustomerType.BUSINESS
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
        }
        val invoice = Invoice().apply {
            id = 980L
            senderProfile = sender
            this.recipient = recipient
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("DE123456789", dto.senderProfile?.identifier?.value)
        assertEquals("9930", dto.senderProfile?.identifier?.schemeId)
        assertEquals("HRB 12345", dto.senderProfile?.legalRegistration?.value)
        assertEquals("0002", dto.senderProfile?.legalRegistration?.schemeId)
        assertEquals("9930", dto.senderProfile?.electronicAddress?.schemeId)
        assertEquals("DE987654321", dto.recipient?.identifier?.value)
        assertEquals("HRB 99999", dto.recipient?.legalRegistration?.value)
        assertEquals("9930", dto.recipient?.electronicAddress?.schemeId)
        assertEquals("BUSINESS", dto.recipient?.customerType)
        assertEquals("VERIFIED", dto.recipient?.vatIdVerificationStatus)
    }

    @Test
    fun `legalRegistration des Anbieters bleibt leer wenn nur eines der beiden Felder gesetzt ist`() {
        // legalRegistrationSchemeId and commercialRegisterNumber are separate fields (unlike for
        // Recipient) -- without the scheme field no SchemedIdentifierDto may be created, even
        // when the register number itself is present.
        val sender = SenderProfile().apply {
            name = "Seller GmbH"
            commercialRegisterNumber = "HRB 12345"
            legalRegistrationSchemeId = null
        }
        val invoice = Invoice().apply {
            id = 981L
            senderProfile = sender
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.senderProfile?.legalRegistration, "ohne Schema-Id entsteht kein SchemedIdentifierDto, auch wenn die Nummer da ist")
    }

    @Test
    fun `legalRegistration of the sender stays empty when only the scheme id is set`() {
        val sender = SenderProfile().apply {
            name = "Seller GmbH"
            legalRegistrationSchemeId = "0002"
            commercialRegisterNumber = null
        }
        val invoice = Invoice().apply {
            id = 1208L
            senderProfile = sender
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.senderProfile?.legalRegistration, "ohne Registernummer entsteht kein SchemedIdentifierDto, auch wenn die Schema-Id da ist")
    }

    // ── Firmierung: Handelsregister-Unterdrueckung bei Einzelunternehmer + HWK ──

    private fun soleProprietorWithHrSender(): SenderProfile = SenderProfile().apply {
        name = "Malerbetrieb Solo"
        legalForm = LegalForm.SOLE_PROPRIETOR
        craftsChamberRegistered = true
        registerCourt = "Amtsgericht Trier"
        commercialRegisterNumber = "HRB 12345"
        legalRegistrationSchemeId = "0002"
    }

    private fun invoiceWith(sender: SenderProfile): Invoice = Invoice().apply {
        id = 4711L
        senderProfile = sender
        recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
    }

    @Test
    fun `ARC-20 - sole proprietor with crafts chamber registration omits register court and number from the DTO`() {
        val dto = InvoiceDtoMapper().mapToDto(invoiceWith(soleProprietorWithHrSender()))

        assertNull(dto.senderProfile?.registerCourt, "PDF-Fusszeile darf kein Registergericht mehr zeigen")
        assertNull(dto.senderProfile?.commercialRegisterNumber, "PDF-Fusszeile darf keine Handelsregisternummer mehr zeigen")
        assertNull(dto.senderProfile?.legalRegistration, "E-Rechnung (BT-30) darf keine Registerangabe mehr tragen, auch wenn das Schema gesetzt ist")
    }

    @Test
    fun `ARC-20 - sole proprietor without crafts chamber registration keeps showing the commercial register`() {
        val sender = soleProprietorWithHrSender().apply { craftsChamberRegistered = false }

        val dto = InvoiceDtoMapper().mapToDto(invoiceWith(sender))

        assertEquals("Amtsgericht Trier", dto.senderProfile?.registerCourt)
        assertEquals("HRB 12345", dto.senderProfile?.commercialRegisterNumber)
        assertEquals("HRB 12345", dto.senderProfile?.legalRegistration?.value)
    }

    @Test
    fun `ARC-20 - GmbH with crafts chamber registration keeps showing the commercial register`() {
        // The suppression rule names one specific combination (SOLE_PROPRIETOR + HWK); any other
        // legal form is unaffected even with the HWK flag set.
        val sender = soleProprietorWithHrSender().apply { legalForm = LegalForm.GMBH }

        val dto = InvoiceDtoMapper().mapToDto(invoiceWith(sender))

        assertEquals("Amtsgericht Trier", dto.senderProfile?.registerCourt)
        assertEquals("HRB 12345", dto.senderProfile?.commercialRegisterNumber)
    }

    @Test
    fun `ARC-20 - no legal form recorded keeps showing the commercial register, matching pre-ARC-20 behavior`() {
        val sender = soleProprietorWithHrSender().apply { legalForm = null }

        val dto = InvoiceDtoMapper().mapToDto(invoiceWith(sender))

        assertEquals("Amtsgericht Trier", dto.senderProfile?.registerCourt)
        assertEquals("HRB 12345", dto.senderProfile?.commercialRegisterNumber)
    }

    // ── the recipient's SIRET on the wire ──

    @Test
    fun `AP-1b - ein erfasster SIRET erreicht den Draht`() {
        val recipient = Recipient().apply {
            name = "Buyer SARL"
            customerNumber = CustomerNumber("KD-1")
            siret = "73282932000074"
        }
        val invoice = Invoice().apply {
            id = 990L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("73282932000074", dto.recipient?.siret)
    }

    @Test
    fun `AP-1b - ohne erfassten SIRET bleibt das Feld null statt leer geschrieben zu werden`() {
        val recipient = Recipient().apply {
            name = "Buyer AG"
            customerNumber = CustomerNumber("KD-1")
        }
        val invoice = Invoice().apply {
            id = 991L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.recipient?.siret, "kein erfasster SIRET heisst null auf dem Draht, nie ein leerer String")
    }

    // ── BG-13, a delivery address that differs from the billing address ──

    @Test
    fun `AP-1c - eine von der Rechnungsadresse abweichende Lieferadresse wird ein eigener shipTo`() {
        val recipient = Recipient().apply { name = "Buyer SARL"; customerNumber = CustomerNumber("KD-2") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue de Facturation 1", "75001", "Paris", Country.FR))
        val invoice = Invoice().apply {
            id = 1100L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
            deliveryAddress = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Rue de Livraison 9", "75010", "Paris", Country.FR)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("Buyer SARL", dto.shipTo?.name, "BT-70 -- der Name des Empfaengers, BG-13 hat keinen eigenen")
        assertEquals("Rue de Livraison 9", dto.shipTo?.street)
        assertEquals("75010", dto.shipTo?.postalCode)
        assertEquals("Paris", dto.shipTo?.city)
        assertEquals("FR", dto.shipTo?.country)
    }

    @Test
    fun `AP-1c - eine deutsche Lieferadresse traegt country null, dieselbe Konvention wie PartyDto`() {
        val recipient = Recipient().apply { name = "Buyer GmbH"; customerNumber = CustomerNumber("KD-3") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rechnungsweg 1", "10115", "Berlin", Country.DE))
        val invoice = Invoice().apply {
            id = 1101L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
            deliveryAddress = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Lieferweg 2", "10117", "Berlin", Country.DE)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertTrue(dto.shipTo != null, "die Strasse unterscheidet sich, also muss ein shipTo entstehen")
        assertEquals("Lieferweg 2", dto.shipTo?.street)
        assertNull(dto.shipTo?.country, "Deutschland wird wie bei PartyDto nicht als Laendercode ausgegeben")
    }

    @Test
    fun `AP-1c - eine mit der Rechnungsadresse identische Lieferadresse erzeugt keinen shipTo`() {
        val recipient = Recipient().apply { name = "Buyer AG"; customerNumber = CustomerNumber("KD-4") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Hauptstrasse 1", "80331", "Muenchen", Country.DE))
        val invoice = Invoice().apply {
            id = 1102L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
            // a separate object, but the same fields as the billing address -- the comparison is
            // by business value, not by object identity.
            deliveryAddress = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Hauptstrasse 1", "80331", "Muenchen", Country.DE)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.shipTo, "gleiche Adresse nach Feldern heisst kein shipTo, auch bei zwei verschiedenen Address-Objekten")
    }

    @Test
    fun `AP-1c - eine fehlende Lieferadresse erzeugt keinen shipTo`() {
        val recipient = Recipient().apply { name = "Buyer OHG"; customerNumber = CustomerNumber("KD-5") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Weg 3", "50667", "Koeln", Country.DE))
        val invoice = Invoice().apply {
            id = 1103L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.shipTo)
    }

    @Test
    fun `AP-1c - die als Rechnungsadress-Fallback einspringende Lieferadresse bleibt genau das, kein shipTo`() {
        // recipient has no billing address of its own -- inv.deliveryAddress steps in as the
        // billing-address fallback (existing, unchanged semantics) and must therefore not
        // additionally appear as a shipTo as well.
        val recipient = Recipient().apply { name = "Buyer KG"; customerNumber = CustomerNumber("KD-6") }
        val invoice = Invoice().apply {
            id = 1104L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
            deliveryAddress = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Nur-Lieferadresse 4", "20095", "Hamburg", Country.DE)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.shipTo)
        assertEquals("Nur-Lieferadresse 4", dto.recipient?.street, "die Adresse traegt weiterhin die Rechnungsadresse per Fallback -- das bleibt unveraendert")
    }

    @Test
    fun `AP-1c - eine abweichende eigene Lieferadresse des Empfaengers wird shipTo, auch ohne Lieferadresse an der Rechnung`() {
        val recipient = Recipient().apply { name = "Buyer Sarl"; customerNumber = CustomerNumber("KD-7") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Facture 5", "69001", "Lyon", Country.FR))
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Rue Livraison 6", "69002", "Lyon", Country.FR))
        val invoice = Invoice().apply {
            id = 1105L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("Rue Livraison 6", dto.shipTo?.street)
        assertEquals("FR", dto.shipTo?.country)
    }

    @Test
    fun `AP-1c - ohne abweichende Lieferadresse bleibt shipTo null`() {
        val recipient = Recipient().apply { name = "Buyer ohne Lieferadresse"; customerNumber = CustomerNumber("KD-8") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Weg 9", "01067", "Dresden", Country.DE))
        val invoice = Invoice().apply {
            id = 1106L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.shipTo, "kein separates shipTo, wenn keine abweichende Lieferadresse existiert")
    }

    // ── closing the gap to 100 % line+branch ─────────────────────────────────────

    @Test
    fun `selectedSeller overrides senderProfile when both are set`() {
        val defaultSender = SenderProfile().apply { name = "Standard-Anbieter" }
        val chosenSeller = SenderProfile().apply { name = "Gewaehlter Anbieter" }
        val invoice = Invoice().apply {
            id = 1200L
            senderProfile = defaultSender
            selectedSeller = chosenSeller
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("Gewaehlter Anbieter", dto.senderProfile?.name, "selectedSeller muss senderProfile ueberstimmen")
    }

    @Test
    fun `an unsaved invoice with a receiptRepository wired still falls back to the entity receipts`() {
        // repo != null, but invoice.id == null (not yet persisted) -- the id-gated branch must
        // fall through to the entity's own (empty) collections, not call the repository at all.
        val receiptRepository = mock<ReceiptRepository>()
        val invoice = Invoice().apply {
            id = null
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)

        assertTrue(dto.receipts.isEmpty())
        verify(receiptRepository, never()).findForInvoice(any(), any(), any())
        verify(receiptRepository, never()).findItemReceiptsForInvoice(any())
    }

    @Test
    fun `an invoice-level receipts read failure without a repository falls back to an empty list instead of failing the mapping`() {
        val poisonReceipts: MutableList<Receipt> = object : java.util.ArrayList<Receipt>() {
            override fun iterator(): MutableIterator<Receipt> = throw RuntimeException("unfetched lazy collection")
        }
        val invoice = Invoice().apply {
            id = 1201L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            receipts = poisonReceipts
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertTrue(dto.receipts.isEmpty(), "eine nicht lesbare Belegliste darf das Mapping nicht scheitern lassen")
    }

    @Test
    fun `an item-level receipts read failure without a repository falls back to an empty list instead of failing the mapping`() {
        val poisonReceipts: MutableList<Receipt> = object : java.util.ArrayList<Receipt>() {
            override fun iterator(): MutableIterator<Receipt> = throw RuntimeException("unfetched lazy collection")
        }
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal("19"))
            grossAmount = de.littletrotter.shared.Money(BigDecimal("119"))
            receipts = poisonReceipts
        }
        val invoice = Invoice().apply {
            id = 1202L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(item)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertTrue(dto.items.single().receipts.isEmpty(), "eine nicht lesbare Positions-Belegliste darf das Mapping nicht scheitern lassen")
    }

    @Test
    fun `a tax rate resolution failure is rethrown with the invoice number and position named in the message`() {
        val item = InvoiceItem().apply {
            positionId = "9"; description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal.ZERO)
            grossAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            // neither taxRateSnapshot nor taxRateRef is set -> effectiveTaxRate() throws
        }
        val invoice = Invoice().apply {
            id = 1203L
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-01-01203")
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(item)
        }

        val ex = org.junit.jupiter.api.Assertions.assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            InvoiceDtoMapper().mapToDto(invoice)
        }
        assertTrue(ex.message?.contains("RE-2026-01-01203") == true, "die Rechnungsnummer muss in der Meldung stehen, war: ${ex.message}")
        assertTrue(ex.message?.contains("position 9") == true, "die Positionsnummer muss in der Meldung stehen, war: ${ex.message}")
    }

    @Test
    fun `offerDate is carried on the wire when set`() {
        val invoice = Invoice().apply {
            id = 1204L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            references.offerReferenceDate = java.time.LocalDate.of(2026, 3, 1)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("2026-03-01", dto.references.offerDate)
    }

    @Test
    fun `unitLabel folgt der eingefrorenen documentLanguage und nicht dem UI-Sprachfeld`() {
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = BigDecimal("2")
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("200"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal.ZERO)
            grossAmount = de.littletrotter.shared.Money(BigDecimal("200"))
        }
        val invoice = Invoice().apply {
            id = 960L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "en"
            itemList.asMutableForTest().add(item)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("hours", dto.items.single().unitLabel)
    }

    @Test
    fun `unitLabel is singular in English for a quantity of exactly one`() {
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal.ZERO)
            grossAmount = de.littletrotter.shared.Money(BigDecimal("100"))
        }
        val invoice = Invoice().apply {
            id = 1205L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "en"
            itemList.asMutableForTest().add(item)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("hour", dto.items.single().unitLabel, "genau eine Einheit muss die Singularform verwenden")
    }

    // ── Kredit-/Sammelbeleg-Felder: originalInvoiceNumber ────────────────────────

    @Test
    fun `originalInvoiceNumber wird getrimmt und ein reiner Leerstring wird zu null`() {
        val invoice = Invoice().apply {
            id = 970L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            originalInvoiceNumber = "   "
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.references.originalInvoiceNumber, "ein reiner Leerstring darf nicht als echter Verweis auf dem Draht landen")
    }

    @Test
    fun `originalInvoiceNumber wird unveraendert uebernommen wenn gesetzt`() {
        val invoice = Invoice().apply {
            id = 971L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            originalInvoiceNumber = "RE-2026-0099"
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("RE-2026-0099", dto.references.originalInvoiceNumber)
    }

    @Test
    fun `itemDescription is trimmed and a pure whitespace value becomes null`() {
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal.ZERO)
            grossAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            itemDescription = "   "
        }
        val invoice = Invoice().apply {
            id = 1206L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(item)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.items.single().itemDescription)
    }

    @Test
    fun `itemDescription is carried unchanged when set to a real value`() {
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal.ZERO)
            grossAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            itemDescription = "Zusatztext BT-154"
        }
        val invoice = Invoice().apply {
            id = 1207L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(item)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("Zusatztext BT-154", dto.items.single().itemDescription)
    }

    // ── article number on every invoice ────────────────

    private fun itemFor(article: de.littletrotter.domain.masterdata.Article? = null): InvoiceItem = InvoiceItem().apply {
        positionId = "1"; description = "Beratung"
        quantity = BigDecimal.ONE
        unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
        taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
        netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
        vatAmount = de.littletrotter.shared.Money(BigDecimal.ZERO)
        grossAmount = de.littletrotter.shared.Money(BigDecimal("100"))
        articleRef = article
    }

    @Test
    fun `articleNumber on the wire comes from the linked article's own system number`() {
        val article = de.littletrotter.domain.masterdata.Article().apply { id = 5L; name = "Kabelbinder"; articleNumber = ArticleNumber("ART-1") }
        val invoice = Invoice().apply {
            id = 1300L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { id = 9L; name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(itemFor(article))
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("ART-1", dto.items.single().articleNumber)
    }

    @Test
    fun `articleNumber is null for a free-text position with no linked article`() {
        val invoice = Invoice().apply {
            id = 1301L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(itemFor(article = null))
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.items.single().articleNumber)
    }

    @Test
    fun `buyerArticleDescription resolves the recipient-specific mapping when an ArticleManagementService is wired`() {
        val article = de.littletrotter.domain.masterdata.Article().apply { id = 5L; name = "Kabelbinder"; articleNumber = ArticleNumber("ART-1") }
        val recipient = Recipient().apply { id = 9L; name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        val mapping = de.littletrotter.domain.masterdata.ArticleRecipientRef.of(article, recipient, "K-42", "Kabelbinder 200mm")
        val articleManagementService = mock<de.littletrotter.application.masterdata.ArticleManagementService>()
        whenever(articleManagementService.findRecipientRef(5L, 9L)).thenReturn(mapping)
        val invoice = Invoice().apply {
            id = 1302L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
            itemList.asMutableForTest().add(itemFor(article))
        }

        val dto = InvoiceDtoMapper(articleManagementService = articleManagementService).mapToDto(invoice)

        assertEquals("Kabelbinder 200mm", dto.items.single().buyerArticleDescription)
    }

    @Test
    fun `buyerArticleDescription is null without an ArticleManagementService wired (plain unit test convenience)`() {
        val article = de.littletrotter.domain.masterdata.Article().apply { id = 5L; name = "Kabelbinder"; articleNumber = ArticleNumber("ART-1") }
        val invoice = Invoice().apply {
            id = 1303L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { id = 9L; name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(itemFor(article))
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.items.single().buyerArticleDescription)
    }

    @Test
    fun `buyerArticleDescription is null when the invoice carries no recipient, even with an ArticleManagementService wired`() {
        val article = de.littletrotter.domain.masterdata.Article().apply { id = 5L; name = "Kabelbinder"; articleNumber = ArticleNumber("ART-1") }
        val articleManagementService = mock<de.littletrotter.application.masterdata.ArticleManagementService>()
        val invoice = Invoice().apply {
            id = 1304L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = null
            itemList.asMutableForTest().add(itemFor(article))
        }

        val dto = InvoiceDtoMapper(articleManagementService = articleManagementService).mapToDto(invoice)

        assertNull(dto.items.single().buyerArticleDescription)
    }

    @Test
    fun `buyerArticleDescription is null when no recipient-specific mapping exists for the pair`() {
        val article = de.littletrotter.domain.masterdata.Article().apply { id = 5L; name = "Kabelbinder"; articleNumber = ArticleNumber("ART-1") }
        val recipient = Recipient().apply { id = 9L; name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        val articleManagementService = mock<de.littletrotter.application.masterdata.ArticleManagementService>()
        whenever(articleManagementService.findRecipientRef(5L, 9L)).thenReturn(null)
        val invoice = Invoice().apply {
            id = 1305L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
            itemList.asMutableForTest().add(itemFor(article))
        }

        val dto = InvoiceDtoMapper(articleManagementService = articleManagementService).mapToDto(invoice)

        assertNull(dto.items.single().buyerArticleDescription)
    }

    @Test
    fun `additional VAT registrations of the sender are carried on the wire`() {
        val sender = SenderProfile().apply {
            name = "Seller GmbH"
            updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.FR, VatId("FR94019655698"))), today = FIXED_TODAY)
        }
        val invoice = Invoice().apply {
            id = 1209L
            senderProfile = sender
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals(1, dto.senderProfile?.additionalVatRegistrations?.size)
        assertEquals("FR", dto.senderProfile?.additionalVatRegistrations?.first()?.country)
        assertEquals("FR94019655698", dto.senderProfile?.additionalVatRegistrations?.first()?.vatId)
    }

    @Test
    fun `buyerReference stays null when the invoice has no recipient at all`() {
        val invoice = Invoice().apply {
            id = 1210L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = null
            references.buyerReference = null
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.references.buyerReference, "ohne Empfaenger gibt es auch keine Leitweg-ID zum Zurueckfallen")
    }

    // ── buildShipTo — each field of the same-place comparison needs its own false branch,
    // not just "everything differs" (short-circuits at street) or "everything matches" ──────────

    private fun shipToInvoice(
        billingStreet: String, billingPostal: String, billingCity: String, billingCountry: Country,
        deliveryStreet: String, deliveryPostal: String, deliveryCity: String, deliveryCountry: Country,
    ): Invoice {
        val recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-9") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, billingStreet, billingPostal, billingCity, billingCountry))
        return Invoice().apply {
            id = 1211L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
            deliveryAddress = Address.forRecipient(recipient, Address.AddressType.DELIVERY, deliveryStreet, deliveryPostal, deliveryCity, deliveryCountry)
        }
    }

    @Test
    fun `shipTo appears when only the postal code differs from the billing address`() {
        val invoice = shipToInvoice(
            "Weg 1", "10115", "Berlin", Country.DE,
            "Weg 1", "10117", "Berlin", Country.DE,
        )
        val dto = InvoiceDtoMapper().mapToDto(invoice)
        assertTrue(dto.shipTo != null, "eine abweichende Postleitzahl allein muss bereits einen shipTo erzeugen")
    }

    @Test
    fun `shipTo appears when only the city differs from the billing address`() {
        val invoice = shipToInvoice(
            "Weg 1", "10115", "Berlin", Country.DE,
            "Weg 1", "10115", "Potsdam", Country.DE,
        )
        val dto = InvoiceDtoMapper().mapToDto(invoice)
        assertTrue(dto.shipTo != null, "eine abweichende Stadt allein muss bereits einen shipTo erzeugen")
    }

    @Test
    fun `shipTo appears when only the country differs from the billing address`() {
        val invoice = shipToInvoice(
            "Weg 1", "10115", "Berlin", Country.DE,
            "Weg 1", "10115", "Berlin", Country.FR,
        )
        val dto = InvoiceDtoMapper().mapToDto(invoice)
        assertTrue(dto.shipTo != null, "ein abweichendes Land allein muss bereits einen shipTo erzeugen")
    }

    /**
     * `PartyDtoMapper.buildShipTo`'s `billing != null` check can never see `billing == null`
     * together with a non-null `delivery` through mapToDto's own call site: both parameters are
     * derived from the same null-source chain (`recipient.billingAddress`/`inv.deliveryAddress`/
     * `recipient.deliveryAddress`) such that `billing == null` implies `delivery == null` too --
     * and `buildShipTo` already returns `null` early in that case, before `samePlace` is ever
     * computed. Calling the public function directly proves its own null-safety
     * independent of that caller-side coincidence, rather than leaving it silently unproven.
     */
    @Test
    fun `buildShipTo treats a missing billing address as automatically different from any delivery address`() {
        val recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        val delivery = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Weg 1", "10115", "Berlin", Country.DE)

        val result = PartyDtoMapper.buildShipTo(recipient, null, delivery)

        assertTrue(result != null, "no billing address at all must count as different from any delivery address")
    }

    // ── buyerReference — the routingId fallback's own blank-value branch ─────────────────────

    @Test
    fun `V1 - a recipient with a blank routingId does not fall back to it either`() {
        val recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1"); routingId = "   " }
        val invoice = Invoice().apply {
            id = 1212L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
            references.buyerReference = null
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.references.buyerReference, "eine reine Leerraum-Leitweg-ID darf ebenso wenig als BT-10 durchgehen wie ein leerer buyerReference")
    }

    // ── receiptRepository wired — receipts come from the pool, not from the entity ──

    @Test
    fun `mapToDto liest Belege aus dem ReceiptRepository wenn eines verdrahtet ist statt aus der Rechnungs-Entitaet`() {
        val receiptRepository = mock<ReceiptRepository>()
        val poolReceipt = Receipt().apply { fileName = "pool.pdf"; objectKey = "obj/pool" }
        val itemReceipt = Receipt().apply { fileName = "item-pool.pdf"; objectKey = "obj/item-pool" }
        whenever(receiptRepository.findForInvoice(eq(900L), any(), any())).thenReturn(listOf(poolReceipt))
        whenever(receiptRepository.findItemReceiptsForInvoice(900L)).thenReturn(mapOf(1L to listOf(itemReceipt)))

        val item = InvoiceItem().apply {
            id = 1L; positionId = "1"; description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal.ZERO)
            grossAmount = de.littletrotter.shared.Money(BigDecimal("100"))
        }
        val invoice = Invoice().apply {
            id = 900L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(item)
        }

        val dto = InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)

        assertEquals(listOf("pool.pdf"), dto.receipts.map { it.fileName }, "die Belege stammen aus dem Pool-Repository, nicht der Entitaet")
        assertEquals(listOf("item-pool.pdf"), dto.items.single().receipts.map { it.fileName })
    }

    @Test
    fun `an item id absent from the pool receipts map falls back to an empty list, not a lookup failure`() {
        val receiptRepository = mock<ReceiptRepository>()
        val poolReceipt = Receipt().apply { fileName = "pool.pdf"; objectKey = "obj/pool" }
        whenever(receiptRepository.findForInvoice(eq(950L), any(), any())).thenReturn(listOf(poolReceipt))
        // only item 1 has an entry -- item 2 below is absent from the map even though the map itself is non-null
        whenever(receiptRepository.findItemReceiptsForInvoice(950L)).thenReturn(mapOf(1L to listOf(poolReceipt)))

        fun item(id: Long, positionId: String) = InvoiceItem().apply {
            this.id = id; this.positionId = positionId; description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal("19"))
            grossAmount = de.littletrotter.shared.Money(BigDecimal("119"))
        }
        val invoice = Invoice().apply {
            id = 950L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(item(1L, "1"))
            itemList.asMutableForTest().add(item(2L, "2"))
        }

        val dto = InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)

        assertEquals(listOf("pool.pdf"), dto.items.first { it.positionId == "1" }.receipts.map { it.fileName })
        assertTrue(dto.items.first { it.positionId == "2" }.receipts.isEmpty(), "eine im Pool fehlende Position bekommt eine leere Liste, keinen Fehler")
    }

    /**
     * The pool-lookup branch (`id != null && poolReceiptsByItemId !=
     * null`) needs its `id == null` sub-branch exercised too, not just the two pool-membership
     * cases above. A real scenario: a freshly-added position on an otherwise-persisted invoice
     * (invoice.id set, so the repository is queried) that has not itself been saved yet, so its
     * own JPA id is still null — that item must fall back to its own (here: empty, freshly
     * constructed) entity collection rather than attempting a pool lookup keyed by a null id.
     */
    @Test
    fun `a newly-added item with no id yet falls back to its own entity receipts even though the invoice's pool is wired`() {
        val receiptRepository = mock<ReceiptRepository>()
        val poolReceipt = Receipt().apply { fileName = "pool.pdf"; objectKey = "obj/pool" }
        whenever(receiptRepository.findForInvoice(eq(951L), any(), any())).thenReturn(listOf(poolReceipt))
        whenever(receiptRepository.findItemReceiptsForInvoice(951L)).thenReturn(mapOf(1L to listOf(poolReceipt)))

        val unsavedItem = InvoiceItem().apply {
            id = null; positionId = "2"; description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal("19"))
            grossAmount = de.littletrotter.shared.Money(BigDecimal("119"))
        }
        val invoice = Invoice().apply {
            id = 951L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(unsavedItem)
        }

        val dto = InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)

        assertTrue(
            dto.items.single().receipts.isEmpty(),
            "ein noch nicht gespeichertes Item (id == null) darf keinen Pool-Lookup versuchen, sondern faellt auf die eigene (leere) Sammlung zurueck",
        )
    }

    /**
     * The counterpart to the two tests above: a **persisted** item
     * (non-null id) mapped through a [InvoiceDtoMapper] that has no `receiptRepository` wired at
     * all — a legitimate call site (any plain unit test, or a future caller that never injects
     * one). `poolReceiptsByItemId` is `null` in that case regardless of the item's own id, so the
     * pool lookup must never even be attempted; the item's own (real, non-empty) entity receipts
     * must be used instead.
     */
    @Test
    fun `a persisted item's own receipts are used when no receiptRepository is wired at all`() {
        val ownReceipt = Receipt().apply { fileName = "entity.pdf"; objectKey = "obj/entity" }
        val item = InvoiceItem().apply {
            id = 5L; positionId = "1"; description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal("19"))
            grossAmount = de.littletrotter.shared.Money(BigDecimal("119"))
            receipts = mutableListOf(ownReceipt)
        }
        val invoice = Invoice().apply {
            id = 952L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(item)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals(
            listOf("entity.pdf"), dto.items.single().receipts.map { it.fileName },
            "ohne verdrahtetes ReceiptRepository muss ein persistiertes Item (id != null) trotzdem auf seine eigene Belegliste zurueckfallen, nicht auf einen Pool-Lookup",
        )
    }

    // ── b2bFieldValueService wired — B2B free-text fields on the wire ─────────────────

    @Test
    fun `b2bValuesFor liefert eine leere Liste wenn der Rechnung noch keine id zugewiesen wurde`() {
        // svc is set, but ownerId (invoice.id) is null -- an invoice not yet persisted cannot
        // carry B2B values, regardless of whether a service is wired.
        val b2bFieldValueService = mock<B2bFieldValueService>()
        val invoice = Invoice().apply {
            id = null
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)

        assertTrue(dto.b2bValues.isEmpty())
    }

    @Test
    fun `b2bValuesFor traegt nur nicht-leere Freifeldwerte auf den Draht und verwirft Leerstrings`() {
        val b2bFieldValueService = mock<B2bFieldValueService>()
        whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, 950L)).thenReturn(
            mapOf("BT-19" to "Kostenstelle 42", "BT-56" to "   ", "BT-58" to null),
        )

        val invoice = Invoice().apply {
            id = 950L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)

        assertEquals(1, dto.b2bValues.size, "der Leerstring- und der null-Wert werden verworfen, nur der echte Wert bleibt")
        assertEquals("BT-19", dto.b2bValues.single().term.officialCode)
    }

    @Test
    fun `recipientPlacedB2bValues traegt nur nicht-leere Kundenwerte auf den Draht`() {
        // The RECIPIENT-placed resolution goes through the same blank/null filter chain
        // as the invoice-owner values; only the real value survives onto the wire.
        val b2bFieldValueService = mock<B2bFieldValueService>()
        whenever(b2bFieldValueService.resolvedRecipientFieldValues(any())).thenReturn(
            mapOf("BT-58" to "kunde@example.org", "BT-56" to "   ", "BT-13" to null),
        )
        val invoice = Invoice().apply {
            id = 951L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)

        assertEquals(1, dto.b2bValues.size, "blank and null recipient resolutions are dropped")
        assertEquals("BT-58", dto.b2bValues.single().term.officialCode)
        assertEquals("kunde@example.org", dto.b2bValues.single().value)
    }

    @Test
    fun `b2bValuesFor drops an officialCode that no longer resolves to a B2bTerm`() {
        // findFieldValues is keyed by the persisted B2bProfileField's officialCode; a
        // stale/removed catalog code (schema drift, same graceful-degradation contract as
        // B2bTermConverter's fromCodeOrNull) must be dropped, not crash the whole mapping.
        val b2bFieldValueService = mock<B2bFieldValueService>()
        whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, 952L)).thenReturn(
            mapOf("BT-19" to "Kostenstelle 42", "BT-9999" to "verwaist"),
        )
        val invoice = Invoice().apply {
            id = 952L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)

        assertEquals(1, dto.b2bValues.size, "the unresolvable code is dropped, the real one stays")
        assertEquals("BT-19", dto.b2bValues.single().term.officialCode)
    }

    @Test
    fun `recipientPlacedB2bValues drops an officialCode that no longer resolves to a B2bTerm`() {
        val b2bFieldValueService = mock<B2bFieldValueService>()
        whenever(b2bFieldValueService.resolvedRecipientFieldValues(any())).thenReturn(
            mapOf("BT-58" to "kunde@example.org", "BT-9999" to "verwaist"),
        )
        val invoice = Invoice().apply {
            id = 953L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)

        assertEquals(1, dto.b2bValues.size, "the unresolvable code is dropped, the real one stays")
        assertEquals("BT-58", dto.b2bValues.single().term.officialCode)
    }

    @Test
    fun `unwired mapper maps invoice-level receipts straight from the entity collection`() {
        // belege-improve A4 fallback — no repository wired (plain unit construction): the
        // invoice-level receipts still reach the wire from the entity's own collection.
        val invoice = Invoice().apply {
            id = 952L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }
        invoice.receipts.add(
            de.littletrotter.domain.masterdata.Receipt().apply {
                fileName = "beleg-a.pdf"
                objectKey = "receipt-pool/a.pdf"
            },
        )

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals(listOf("beleg-a.pdf"), dto.receipts.map { it.fileName })
        assertEquals(listOf("receipt-pool/a.pdf"), dto.receipts.map { it.objectKey })
    }

    /**
     * [de.littletrotter.domain.masterdata.Recipient.b2bProfile] and
     * [Invoice.b2bProfile] are read nowhere in [InvoiceDtoMapper.mapToDto]: only
     * [B2bFieldOwnerType.INVOICE]-keyed captured values ([InvoiceDtoMapper] internal helper)
     * ever reach the wire. Proven here by comparing the exact same invoice with and without a
     * B2B profile assigned (same captured values either way) — the two mapped DTOs must be
     * equal, not merely "similar", because nothing about the profile *assignment* (as opposed to
     * captured *values*) may leak onto the wire.
     */
    @Test
    fun `AP-9 - assigning a B2B profile to recipient and invoice leaves the mapped DTO unchanged`() {
        fun mapWithProfile(profile: de.littletrotter.domain.reference.B2bProfile?): InvoiceDto {
            val b2bFieldValueService = mock<B2bFieldValueService>()
            whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, 950L)).thenReturn(emptyMap())

            val recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1"); b2bProfile = profile }
            val invoice = Invoice().apply {
                id = 950L
                senderProfile = SenderProfile().apply { name = "Seller" }
                this.recipient = recipient
                b2bProfile = profile
            }

            return InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)
        }

        val withoutProfile = mapWithProfile(null)
        val withProfile = mapWithProfile(
            de.littletrotter.domain.reference.B2bProfile().apply { id = 5L; name = "B2B-Standard" },
        )

        assertEquals(withoutProfile, withProfile, "die Profilzuordnung an Kunde/Rechnung darf den Draht nicht beeinflussen")
    }

    // ── defensive hardening against nullability, partial data, and edge cases ──────

    @Test
    fun `H27-35 - invoice with no sender and no selectedSeller produces dto with null senderProfile`() {
        // Edge case: both sender options are null. The mapping must not throw,
        // even though the resulting DTO has null senderProfile.
        val invoice = Invoice().apply {
            id = 2000L
            senderProfile = null
            selectedSeller = null
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.senderProfile, "null sender must not cause a throw, just produce null senderProfile")
        assertEquals(2000L, dto.id)
    }

    @Test
    fun `H27-35 - invoice with no recipient produces dto with null recipient and no shipTo`() {
        // Edge case: recipient is null. The mapping must not throw.
        val invoice = Invoice().apply {
            id = 2001L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = null
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.recipient, "null recipient must not cause a throw")
        assertNull(dto.shipTo, "null recipient means no shipTo possible")
    }

    @Test
    fun `H27-35 - invoice with no optional dates (draft state) still maps successfully`() {
        // Edge case: draft invoice has no servicePeriodFrom/To/deliveryDate/dueDate — all of
        // which stay optional. date invariants (PO 2026-09-02) — invoiceDate itself is no
        // longer nullable (mandatory from creation, see Invoice.invoiceDate's own KDoc), so it is
        // no longer part of this "no dates" edge case; it maps to a non-null string instead.
        val invoice = Invoice().apply {
            id = 2002L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            servicePeriodFrom = null
            servicePeriodTo = null
            deliveryDate = null
            dueDate = null
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNotNull(dto.dates.invoiceDate, "invoiceDate is mandatory and must always map to a value")
        assertNull(dto.dates.servicePeriodFrom)
        assertNull(dto.dates.servicePeriodTo)
        assertNull(dto.dates.deliveryDate)
        assertNull(dto.dates.dueDate)
    }

    @Test
    fun `H27-35 - invoice with empty items list maps successfully`() {
        // Edge case: invoice with no items. The mapping should produce an empty items list,
        // not throw. (Note: while a valid invoice for business logic would have items,
        // the mapper itself should not enforce this.)
        val invoice = Invoice().apply {
            id = 2003L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            // items list is empty (default construction)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertTrue(dto.items.isEmpty(), "empty items list must map to empty dto items, not throw")
    }

    @Test
    fun `H27-35 - invoice with null sender addresses still produces valid party dto`() {
        // Edge case: sender has no billingAddress, and invoice.billingAddress is also null.
        // The mapper must defensively handle this and not throw.
        val sender = SenderProfile().apply {
            name = "Seller"
            // addresses collection is empty, no billing address set
        }
        val invoice = Invoice().apply {
            id = 2004L
            senderProfile = sender
            billingAddress = null
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNotNull(dto.senderProfile, "even without addresses, senderProfile should be created")
        assertEquals("Seller", dto.senderProfile?.name)
        assertNull(dto.senderProfile?.street, "null address means null street, not an exception")
    }

    @Test
    fun `H27-35 - invoice with partial dates (only some dates set) maps correctly`() {
        // Edge case: invoice has invoiceDate but no servicePeriodFrom/To.
        // Mixed null/non-null dates should be handled gracefully.
        val invoice = Invoice().apply {
            id = 2005L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            invoiceDate = java.time.LocalDate.of(2026, 8, 28)
            servicePeriodFrom = null
            servicePeriodTo = null
            deliveryDate = java.time.LocalDate.of(2026, 8, 30)
            dueDate = null
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("2026-08-28", dto.dates.invoiceDate)
        assertNull(dto.dates.servicePeriodFrom, "null service period dates should stay null")
        assertNull(dto.dates.servicePeriodTo)
        assertEquals("2026-08-30", dto.dates.deliveryDate)
        assertNull(dto.dates.dueDate)
    }

    @Test
    fun `H27-35 - partially loaded invoice with items (findByIdWithItems scenario) maps correctly`() {
        // Edge case: simulate a partially-loaded entity via findByIdWithItems.
        // The invoice has items loaded, but lazy associations like sender/recipient might not be.
        // The mapper must still work with whatever data is available.
        val item = InvoiceItem().apply {
            id = 1L
            positionId = "1"
            description = "Service"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal("19"))
            grossAmount = de.littletrotter.shared.Money(BigDecimal("119"))
        }
        val invoice = Invoice().apply {
            id = 2006L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(item)
            invoiceDate = java.time.LocalDate.of(2026, 8, 28)
            // Simulate partial load: some fields set, some null (as if lazy-fetched)
            deliveryAddress = null
            billingAddress = null
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals(1, dto.items.size, "partially loaded items should map successfully")
        assertEquals("1", dto.items.first().positionId)
        assertEquals("2026-08-28", dto.dates.invoiceDate)
    }

    @Test
    fun `H27-35 - invoice with non-default currency maps through unchanged`() {
        // InvoiceTotalsDto.currency is a typed Currency now (was a raw Int ordinal); the
        // mapper passes inv.currency through directly, no ordinal conversion left to verify here.
        val invoice = Invoice().apply {
            id = 2007L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            currency = de.littletrotter.shared.Currency.CHF
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals(de.littletrotter.shared.Currency.CHF, dto.totals.currency)
    }

    @Test
    fun `H27-35 - invoice with all nullable fields null except required ones maps defensively`() {
        // Comprehensive edge case: maximal nullability scenario. Only the absolutely
        // required fields are set; everything optional is null. The mapper must produce
        // a valid DTO without throwing.
        val invoice = Invoice().apply {
            id = 2008L
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-0001")
            senderProfile = null
            selectedSeller = null
            recipient = null
            billingAddress = null
            deliveryAddress = null
            // date invariants (PO 2026-09-02) — invoiceDate is mandatory, no longer part of this
            // "all nullable fields null" scenario; it keeps its Invoice() construction default.
            servicePeriodFrom = null
            servicePeriodTo = null
            deliveryDate = null
            dueDate = null
            // items list is empty
            // all reference fields are null/default
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals(2008L, dto.id)
        assertEquals("RE-2026-0001", dto.invoiceNumber.value)
        assertNull(dto.senderProfile)
        assertNull(dto.recipient)
        assertTrue(dto.items.isEmpty())
        assertNotNull(dto.dates.invoiceDate, "invoiceDate is mandatory and must always map to a value")
        assertNull(dto.shipTo)
    }

    @Test
    fun `H27-35 - invoice with null recipient routingId fallback for buyerReference handles blank routing id`() {
        // Edge case: recipient exists but routingId is blank. The buyerReference should
        // fall back and discover the routingId is blank, then return null.
        val recipient = Recipient().apply {
            name = "Buyer"
            customerNumber = CustomerNumber("KD-1")
            routingId = "     " // only whitespace
        }
        val invoice = Invoice().apply {
            id = 2009L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
            references.buyerReference = null // explicitly null, not set
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.references.buyerReference, "blank routingId should not be used as fallback")
    }

    @Test
    fun `H27-35 - recipient with null or missing addresses still produces valid party dto`() {
        // Edge case: recipient has no addresses at all (neither billing nor delivery).
        val recipient = Recipient().apply {
            name = "Buyer without addresses"
            customerNumber = CustomerNumber("KD-1")
        }
        val invoice = Invoice().apply {
            id = 2010L
            senderProfile = SenderProfile().apply { name = "Seller" }
            this.recipient = recipient
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNotNull(dto.recipient)
        assertEquals("Buyer without addresses", dto.recipient?.name)
        assertNull(dto.recipient?.street, "missing addresses should produce null fields, not throw")
        assertNull(dto.shipTo, "no delivery address means no shipTo")
    }

    @Test
    fun `H27-35 - invoice with sender but no sender billing address uses null fallback`() {
        // Edge case: sender.billingAddress is null, inv.billingAddress is null, and we call
        // sender.toDto(null). The PartyDto constructor must handle null street/postal/city gracefully.
        val sender = SenderProfile().apply {
            name = "Seller without address"
            email = Email.ofNullable("seller@example.com")
        }
        val invoice = Invoice().apply {
            id = 2011L
            senderProfile = sender
            billingAddress = null
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNotNull(dto.senderProfile)
        assertEquals("Seller without address", dto.senderProfile?.name)
        assertNull(dto.senderProfile?.street)
        assertEquals("seller@example.com", dto.senderProfile?.email)
    }

    @Test
    fun `H27-35 - invoice item with incomplete tax resolution throws with invoice context`() {
        // Edge case: item without taxRateRef or taxRateSnapshot throws InvoiceValidationException
        // from effectiveTaxRate(). The mapper re-throws with invoice number and position context.
        val itemNoTax = InvoiceItem().apply {
            id = 1L
            positionId = "5"
            description = "Bad item"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal.ZERO)
            grossAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            // No taxRateRef, no taxRateSnapshot → effectiveTaxRate() throws
        }
        val invoice = Invoice().apply {
            id = 2012L
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-0012")
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(itemNoTax)
        }

        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            de.littletrotter.domain.exception.InvoiceValidationException::class.java,
        ) {
            InvoiceDtoMapper().mapToDto(invoice)
        }

        assertTrue(ex.message?.contains("RE-2026-0012") == true, "exception must name the invoice number")
        assertTrue(ex.message?.contains("position 5") == true, "exception must name the position")
    }

    @Test
    fun `H27-35 - multiple items with one invalid tax handling includes context`() {
        // Edge case: multiple items, one without tax. Ensure error message is clear.
        val goodItem = InvoiceItem().apply {
            positionId = "1"
            description = "Good item"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
            netAmount = de.littletrotter.shared.Money(BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal("19"))
            grossAmount = de.littletrotter.shared.Money(BigDecimal("119"))
        }
        val badItem = InvoiceItem().apply {
            positionId = "2"
            description = "Bad item"
            quantity = BigDecimal.ONE
            unitPriceNet = de.littletrotter.shared.Money(BigDecimal("50"))
            netAmount = de.littletrotter.shared.Money(BigDecimal("50"))
            vatAmount = de.littletrotter.shared.Money(BigDecimal.ZERO)
            grossAmount = de.littletrotter.shared.Money(BigDecimal("50"))
            // No tax info
        }
        val invoice = Invoice().apply {
            id = 2013L
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-0013")
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            itemList.asMutableForTest().add(goodItem)
            itemList.asMutableForTest().add(badItem)
        }

        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            de.littletrotter.domain.exception.InvoiceValidationException::class.java,
        ) {
            InvoiceDtoMapper().mapToDto(invoice)
        }

        assertTrue(ex.message?.contains("position 2") == true, "error must identify the bad position")
    }

    // ── draft watermark: InvoiceStatus/typeCode -> InvoiceDto.draft/storno ──

    private fun invoiceWithStatus(
        status: de.littletrotter.domain.invoice.InvoiceStatus,
        typeCode: de.littletrotter.domain.invoice.InvoiceTypeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.COMMERCIAL_INVOICE,
    ): Invoice = Invoice().apply {
        id = 3000L
        senderProfile = SenderProfile().apply { name = "Seller" }
        recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
        this.forceStatusForTest(status)
        this.typeCode = typeCode
    }

    @Test
    fun `spec-draft-watermark - DRAFT status maps to draft true`() {
        val dto = InvoiceDtoMapper().mapToDto(invoiceWithStatus(de.littletrotter.domain.invoice.InvoiceStatus.DRAFT))
        assertTrue(dto.draft, "DRAFT is non-finalized -- the PDF must carry the draft watermark")
        assertFalse(dto.storno, "a plain COMMERCIAL_INVOICE is never a storno document")
    }

    @Test
    fun `issued job - IN_REVIEW invoice maps to draft false so the SEND_ISSUE job archives`() {
        val dto = InvoiceDtoMapper().mapToDto(
            invoiceWithStatus(de.littletrotter.domain.invoice.InvoiceStatus.IN_REVIEW),
            issued = true,
        )
        assertFalse(dto.draft, "an issuing job must never produce a draft document")
    }

    @Test
    fun `unissued job - IN_REVIEW invoice keeps the status-derived draft true`() {
        val dto = InvoiceDtoMapper().mapToDto(
            invoiceWithStatus(de.littletrotter.domain.invoice.InvoiceStatus.IN_REVIEW),
            issued = false,
        )
        assertTrue(dto.draft)
    }

    @Test
    fun `issued job - VOIDED invoice stays draft true`() {
        val dto = InvoiceDtoMapper().mapToDto(
            invoiceWithStatus(de.littletrotter.domain.invoice.InvoiceStatus.VOIDED),
            issued = true,
        )
        assertTrue(dto.draft)
    }

    @Test
    fun `spec-draft-watermark - IN_REVIEW status maps to draft true`() {
        val dto = InvoiceDtoMapper().mapToDto(invoiceWithStatus(de.littletrotter.domain.invoice.InvoiceStatus.IN_REVIEW))
        assertTrue(dto.draft, "IN_REVIEW is non-finalized -- the PDF must carry the draft watermark, same as DRAFT")
    }

    @Test
    fun `spec-draft-watermark - SENT status maps to draft false`() {
        val dto = InvoiceDtoMapper().mapToDto(invoiceWithStatus(de.littletrotter.domain.invoice.InvoiceStatus.SENT))
        assertFalse(dto.draft, "a SENT invoice's PDF must stay unchanged -- no draft watermark")
    }

    @Test
    fun `RS-2 - CANCELLED status maps to draft false and no longer sets a dedicated flag`() {
        val dto = InvoiceDtoMapper().mapToDto(invoiceWithStatus(de.littletrotter.domain.invoice.InvoiceStatus.CANCELLED))
        assertFalse(dto.draft, "a CANCELLED invoice's PDF must stay unchanged -- no draft watermark, same as SENT")
        assertFalse(dto.storno, "the cancelled original itself is not the credit note")
    }

    @Test
    fun `spec-draft-watermark - VOIDED status maps to draft true`() {
        // PO decision 2026-08-31 (Annullierung) — unlike SENT/CANCELLED, a VOIDED invoice was
        // never sent and never will be; any PDF that ever gets rendered for it (normally blocked
        // at GenerationJobService.createJob, this is the defense-in-depth branch for a job queued
        // before the void) must never look like a real, final document.
        val dto = InvoiceDtoMapper().mapToDto(invoiceWithStatus(de.littletrotter.domain.invoice.InvoiceStatus.VOIDED))
        assertTrue(dto.draft, "a VOIDED invoice's PDF must carry the draft watermark -- it was never a real document")
    }

    @Test
    fun `ST-5 - a CORRECTED_INVOICE invoice maps to storno true regardless of its own status`() {
        // PO decision 1 (2026-09-02) — the storno flow stamps CORRECTED_INVOICE (384), not
        // CREDIT_NOTE (381) any more; this mapping moved with it.
        val dto = InvoiceDtoMapper().mapToDto(
            invoiceWithStatus(
                de.littletrotter.domain.invoice.InvoiceStatus.CANCELLED,
                typeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.CORRECTED_INVOICE,
            ),
        )
        assertTrue(dto.storno, "typeCode == CORRECTED_INVOICE must map to storno true -- ST-5 mark table row 1")
        assertFalse(dto.draft, "a CANCELLED storno document is not a draft")
    }

    @Test
    fun `ST-5 - a COMMERCIAL_INVOICE never maps to storno true`() {
        val dto = InvoiceDtoMapper().mapToDto(invoiceWithStatus(de.littletrotter.domain.invoice.InvoiceStatus.SENT))
        assertFalse(dto.storno, "a plain invoice's typeCode is COMMERCIAL_INVOICE, never storno")
    }

    @Test
    fun `PO decision 1 (2026-09-02) - a CREDIT_NOTE invoice no longer maps to storno true`() {
        // CREDIT_NOTE (381) is reserved for a genuine credit note now, which this system does not
        // produce through any code path -- it must not pick up the storno watermark any more.
        val dto = InvoiceDtoMapper().mapToDto(
            invoiceWithStatus(
                de.littletrotter.domain.invoice.InvoiceStatus.CANCELLED,
                typeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.CREDIT_NOTE,
            ),
        )
        assertFalse(dto.storno, "typeCode == CREDIT_NOTE must no longer map to storno true")
    }

    // ── the seller's invoice note follows documentLanguage, covering the
    //    three-column shape (German document / English present / English missing → German) ────

    private fun language(id: Int, code: String, isFallback: Boolean) =
        de.littletrotter.domain.reference.Language().apply { this.id = id; this.code = code; this.isFallback = isFallback }

    private val germanLanguage = language(1, "de", isFallback = true)
    private val englishLanguage = language(2, "en", isFallback = false)

    private fun senderWithNote(vararg notes: Pair<de.littletrotter.domain.reference.Language, String>) =
        SenderProfile().apply {
            name = "Seller"
            notes.forEach { (language, text) -> setInvoiceNote(language, text) }
        }

    // The payment statement's three resolution branches. Before these, neither
    // paymentTermSnapshot nor paymentTerm was set anywhere in this class: only the "no term at
    // all" branch was exercised, and the snapshot-wins rule had no test at all. The class-level
    // 100 % branch gate on InvoiceDtoMapper is what surfaced that.

    @Test
    fun `IN-4 - the frozen payment-term snapshot wins over the live association`() {
        val invoice = Invoice().apply {
            id = 2101L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "de"
            // The live term was renamed after this invoice was sent; the document must keep the
            // wording it was issued with (W7).
            paymentTerm = de.littletrotter.domain.masterdata.PaymentTerm().apply {
                setName(germanLanguage, "Heute umbenannt")
            }
            paymentTermSnapshot = de.littletrotter.domain.invoice.InvoicePaymentTermSnapshot().apply {
                name = "14 Tage netto"
            }
        }

        assertEquals("14 Tage netto", InvoiceDtoMapper().mapToDto(invoice).dates.paymentTermName)
    }

    @Test
    fun `IN-4 - without a snapshot the live payment term is resolved in the document language`() {
        fun invoiceIn(language: String) = Invoice().apply {
            id = 2102L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = language
            paymentTerm = de.littletrotter.domain.masterdata.PaymentTerm().apply {
                setName(germanLanguage, "14 Tage netto")
                setName(englishLanguage, "Net 14 days")
            }
        }

        assertEquals("14 Tage netto", InvoiceDtoMapper().mapToDto(invoiceIn("de")).dates.paymentTermName)
        assertEquals("Net 14 days", InvoiceDtoMapper().mapToDto(invoiceIn("en")).dates.paymentTermName)
    }

    @Test
    fun `IN-4 - an invoice that never had a payment term carries no payment-term name`() {
        val invoice = Invoice().apply {
            id = 2103L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "de"
        }

        assertNull(InvoiceDtoMapper().mapToDto(invoice).dates.paymentTermName)
    }

    @Test
    fun `cashDiscount - an invoice with a discount term carries percent, days, deadline and amounts`() {
        val invoice = Invoice().apply {
            id = 2104L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "de"
            invoiceDate = java.time.LocalDate.of(2026, 9, 28)
            paymentTerm = de.littletrotter.domain.masterdata.PaymentTerm().apply {
                discountPercent = de.littletrotter.domain.value.DiscountPercent(BigDecimal("2"))
                discountDays = 7
            }
            totals.grossAmount = de.littletrotter.shared.Money(BigDecimal("604.66"))
        }

        val cashDiscount = InvoiceDtoMapper().mapToDto(invoice).cashDiscount

        assertEquals(BigDecimal("2"), cashDiscount?.percent)
        assertEquals(7, cashDiscount?.days)
        assertEquals("2026-10-05", cashDiscount?.deadline)
        assertEquals(de.littletrotter.shared.Money(BigDecimal("12.09")), cashDiscount?.amount)
        assertEquals(de.littletrotter.shared.Money(BigDecimal("592.57")), cashDiscount?.payableAmount)
    }

    @Test
    fun `cashDiscount - an invoice without a discount term carries no cashDiscount`() {
        val invoice = Invoice().apply {
            id = 2105L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "de"
            totals.grossAmount = de.littletrotter.shared.Money(BigDecimal("604.66"))
        }

        assertNull(InvoiceDtoMapper().mapToDto(invoice).cashDiscount)
    }

    @Test
    fun `meta profileId - a draft with a discount term and no vat ids stays EN16931 while the EXTENDED opt-in is off`() {
        val invoice = Invoice().apply {
            id = 2106L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "de"
            invoiceDate = java.time.LocalDate.of(2026, 9, 28)
            documentEngine = de.littletrotter.domain.invoice.DocumentEngine.MUSTANG
            paymentTerm = de.littletrotter.domain.masterdata.PaymentTerm().apply {
                discountPercent = de.littletrotter.domain.value.DiscountPercent(BigDecimal("2"))
                discountDays = 7
            }
            totals.grossAmount = de.littletrotter.shared.Money(BigDecimal("604.66"))
        }

        // PO decision 2026-09-28 — no gate wired (the plain-unit-test default) falls back to
        // FeatureFlag.EXTENDED_PROFILE.defaultEnabled = false, same as an unwired production gate.
        assertEquals("EN16931", InvoiceDtoMapper().mapToDto(invoice).meta.profileId)
    }

    @Test
    fun `meta profileId - a draft with a discount term and no vat ids escalates to EXTENDED once opted in`() {
        val invoice = Invoice().apply {
            id = 2108L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "de"
            invoiceDate = java.time.LocalDate.of(2026, 9, 28)
            documentEngine = de.littletrotter.domain.invoice.DocumentEngine.MUSTANG
            paymentTerm = de.littletrotter.domain.masterdata.PaymentTerm().apply {
                discountPercent = de.littletrotter.domain.value.DiscountPercent(BigDecimal("2"))
                discountDays = 7
            }
            totals.grossAmount = de.littletrotter.shared.Money(BigDecimal("604.66"))
        }
        val gate = mock<de.littletrotter.application.settings.FeatureSettingsGate>()
        whenever(gate.isEnabled(de.littletrotter.domain.settings.FeatureFlag.EXTENDED_PROFILE)).thenReturn(true)

        assertEquals("EXTENDED", InvoiceDtoMapper(featureSettingsGate = gate).mapToDto(invoice).meta.profileId)
    }

    @Test
    fun `meta profileId - the Factur-X engine keeps EN16931 even with a discount term and the opt-in on`() {
        val invoice = Invoice().apply {
            id = 2107L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "de"
            invoiceDate = java.time.LocalDate.of(2026, 9, 28)
            documentEngine = de.littletrotter.domain.invoice.DocumentEngine.FACTURX
            paymentTerm = de.littletrotter.domain.masterdata.PaymentTerm().apply {
                discountPercent = de.littletrotter.domain.value.DiscountPercent(BigDecimal("2"))
                discountDays = 7
            }
            totals.grossAmount = de.littletrotter.shared.Money(BigDecimal("604.66"))
        }
        val gate = mock<de.littletrotter.application.settings.FeatureSettingsGate>()
        whenever(gate.isEnabled(de.littletrotter.domain.settings.FeatureFlag.EXTENDED_PROFILE)).thenReturn(true)

        assertEquals("EN16931", InvoiceDtoMapper(featureSettingsGate = gate).mapToDto(invoice).meta.profileId)
    }

    @Test
    fun `INV-B - export prints the persisted German invoice note snapshot`() {
        val invoice = Invoice().apply {
            id = 2001L
            senderProfile = senderWithNote(germanLanguage to "Zahlbar ohne Abzug.")
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "de"
            invoiceNote = "Zahlbar ohne Abzug."
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("Zahlbar ohne Abzug.", dto.senderProfile?.footerNote)
    }

    @Test
    fun `consumer note snapshot is exported on the meta block`() {
        val invoice = Invoice().apply {
            id = 2002L
            senderProfile = senderWithNote()
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            consumerNote = "Bitte aufbewahren."
        }

        assertEquals("Bitte aufbewahren.", InvoiceDtoMapper().mapToDto(invoice).meta.consumerNote)
    }

    @Test
    fun `INV-B - export prints the persisted English invoice note snapshot`() {
        val invoice = Invoice().apply {
            id = 2002L
            senderProfile = senderWithNote(germanLanguage to "Zahlbar ohne Abzug.", englishLanguage to "Payable without deduction.")
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "en"
            invoiceNote = "Payable without deduction."
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("Payable without deduction.", dto.senderProfile?.footerNote)
    }

    @Test
    fun `INV-B - resolved fallback is frozen on the invoice`() {
        val invoice = Invoice().apply {
            id = 2003L
            senderProfile = senderWithNote(germanLanguage to "Zahlbar ohne Abzug.")
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "en"
            invoiceNote = "Zahlbar ohne Abzug."
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("Zahlbar ohne Abzug.", dto.senderProfile?.footerNote)
    }

    @Test
    fun `INV-B - empty frozen note does not read a later live sender note`() {
        val invoice = Invoice().apply {
            id = 2004L
            senderProfile = senderWithNote(englishLanguage to "Payable without deduction.")
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "de"
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.senderProfile?.footerNote, "no German text exists — the box must stay empty, never fall through to English")
    }

    @Test
    fun `INV-B - finalized export keeps its snapshot after the sender note is deleted`() {
        val sender = senderWithNote(germanLanguage to "Alter Hinweis")
        val invoice = Invoice().apply {
            id = 2005L
            senderProfile = sender
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "de"
            invoiceNote = "Alter Hinweis"
            forceStatusForTest(InvoiceStatus.SENT)
        }
        sender.invoiceNote = null

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals("Alter Hinweis", dto.senderProfile?.footerNote)
    }

    @Test
    fun `INV-B - seller without note creates an empty export snapshot`() {
        val invoice = Invoice().apply {
            id = 2005L
            senderProfile = SenderProfile().apply { name = "Seller" }
            recipient = Recipient().apply { name = "Buyer"; customerNumber = CustomerNumber("KD-1") }
            documentLanguage = "en"
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertNull(dto.senderProfile?.footerNote)
    }
}
