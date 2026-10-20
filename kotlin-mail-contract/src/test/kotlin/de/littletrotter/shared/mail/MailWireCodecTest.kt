package de.littletrotter.shared.mail

import com.fasterxml.jackson.core.type.TypeReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate

/** The shared codec behind every plain REST view and request type. */
class MailWireCodecTest {

    private val at = Instant.parse("2026-10-01T08:15:30Z")

    private inline fun <reified T> decode(json: String?, what: String): T =
        MailWireCodec.decode(json, what, object : TypeReference<T>() {})

    private inline fun <reified T : Any> roundTrip(value: T) {
        assertEquals(value, decode<T>(MailWireCodec.encode(value), "test"))
    }

    @Test
    fun `draft types round-trip`() {
        roundTrip(MailDraftCreated("d1", "https://x/compose/d1", at))
        roundTrip(MailDraftAttachmentView("a.pdf", "application/pdf", 42L, "f".repeat(64), "mail-drafts/d/0-a.pdf"))
        roundTrip(
            MailDraftView(
                draftId = "d1", referenceKey = "RE-1", documentFamily = MailDocumentFamily.INTERNATIONAL,
                documentKind = MailDocumentKind.QUOTE_DISPATCH, recipientAddress = "a@b.de", recipientName = "A",
                placeholderValues = mapOf("k" to "v"),
                attachments = listOf(MailDraftAttachmentView("a.pdf", "application/pdf", 42L, "f".repeat(64), "mail-drafts/d/0-a.pdf")),
                totalAttachmentBytes = 42L, composeUrl = "https://x", createdAt = at, expiresAt = at.plusSeconds(60),
            ),
        )
    }

    @Test
    fun `send types round-trip`() {
        val ref = MailSendAttachmentRef("key/1", "a.pdf", "application/pdf", "e".repeat(64))
        roundTrip(ref)
        roundTrip(
            MailSendRequest(
                kind = MailDocumentKind.QUOTE_DISPATCH, documentFamily = MailDocumentFamily.INTERNATIONAL,
                recipientAddress = "a@b.de", referenceKey = "RE-1", subject = "S", body = "B",
                placeholderValues = mapOf("k" to "v", "n" to null), bodyEditedByUser = true,
                attachments = listOf(ref), draftId = "d1",
            ),
        )
        roundTrip(MailSendResult(7L, MailSendStatus.QUEUED))
        roundTrip(MailRearchiveResult(7L, MailArchiveStatus.FAILED, "boom"))
        roundTrip(MailTestSendRequest("a@b.de"))
        roundTrip(MailTestSendResult("a@b.de", "<m@x>", at))
        roundTrip(MailTemplateBccTestRequest("bcc@b.de"))
        roundTrip(MailTemplateBccTestResult("bcc@b.de", "<m@x>", at))
    }

    @Test
    fun `template types round-trip`() {
        roundTrip(MailTemplateView(3L, MailDocumentKind.QUOTE_DISPATCH, MailLanguage.EN, 4, "S", "B", "bcc@b.de", true, at))
        roundTrip(MailTemplateUpdate(MailDocumentKind.QUOTE_DISPATCH, MailLanguage.EN, "S", "B", "bcc@b.de"))
        roundTrip(
            MailTemplateVersionView(
                MailDocumentKind.QUOTE_DISPATCH, MailLanguage.EN, 4, "S", "B", "bcc@b.de", true, at, current = true,
            ),
        )
        roundTrip(
            MailPlaceholderCatalog(
                listOf(MailPlaceholderCatalogEntry("invoiceNumber", MailPlaceholderValueType.MONEY, "1,00 EUR")),
            ),
        )
        roundTrip(MailPlaceholderCatalogEntry("invoiceDate", MailPlaceholderValueType.DATE, "01.10.2026"))
        roundTrip(
            MailRenderPreviewRequest(
                MailDocumentKind.QUOTE_DISPATCH, MailDocumentFamily.INTERNATIONAL, mapOf("k" to "v", "n" to null),
            ),
        )
        roundTrip(MailRenderPreview("S", "B", MailLanguage.EN, 2))
    }

