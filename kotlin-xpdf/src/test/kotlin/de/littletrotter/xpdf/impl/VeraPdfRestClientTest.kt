package de.littletrotter.xpdf.impl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.littletrotter.shared.KositOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat

/** Talks to a real local HTTP server standing in for the veraPDF service. */
class VeraPdfRestClientTest {

    private val pdf = "%PDF-1.7 test document".toByteArray()
    private val pdfSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf))

    private val compliant = """
        {"report":{"jobs":[{"validationResult":{"profileName":"PDF/A-3B validation profile","compliant":true,
         "details":{"failedRules":0,"failedChecks":0,"ruleSummaries":[]}}}]}}
    """.trimIndent()

    private var server: HttpServer? = null
    @Volatile private var requestPath: String? = null
    @Volatile private var requestContentType: String? = null
    @Volatile private var requestBody: ByteArray = ByteArray(0)

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    private fun serve(path: String, status: Int, body: String, delayMillis: Long = 0): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext(path) { exchange -> answer(exchange, status, body, delayMillis) }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun answer(exchange: HttpExchange, status: Int, body: String, delayMillis: Long) {
        requestPath = exchange.requestURI.path
        requestContentType = exchange.requestHeaders.getFirst("Content-Type")
        requestBody = exchange.requestBody.readAllBytes()
        if (delayMillis > 0) Thread.sleep(delayMillis)
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun client(baseUrl: String, timeoutSeconds: Long = 5) =
        VeraPdfRestClient(baseUrl = baseUrl, pinnedVersion = "1.30.2", timeoutSeconds = timeoutSeconds)

    @Test
    fun `a PDF is streamed as multipart to the flavour endpoint and the verdict is parsed`() {
        val result = client(serve("/api/validate", 200, compliant)).validate(pdf)

        assertEquals(KositOutcome.VALID, result.outcome)
        assertEquals("/api/validate/3b", requestPath)
        assertTrue(requestContentType!!.startsWith("multipart/form-data; boundary="), "was: $requestContentType")
        assertTrue(String(requestBody, Charsets.ISO_8859_1).contains(String(pdf, Charsets.ISO_8859_1)), "the document must be in the body")
        assertTrue(String(requestBody).contains("name=\"file\""))
    }

    @Test
    fun `a matching digest is accepted`() {
        val result = client(serve("/api/validate", 200, compliant)).validate(ByteArrayInputStream(pdf), pdfSha256)

        assertEquals(KositOutcome.VALID, result.outcome)
    }

    @Test
    fun `a different digest is an engine error, not a verdict about another document`() {
        val result = client(serve("/api/validate", 200, compliant)).validate(ByteArrayInputStream(pdf), "00".repeat(32))

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertTrue(result.engineError!!.contains("Prüfsumme"), "was: ${result.engineError}")
    }

    @Test
    fun `an HTTP error is an engine error with the status`() {
        val result = client(serve("/api/validate", 500, "boom")).validate(pdf)

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertTrue(result.engineError!!.contains("HTTP 500"), "was: ${result.engineError}")
    }

    @Test
    fun `an unreadable answer is an engine error`() {
        val result = client(serve("/api/validate", 200, "not json {{{")).validate(pdf)

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertTrue(result.engineError!!.contains("nicht auswertbar"), "was: ${result.engineError}")
    }

    @Test
    fun `an unreachable service is an engine error`() {
        val unused = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = unused.address.port
        unused.stop(0)

        val result = client("http://127.0.0.1:$port").validate(pdf)

        assertEquals(KositOutcome.ERROR, result.outcome)
        // A refused connection answers at once; a host that drops packets runs into the deadline.
        // Both are the same statement: the check could not be performed.
        assertTrue(
            result.engineError!!.contains("nicht erreichbar") || result.engineError!!.contains("Timeout"),
            "was: ${result.engineError}",
        )
    }

    @Test
    fun `a service that answers too late is an engine error after the deadline`() {
        val result = client(serve("/api/validate", 200, compliant, delayMillis = 3_000), timeoutSeconds = 1).validate(pdf)

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertTrue(result.engineError!!.contains("Timeout"), "was: ${result.engineError}")
    }

    @Test
    fun `the service version is read from the info answer`() {
        assertEquals("1.30.2", client(serve("/api/info", 200, """{"name":"x","version":"1.30.2"}""")).serviceVersion())
    }

    @Test
    fun `an info answer without a version still proves the service is up under the pinned version`() {
        assertEquals("1.30.2", client(serve("/api/info", 200, """{"name":"x"}""")).serviceVersion())
        assertEquals("1.30.2", client(serve("/api/info", 200, "not json {{{")).serviceVersion())
    }

    @Test
    fun `a failing or missing service reports no version`() {
        assertNull(client(serve("/api/info", 503, "down")).serviceVersion())
        val unused = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = unused.address.port
        unused.stop(0)
        assertNull(client("http://127.0.0.1:$port").serviceVersion())
    }

    @Test
    fun `an info answer with a blank version is reported under the pinned version`() {
        assertEquals("1.30.2", client(serve("/api/info", 200, """{"version":"  "}""")).serviceVersion())
    }

    @Test
    fun `an interrupted validation is an engine error and keeps the interrupt flag`() {
        val interrupted = mock<HttpClient>()
        whenever(interrupted.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())).thenThrow(InterruptedException("stop"))

        try {
            val result = VeraPdfRestClient(baseUrl = "http://unused", pinnedVersion = "1.30.2", timeoutSeconds = 1, http = interrupted).validate(pdf)

            assertEquals(KositOutcome.ERROR, result.outcome)
            assertTrue(result.engineError!!.contains("unterbrochen"), "was: ${result.engineError}")
            assertTrue(Thread.currentThread().isInterrupted, "the interrupt must stay visible to the caller")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an interrupted version probe reports no version and keeps the interrupt flag`() {
        val interrupted = mock<HttpClient>()
        whenever(interrupted.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())).thenThrow(InterruptedException("stop"))

        try {
            assertNull(VeraPdfRestClient(baseUrl = "http://unused", pinnedVersion = "1.30.2", http = interrupted).serviceVersion())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a trailing slash on the base url does not double the path`() {
        client(serve("/api/validate", 200, compliant) + "/").validate(pdf)

        assertEquals("/api/validate/3b", requestPath)
    }

    @Test
    fun `a connection that breaks is an engine error naming the service`() {
        val broken = mock<HttpClient>()
        whenever(broken.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())).thenThrow(java.io.IOException("reset"))

        val result = VeraPdfRestClient(baseUrl = "http://verapdf:8080", pinnedVersion = "1.30.2", http = broken).validate(pdf)

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertTrue(result.engineError!!.contains("nicht erreichbar (http://verapdf:8080)"), "was: ${result.engineError}")
    }

    @Test
    fun `the settings come from the environment variables`() {
        val env = mapOf("VERAPDF_URL" to "http://svc:9", "VERAPDF_VERSION" to "1.31.0", "VERAPDF_TIMEOUT" to "42")

        assertEquals(VeraPdfRestSettings("http://svc:9", "1.31.0", 42L), VeraPdfRestSettings.from { env[it] })
    }

    @Test
    fun `missing or unusable variables fall back to the defaults`() {
        val defaults = VeraPdfRestSettings.from { null }
        val unusable = VeraPdfRestSettings.from { if (it == "VERAPDF_TIMEOUT") "soon" else null }

        assertEquals(VeraPdfRestClient.DEFAULT_URL, defaults.baseUrl)
        assertEquals(VeraPdfRestClient.DEFAULT_TIMEOUT_SECONDS, defaults.timeoutSeconds)
        assertEquals(defaults, unusable)
    }

    @Test
    fun `a client given only the url pins the version from the settings`() {
        val url = serve("/api/info", 200, """{"name":"x"}""")

        assertEquals(VeraPdfRestSettings.fromEnvironment().pinnedVersion, VeraPdfRestClient(baseUrl = url).serviceVersion())
    }
}
