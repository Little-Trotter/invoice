package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.littletrotter.application.mail.InvoiceMailAttachment
import de.littletrotter.application.mail.InvoiceMailDraft
import de.littletrotter.application.mail.InvoiceMailPlaceholderValue
import de.littletrotter.application.mail.InvoiceMailSenderBlock
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.Email
import de.littletrotter.infrastructure.mail.acl.MailComposeRequestTranslator
import de.littletrotter.shared.mail.MailComposeRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

/**
 * The HTTP adapter against a local stub endpoint.
 *
 * Uses `com.sun.net.httpserver.HttpServer` on an ephemeral port, the mechanism this module already
 * uses for its other outbound adapter ([de.littletrotter.infrastructure.vies.ViesHttpClientTest]) —
 * no new test dependency, and the real JDK `HttpClient` on the client side, so the request headers
 * and the encoded body are the ones production would send.
 *
 * What is asserted, beyond the happy path, is that **nothing degrades**: a compose handover has no
 * useful fallback, so an unreachable component, a non-2xx answer and an unreadable body all become
 * a [MailDispatchException] the UI can show, never a silently empty draft.
 */
@DisplayName("HttpMailDispatchAdapter (POST /mail/api/compose)")
class HttpMailDispatchAdapterTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val objectMapper = ObjectMapper()
    private val lastRequest = AtomicReference<CapturedRequest?>()

    private data class CapturedRequest(val path: String, val authorization: String?, val body: String, val headers: Map<String, String?>)

    private val draft = InvoiceMailDraft(
        referenceKey = "RE-2026-0001",
        documentFamily = InvoiceFormat.GERMAN,
        recipientAddress = Email("kunde@example.com"),
        recipientName = "Muster GmbH",
        placeholderValues = mapOf(
            "invoiceNumber" to InvoiceMailPlaceholderValue.Text("RE-2026-0001"),
            "invoiceDate" to InvoiceMailPlaceholderValue.Date(LocalDate.of(2026, 9, 7)),
        ),
        attachments = listOf(
            InvoiceMailAttachment(
                filename = "Rechnung-RE-2026-0001.pdf",
                contentType = "application/pdf",
                sizeBytes = 1234L,
                sha256 = "b".repeat(64),
                downloadUrl = "http://rustfs:9000/invoice-documents/x.pdf?sig=1",
            ),
        ),
    )

    /** The wire request the adapter is expected to send — the translator's own output. */
    private val request: MailComposeRequest = MailComposeRequestTranslator.toComposeRequest(draft)

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
        lastRequest.set(null)
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun adapter(token: String = "s3cr3t", url: String = baseUrl): HttpMailDispatchAdapter =
        HttpMailDispatchAdapter(MailStubServer.client(url, token = Optional.of(token)), objectMapper)

    private fun respond(status: Int, body: String) {
        server.createContext("/mail/api/compose") { exchange: HttpExchange ->
            lastRequest.set(
                CapturedRequest(
                    path = exchange.requestURI.path,
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                    headers = mapOf(
                        "X-Lt-User" to exchange.requestHeaders.getFirst("X-Lt-User"),
                        "X-Lt-Groups" to exchange.requestHeaders.getFirst("X-Lt-Groups"),
                        "X-Lt-Email" to exchange.requestHeaders.getFirst("X-Lt-Email"),
                    ),
                ),
            )
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a 200 answer yields the draft id and the compose URL verbatim")
    fun returnsTheHandover() {
        respond(200, """{"draftId":"d-7f3a","composeUrl":"/mail/compose/d-7f3a"}""")

        val handover = adapter().compose(draft)

        assertEquals("d-7f3a", handover.draftId)
        assertEquals("/mail/compose/d-7f3a", handover.composeUrl)
    }

    @Test
    @DisplayName("the request carries the bearer token and the contract's own encoding of the body")
    fun sendsTokenAndContractEncodedBody() {
        respond(200, """{"draftId":"d1","composeUrl":"/mail/compose/d1"}""")

        adapter(token = "integration-token").compose(draft)

        val captured = requireNotNull(lastRequest.get())
        assertEquals("/mail/api/compose", captured.path)
        assertEquals("Bearer integration-token", captured.authorization)
        // Not "a JSON body that happens to match": the bytes are exactly what the shared codec
        // produces, so a Kotlin property rename on either side cannot change the wire silently.
        assertEquals(MailComposeRequest.encode(request), captured.body)
        // And it round-trips back through the contract, which is what the component will do.
        assertEquals(request, MailComposeRequest.decode(captured.body))
    }

    @Test
    @DisplayName("the request carries the acting user's name, groups and OIDC e-mail address")
    fun sendsIdentityHeaders() {
        respond(200, """{"draftId":"d1","composeUrl":"/mail/compose/d1"}""")

        adapter().compose(draft)

        val headers = requireNotNull(lastRequest.get()).headers
        assertEquals("anna", headers["X-Lt-User"])
        assertEquals("invoicing,admin", headers["X-Lt-Groups"])
        assertEquals("anna@example.com", headers["X-Lt-Email"])
    }

    @Test
    @DisplayName("the sender block of the draft travels on the wire")
    fun sendsTheSenderBlock() {
        respond(200, """{"draftId":"d1","composeUrl":"/mail/compose/d1"}""")
        val withBlock = draft.copy(senderBlock = InvoiceMailSenderBlock("Little Trotter", phone = "+49 1", legalLines = listOf("HRB 1")))

        adapter().compose(withBlock)

        val decoded = MailComposeRequest.decode(requireNotNull(lastRequest.get()).body)
        assertEquals("Little Trotter", decoded.senderBlock?.name)
        assertEquals(listOf("HRB 1"), decoded.senderBlock?.legalLines)
    }

    @Test
    @DisplayName("a trailing slash on mail.base-url does not produce a double slash")
    fun tolerantOfATrailingSlash() {
        respond(200, """{"draftId":"d1","composeUrl":"/mail/compose/d1"}""")

        adapter(url = "$baseUrl/").compose(draft)

        assertEquals("/mail/api/compose", requireNotNull(lastRequest.get()).path)
    }

    @Test
    @DisplayName("a 201 answer is accepted too — any 2xx is a created draft")
    fun accepts2xx() {
        respond(201, """{"draftId":"d2","composeUrl":"/mail/compose/d2"}""")
        assertEquals("d2", adapter().compose(draft).draftId)
    }

    // ── Failures ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a blank integration token fails before any request is sent")
    fun refusesWithoutAToken() {
        respond(200, """{"draftId":"d1","composeUrl":"/mail/compose/d1"}""")

        val e = assertThrows(MailDispatchException::class.java) { adapter(token = "  ").compose(draft) }

        assertTrue(e.message!!.contains("MAIL_INTEGRATION_TOKEN"))
        assertEquals(null, lastRequest.get(), "no request may leave without a token")
    }

    @Test
    @DisplayName("an absent integration token behaves the same as a blank one")
    fun refusesWithoutTheProperty() {
        respond(200, """{"draftId":"d1","composeUrl":"/mail/compose/d1"}""")

        val absent = HttpMailDispatchAdapter(MailStubServer.client(baseUrl, token = Optional.empty()), objectMapper)
        val e = assertThrows(MailDispatchException::class.java) { absent.compose(draft) }

        assertTrue(e.message!!.contains("MAIL_INTEGRATION_TOKEN"))
        assertEquals(null, lastRequest.get(), "no request may leave without a token")
    }

    @Test
    @DisplayName("a 401 becomes a MailDispatchException naming the status")
    fun rejectsUnauthorized() {
        respond(401, """{"error":"unauthorized"}""")
        val e = assertThrows(MailDispatchException::class.java) { adapter().compose(draft) }
        assertTrue(e.message!!.contains("401"))
    }

    @Test
    @DisplayName("a 500 becomes a MailDispatchException naming the status")
    fun rejectsServerError() {
        respond(500, "boom")
        val e = assertThrows(MailDispatchException::class.java) { adapter().compose(draft) }
        assertTrue(e.message!!.contains("500"))
    }

    @Test
    @DisplayName("an unreachable component is reported as such, never as a silent success")
    fun reportsUnreachable() {
        // A port nothing listens on: the server above is running, this URL is not it.
        val dead = "http://127.0.0.1:1"
        val e = assertThrows(MailDispatchException::class.java) { adapter(url = dead).compose(draft) }
        assertTrue(e.message!!.contains("not reachable"))
    }

    @Test
    @DisplayName("an answer that is not JSON is rejected")
    fun rejectsNonJsonAnswer() {
        respond(200, "<html>gateway</html>")
        val e = assertThrows(MailDispatchException::class.java) { adapter().compose(draft) }
        assertTrue(e.message!!.contains("not readable JSON"))
    }

    @Test
    @DisplayName("an answer without a compose URL is rejected rather than opening an empty frame")
    fun rejectsAnswerWithoutUrl() {
        respond(200, """{"draftId":"d1"}""")
        val e = assertThrows(MailDispatchException::class.java) { adapter().compose(draft) }
        assertTrue(e.message!!.contains("no draft id or compose URL"))
    }

    @Test
    @DisplayName("an answer with a blank draft id is rejected — no field on this path defaults to empty")
    fun rejectsBlankDraftId() {
        respond(200, """{"draftId":"","composeUrl":"/mail/compose/x"}""")
        assertThrows(MailDispatchException::class.java) { adapter().compose(draft) }
    }
}
