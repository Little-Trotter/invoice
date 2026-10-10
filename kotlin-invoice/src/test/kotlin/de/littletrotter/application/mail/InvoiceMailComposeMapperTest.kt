package de.littletrotter.application.mail

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.LocalDate

/**
 * Unit tests for [InvoiceMailComposeMapper], the domain -> local-model half of
 * the anti-corruption layer (the contract-facing half is
 * `de.littletrotter.infrastructure.mail.acl.MailComposeRequestTranslatorTest`).
 *
 * The rules the mapper still enforces each get their own group here: the document-family carried
 * verbatim from the recipient (every [InvoiceFormat] constant), typed-not-formatted placeholder
 * values, and the "a blank STRING placeholder is omitted, never sent as an empty string" rule.
 */
@DisplayName("InvoiceMailComposeMapper (ACL: Invoice/Recipient -> InvoiceMailDraft)")
class InvoiceMailComposeMapperTest {

    private fun recipient(
        name: String = "Muster GmbH",
        email: String? = "kunde@example.com",
        format: InvoiceFormat = InvoiceFormat.GERMAN,
    ) = Recipient().apply {
        this.name = name
        this.email = email?.let { Email(it) }
        this.invoiceFormat = format
    }

    private fun sender(name: String = "Little Trotter", iban: String? = "DE02120300000000202051") =
        SenderProfile().apply {
            this.name = name
            this.iban = iban?.let { Iban(it) }
        }

    private fun invoice(
        number: String = "RE-2026-0001",
        recipient: Recipient? = recipient(),
        sender: SenderProfile? = sender(),
        invoiceDate: LocalDate = LocalDate.of(2026, 9, 7),
        dueDate: LocalDate? = LocalDate.of(2026, 9, 21),
        gross: Money = Money.of("1234.56"),
        typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
        originalInvoiceNumber: String? = null,
    ) = Invoice().apply {
        this.typeCode = typeCode
        this.originalInvoiceNumber = originalInvoiceNumber
        this.id = 42L
        this.invoiceNumber = InvoiceNumber(number)
        this.recipient = recipient
        this.senderProfile = sender
        this.invoiceDate = invoiceDate
        this.dueDate = dueDate
        this.totals.grossAmount = gross
    }

    // ── Document family ────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(InvoiceFormat::class)
    @DisplayName("every InvoiceFormat is carried through verbatim as the draft's document family")
    fun everyInvoiceFormatIsCarriedThrough(format: InvoiceFormat) {
        val draft = InvoiceMailComposeMapper.toDraft(invoice(recipient = recipient(format = format)))
        assertEquals(format, draft.documentFamily)
    }

    @Test
    @DisplayName("the recipient's invoice format decides the family, not the invoice's own")
    fun familyComesFromTheRecipient() {
        val invoice = invoice(recipient = recipient(format = InvoiceFormat.INTERNATIONAL)).apply {
            // Invoice.invoiceFormat is a different decision entirely (the legal marker for a
            // foreign seller VAT registration) and must not leak into the mail draft.
            this.invoiceFormat = InvoiceFormat.GERMAN
        }
        assertEquals(InvoiceFormat.INTERNATIONAL, InvoiceMailComposeMapper.toDraft(invoice).documentFamily)
    }

    // ── Typed placeholder values ───────────────────────────────────────────────

    @Test
    @DisplayName("placeholder values are typed, never pre-formatted strings")
    fun placeholdersAreTyped() {
        val values = InvoiceMailComposeMapper.toDraft(invoice()).placeholderValues

        val gross = assertInstanceOf(InvoiceMailPlaceholderValue.Amount::class.java, values["grossTotal"])
        assertEquals(Money.of("1234.56"), gross.value)

        val invoiceDate = assertInstanceOf(InvoiceMailPlaceholderValue.Date::class.java, values["invoiceDate"])
        assertEquals(LocalDate.of(2026, 9, 7), invoiceDate.value)

        val dueDate = assertInstanceOf(InvoiceMailPlaceholderValue.Date::class.java, values["dueDate"])
        assertEquals(LocalDate.of(2026, 9, 21), dueDate.value)

        assertInstanceOf(InvoiceMailPlaceholderValue.Text::class.java, values["invoiceNumber"])
        assertInstanceOf(InvoiceMailPlaceholderValue.Text::class.java, values["recipientName"])
        assertInstanceOf(InvoiceMailPlaceholderValue.Text::class.java, values["senderName"])
        assertInstanceOf(InvoiceMailPlaceholderValue.Text::class.java, values["iban"])
    }

