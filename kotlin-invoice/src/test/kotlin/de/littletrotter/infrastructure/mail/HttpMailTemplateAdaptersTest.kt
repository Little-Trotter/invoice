package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailCaller
import de.littletrotter.application.mail.MailCallerProvider
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailTemplateDraft
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("HttpMailTemplateQueryAdapter / HttpMailTemplateAdapter (/mail/api/templates…)")
class HttpMailTemplateAdaptersTest {

    private val stub = MailStubServer()
    private val api = MailStubServer.client(stub.baseUrl)
    private val query = HttpMailTemplateQueryAdapter(api)
    private val commands = HttpMailTemplateAdapter(api, MailCallerProvider { MailStubServer.CALLER })

    @AfterEach
    fun tearDown() = stub.close()

    private fun template(kind: String, language: String, version: Int = 1) =
        """{"id":1,"kind":"$kind","language":"$language","version":$version,"subject":"S","body":"B",
            "bccAddress":null,"seededDefault":true,"createdAt":"2026-10-01T10:00:00Z"}"""

    @Test
    fun `list maps every template`() {
        stub.respond("/mail/api/templates", 200, "[${template("INVOICE_DISPATCH", "DE")},${template("QUOTE_DISPATCH", "EN")}]")

        val all = query.listTemplates()

        assertEquals(2, all.size)
        assertEquals(MailTemplateKind.QUOTE_DISPATCH, all[1].kind)
        assertEquals(MailTemplateLanguage.EN, all[1].language)
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), all[0].createdAt)
        assertTrue(all[0].seededDefault)
        assertNull(all[0].bccAddress)
    }

    @Test
    fun `find returns the matching template or null`() {
        stub.respond("/mail/api/templates", 200, "[${template("INVOICE_DISPATCH", "DE")}]")

        assertEquals(MailTemplateLanguage.DE, query.findTemplate(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE)?.language)
        assertNull(query.findTemplate(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.EN))
        assertNull(query.findTemplate(MailTemplateKind.QUOTE_DISPATCH, MailTemplateLanguage.DE))
    }

    @Test
    fun `versions use the kind and language path and map the current flag`() {
        stub.respond(
            "/mail/api/templates/INVOICE_DISPATCH/DE/versions",
            200,
            """[{"kind":"INVOICE_DISPATCH","language":"DE","version":2,"subject":"S","body":"B","bccAddress":"b@example.com",
                "seededDefault":false,"createdAt":"2026-10-01T10:00:00Z","current":true}]""",
        )

        val versions = query.listVersions(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE)

        assertEquals(2, versions.single().version)
        assertTrue(versions.single().current)
        assertEquals("b@example.com", versions.single().bccAddress)
        assertEquals("GET", stub.last().method)
    }

    @Test
    fun `save posts the contract update and returns the new version`() {
        stub.respond("/mail/api/templates", 201, template("INVOICE_DISPATCH", "DE", version = 4))

        val saved = commands.save(MailTemplateDraft(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, "Subj", "Body", "b@example.com"))

        assertEquals(4, saved.version)
        assertEquals("POST", stub.last().method)
        assertTrue(stub.last().body.contains("\"subject\":\"Subj\""))
        assertTrue(stub.last().body.contains("\"kind\":\"INVOICE_DISPATCH\""))
        assertTrue(stub.last().body.contains("\"bccAddress\":\"b@example.com\""))
    }

    @Test
    fun `restore posts to the version path without a body`() {
        stub.respond("/mail/api/templates/QUOTE_DISPATCH/EN/versions/2/restore", 200, template("QUOTE_DISPATCH", "EN", version = 5))

        val restored = commands.restoreVersion(MailTemplateKind.QUOTE_DISPATCH, MailTemplateLanguage.EN, 2)

        assertEquals(5, restored.version)
        assertEquals("POST", stub.last().method)
        assertEquals("", stub.last().body)
    }

    @Test
    fun `test to me posts without a recipient and the address travels only in the header`() {
        stub.respond(
            "/mail/api/templates/INVOICE_DISPATCH/DE/test-to-me",
            200,
            """{"recipientAddress":"anna@example.com","messageId":"<m1>","sentAt":"2026-10-01T10:00:00Z"}""",
        )

        val result = commands.sendTestToMe(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE)

        assertEquals("anna@example.com", result.recipientAddress)
        assertEquals("<m1>", result.messageId)
        assertEquals("anna@example.com", stub.last().headers["x-lt-email"])
        assertEquals("", stub.last().body)
    }

    @Test
    fun `test to me refuses without an e-mail claim and sends nothing`() {
        stub.respond("/mail/api/templates/INVOICE_DISPATCH/DE/test-to-me", 200, "{}")
        val noEmail = HttpMailTemplateAdapter(api, MailCallerProvider { MailCaller("anna", listOf("admin"), null) })

        val e = assertThrows(MailDispatchException::class.java) {
            noEmail.sendTestToMe(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE)
        }

        assertTrue(e.message!!.contains("e-mail"))
        assertFalse(stub.requests.isNotEmpty())
    }

    @Test
    fun `a rejected write surfaces as a dispatch exception`() {
        stub.respond("/mail/api/templates", 403, "no")
        assertThrows(MailDispatchException::class.java) {
            commands.save(MailTemplateDraft(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, "s", "b"))
        }
    }
}
