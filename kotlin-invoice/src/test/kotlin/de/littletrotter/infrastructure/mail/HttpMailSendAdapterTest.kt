package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailSendAttachment
import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.domain.shared.InvoiceFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HttpMailSendAdapter (POST /mail/api/send)")
class HttpMailSendAdapterTest {

    private val stub = MailStubServer()
    private val adapter = HttpMailSendAdapter(MailStubServer.client(stub.baseUrl))

    @AfterEach
    fun tearDown() = stub.close()

    private val command = MailSendCommand(
        kind = MailTemplateKind.INVOICE_DISPATCH,
        documentFamily = InvoiceFormat.INTERNATIONAL,
        recipientAddress = "k@example.com",
        referenceKey = "RE-1",
        subject = "Invoice",
        body = "Hello",
        placeholderValues = mapOf("invoiceNumber" to "RE-1", "iban" to null),
        bodyEditedByUser = true,
        attachments = listOf(MailSendAttachment("k/1", "a.pdf", "application/pdf", "a".repeat(64))),
        draftId = "d-1",
    )

    @Test
    fun `send posts the contract request and maps the accepted journal row`() {
        stub.respond("/mail/api/send", 202, """{"journalId":9,"status":"QUEUED"}""")

        val outcome = adapter.send(command)

        assertEquals(9L, outcome.journalId)
        assertEquals(MailJournalStatus.QUEUED, outcome.status)
        val body = stub.last().body
        assertTrue(body.contains("\"documentFamily\":\"INTERNATIONAL\""))
        assertTrue(body.contains("\"referenceKey\":\"RE-1\""))
        assertTrue(body.contains("\"s3Key\":\"k/1\""))
        assertTrue(body.contains("\"draftId\":\"d-1\""))
        assertTrue(body.contains("\"bodyEditedByUser\":true"))
    }

    @Test
    fun `a draft id is optional`() {
        stub.respond("/mail/api/send", 202, """{"journalId":1,"status":"CREATED"}""")
        assertEquals(1L, adapter.send(command.copy(draftId = null, documentFamily = InvoiceFormat.GERMAN)).journalId)
    }

    @Test
    fun `a rejected send is an error, never a silent success`() {
        stub.respond("/mail/api/send", 422, "invalid")
        assertThrows(MailDispatchException::class.java) { adapter.send(command) }
    }
}
