package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("HttpMailTemplateResetAdapter / HttpMailTemplateBccTestAdapter")
class HttpMailTemplateExtraAdaptersTest {

    private val stub = MailStubServer()
    private val api = MailStubServer.client(stub.baseUrl)

    @AfterEach
    fun tearDown() = stub.close()

    @Test
    fun `reset posts to the reset path without a body and maps the new version`() {
        stub.respond(
            "/mail/api/templates/QUOTE_DISPATCH/EN/reset",
            200,
            """{"id":1,"kind":"QUOTE_DISPATCH","language":"EN","version":6,"subject":"S","body":"B",
                "bccAddress":null,"seededDefault":true,"createdAt":"2026-10-01T10:00:00Z"}""",
        )

        val reset = HttpMailTemplateResetAdapter(api).reset(MailTemplateKind.QUOTE_DISPATCH, MailTemplateLanguage.EN)

        assertEquals(6, reset.version)
        assertTrue(reset.seededDefault)
        assertEquals("POST", stub.requests.last().method)
        assertEquals("", stub.requests.last().body)
    }

    @Test
    fun `bcc test posts the address to try and maps the result`() {
        stub.respond(
            "/mail/api/templates/INVOICE_DISPATCH/DE/bcc/test",
            200,
            """{"bccAddress":"bcc@example.com","messageId":"<b1>","sentAt":"2026-10-01T10:00:00Z"}""",
        )

        val result = HttpMailTemplateBccTestAdapter(api)
            .sendBccTest(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, "bcc@example.com")

        assertEquals("<b1>", result.messageId)
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), result.sentAt)
        assertTrue(stub.requests.last().body.contains("\"address\":\"bcc@example.com\""))
    }

    @Test
    fun `rejected calls surface as dispatch exceptions`() {
        stub.respond("/mail/api/templates/INVOICE_DISPATCH/DE/reset", 403, "no")
        stub.respond("/mail/api/templates/INVOICE_DISPATCH/DE/bcc/test", 400, "bad")

        assertThrows(MailDispatchException::class.java) {
            HttpMailTemplateResetAdapter(api).reset(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE)
        }
        assertThrows(MailDispatchException::class.java) {
            HttpMailTemplateBccTestAdapter(api).sendBccTest(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, "x")
        }
    }
}
