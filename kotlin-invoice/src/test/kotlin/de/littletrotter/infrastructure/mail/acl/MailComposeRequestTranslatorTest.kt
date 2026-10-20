package de.littletrotter.infrastructure.mail.acl

import de.littletrotter.application.mail.InvoiceMailAttachment
import de.littletrotter.application.mail.InvoiceMailDraft
import de.littletrotter.application.mail.InvoiceMailPlaceholderValue
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.Email
import de.littletrotter.shared.Money
import de.littletrotter.shared.mail.MailComposeRequest
import de.littletrotter.shared.mail.MailContract
import de.littletrotter.shared.mail.MailDocumentFamily
import de.littletrotter.shared.mail.MailDocumentKind
import de.littletrotter.shared.mail.MailPlaceholderCatalogue
import de.littletrotter.shared.mail.MailPlaceholderValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.LocalDate

/**
 * Unit tests for the outbound (compose) translator, the contract-facing half of
 * the anti-corruption layer that used to live in `InvoiceMailComposeMapper`
 * ([de.littletrotter.application.mail.InvoiceMailComposeMapperTest] covers the domain -> local-model
 * half that remains there).
 */
@DisplayName("MailComposeRequestTranslator (InvoiceMailDraft -> MailComposeRequest)")
class MailComposeRequestTranslatorTest {

    private fun draft(
        family: InvoiceFormat = InvoiceFormat.GERMAN,
        placeholderValues: Map<String, InvoiceMailPlaceholderValue> = emptyMap(),
        attachments: List<InvoiceMailAttachment> = emptyList(),
    ) = InvoiceMailDraft(
        referenceKey = "RE-2026-0001",
        documentFamily = family,
        recipientAddress = Email("kunde@example.com"),
        recipientName = "Muster GmbH",
        placeholderValues = placeholderValues,
        attachments = attachments,
    )

    // ── Document family — every InvoiceFormat branch ──────────────────────────

    @ParameterizedTest
    @EnumSource(InvoiceFormat::class)
    @DisplayName("every InvoiceFormat maps to a MailDocumentFamily of the same name")
    fun everyInvoiceFormatMaps(format: InvoiceFormat) {
        val request = MailComposeRequestTranslator.toComposeRequest(draft(family = format))
        assertEquals(MailDocumentFamily.valueOf(format.name), request.documentFamily)
    }

    // ── Document kind ────────────────────────────────────────────────────────

    @Test
    @DisplayName("every request is composed for the INVOICE_DISPATCH document kind — this host has no other")
    fun setsInvoiceDispatchDocumentKind() {
        val request = MailComposeRequestTranslator.toComposeRequest(draft())
        assertEquals(MailDocumentKind.INVOICE_DISPATCH, request.documentKind)
    }

    // ── Placeholder values — every kind ────────────────────────────────────────

    @Test
    @DisplayName("a Text placeholder maps to MailPlaceholderValue.Text")
    fun mapsText() {
        val request = MailComposeRequestTranslator.toComposeRequest(
            draft(placeholderValues = mapOf("iban" to InvoiceMailPlaceholderValue.Text("DE02120300000000202051"))),
        )
        val value = assertInstanceOf(MailPlaceholderValue.Text::class.java, request.placeholderValues["iban"])
        assertEquals("DE02120300000000202051", value.value)
    }

    @Test
    @DisplayName("a Date placeholder maps to MailPlaceholderValue.Date")
    fun mapsDate() {
        val request = MailComposeRequestTranslator.toComposeRequest(
            draft(placeholderValues = mapOf("invoiceDate" to InvoiceMailPlaceholderValue.Date(LocalDate.of(2026, 9, 7)))),
        )
        val value = assertInstanceOf(MailPlaceholderValue.Date::class.java, request.placeholderValues["invoiceDate"])
        assertEquals(LocalDate.of(2026, 9, 7), value.value)
    }

    @Test
    @DisplayName("an Amount placeholder maps to MailPlaceholderValue.Amount")
    fun mapsAmount() {
        val request = MailComposeRequestTranslator.toComposeRequest(
            draft(placeholderValues = mapOf("grossTotal" to InvoiceMailPlaceholderValue.Amount(Money.of("1234.56")))),
        )
        val value = assertInstanceOf(MailPlaceholderValue.Amount::class.java, request.placeholderValues["grossTotal"])
        assertEquals(Money.of("1234.56"), value.value)
    }

