package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.application.mail.port.inbound.MailJournalSortColumn
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailSortOrder
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

@DisplayName("HttpMailJournalQueryAdapter (GET /mail/api/journal…)")
class HttpMailJournalQueryAdapterTest {

    private val stub = MailStubServer()
    private val adapter = HttpMailJournalQueryAdapter(MailStubServer.client(stub.baseUrl))

    @AfterEach
    fun tearDown() = stub.close()

    private val row = """
        {"id":5,"referenceKey":"RE-1","recipient":"k@example.com","subject":"Rechnung","messageId":null,
         "status":"SENT","smtpResult":"250 ok","templateKind":"INVOICE_DISPATCH","templateLanguage":"DE",
         "templateVersion":3,"bodyEditedByUser":true,"retryOfJournalId":4,"createdAt":"2026-10-01T10:00:00Z",
         "attachments":[{"filename":"a.pdf","contentType":"application/pdf","sizeBytes":12,"sha256":"${"a".repeat(64)}"}],
         "archiveStatus":"ARCHIVED","archiveS3Key":"k/1"}
    """.trimIndent()

    @Test
    fun `the page request carries every filter, the sort and the window`() {
        stub.respond("/mail/api/journal", 200, "[$row]")
        val criteria = MailJournalCriteria(
            text = "foo", referenceKey = "RE-1", status = MailJournalStatus.FAILED,
            archiveStatus = MailJournalArchiveStatus.PENDING, templateKind = MailTemplateKind.QUOTE_DISPATCH,
            from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30), editedOnly = true,
            sort = MailJournalSortColumn.RECIPIENT, direction = MailSortOrder.ASC, page = 2, size = 25,
        )

        val page = adapter.findPage(criteria)

        assertEquals(
            "text=foo&referenceKey=RE-1&status=FAILED&archiveStatus=PENDING&templateKind=QUOTE_DISPATCH" +
                "&from=2026-09-01&to=2026-09-30&editedOnly=true&sort=RECIPIENT&dir=ASC&page=2&size=25",
            stub.last().query,
        )
        val entry = page.single()
        assertEquals(5L, entry.id)
        assertEquals(MailJournalStatus.SENT, entry.status)
        assertEquals(MailTemplateKind.INVOICE_DISPATCH, entry.templateKind)
        assertEquals(MailTemplateLanguage.DE, entry.templateLanguage)
        assertEquals(MailJournalArchiveStatus.ARCHIVED, entry.archiveStatus)
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), entry.createdAt)
        assertEquals(12L, entry.attachments.single().sizeBytes)
        assertEquals(4L, entry.retryOfJournalId)
        assertNull(entry.messageId)
    }

    @Test
    fun `default criteria send only sort and window, blank text is dropped`() {
        stub.respond("/mail/api/journal", 200, "[]")

        val page = adapter.findPage(MailJournalCriteria(text = "  ", referenceKey = ""))

        assertTrue(page.isEmpty())
        assertEquals("sort=CREATED_AT&dir=DESC&page=0&size=50", stub.last().query)
    }

    @Test
    fun `count sends the filter without sort or window`() {
        stub.respond("/mail/api/journal/count", 200, """{"count":42}""")

        val count = adapter.count(MailJournalCriteria(status = MailJournalStatus.SENT, page = 3))

        assertEquals(42L, count)
        assertEquals("status=SENT", stub.last().query)
    }

    @Test
    fun `countByStatus fills absent statuses with zero`() {
        stub.respond("/mail/api/journal/count-by-status", 200, """{"counts":{"SENT":4,"FAILED":1}}""")

        val counts = adapter.countByStatus(MailJournalCriteria())

        assertEquals(
            mapOf(
                MailJournalStatus.CREATED to 0L, MailJournalStatus.QUEUED to 0L, MailJournalStatus.SENDING to 0L,
                MailJournalStatus.SENT to 4L, MailJournalStatus.FAILED to 1L,
            ),
            counts,
        )
    }

    @Test
    fun `a rejected query surfaces as a dispatch exception`() {
        stub.respond("/mail/api/journal", 400, "bad")
        assertThrows(MailDispatchException::class.java) { adapter.findPage(MailJournalCriteria()) }
    }
}