    @Test
    @DisplayName("placeholder keys are the component's template keys, in its declaration order")
    fun placeholderKeys() {
        val values = InvoiceMailComposeMapper.toDraft(invoice()).placeholderValues
        assertEquals(
            listOf("invoiceNumber", "invoiceDate", "dueDate", "grossTotal", "recipientName", "senderName", "iban"),
            values.keys.toList(),
        )
    }

    // ── The blank-string rule ──────────────────────────────────────────────────

    @Test
    @DisplayName("a blank STRING placeholder is omitted, never sent as an empty value")
    fun blankStringsAreOmitted() {
        val values = InvoiceMailComposeMapper.toDraft(
            invoice(sender = sender(name = "   ", iban = null)),
        ).placeholderValues

        assertFalse(values.containsKey("senderName"), "a blank sender name must be omitted, not sent as \"\"")
        assertFalse(values.containsKey("iban"), "an absent IBAN must be omitted, not sent as \"\"")
        assertTrue(values.values.none { it is InvoiceMailPlaceholderValue.Text && it.value.isBlank() })
    }

    @Test
    @DisplayName("an absent due date omits its key so the template's optional payment section drops out")
    fun absentDueDateIsOmitted() {
        val values = InvoiceMailComposeMapper.toDraft(invoice(dueDate = null)).placeholderValues
        assertFalse(values.containsKey("dueDate"))
        assertTrue(values.containsKey("invoiceDate"))
    }

    @Test
    @DisplayName("a credit note omits the due date even when one is stored")
    fun creditNoteOmitsDueDate() {
        val values = InvoiceMailComposeMapper.toDraft(invoice(typeCode = InvoiceTypeCode.CORRECTED_INVOICE, originalInvoiceNumber = "RE-2026-0001")).placeholderValues
        assertFalse(values.containsKey("dueDate"))
    }

    @Test
    @DisplayName("text placeholder values are trimmed")
    fun textIsTrimmed() {
        val values = InvoiceMailComposeMapper.toDraft(
            invoice(sender = sender(name = "  Little Trotter  ")),
        ).placeholderValues
        assertEquals(InvoiceMailPlaceholderValue.Text("Little Trotter"), values["senderName"])
    }

    // ── Attachments ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("attachments are passed through unchanged, with all five handover fields")
    fun attachmentsArePassedThrough() {
        val attachment = InvoiceMailAttachment(
            filename = "Rechnung-RE-2026-0001.pdf",
            contentType = "application/pdf",
            sizeBytes = 84_213L,
            sha256 = "a".repeat(64),
            downloadUrl = "http://rustfs:9000/invoice-documents/2026/RE-2026-0001.pdf?X-Amz-Signature=deadbeef",
        )
        val draft = InvoiceMailComposeMapper.toDraft(invoice(), listOf(attachment))

        assertEquals(1, draft.attachments.size)
        val actual = draft.attachments.single()
        assertEquals("Rechnung-RE-2026-0001.pdf", actual.filename)
        assertEquals("application/pdf", actual.contentType)
        assertEquals(84_213L, actual.sizeBytes)
        assertEquals("a".repeat(64), actual.sha256)
        assertTrue(actual.downloadUrl.contains("X-Amz-Signature"), "the handover carries a presigned URL, not a bucket key")
    }

    @Test
    @DisplayName("no attachment is the default — a reminder mail carries none")
    fun attachmentsMayBeEmpty() {
        assertTrue(InvoiceMailComposeMapper.toDraft(invoice()).attachments.isEmpty())
    }

    // ── Refusals ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an invoice without a recipient is refused, not mapped into an empty address")
    fun refusesMissingRecipient() {
        val e = assertThrows(MailDispatchException::class.java) {
            InvoiceMailComposeMapper.toDraft(invoice(recipient = null))
        }
        assertTrue(e.message!!.contains("no recipient"))
    }

