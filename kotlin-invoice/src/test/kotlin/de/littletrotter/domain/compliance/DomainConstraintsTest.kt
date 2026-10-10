package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.shared.Money
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.germanFallbackLanguage
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.domain.value.DiscountPercent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import de.littletrotter.shared.country.Country
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

class DomainConstraintsTest {

    // The number is mandatory only for a SENT invoice; a draft without a number is the normal
    // case (allocation happens only on the transition to SENT, not on the draft) — see
    // Invoice.validate().
    @Test
    fun `a draft with a blank invoice number is not rejected`() {
        val invoice = validInvoice().apply {
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("   ")
        }

        assertDoesNotThrow { DomainConstraints.validateInvoice(invoice) }
    }

    @Test
    fun `a SENT invoice with a blank invoice number is rejected`() {
        val invoice = validInvoice().apply {
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("   ")
            forceStatusForTest(de.littletrotter.domain.invoice.InvoiceStatus.SENT)
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice without sender profile is rejected`() {
        val invoice = validInvoice().apply {
            senderProfile = null
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice without recipient is rejected`() {
        val invoice = validInvoice().apply {
            recipient = null
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with negative net amount is rejected`() {
        val invoice = validInvoice().apply {
            totals.netAmount = de.littletrotter.shared.Money(BigDecimal("-0.01"))
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with negative vat amount is rejected`() {
        val invoice = validInvoice().apply {
            totals.vatAmount = de.littletrotter.shared.Money(BigDecimal("-0.01"))
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with negative gross amount is rejected`() {
        val invoice = validInvoice().apply {
            totals.grossAmount = de.littletrotter.shared.Money(BigDecimal("-0.01"))
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice without items is rejected`() {
        val invoice = validInvoice().apply {
            itemList.asMutableForTest().clear()
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `valid invoice passes validation`() {
        assertDoesNotThrow {
            DomainConstraints.validateInvoice(validInvoice())
        }
    }

    @Test
    fun `invoice with negative payment terms is rejected`() {
        val invoice = validInvoice().apply {
            paymentTermsDays = -1
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with malformed invoice number is rejected`() {
        assertThrows(InvoiceValidationException::class.java) {
            de.littletrotter.domain.invoice.InvoiceNumber("RE2026/1")
        }
    }

    @Test
    fun `invoice with legacy invoice number format is accepted`() {
        assertDoesNotThrow {
            de.littletrotter.domain.invoice.InvoiceNumber("DEVEDGE-2026-0001")
        }
    }

    @Test
    fun `invoice with due date before invoice date is rejected`() {
        // date invariants (PO 2026-09-02) — supersedes the former "accepted, only a WARNING"
        // behavior: dueDate >= invoiceDate is now a hard rejection at every save/edit (Invoice
        // .validate(), see that method's own KDoc), not only the still-unchanged, WARNING-severity
        // finalization-checklist entry (DueDateRules — see DueDateRulesTest, which is about the
        // separate, non-blocking checklist finding, not this save-time gate).
        val invoice = validInvoice().apply {
            invoiceDate = LocalDate.of(2026, 3, 10)
            dueDate = LocalDate.of(2026, 3, 9)
        }

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.DUE_DATE_BEFORE_INVOICE_DATE, ex.error)
    }

    @Test
    fun `invoice with due date equal invoice date is accepted`() {
        val invoice = validInvoice().apply {
            invoiceDate = LocalDate.of(2026, 3, 10)
            dueDate = LocalDate.of(2026, 3, 10)
        }

        assertDoesNotThrow {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with service end before service start is rejected`() {
        val invoice = validInvoice().apply {
            servicePeriodFrom = LocalDate.of(2026, 3, 20)
            servicePeriodTo = LocalDate.of(2026, 3, 19)
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with service period boundaries equal is accepted`() {
        val invoice = validInvoice().apply {
            servicePeriodFrom = LocalDate.of(2026, 3, 20)
            servicePeriodTo = LocalDate.of(2026, 3, 20)
        }

        assertDoesNotThrow {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with valid structured invoice number is accepted`() {
        val invoice = validInvoice().apply {
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("ACME-2026-03-00042")
        }

        assertDoesNotThrow {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with recipient without customer number is rejected`() {
        val invoice = validInvoice().apply {
            recipient = recipient?.apply { customerNumber = null }
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with recipient without name is rejected`() {
        val invoice = validInvoice().apply {
            recipient = recipient?.apply { name = "" }
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with sender profile without name is rejected`() {
        val invoice = validInvoice().apply {
            senderProfile = senderProfile?.apply { name = "  " }
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with sender profile without invoice prefix is rejected`() {
        val invoice = validInvoice().apply {
            senderProfile = senderProfile?.apply { invoicePrefix = "" }
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    // an out-of-range percentage now throws at TaxRatePercent construction, before this
    // item could ever reach DomainConstraints.validateInvoiceItem.
    @Test
    fun `constructing a tax rate for an invoice item with an invalid percentage throws immediately`() {
        assertThrows(InvalidParameterException::class.java) {
            TaxRate().apply { rate = TaxRatePercent(BigDecimal("120.00")) }
        }
    }

    @Test
    fun `invoice item with negative quantity is rejected`() {
        val item = validInvoiceItem().apply {
            quantity = BigDecimal("-1.00")
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoiceItem(item)
        }
    }

    @Test
    fun `happy path - valid invoice item passes validation via DomainConstraints`() {
        assertDoesNotThrow {
            DomainConstraints.validateInvoiceItem(validInvoiceItem())
        }
    }

    @Test
    fun `payment term with blank name is rejected`() {
        val term = validPaymentTerm().apply { setName(germanFallbackLanguage(), "   ") }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validatePaymentTerm(term)
        }
    }

    @Test
    fun `payment term with negative due days is rejected`() {
        val term = validPaymentTerm().apply { dueInDays = -1 }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validatePaymentTerm(term)
        }
    }

    @Test
    fun `payment term with negative discount days is rejected`() {
        val term = validPaymentTerm().apply { discountDays = -5 }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validatePaymentTerm(term)
        }
    }

    // the 0..100 range is now DiscountPercent's own constructor invariant: it throws
    // immediately when the value is built, before a PaymentTerm carrying it could ever reach
    // DomainConstraints.validatePaymentTerm.
    @Test
    fun `payment term with discount percent above 100 is rejected at construction`() {
        assertThrows(InvalidParameterException::class.java) { DiscountPercent(BigDecimal("120.00")) }
    }

    @Test
    fun `valid payment term passes validation`() {
        assertDoesNotThrow {
            DomainConstraints.validatePaymentTerm(validPaymentTerm())
        }
    }

    @Test
    fun `tax rate with blank name is rejected`() {
        val taxRate = validTaxRate().apply { name = " " }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateTaxRate(taxRate)
        }
    }

    // an out-of-range percentage now throws at TaxRatePercent construction, before this
    // rate could ever reach DomainConstraints.validateTaxRate.
    @Test
    fun `constructing a tax rate with an excessive percentage throws immediately`() {
        assertThrows(InvalidParameterException::class.java) {
            validTaxRate().apply { rate = TaxRatePercent(BigDecimal("120.00")) }
        }
    }

    @Test
    fun `valid tax rate passes validation`() {
        assertDoesNotThrow {
            DomainConstraints.validateTaxRate(validTaxRate())
        }
    }

    @Test
    fun `per diem rate with negative full day rate is rejected`() {
        val rate = validPerDiemRate().apply { fullDayRate = Money.of("-1.00") }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validatePerDiemRate(rate)
        }
    }

    @Test
    fun `per diem rate with invalid country code is rejected`() {
        val rate = validPerDiemRate().apply { country = null }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validatePerDiemRate(rate)
        }
    }

    @Test
    fun `valid per diem rate passes validation`() {
        assertDoesNotThrow {
            DomainConstraints.validatePerDiemRate(validPerDiemRate())
        }
    }

    @Test
    fun `article with blank name is rejected`() {
        val article = validArticle().apply { name = "   " }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateArticle(article, today = FIXED_TODAY)
        }
    }

    @Test
    fun `article with negative default quantity is rejected`() {
        val article = validArticle().apply { defaultQuantity = BigDecimal("-1.0") }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateArticle(article, today = FIXED_TODAY)
        }
    }

    @Test
    fun `happy path - valid article passes validation via DomainConstraints`() {
        assertDoesNotThrow {
            DomainConstraints.validateArticle(validArticle(), today = FIXED_TODAY)
        }
    }

    @Test
    fun `address with blank street is rejected`() {
        val address = validAddress().apply { street = "" }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateAddress(address)
        }
    }

    @Test
    fun `valid address passes validation`() {
        assertDoesNotThrow {
            DomainConstraints.validateAddress(validAddress())
        }
    }

    @Test
    fun `travel expense entry with negative employer contribution is rejected`() {
        val entry = TravelExpenseEntry().apply {
            country = Country.DE
            travelDate = LocalDate.now()
            fullDayRateSnapshot = Money.of("28.00")
            arrivalDepartureRateSnapshot = Money.of("14.00")
            calculatedAmount = Money.of("14.00")
            employerContribution = Money.of("-1.00")
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateTravelExpenseEntry(entry)
        }
    }

    @Test
    fun `travel expense entry with blank country is rejected`() {
        val entry = TravelExpenseEntry().apply {
            description = "Team-Reise Hamburg"
            participant = "Kundentermin"
            country = Country.DE
            travelDate = LocalDate.now()
            fullDayRateSnapshot = Money.of("28.00")
            arrivalDepartureRateSnapshot = Money.of("14.00")
            calculatedAmount = Money.of("14.00")
            employerContribution = Money.of("0.00")
        }

        assertDoesNotThrow {
            DomainConstraints.validateTravelExpenseEntry(entry)
        }
    }

    @Test
    fun `travel expense entry without travel date is rejected`() {
        val entry = TravelExpenseEntry().apply {
            country = Country.DE
            travelDate = null
            fullDayRateSnapshot = Money.of("28.00")
            arrivalDepartureRateSnapshot = Money.of("14.00")
            calculatedAmount = Money.of("14.00")
            employerContribution = Money.of("0.00")
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateTravelExpenseEntry(entry)
        }
    }

    @Test
    fun `valid travel expense entry passes validation`() {
        val entry = TravelExpenseEntry().apply {
            description = "Team-Reise Hamburg"
            participant = "Kundentermin"
            country = Country.DE
            travelDate = LocalDate.now()
            fullDayRateSnapshot = Money.of("28.00")
            arrivalDepartureRateSnapshot = Money.of("14.00")
            calculatedAmount = Money.of("14.00")
            employerContribution = Money.of("0.00")
        }

        assertDoesNotThrow {
            DomainConstraints.validateTravelExpenseEntry(entry)
        }
    }

    @Test
    fun `receipt without checksum is rejected`() {
        val receipt = validReceipt().apply { sha256Checksum = null }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateReceipt(receipt)
        }
    }

    @Test
    fun `receipt with blank file name is rejected`() {
        val receipt = validReceipt().apply { fileName = "  " }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateReceipt(receipt)
        }
    }

    @Test
    fun `receipt with blank object key is rejected`() {
        val receipt = validReceipt().apply { objectKey = "" }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateReceipt(receipt)
        }
    }

    @Test
    fun `receipt with negative size is rejected`() {
        val receipt = validReceipt().apply { sizeBytes = -1L }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateReceipt(receipt)
        }
    }

    @Test
    fun `receipt without uploaded timestamp is rejected`() {
        val receipt = validReceipt().apply { uploadedAt = null }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateReceipt(receipt)
        }
    }

    @Test
    fun `valid receipt passes validation`() {
        assertDoesNotThrow {
            DomainConstraints.validateReceipt(validReceipt())
        }
    }

    private fun validInvoice(): Invoice {
        val sender = SenderProfile().apply {
            name = "Muster GmbH"
            email = de.littletrotter.domain.value.Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = de.littletrotter.domain.value.Iban("DE89370400440532013000")
        }
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-001")
            name = "Kunde AG"
            email = de.littletrotter.domain.value.Email("kunde@kunde-ag.example")
            vatId = VatId("DE987654321")
            iban = de.littletrotter.domain.value.Iban("DE02120300000000202051")
        }
        val item = validInvoiceItem()
        return Invoice().apply {
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-01-00001")
            senderProfile = sender
            this.recipient = recipient
            paymentTermsDays = 14
            totals.netAmount = de.littletrotter.shared.Money(BigDecimal("100.00"))
            totals.vatAmount = de.littletrotter.shared.Money(BigDecimal("19.00"))
            totals.grossAmount = de.littletrotter.shared.Money(BigDecimal("119.00"))
            itemList.asMutableForTest().add(item)
        }
    }

    private fun validInvoiceItem(): InvoiceItem = InvoiceItem().apply {
        positionId = "1"
        description = "Beratung"
        quantity = BigDecimal("1.00")
        unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100.00"))
        netAmount = de.littletrotter.shared.Money(BigDecimal("100.00"))
        vatAmount = de.littletrotter.shared.Money(BigDecimal("19.00"))
        grossAmount = de.littletrotter.shared.Money(BigDecimal("119.00"))
        taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
    }

    private fun validReceipt(): Receipt = Receipt().apply {
        fileName = "receipt.pdf"
        description = "Beleg receipt.pdf"
        objectKey = "receipt-pool/test.pdf"
        sizeBytes = 10L
        sha256Checksum = "a".repeat(64)
        uploadedAt = OffsetDateTime.now()
        documentDate = java.time.LocalDate.of(2026, 3, 14)
    }

    private fun validPaymentTerm(): PaymentTerm = PaymentTerm().apply {
        setName(germanFallbackLanguage(), "14 Tage netto")
        dueInDays = 14
        discountPercent = DiscountPercent(BigDecimal("2.00"))
        discountDays = 10
    }

    private fun validTaxRate(): TaxRate = TaxRate().apply {
        name = "19 %"
        rate = TaxRatePercent(BigDecimal("19.00"))
        country = Country.DE
        validFrom = LocalDate.now().minusDays(1)
        validTo = LocalDate.now().plusYears(1)
        isDefault = true
    }

    private fun validPerDiemRate(): PerDiemRate = PerDiemRate().apply {
        country = Country.DE
        region = null
        fullDayRate = Money.of("28.00")
        arrivalDepartureRate = Money.of("14.00")
        validFrom = LocalDate.now().minusDays(1)
        validTo = LocalDate.now().plusYears(1)
        inlandRules = true
    }

    private fun validArticle(): Article = Article().apply {
        name = "Beratung"
        defaultQuantity = BigDecimal("1.00")
        defaultUnitPriceNet = de.littletrotter.shared.Money.of("100.00")
    }

    private fun validAddress(): Address = Address.forRecipient(Recipient().apply { name = "Kunde" }, Address.AddressType.BILLING, "Musterstrasse 1", "12345", "Musterstadt")
}