    @Test
    @DisplayName("an empty placeholder map stays empty")
    fun emptyPlaceholdersStayEmpty() {
        assertTrue(MailComposeRequestTranslator.toComposeRequest(draft()).placeholderValues.isEmpty())
    }

    // ── Attachments ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an attachment's five fields are carried through unchanged")
    fun mapsAttachment() {
        val attachment = InvoiceMailAttachment(
            filename = "Rechnung-RE-2026-0001.pdf",
            contentType = "application/pdf",
            sizeBytes = 84_213L,
            sha256 = "a".repeat(64),
            downloadUrl = "http://rustfs:9000/invoice-documents/x.pdf?sig=1",
        )
        val request = MailComposeRequestTranslator.toComposeRequest(draft(attachments = listOf(attachment)))

        val actual = request.attachments.single()
        assertEquals("Rechnung-RE-2026-0001.pdf", actual.filename)
        assertEquals("application/pdf", actual.contentType)
        assertEquals(84_213L, actual.sizeBytes)
        assertEquals("a".repeat(64), actual.sha256)
        assertEquals(attachment.downloadUrl, actual.downloadUrl)
    }

    @Test
    @DisplayName("no attachments stays no attachments")
    fun emptyAttachmentsStayEmpty() {
        assertTrue(MailComposeRequestTranslator.toComposeRequest(draft()).attachments.isEmpty())
    }

    // ── Envelope fields and round-trip ─────────────────────────────────────────

    @Test
    @DisplayName("referenceKey, recipient address and name are carried through verbatim")
    fun carriesEnvelopeFields() {
        val request = MailComposeRequestTranslator.toComposeRequest(draft())
        assertEquals("RE-2026-0001", request.referenceKey)
        assertEquals("kunde@example.com", request.recipientAddress)
        assertEquals("Muster GmbH", request.recipientName)
    }

    @Test
    @DisplayName("the translated request round-trips through the contract's own codec")
    fun roundTripsThroughTheContract() {
        val request = MailComposeRequestTranslator.toComposeRequest(
            draft(
                placeholderValues = mapOf(
                    "invoiceNumber" to InvoiceMailPlaceholderValue.Text("RE-2026-0001"),
                    "invoiceDate" to InvoiceMailPlaceholderValue.Date(LocalDate.of(2026, 9, 7)),
                    "grossTotal" to InvoiceMailPlaceholderValue.Amount(Money.of("1234.56")),
                ),
                attachments = listOf(
                    InvoiceMailAttachment(
                        filename = "Rechnung-RE-2026-0001.pdf",
                        contentType = "application/pdf",
                        sizeBytes = 84_213L,
                        sha256 = "a".repeat(64),
                        downloadUrl = "http://rustfs:9000/invoice-documents/x.pdf?sig=1",
                    ),
                ),
            ),
        )
        val decoded = MailComposeRequest.decode(MailComposeRequest.encode(request))
        assertEquals(request, decoded)
        assertEquals(MailContract.SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test
    @DisplayName("a full invoice's placeholder values satisfy the INVOICE_DISPATCH catalogue")
    fun placeholdersSatisfyTheCatalogue() {
        // The same seven keys InvoiceMailComposeMapperTest's default fixture produces for a fully
        // populated invoice (invoice number, both dates, gross total, recipient, sender, IBAN) —
        // this is what a synthetic datagen invoice actually hands the translator.
        val request = MailComposeRequestTranslator.toComposeRequest(
            draft(
                placeholderValues = mapOf(
                    "invoiceNumber" to InvoiceMailPlaceholderValue.Text("RE-2026-0001"),
                    "invoiceDate" to InvoiceMailPlaceholderValue.Date(LocalDate.of(2026, 9, 7)),
                    "dueDate" to InvoiceMailPlaceholderValue.Date(LocalDate.of(2026, 9, 21)),
                    "grossTotal" to InvoiceMailPlaceholderValue.Amount(Money.of("1234.56")),
                    "recipientName" to InvoiceMailPlaceholderValue.Text("Muster GmbH"),
                    "senderName" to InvoiceMailPlaceholderValue.Text("Little Trotter"),
                    "iban" to InvoiceMailPlaceholderValue.Text("DE02120300000000202051"),
                ),
            ),
        )

        assertEquals(
            emptyList<String>(),
            MailPlaceholderCatalogue.validate(MailDocumentKind.INVOICE_DISPATCH, request.placeholderValues),
        )
    }
}