    @Test
    fun `journal types round-trip`() {
        roundTrip(
            MailJournalQuery(
                text = "x", referenceKey = "RE-1", status = MailSendStatus.SENT, archiveStatus = MailArchiveStatus.ARCHIVED,
                templateKind = MailDocumentKind.QUOTE_DISPATCH, from = LocalDate.of(2026, 1, 2),
                to = LocalDate.of(2026, 3, 4), editedOnly = true, sort = MailJournalSortField.RECIPIENT,
                direction = MailSortDirection.ASC, page = 2, size = 10,
            ),
        )
        val attachment = MailJournalAttachmentView("a.pdf", "application/pdf", 9L, "d".repeat(64))
        roundTrip(attachment)
        roundTrip(attachment.copy(sizeBytes = null))
        roundTrip(
            MailJournalEntryView(
                id = 5L, referenceKey = "RE-1", recipient = "a@b.de", subject = "S", messageId = "<m@x>",
                status = MailSendStatus.SENDING, smtpResult = "250", templateKind = MailDocumentKind.QUOTE_DISPATCH,
                templateLanguage = MailLanguage.EN, templateVersion = 3, bodyEditedByUser = true,
                retryOfJournalId = 4L, createdAt = at, attachments = listOf(attachment),
                archiveStatus = MailArchiveStatus.ARCHIVED, archiveS3Key = "k/1",
                archiveHandoverStatus = MailArchiveHandoverStatus.FAILED, archiveHandoverAt = at,
                archiveHandoverError = "mailbox full",
            ),
        )
        roundTrip(MailJournalCount(42L))
        roundTrip(MailJournalStatusCounts(mapOf(MailSendStatus.SENT to 3L, MailSendStatus.FAILED to 1L)))
    }

    @Test
    fun `journal entry without handover fields decodes with not-configured default`() {
        val json = """{"id":1,"referenceKey":"RE-1","recipient":"a@b.de","subject":"S","messageId":null,
            "status":"SENT","smtpResult":null,"templateKind":"INVOICE_DISPATCH","templateLanguage":"DE",
            "templateVersion":1,"bodyEditedByUser":false,"retryOfJournalId":null,"createdAt":"2026-10-01T08:15:30Z"}"""
        val decoded = decode<MailJournalEntryView>(json, "t")
        assertEquals(MailArchiveHandoverStatus.NOT_CONFIGURED, decoded.archiveHandoverStatus)
        assertEquals(null, decoded.archiveHandoverAt)
        assertEquals(null, decoded.archiveHandoverError)
    }

    @Test
    fun `sender block round-trips`() {
        roundTrip(MailSenderBlock("N", "1", "e@x.de", "https://x", listOf("l1", "l2")))
    }

    @Test
    fun `unknown top-level fields are ignored`() {
        val decoded = decode<MailSendResult>("""{"journalId":1,"status":"SENT","future":{"a":1}}""", "t")
        assertEquals(MailSendResult(1L, MailSendStatus.SENT), decoded)
    }

    @Test
    fun `enums are written by name and instants as ISO-8601 text`() {
        val json = MailWireCodec.encode(MailTestSendResult("a@b.de", "<m>", at))
        assertTrue(json.contains(""""sentAt":"2026-10-01T08:15:30Z""""), json)
        assertTrue(MailWireCodec.encode(MailSendResult(1L, MailSendStatus.SENT)).contains(""""status":"SENT""""))
    }

    @Test
    fun `a blank payload is a contract violation`() {
        assertThrows<MailContractViolationException> { decode<MailSendResult>("  ", "t") }
        assertThrows<MailContractViolationException> { decode<MailSendResult>(null, "t") }
    }

    @Test
    fun `a payload that does not fit the type is a contract violation naming the payload`() {
        val thrown = assertThrows<MailContractViolationException> {
            decode<MailSendResult>("""{"journalId":1,"status":"BOGUS"}""", "SendResult")
        }
        assertTrue(thrown.message!!.startsWith("SendResult"))
        assertThrows<MailContractViolationException> { decode<MailSendResult>("{nope", "t") }
    }

    @Test
    fun `an empty object yields the journal query defaults`() {
        assertEquals(MailJournalQuery(), decode<MailJournalQuery>("{}", "t"))
    }

    @Test
    fun `generic shapes decode through the type reference`() {
        val list = MailWireCodec.encode(listOf(MailSendResult(1L, MailSendStatus.SENT)))
        assertEquals(listOf(MailSendResult(1L, MailSendStatus.SENT)), decode<List<MailSendResult>>(list, "t"))
    }
}
