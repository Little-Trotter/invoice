package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailPlaceholderType
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewRequest
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.domain.shared.InvoiceFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("HttpMailComposeAdapter (/mail/api/compose, /placeholders, /render-preview)")
class HttpMailComposeAdapterTest {

    private val stub = MailStubServer()
    private val adapter = HttpMailComposeAdapter(MailStubServer.client(stub.baseUrl))

    @AfterEach
    fun tearDown() = stub.close()

    private fun draft(family: String) =
        """{"draftId":"d-1","referenceKey":"RE-1","documentFamily":"$family","documentKind":"INVOICE_DISPATCH",
            "recipientAddress":"kunde@example.com","recipientName":"Kunde GmbH",
            "placeholderValues":{"invoiceNumber":"RE-1"},
            "attachments":[{"filename":"RE-1.pdf","contentType":"application/pdf","sizeBytes":1234,"sha256":"abc","s3Key":"mail-drafts/d-1/0-RE-1.pdf"}],
            "totalAttachmentBytes":1234,"composeUrl":"/mail/compose/d-1",
            "createdAt":"2026-10-01T10:00:00Z","expiresAt":"2026-10-01T11:00:00Z"}"""

    @Test
    fun `the draft is translated into the local model`() {
        stub.respond("/mail/api/compose/d-1", 200, draft("GERMAN"))

        val found = requireNotNull(adapter.findDraft("d-1"))

        assertEquals("RE-1", found.referenceKey)
        assertEquals(MailTemplateKind.INVOICE_DISPATCH, found.kind)
        assertEquals(InvoiceFormat.GERMAN, found.documentFamily)
        assertEquals("kunde@example.com", found.recipientAddress)
        assertEquals(mapOf("invoiceNumber" to "RE-1"), found.placeholderValues)
        assertEquals("mail-drafts/d-1/0-RE-1.pdf", found.attachments.single().s3Key)
        assertEquals(1234L, found.totalAttachmentBytes)
        assertEquals(Instant.parse("2026-10-01T11:00:00Z"), found.expiresAt)
        assertEquals("GET", stub.last().method)
    }

    @Test
    fun `an international draft maps to the international format`() {
        stub.respond("/mail/api/compose/d-1", 200, draft("INTERNATIONAL"))

        assertEquals(InvoiceFormat.INTERNATIONAL, adapter.findDraft("d-1")?.documentFamily)
    }

    @Test
    fun `an unknown or expired draft is null, not an error`() {
        stub.respond("/mail/api/compose/gone", 404, "{}")

        assertNull(adapter.findDraft("gone"))
    }

    @Test
    fun `any other failure of the draft read surfaces`() {
        stub.respond("/mail/api/compose/d-1", 500, "boom")

        assertThrows(MailDispatchException::class.java) { adapter.findDraft("d-1") }
    }

    @Test
    fun `the catalogue is requested per kind and every value type is mapped`() {
        stub.respond(
            "/mail/api/placeholders",
            200,
            """{"entries":[{"templateKey":"invoiceNumber","valueType":"STRING","sampleValue":"RE-1"},
                {"templateKey":"dueDate","valueType":"DATE","sampleValue":"01.11.2026"},
                {"templateKey":"grossTotal","valueType":"MONEY","sampleValue":"119,00 EUR"}]}""",
        )

        val entries = adapter.placeholders(MailTemplateKind.QUOTE_DISPATCH)

        assertEquals(listOf(MailPlaceholderType.TEXT, MailPlaceholderType.DATE, MailPlaceholderType.MONEY), entries.map { it.type })
        assertEquals("invoiceNumber", entries.first().key)
        assertEquals("119,00 EUR", entries.last().sampleValue)
        assertEquals("kind=QUOTE_DISPATCH", stub.last().query)
    }

    @Test
    fun `the preview posts kind, family and values and returns the rendered mail`() {
        stub.respond(
            "/mail/api/render-preview",
            200,
            """{"subject":"Rechnung RE-1","body":"Guten Tag","templateLanguage":"DE","templateVersion":3}""",
        )

        val preview = adapter.renderPreview(
            MailRenderPreviewRequest(MailTemplateKind.INVOICE_DISPATCH, InvoiceFormat.INTERNATIONAL, mapOf("invoiceNumber" to "RE-1", "dueDate" to null)),
        )

        assertEquals("Rechnung RE-1", preview.subject)
        assertEquals("Guten Tag", preview.body)
        assertEquals(MailTemplateLanguage.DE, preview.language)
        assertEquals(3, preview.templateVersion)
        assertEquals("POST", stub.last().method)
        assertTrue(stub.last().body.contains("\"kind\":\"INVOICE_DISPATCH\""))
        assertTrue(stub.last().body.contains("\"documentFamily\":\"INTERNATIONAL\""))
        assertTrue(stub.last().body.contains("\"invoiceNumber\":\"RE-1\""))
    }
}