    @Test
    @DisplayName("a recipient without an email address is refused")
    fun refusesMissingEmail() {
        val e = assertThrows(MailDispatchException::class.java) {
            InvoiceMailComposeMapper.toDraft(invoice(recipient = recipient(email = null)))
        }
        assertTrue(e.message!!.contains("no email address"))
    }

    @Test
    @DisplayName("an invoice without a number is refused — the reference key is what the result comes back on")
    fun refusesMissingInvoiceNumber() {
        val e = assertThrows(MailDispatchException::class.java) {
            InvoiceMailComposeMapper.toDraft(invoice(number = ""))
        }
        assertTrue(e.message!!.contains("reference key"))
    }

    @Test
    @DisplayName("the reference key is the invoice number, and the recipient's own fields are carried verbatim")
    fun carriesReferenceKeyAndRecipient() {
        val draft = InvoiceMailComposeMapper.toDraft(invoice())
        assertEquals("RE-2026-0001", draft.referenceKey)
        assertEquals("kunde@example.com", draft.recipientAddress.value)
        assertEquals("Muster GmbH", draft.recipientName)
    }

    // ── Sender block and sender placeholders ───────────────────────────────────

    private fun fullSender() = sender().apply {
        phone = " +49 2651 123456 "
        email = Email("info@example.com")
        website = "https://example.com"
        registerCourt = "AG Koblenz"
        commercialRegisterNumber = "HRB 1234"
        managingDirector = "Erika Muster"
        vatId = VatId.of("DE123456789")
    }

    @Test
    fun `sender contact data becomes placeholders`() {
        val values = InvoiceMailComposeMapper.toDraft(invoice(sender = fullSender())).placeholderValues
        assertEquals(InvoiceMailPlaceholderValue.Text("+49 2651 123456"), values["senderPhone"])
        assertEquals(InvoiceMailPlaceholderValue.Text("info@example.com"), values["senderEmail"])
        assertEquals(InvoiceMailPlaceholderValue.Text("https://example.com"), values["senderWebsite"])
    }

    @Test
    fun `a German recipient gets a German signature block`() {
        val block = InvoiceMailComposeMapper.toDraft(invoice(sender = fullSender())).senderBlock
        assertEquals("Little Trotter", block?.name)
        assertEquals("+49 2651 123456", block?.phone)
        assertEquals("info@example.com", block?.email)
        assertEquals("https://example.com", block?.website)
        assertEquals(listOf("AG Koblenz HRB 1234", "Geschäftsführer: Erika Muster", "USt-IdNr.: DE123456789"), block?.legalLines)
    }

    @Test
    fun `an international recipient gets English labels`() {
        val block = InvoiceMailComposeMapper.toDraft(
            invoice(recipient = recipient(format = InvoiceFormat.INTERNATIONAL), sender = fullSender()),
        ).senderBlock
        assertEquals(listOf("AG Koblenz HRB 1234", "Managing director: Erika Muster", "VAT ID: DE123456789"), block?.legalLines)
    }

    @Test
    fun `a bare sender yields a name-only block`() {
        val block = InvoiceMailComposeMapper.toDraft(invoice()).senderBlock
        assertEquals("Little Trotter", block?.name)
        assertEquals(null, block?.phone)
        assertEquals(emptyList<String>(), block?.legalLines)
    }

    @Test
    fun `no sender or a blank sender name yields no block`() {
        assertEquals(null, InvoiceMailComposeMapper.toDraft(invoice(sender = null)).senderBlock)
        assertEquals(null, InvoiceMailComposeMapper.toDraft(invoice(sender = sender(name = "  "))).senderBlock)
    }

    @Test
    fun `a sole proprietor without register entry prints no register line`() {
        val sole = fullSender().apply {
            legalForm = LegalForm.SOLE_PROPRIETOR
            craftsChamberRegistered = true
        }
        val block = InvoiceMailComposeMapper.toDraft(invoice(sender = sole)).senderBlock
        assertEquals(listOf("Geschäftsführer: Erika Muster", "USt-IdNr.: DE123456789"), block?.legalLines)
    }

    @Test
    fun `a register court without a number is printed alone, blanks are dropped`() {
        val partial = sender().apply {
            registerCourt = "AG Koblenz"
            managingDirector = "  "
        }
        assertEquals(listOf("AG Koblenz"), InvoiceMailComposeMapper.toDraft(invoice(sender = partial)).senderBlock?.legalLines)
    }
}
