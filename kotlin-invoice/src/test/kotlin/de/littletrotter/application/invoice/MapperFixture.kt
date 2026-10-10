package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.asMutableForTest

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.initialInvoiceNoteSnapshot
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.LegalRegistrationIdentifier
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.PayloadExchange
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * Z4 — one fully populated [Invoice] (items, receipts, party identifiers/electronic addresses,
 * B2B values, payment term, tax snapshots) plus the collaborator stubs the mapping code needs
 * (receipts, B2B free values), so the pre-refactor golden JSON and the
 * post-refactor [InvoiceDtoMapper] output are always produced from the exact same input.
 */
object MapperFixture {

    /** Everything one full mapping run needs, bundled so both call sites stay in sync. */
    data class Context(
        val invoice: Invoice,
        val receiptRepository: ReceiptRepository,
        val b2bFieldValueService: B2bFieldValueService,
        val payloadExchange: PayloadExchange,
    )

    fun build(): Context {
        val sender = SenderProfile().apply {
            name = "Golden Seller GmbH"
            email = Email.ofNullable("seller@golden.example")
            phone = "+49 221 1234567"
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE02120300000000202051")
            bic = Bic("BYLADEM1001")
            taxNumber = TaxNumber("12/345/67890")
            globalId = "4012345678901"
            bankName = "Sparkasse Koeln"
            managingDirector = "Erika Musterfrau"
            courtOfJurisdiction = "AG Koeln"
            registerCourt = "AG Koeln HRB"
            commercialRegisterNumber = "HRB 12345"
            // footerNote is the resolved text of invoiceNote for the document
            // language; the fixture's own invoice is documentLanguage = "de" below, so a German
            // entry alone reproduces the exact same wire value the old plain-string field used to.
            setInvoiceNote(Language().apply { id = 1; code = "de"; isFallback = true }, "Footer note")
            sellerIdentifier = PartyIdentifier.of("DE123456789", "9930")
            electronicAddress = ElectronicAddress.of("DE123456789", "9930")
            legalRegistrationSchemeId = "0002"
            updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)
            setBillingAddress(
                Address.forSender(this, Address.AddressType.BILLING, "Senderweg 1", "50667", "Koeln", Country.DE, true),
            )
        }
        val recipient = Recipient().apply {
            name = "Golden Buyer AG"
            customerNumber = CustomerNumber("KD-GOLD-1")
            email = Email.ofNullable("buyer@golden.example")
            phone = "+49 30 7654321"
            vatId = VatId("DE987654321")
            globalId = "4012345678902"
            buyerIdentifier = PartyIdentifier.of("DE987654321", "9930")
            legalRegistrationIdentifier = LegalRegistrationIdentifier.of("HRB 99999", "0002")
            electronicAddress = ElectronicAddress.of("DE987654321", "9930")
            customerType = CustomerType.BUSINESS
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
            routingId = "04011000-1234512345-06"
            siret = "73282932000074"
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Rechnungsweg 7", "80331", "Muenchen", Country.DE))
        }

        val item1 = InvoiceItem().apply {
            id = 1L
            positionId = "1"
            description = "Consulting"
            itemDescription = "Zusatztext BT-154"
            quantity = BigDecimal("2.5")
            unitPriceNet = Money(BigDecimal("100.00"))
            productClassificationScheme = "UNSPSC"
            productClassificationCode = "12345678"
            servicePeriodFrom = LocalDate.of(2026, 8, 1)
            servicePeriodTo = LocalDate.of(2026, 8, 15)
            netAmount = Money(BigDecimal("250.00"))
            vatAmount = Money(BigDecimal("47.50"))
            grossAmount = Money(BigDecimal("297.50"))
            applyTaxRule(
                TaxRate().apply { code = "DE_STANDARD_19"; category = "S"; rate = TaxRatePercent(BigDecimal("19.00")) },
            )
        }
        val item2 = InvoiceItem().apply {
            id = 2L
            positionId = "2"
            description = "Reverse-Charge-Dienstleistung"
            quantity = BigDecimal.ONE
            unitPriceNet = Money(BigDecimal("500.00"))
            netAmount = Money(BigDecimal("500.00"))
            vatAmount = Money(BigDecimal.ZERO)
            grossAmount = Money(BigDecimal("500.00"))
            applyTaxRule(
                TaxRate().apply {
                    code = "EU_B2B_RC_SERVICE"
                    category = "AE"
                    rate = TaxRatePercent(BigDecimal.ZERO)
                    exemptionReasonCode = "VATEX-EU-AE"
                    exemptionReasonText = "Reverse charge"
                    legalBasis = "Art. 196 RL 2006/112/EG"
                },
            )
        }

        val invoice = Invoice().apply {
            id = 5000L
            invoiceNumber = InvoiceNumber("RE-2026-08-05000")
            invoiceDate = LocalDate.of(2026, 8, 20)
            servicePeriodFrom = LocalDate.of(2026, 8, 1)
            servicePeriodTo = LocalDate.of(2026, 8, 15)
            dueDate = LocalDate.of(2026, 9, 3)
            paymentTermsDays = 14
            references.buyerReference = "PO-2026-GOLD-1"
            references.purchaseOrderReference = "BST-2026-GOLD-1"
            references.orderReference = "ANG-2026-GOLD-1"
            references.orderReferenceDate = LocalDate.of(2026, 7, 15)
            references.offerReferenceDate = LocalDate.of(2026, 7, 1)
            senderProfile = sender
            this.recipient = recipient
            deliveryAddress = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Lieferweg 9", "10115", "Berlin", Country.DE)
            currency = Currency.EUR
            languageId = "de"
            documentLanguage = "de"
            applyInvoiceNoteSnapshot(initialInvoiceNoteSnapshot(sender.id, documentLanguage, sender.invoiceNoteFor(documentLanguage)))
            documentStatusReasonCode = "01"
            freeText = "Freitext"
            itemList.asMutableForTest().add(item1)
            itemList.asMutableForTest().add(item2)
            totals.netAmount = Money(BigDecimal("750.00"))
            totals.vatAmount = Money(BigDecimal("47.50"))
            totals.grossAmount = Money(BigDecimal("797.50"))
            sellerVatRegistrationCountry = Country.LU
            invoiceFormat = InvoiceFormat.GERMAN
            deliveryDate = LocalDate.of(2026, 8, 15)
            typeCode = InvoiceTypeCode.CREDIT_NOTE
            originalInvoiceNumber = "RE-2026-08-04999"
            originalInvoiceDate = LocalDate.of(2026, 8, 10)
        }

        val receiptRepository = mock<ReceiptRepository>()
        val invoiceReceipt = Receipt().apply { fileName = "vertrag.pdf"; objectKey = "obj/vertrag" }
        val itemReceipt = Receipt().apply { fileName = "beleg-item1.pdf"; objectKey = "obj/item1" }
        whenever(receiptRepository.findForInvoice(eq(5000L), any(), any())).thenReturn(listOf(invoiceReceipt))
        whenever(receiptRepository.findItemReceiptsForInvoice(5000L)).thenReturn(mapOf(1L to listOf(itemReceipt)))

        val b2bFieldValueService = mock<B2bFieldValueService>()
        whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, 5000L))
            .thenReturn(mapOf("BT-19" to "Kostenstelle 42", "BT-56" to "   ", "BT-58" to null))
        whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 1L))
            .thenReturn(mapOf("BT-155" to "Positionsfreitext"))
        whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 2L))
            .thenReturn(emptyMap())

        val payloadExchange = mock<PayloadExchange>()

        return Context(invoice, receiptRepository, b2bFieldValueService, payloadExchange)
    }
}
