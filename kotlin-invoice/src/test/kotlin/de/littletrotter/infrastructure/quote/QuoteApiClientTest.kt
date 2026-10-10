package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNoNumberRangeException
import de.littletrotter.application.quote.QuoteModuleDisabledException
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.QuoteWriteGuard
import de.littletrotter.shared.quote.QuoteSettingsView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
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

@DisplayName("QuoteApiClient (bearer + identity headers, error mapping)")
class QuoteApiClientTest {

    private val stub = QuoteStubServer()
    private val settingsType = object : TypeReference<QuoteSettingsView>() {}

    @AfterEach
    fun tearDown() = stub.close()

    @Test
    fun `write calls are refused before any request while the module is off, reads and unguarded puts pass`() {
        stub.respond("/quote/api/settings", 200, """{"validityDays":30}""")
        val guard = mock<QuoteWriteGuard>()
        whenever(guard.requireEnabled()).thenThrow(QuoteModuleDisabledException())
        val client = QuoteStubServer.client(stub.baseUrl, guard = guard)

        assertThrows(QuoteModuleDisabledException::class.java) { client.post("/api/quotes", null, "x", settingsType) }
        assertThrows(QuoteModuleDisabledException::class.java) { client.postNoContent("/api/quotes", null) }
        assertThrows(QuoteModuleDisabledException::class.java) { client.put("/api/settings", "{}") }
        assertThrows(QuoteModuleDisabledException::class.java) { client.delete("/api/quotes/1") }
        assertTrue(stub.requests.isEmpty(), "a refused write must not reach the component")

        client.put("/api/settings", "{}", guarded = false)
        assertEquals("PUT", stub.last().method)
        assertEquals(30L, client.get("/api/settings", emptyList(), "settings", settingsType).validityDays)
    }

    @Test
    fun `GET carries bearer, identity headers and the url-encoded query under the quote root`() {
        stub.respond("/quote/api/settings", 200, """{"validityDays":30}""")

        val result = QuoteStubServer.client(stub.baseUrl + "/")
            .get("/api/settings", listOf("text" to "a b&c", "page" to "0"), "settings", settingsType)

        assertEquals(30L, result.validityDays)
        val request = stub.last()
        assertEquals("GET", request.method)
        assertEquals("text=a+b%26c&page=0", request.query)
        assertEquals("Bearer s3cr3t", request.headers["authorization"])
        assertEquals("anna", request.headers["x-lt-user"])
        assertEquals("quoting,admin", request.headers["x-lt-groups"])
        assertNull(request.headers["content-type"], "a GET has no body and so no content type")
    }

    @Test
    fun `an empty query adds no question mark`() {
        stub.respond("/quote/api/settings", 200, """{"validityDays":1}""")
        QuoteStubServer.client(stub.baseUrl).get("/api/settings", emptyList(), "settings", settingsType)
        assertNull(stub.last().query)
    }

    @Test
    fun `POST sends the JSON body with its content type and decodes the answer`() {
        stub.respond("/quote/api/settings", 200, """{"validityDays":7}""")

        val result = QuoteStubServer.client(stub.baseUrl).post("/api/settings", """{"a":1}""", "settings", settingsType)

        assertEquals(7L, result.validityDays)
        assertEquals("POST", stub.last().method)
        assertEquals("""{"a":1}""", stub.last().body)
        assertEquals("application/json", stub.last().headers["content-type"])
    }

    @Test
    fun `postNoContent without a body sends no content type`() {
        stub.respond("/quote/api/x", 204, "")

        QuoteStubServer.client(stub.baseUrl).postNoContent("/api/x", null)

        assertEquals("POST", stub.last().method)
        assertEquals("", stub.last().body)
        assertNull(stub.last().headers["content-type"])
    }

    @Test
    fun `PUT and DELETE use their verbs`() {
        stub.respond("/quote/api/x", 204, "")
        val client = QuoteStubServer.client(stub.baseUrl)

        client.put("/api/x", "{}")
        assertEquals("PUT", stub.last().method)
        assertEquals("{}", stub.last().body)

        client.delete("/api/x")
        assertEquals("DELETE", stub.last().method)
    }

