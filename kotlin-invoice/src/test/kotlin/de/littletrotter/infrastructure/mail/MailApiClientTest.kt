package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.MailCaller
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.MailNotFoundException
import de.littletrotter.shared.mail.MailJournalCount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.util.Optional

@DisplayName("MailApiClient (bearer + identity headers, error mapping)")
class MailApiClientTest {

    private val stub = MailStubServer()
    private val countType = object : TypeReference<MailJournalCount>() {}

    @AfterEach
    fun tearDown() = stub.close()

    @Test
    fun `GET carries bearer, identity headers and the url-encoded query`() {
        stub.respond("/mail/api/journal/count", 200, """{"count":7}""")

        val result = MailStubServer.client(stub.baseUrl)
            .get("/journal/count", listOf("text" to "a b&c", "page" to "0"), "count", countType)

        assertEquals(7L, result.count)
        val request = stub.last()
        assertEquals("GET", request.method)
        assertEquals("text=a+b%26c&page=0", request.query)
        assertEquals("Bearer s3cr3t", request.headers["authorization"])
        assertEquals("anna", request.headers["x-lt-user"])
        assertEquals("invoicing,admin", request.headers["x-lt-groups"])
        assertEquals("anna@example.com", request.headers["x-lt-email"])
        assertNull(request.headers["content-type"], "a GET has no body and so no content type")
    }

    @Test
    fun `an empty query adds no question mark`() {
        stub.respond("/mail/api/journal/count", 200, """{"count":1}""")
        MailStubServer.client(stub.baseUrl).get("/journal/count", emptyList(), "count", countType)
        assertNull(stub.last().query)
    }

    @Test
    fun `no known e-mail address means no X-Lt-Email header at all`() {
        stub.respond("/mail/api/journal/count", 200, """{"count":1}""")
        val noEmail = MailCaller("system", emptyList(), null)

        MailStubServer.client(stub.baseUrl, caller = noEmail).get("/journal/count", emptyList(), "count", countType)

        assertFalse(stub.last().headers.containsKey("x-lt-email"))
        assertEquals("", stub.last().headers["x-lt-groups"])
    }

    @Test
    fun `POST with a body sets the content type, POST without one sends no body`() {
        stub.respond("/mail/api/templates/x", 200, """{"count":2}""")
        val client = MailStubServer.client(stub.baseUrl)

        client.post("/templates/x", """{"a":1}""", "t", countType)
        assertEquals("application/json", stub.last().headers["content-type"])
        assertEquals("""{"a":1}""", stub.last().body)

        client.post("/templates/x", null, "t", countType)
        assertNull(stub.last().headers["content-type"])
        assertEquals("", stub.last().body)
    }

    @Test
    fun `postRaw returns the unparsed answer`() {
        stub.respond("/mail/api/compose", 202, """{"x":1}""")
        assertEquals("""{"x":1}""", MailStubServer.client(stub.baseUrl + "/").postRaw("/compose", "{}"))
    }

    @Test
    fun `a missing token fails before any request leaves`() {
        stub.respond("/mail/api/journal/count", 200, """{"count":1}""")
        listOf(Optional.empty(), Optional.of("  ")).forEach { token ->
            val e = assertThrows(MailDispatchException::class.java) {
                MailStubServer.client(stub.baseUrl, token = token).get("/journal/count", emptyList(), "count", countType)
            }
            assertTrue(e.message!!.contains("MAIL_INTEGRATION_TOKEN"))
        }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `a non-2xx answer names the method and path but never leaks the query`() {
        stub.respond("/mail/api/journal/count", 503, "down")
        val e = assertThrows(MailDispatchException::class.java) {
            MailStubServer.client(stub.baseUrl).get("/journal/count", listOf("text" to "secret"), "count", countType)
        }
        assertTrue(e.message!!.contains("503"))
        assertTrue(e.message!!.contains("GET /journal/count"))
        assertFalse(e.message!!.contains("secret"))
    }

    @Test
    fun `a 404 answer is told apart from other failures`() {
        stub.respond("/mail/api/compose/gone", 404, "")
        val e = assertThrows(MailNotFoundException::class.java) {
            MailStubServer.client(stub.baseUrl).get("/compose/gone", emptyList(), "draft", countType)
        }
        assertTrue(e.message!!.contains("404"))
    }

    @Test
    fun `an answer that violates the contract becomes a dispatch exception`() {
        stub.respond("/mail/api/journal/count", 200, "<html/>")
        val e = assertThrows(MailDispatchException::class.java) {
            MailStubServer.client(stub.baseUrl).get("/journal/count", emptyList(), "journal count", countType)
        }
        assertTrue(e.message!!.contains("journal count"))
    }

    @Test
    fun `a blank answer is a violation, never an empty object`() {
        stub.respond("/mail/api/journal/count", 200, "")
        assertThrows(MailDispatchException::class.java) {
            MailStubServer.client(stub.baseUrl).get("/journal/count", emptyList(), "count", countType)
        }
    }

    @Test
    fun `an unreachable component is reported as such`() {
        val e = assertThrows(MailDispatchException::class.java) {
            MailStubServer.client("http://127.0.0.1:1").get("/journal/count", emptyList(), "count", countType)
        }
        assertTrue(e.message!!.contains("not reachable"))
    }

    @Test
    fun `an interrupted call restores the interrupt flag and reports it`() {
        val interrupting = mock<HttpClient>()
        whenever(interrupting.send(any(), any<HttpResponse.BodyHandler<String>>())).doThrow(InterruptedException())
        val api = MailStubServer.client(stub.baseUrl).also { it.client = interrupting }

        try {
            val e = assertThrows(MailDispatchException::class.java) { api.get("/journal/count", emptyList(), "count", countType) }
            assertTrue(e.message!!.contains("interrupted"))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
