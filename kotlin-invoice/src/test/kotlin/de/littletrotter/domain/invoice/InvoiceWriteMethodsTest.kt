package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.document.DocumentLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The write methods of [Invoice]: each one is the only way to change its group of fields from
 * outside the aggregate, so its invariant and its edge case are pinned here.
 */
class InvoiceWriteMethodsTest {

    private fun rate19() = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19.00")) }

    // ── assignNumber ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `assignNumber stores number, prefix and sequence together`() {
        val invoice = Invoice()

        invoice.assignNumber(InvoiceNumber("RE-2026-04-00007"), "RE", 7L)

        assertEquals(InvoiceNumber("RE-2026-04-00007"), invoice.invoiceNumber)
        assertEquals("RE", invoice.invoiceNumberPrefix)
        assertEquals(7L, invoice.invoiceNumberSequence)
    }

    @Test
    fun `assignNumber a second time throws and keeps the first number`() {
        val invoice = Invoice()
        invoice.assignNumber(InvoiceNumber("RE-2026-04-00007"), "RE", 7L)

        assertThrows<IllegalStateException> {
            invoice.assignNumber(InvoiceNumber("RE-2026-04-00008"), "RE", 8L)
        }

        assertEquals(InvoiceNumber("RE-2026-04-00007"), invoice.invoiceNumber)
        assertEquals(7L, invoice.invoiceNumberSequence)
    }

    @Test
    fun `assignNumber with the unassigned number leaves the invoice assignable`() {
        val invoice = Invoice()

        invoice.assignNumber(InvoiceNumber.UNASSIGNED, null, null)
        invoice.assignNumber(InvoiceNumber("RE-2026-04-00001"), "RE", 1L)

        assertTrue(invoice.invoiceNumber.isAssigned())
    }

    // ── updateHeader ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateHeader replaces every header value as one group`() {
        val invoice = anInvoice(currency = Currency.EUR, paymentTermsDays = 30)

        invoice.updateHeader(
            InvoiceHeaderChange(
                invoiceDate = LocalDate.of(2026, 4, 6),
                servicePeriodFrom = LocalDate.of(2026, 4, 1),
                servicePeriodTo = LocalDate.of(2026, 4, 5),
                deliveryDate = LocalDate.of(2026, 4, 5),
                dueDate = LocalDate.of(2026, 4, 20),
                paymentTermsDays = 14,
                currency = Currency.USD,
                invoiceFormat = InvoiceFormat.INTERNATIONAL,
                sellerVatRegistrationCountry = Country.LU,
            ),
        )

        assertEquals(LocalDate.of(2026, 4, 6), invoice.invoiceDate)
        assertEquals(LocalDate.of(2026, 4, 1), invoice.servicePeriodFrom)
        assertEquals(LocalDate.of(2026, 4, 5), invoice.servicePeriodTo)
        assertEquals(LocalDate.of(2026, 4, 5), invoice.deliveryDate)
        assertEquals(LocalDate.of(2026, 4, 20), invoice.dueDate)
        assertEquals(14, invoice.paymentTermsDays)
        assertEquals(Currency.USD, invoice.currency)
        assertEquals(InvoiceFormat.INTERNATIONAL, invoice.invoiceFormat)
        assertEquals(Country.LU, invoice.sellerVatRegistrationCountry)
    }

    @Test
    fun `updateHeader with a change that omits optional values clears them`() {
        val invoice = anInvoice(
            servicePeriodFrom = LocalDate.of(2026, 4, 1),
            dueDate = LocalDate.of(2026, 4, 20),
            sellerVatRegistrationCountry = Country.LU,
        )

        invoice.updateHeader(InvoiceHeaderChange(invoiceDate = LocalDate.of(2026, 4, 6)))

        assertNull(invoice.servicePeriodFrom)
        assertNull(invoice.dueDate)
        assertNull(invoice.sellerVatRegistrationCountry)
        assertEquals(Invoice.DEFAULT_PAYMENT_TERMS_DAYS, invoice.paymentTermsDays)
    }

    @Test
    fun `headerChange read back from the invoice is the identity for updateHeader`() {
        val invoice = anInvoice(
            invoiceDate = LocalDate.of(2026, 4, 6),
            dueDate = LocalDate.of(2026, 4, 20),
            paymentTermsDays = 7,
        )
        val before = invoice.headerChange()

        invoice.updateHeader(before)

        assertEquals(before, invoice.headerChange())
    }

    // ── assignParties ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `assignParties replaces the parties and the party-derived choices`() {
        val sender = aSenderProfile { name = "Sender" }
        val recipient = aRecipient { name = "Recipient" }
        val term = PaymentTerm()
        val invoice = Invoice()

        invoice.assignParties(
            InvoiceParties(
                senderProfile = sender,
                recipient = recipient,
                paymentTerm = term,
                documentLanguage = "en",
            ),
        )

        assertSame(sender, invoice.senderProfile)
        assertSame(recipient, invoice.recipient)
        assertSame(term, invoice.paymentTerm)
        assertEquals(DocumentLanguage.EN, invoice.documentLanguage)
    }

    @Test
    fun `assignParties with empty parties clears a previous assignment`() {
        val invoice = anInvoice(senderProfile = SenderProfile(), recipient = Recipient(), documentLanguage = "en")

        invoice.assignParties(InvoiceParties(senderProfile = null, recipient = null))

        assertNull(invoice.senderProfile)
        assertNull(invoice.recipient)
        assertEquals(DocumentLanguage.DE, invoice.documentLanguage)
    }

    // ── decideReverseCharge ─────────────────────────────────────────────

    @Test
    fun `decideReverseCharge sets and clears the header decision independent of the items`() {
        val invoice = Invoice()
        assertFalse(invoice.reverseCharge)

        invoice.decideReverseCharge(true)
        assertTrue(invoice.reverseCharge)
        assertFalse(invoice.hasReverseChargeItems())

        invoice.decideReverseCharge(false)
        assertFalse(invoice.reverseCharge)
    }

    // ── recordMailSent / linkToQuote ───────────────────────────────────────────────────────────

    @Test
    fun `recordMailSent flags the invoice and keeps the instant`() {
        val invoice = Invoice()
        assertNull(invoice.mailSent)
        assertNull(invoice.mailSentAt)
        val at = OffsetDateTime.of(2026, 4, 6, 10, 15, 0, 0, ZoneOffset.UTC)

        invoice.recordMailSent(at)

        assertEquals(true, invoice.mailSent)
        assertEquals(at, invoice.mailSentAt)
    }

    @Test
    fun `recordMailSent a second time moves the instant to the latest dispatch`() {
        val invoice = Invoice()
        val first = OffsetDateTime.of(2026, 4, 6, 10, 15, 0, 0, ZoneOffset.UTC)
        val second = first.plusDays(1)

        invoice.recordMailSent(first)
        invoice.recordMailSent(second)

        assertEquals(second, invoice.mailSentAt)
        assertEquals(true, invoice.mailSent)
    }

    @Test
    fun `linkToQuote records the source quote and a second link is rejected`() {
        val invoice = Invoice()
        assertNull(invoice.sourceQuoteId)
        val first = UUID.randomUUID()

        invoice.linkToQuote(first)
        assertEquals(first, invoice.sourceQuoteId)

        assertThrows<IllegalStateException> { invoice.linkToQuote(UUID.randomUUID()) }
        assertEquals(first, invoice.sourceQuoteId)
    }

    // ── draft ──────────────────────────────────────────────────────────────────────────────────

    private fun noteSnapshot(value: String?) = InvoiceNoteSnapshot(value, InvoiceNoteOrigin.MANUAL, 5L, "en", "default")

    @Test
    fun `draft creates an invoice that is complete from its first moment`() {
        val sender = aSenderProfile { name = "Sender" }
        val invoice = Invoice.draft(
            InvoiceDraft(
                parties = InvoiceParties(senderProfile = sender, recipient = null, documentLanguage = "en"),
                header = InvoiceHeaderChange(invoiceDate = LocalDate.of(2026, 4, 6), paymentTermsDays = 21),
                invoiceNote = noteSnapshot("seller note"),
                consumerNote = noteSnapshot("consumer note"),
                buyerReference = "991-01484-64",
                freeText = "free",
                reverseCharge = true,
            ),
        )

        assertEquals(InvoiceStatus.DRAFT, invoice.status)
        assertSame(sender, invoice.senderProfile)
        assertEquals(DocumentLanguage.EN, invoice.documentLanguage)
        assertEquals(LocalDate.of(2026, 4, 6), invoice.invoiceDate)
        assertEquals(21, invoice.paymentTermsDays)
        assertEquals("seller note", invoice.texts.invoiceNote)
        assertEquals("consumer note", invoice.texts.consumerNote)
        assertEquals("991-01484-64", invoice.references.buyerReference)
        assertEquals("free", invoice.texts.freeText)
        assertTrue(invoice.reverseCharge)
        assertEquals(InvoiceTypeCode.COMMERCIAL_INVOICE, invoice.typeCode)
        assertEquals(ValidationStatus.NOT_VALIDATED, invoice.validationStatus)
    }

    @Test
    fun `draft born in another status or type code keeps them`() {
        val invoice = Invoice.draft(
            InvoiceDraft(
                parties = InvoiceParties(senderProfile = null, recipient = null),
                header = InvoiceHeaderChange(invoiceDate = LocalDate.of(2026, 4, 6)),
                invoiceNote = noteSnapshot(null),
                consumerNote = noteSnapshot(null),
                status = InvoiceStatus.ISSUED,
                typeCode = InvoiceTypeCode.CORRECTED_INVOICE,
                originalInvoiceNumber = "RE-2026-04-00001",
                validationStatus = ValidationStatus.VALID,
            ),
        )

        assertEquals(InvoiceStatus.ISSUED, invoice.status)
        assertEquals(InvoiceTypeCode.CORRECTED_INVOICE, invoice.typeCode)
        assertEquals("RE-2026-04-00001", invoice.originalInvoiceNumber?.value)
        assertEquals(ValidationStatus.VALID, invoice.validationStatus)
        assertNull(invoice.references.buyerReference)
    }

    // ── creditNoteFor ──────────────────────────────────────────────────────────────────────────

    private fun original(): Invoice = anInvoice(
        id = 5L,
        invoiceNumber = InvoiceNumber("RE-2026-04-00001"),
        invoiceDate = LocalDate.of(2026, 4, 6),
        servicePeriodFrom = LocalDate.of(2026, 4, 1),
        servicePeriodTo = LocalDate.of(2026, 4, 5),
        deliveryDate = LocalDate.of(2026, 4, 5),
        recipient = aRecipient { name = "Recipient" },
        currency = Currency.USD,
        documentLanguage = "en",
        paymentTermsDays = 30,
        reverseCharge = true,
        references = InvoiceReferencesChange(buyerReference = "BR-1", projectNumber = "P-9"),
        invoiceNote = "kept note",
        items = listOf(
            anInvoiceItem(
                positionId = "1",
                description = "Beratung",
                quantity = BigDecimal("2"),
                unitPriceNet = Money.of("100.00"),
                taxRateRef = rate19(),
            ),
        ),
        recalculate = true,
    )

    @Test
    fun `creditNoteFor builds a cancellation document that references the original`() {
        val original = original()
        val sender = aSenderProfile { name = "Sender" }

        val cancellation = Invoice.creditNoteFor(
            original,
            sender,
            CreditNoteDetails("RE-2026-04-00001", LocalDate.of(2026, 4, 20), "Storno wegen Fehler"),
        )

        assertEquals(InvoiceTypeCode.CORRECTED_INVOICE, cancellation.typeCode)
        assertEquals("384", cancellation.typeCode.code)
        assertEquals(InvoiceStatus.CANCELLED, cancellation.status)
        assertEquals("RE-2026-04-00001", cancellation.originalInvoiceNumber?.value)
        assertEquals(LocalDate.of(2026, 4, 6), cancellation.originalInvoiceDate)
        assertEquals(LocalDate.of(2026, 4, 20), cancellation.invoiceDate)
        assertSame(sender, cancellation.senderProfile)
        assertSame(original.recipient, cancellation.recipient)
        assertEquals(0, cancellation.paymentTermsDays)
        assertEquals("Storno wegen Fehler", cancellation.texts.freeText)
        assertEquals("kept note", cancellation.texts.invoiceNote)
        assertEquals(Currency.USD, cancellation.currency)
        assertEquals(DocumentLanguage.EN, cancellation.documentLanguage)
        assertTrue(cancellation.reverseCharge)
        assertEquals("BR-1", cancellation.references.buyerReference)
        assertEquals("P-9", cancellation.references.projectNumber)
        assertEquals(LocalDate.of(2026, 4, 1), cancellation.servicePeriodFrom)
        assertEquals(LocalDate.of(2026, 4, 5), cancellation.deliveryDate)
        assertTrue(cancellation.isReadOnly())
    }

    @Test
    fun `creditNoteFor negates the items and the totals and leaves the original untouched`() {
        val original = original()

        val cancellation = Invoice.creditNoteFor(
            original,
            SenderProfile(),
            CreditNoteDetails("RE-2026-04-00001", LocalDate.of(2026, 4, 20), ""),
        )

        assertEquals(1, cancellation.itemList.size)
        assertEquals(Money.of("-100.00"), cancellation.itemList.single().unitPriceNet)
        assertEquals(0, cancellation.totals.netAmount.amount.compareTo(BigDecimal("-200.00")))
        assertEquals(0, cancellation.totals.vatAmount.amount.compareTo(BigDecimal("-38.00")))
        assertEquals(0, cancellation.totals.grossAmount.amount.compareTo(BigDecimal("-238.00")))
        assertEquals(Money.of("100.00"), original.itemList.single().unitPriceNet)
        assertEquals(0, original.totals.netAmount.amount.compareTo(BigDecimal("200.00")))
        assertFalse(cancellation.invoiceNumber.isAssigned(), "the storno draws its own number later")
    }

    // ── freezePaymentTermSnapshot ──────────────────────────────────────────────────────────────

    @Test
    fun `freezePaymentTermSnapshot without a payment term freezes nothing`() {
        val invoice = Invoice()

        invoice.freezePaymentTermSnapshot()

        assertNull(invoice.paymentTermSnapshot)
    }

    @Test
    fun `freezePaymentTermSnapshot freezes the live term once and ignores a later term`() {
        val invoice = anInvoice(paymentTerm = aPaymentTerm(dueInDays = 5))

        invoice.freezePaymentTermSnapshot()
        val frozen = invoice.paymentTermSnapshot
        invoice.assignParties(invoice.parties().copy(paymentTerm = aPaymentTerm(dueInDays = 99)))
        invoice.freezePaymentTermSnapshot()

        assertEquals(5, frozen?.dueInDays)
        assertSame(frozen, invoice.paymentTermSnapshot)
    }
}