    @Test
    fun `getBytes returns the raw bytes and the naming headers`() {
        val bytes = byteArrayOf(0, 1, 2, -1)
        stub.respondBytes("/quote/api/f", 200, bytes, "application/pdf", mapOf("Content-Disposition" to "attachment; filename=a.pdf"))

        val answer = QuoteStubServer.client(stub.baseUrl).getBytes("/api/f")

        assertArrayEquals(bytes, answer.bytes)
        assertEquals("application/pdf", answer.contentType)
        assertEquals("attachment; filename=a.pdf", answer.contentDisposition)
    }

    @Test
    fun `a blank token fails closed before any request`() {
        val client = QuoteStubServer.client(stub.baseUrl, token = Optional.of("  "))
        val e = assertThrows(QuoteServiceException::class.java) { client.get("/api/settings", emptyList(), "s", settingsType) }
        assertTrue(e.message!!.contains("QUOTE_INTEGRATION_TOKEN ist nicht konfiguriert"))
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `an absent token fails closed as well`() {
        val client = QuoteStubServer.client(stub.baseUrl, token = Optional.empty())
        assertThrows(QuoteServiceException::class.java) { client.delete("/api/x") }
    }

    @Test
    fun `404 maps to not found and carries the component's message`() {
        stub.respond("/quote/api/x", 404, """{"status":404,"message":"offer does not exist"}""")
        val e = assertThrows(QuoteNotFoundException::class.java) { QuoteStubServer.client(stub.baseUrl).delete("/api/x") }
        assertEquals("offer does not exist", e.message)
    }

    @Test
    fun `a 409 domain refusal reaches the caller with the component's own explanation`() {
        stub.respond("/quote/api/x", 409, """{"status":409,"message":"only a draft can be edited"}""")
        val e = assertThrows(QuoteServiceException::class.java) { QuoteStubServer.client(stub.baseUrl).put("/api/x", "{}") }
        assertFalse(e is QuoteNotFoundException)
        assertEquals("only a draft can be edited", e.message)
    }

    @Test
    fun `a 409 for a missing number range becomes the dedicated exception with the message key`() {
        stub.respond("/quote/api/x", 409, """{"status":409,"message":"quote.numberRange.missing"}""")
        val e = assertThrows(QuoteNoNumberRangeException::class.java) { QuoteStubServer.client(stub.baseUrl).put("/api/x", "{}") }
        assertEquals("quote.error.noNumberRange", e.message)
    }

    @Test
    fun `a refusal without the error shape falls back to status and route`() {
        stub.respond("/quote/api/x", 502, "<html>bad gateway</html>")
        val e = assertThrows(QuoteServiceException::class.java) { QuoteStubServer.client(stub.baseUrl).get("/api/x", listOf("q" to "1"), "x", settingsType) }
        assertEquals("Die Angebotskomponente hat die Anfrage GET /api/x mit HTTP 502 beantwortet", e.message)
    }

    @Test
    fun `an error body with a blank message falls back to status and route`() {
        stub.respond("/quote/api/x", 500, """{"status":500,"message":" "}""")
        val e = assertThrows(QuoteServiceException::class.java) { QuoteStubServer.client(stub.baseUrl).delete("/api/x") }
        assertEquals("Die Angebotskomponente hat die Anfrage DELETE /api/x mit HTTP 500 beantwortet", e.message)
    }

    @Test
    fun `an answer that does not match the contract is reported, not defaulted`() {
        stub.respond("/quote/api/settings", 200, """{"nonsense":true}""")
        val e = assertThrows(QuoteServiceException::class.java) {
            QuoteStubServer.client(stub.baseUrl).get("/api/settings", emptyList(), "settings", settingsType)
        }
        assertTrue(e.message!!.startsWith("Die Antwort der Angebotskomponente zu settings ist nicht lesbar"))
    }

    @Test
    fun `an unreachable component is reported with its address`() {
        val url = stub.baseUrl
        stub.close()
        val e = assertThrows(QuoteServiceException::class.java) { QuoteStubServer.client(url).delete("/api/x") }
        assertTrue(e.message!!.contains("ist nicht erreichbar"))
    }

    @Test
    fun `an interrupted call restores the flag and is reported`() {
        val http = mock<HttpClient>()
        whenever(http.send(any(), any<HttpResponse.BodyHandler<ByteArray>>())).doThrow(InterruptedException("stop"))
        val client = QuoteStubServer.client(stub.baseUrl).also { it.client = http }
        try {
            val e = assertThrows(QuoteServiceException::class.java) { client.delete("/api/x") }
            assertTrue(e.message!!.contains("unterbrochen"))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
