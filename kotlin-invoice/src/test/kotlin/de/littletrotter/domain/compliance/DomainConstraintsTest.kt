package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aReceipt
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.shared.Money
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.germanFallbackLanguage
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
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.PersistenceEmulation
import de.littletrotter.fixture.edit

class DomainConstraintsTest {

    // The number is mandatory only for an ISSUED invoice; a draft without a number is the normal
    // case (allocation happens only on the transition to ISSUED, not on the draft) — see
    // Invoice.validate().
    @Test
    fun `a draft with a blank invoice number is not rejected`() {
        val invoice = validInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("   "))

        assertDoesNotThrow { DomainConstraints.validateInvoice(invoice) }
    }

    @Test
    fun `an ISSUED invoice with a blank invoice number is rejected`() {
        val invoice = validInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("   ")).apply {
            forceStatusForTest(de.littletrotter.domain.invoice.InvoiceStatus.ISSUED)
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice without sender profile is rejected`() {
        val invoice = validInvoice().edit { senderProfile = null }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice without recipient is rejected`() {
        val invoice = validInvoice().edit { recipient = null }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with negative net amount is rejected`() {
        val invoice = PersistenceEmulation.assignTotals(
            validInvoice(),
            de.littletrotter.domain.invoice.InvoiceTotals(de.littletrotter.shared.Money(BigDecimal("-0.01")), de.littletrotter.shared.Money.ZERO, de.littletrotter.shared.Money.ZERO),
        )

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with negative vat amount is rejected`() {
        val invoice = PersistenceEmulation.assignTotals(
            validInvoice(),
            de.littletrotter.domain.invoice.InvoiceTotals(de.littletrotter.shared.Money.ZERO, de.littletrotter.shared.Money(BigDecimal("-0.01")), de.littletrotter.shared.Money.ZERO),
        )

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with negative gross amount is rejected`() {
        val invoice = PersistenceEmulation.assignTotals(
            validInvoice(),
            de.littletrotter.domain.invoice.InvoiceTotals(de.littletrotter.shared.Money.ZERO, de.littletrotter.shared.Money.ZERO, de.littletrotter.shared.Money(BigDecimal("-0.01"))),
        )

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
        val invoice = validInvoice().edit { paymentTermsDays = -1 }

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
        val invoice = validInvoice().edit {
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
        val invoice = validInvoice().edit {
            invoiceDate = LocalDate.of(2026, 3, 10)
            dueDate = LocalDate.of(2026, 3, 10)
        }

        assertDoesNotThrow {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with service end before service start is rejected`() {
        val invoice = validInvoice().edit {
            servicePeriodFrom = LocalDate.of(2026, 3, 20)
            servicePeriodTo = LocalDate.of(2026, 3, 19)
        }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with service period boundaries equal is accepted`() {
        val invoice = validInvoice().edit {
            servicePeriodFrom = LocalDate.of(2026, 3, 20)
            servicePeriodTo = LocalDate.of(2026, 3, 20)
        }

        assertDoesNotThrow {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with valid structured invoice number is accepted`() {
        val invoice = validInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("ACME-2026-03-00042"))

        assertDoesNotThrow {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with recipient without customer number is rejected`() {
        val invoice = validInvoice().edit { recipient = recipient?.edit { customerNumber = null } }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with recipient without name is rejected`() {
        val invoice = validInvoice().edit { recipient = recipient?.edit { name = "" } }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with sender profile without name is rejected`() {
        val invoice = validInvoice().edit { senderProfile = senderProfile?.edit { name = "  " } }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    @Test
    fun `invoice with sender profile without invoice prefix is rejected`() {
        val invoice = validInvoice().edit { senderProfile = senderProfile?.edit { invoicePrefix = "" } }

        assertThrows(InvoiceValidationException::class.java) {
            DomainConstraints.validateInvoice(invoice)
        }
    }

    // an out-of-range percentage now throws at TaxRatePercent construction, before this
    // item could ever reach DomainConstraints.validateInvoiceItem.
    @Test
    fun `constructing a tax rate for an invoice item with an invalid percentage throws immediately`() {
        assertThrows(InvalidParameterException::class.java) {
            TaxRate().edit { rate = TaxRatePercent(BigDecimal("120.00")) }
        }
    }

    @Test
    fun `invoice item with negative quantity is rejected`() {
        val item = validInvoiceItem().edit { quantity = BigDecimal("-1.00") }

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
        val term = validPaymentTerm().apply { changeName(germanFallbackLanguage(), "   ") }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validatePaymentTerm(term)
        }
    }

    @Test
    fun `payment term with negative due days is rejected`() {
        val term = validPaymentTerm().edit { dueInDays = -1 }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validatePaymentTerm(term)
        }
    }

    @Test
    fun `payment term with negative discount days is rejected`() {
        val term = validPaymentTerm().edit { discountDays = -5 }

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
        val taxRate = validTaxRate().edit { name = " " }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateTaxRate(taxRate)
        }
    }

    // an out-of-range percentage now throws at TaxRatePercent construction, before this
    // rate could ever reach DomainConstraints.validateTaxRate.
    @Test
    fun `constructing a tax rate with an excessive percentage throws immediately`() {
        assertThrows(InvalidParameterException::class.java) {
            validTaxRate().edit { rate = TaxRatePercent(BigDecimal("120.00")) }
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
        val rate = validPerDiemRate().edit { fullDayRate = Money.of("-1.00") }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validatePerDiemRate(rate)
        }
    }

    @Test
    fun `per diem rate with invalid country code is rejected`() {
        val rate = validPerDiemRate().edit { country = null }

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
        val article = validArticle().edit { name = "   " }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateArticle(article, today = FIXED_TODAY)
        }
    }

    @Test
    fun `article with negative default quantity is rejected`() {
        val article = validArticle().edit { defaultQuantity = BigDecimal("-1.0") }

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
        val address = validAddress().edit { street = "" }

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
        val entry = TravelExpenseEntry().edit {
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
        val entry = TravelExpenseEntry().edit {
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
        val entry = TravelExpenseEntry().edit {
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
        val entry = TravelExpenseEntry().edit {
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
        val receipt = validReceipt().edit { sha256Checksum = null }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateReceipt(receipt)
        }
    }

    @Test
    fun `receipt with blank file name is rejected`() {
        val receipt = validReceipt().edit { fileName = "  " }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateReceipt(receipt)
        }
    }

    @Test
    fun `receipt with blank object key is rejected`() {
        val receipt = validReceipt().edit { objectKey = "" }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateReceipt(receipt)
        }
    }

    @Test
    fun `receipt with negative size is rejected`() {
        val receipt = validReceipt().edit { sizeBytes = -1L }

        assertThrows(InvalidParameterException::class.java) {
            DomainConstraints.validateReceipt(receipt)
        }
    }

    @Test
    fun `receipt without uploaded timestamp is rejected`() {
        val receipt = validReceipt().edit { uploadedAt = null }

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

    private fun validInvoice(
        invoiceNumber: de.littletrotter.domain.invoice.InvoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-01-00001"),
    ): Invoice {
        val sender = aSenderProfile {
            name = "Muster GmbH"
            email = de.littletrotter.domain.value.Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = de.littletrotter.domain.value.Iban("DE89370400440532013000")
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-001")
            name = "Kunde AG"
            email = de.littletrotter.domain.value.Email("kunde@kunde-ag.example")
            vatId = VatId("DE987654321")
            iban = de.littletrotter.domain.value.Iban("DE02120300000000202051")
        }
        return anInvoice(
            invoiceNumber = invoiceNumber,
            senderProfile = sender,
            recipient = recipient,
            paymentTermsDays = 14,
            items = listOf(validInvoiceItem()),
            recalculate = true,
        )
    }

    private fun validInvoiceItem(): InvoiceItem = anInvoiceItem(
        positionId = "1",
        description = "Beratung",
        quantity = BigDecimal("1.00"),
        unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100.00")),
        taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19.00")) },
    ).also { it.calculateAmounts() }

    private fun validReceipt(): Receipt = aReceipt {
        fileName = "receipt.pdf"
        description = "Beleg receipt.pdf"
        objectKey = "receipt-pool/test.pdf"
        sizeBytes = 10L
        sha256Checksum = "a".repeat(64)
        uploadedAt = OffsetDateTime.now()
        documentDate = java.time.LocalDate.of(2026, 3, 14)
    }

    private fun validPaymentTerm(): PaymentTerm = aPaymentTerm {
        changeName(germanFallbackLanguage(), "14 Tage netto")
        dueInDays = 14
        discountPercent = DiscountPercent(BigDecimal("2.00"))
        discountDays = 10
    }

    private fun validTaxRate(): TaxRate = TaxRate().edit {
        name = "19 %"
        rate = TaxRatePercent(BigDecimal("19.00"))
        country = Country.DE
        validFrom = LocalDate.now().minusDays(1)
        validTo = LocalDate.now().plusYears(1)
        isDefault = true
    }

    private fun validPerDiemRate(): PerDiemRate = PerDiemRate().edit {
        country = Country.DE
        region = null
        fullDayRate = Money.of("28.00")
        arrivalDepartureRate = Money.of("14.00")
        validFrom = LocalDate.now().minusDays(1)
        validTo = LocalDate.now().plusYears(1)
        inlandRules = true
    }

    private fun validArticle(): Article = anArticle {
        name = "Beratung"
        defaultQuantity = BigDecimal("1.00")
        defaultUnitPriceNet = de.littletrotter.shared.Money.of("100.00")
    }

    private fun validAddress(): Address = Address.forRecipient(aRecipient { name = "Kunde" }, Address.AddressType.BILLING, "Musterstrasse 1", "12345", "Musterstadt")
}
