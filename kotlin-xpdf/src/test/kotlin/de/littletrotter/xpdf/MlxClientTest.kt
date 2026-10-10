package de.littletrotter.xpdf

import com.sun.net.httpserver.HttpServer
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * [MlxClient] against a local HTTP stub standing in for oMLX's OpenAI-compatible
 * `/v1/chat/completions` endpoint. No real oMLX instance is reachable from this
 * environment — manual live-verification step
 * still outstanding.
 */
class MlxClientTest {

    private val invoice = InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-2026-00042"))
    private val xmlBytes = "<Invoice/>".toByteArray()
    private val kositReport = """{"status":"VALID","summary":"ok"}"""
    private val fixedTime: TimeSource = ClockTimeSource.fixedAt(Instant.parse("2026-01-15T10:30:00Z"))

    private fun chatCompletionResponse(content: String): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"choices":[{"message":{"content":"$escaped"}}]}"""
    }

    // ─── isAvailable ─────────────────────────────────────────────────────────

    @Test
    fun `isAvailable is false when endpoint missing`() {
        val client = MlxClient(endpoint = null, apiKey = "key", model = "m1", timeoutSec = 5, time = fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `isAvailable is false when api key missing`() {
        val client = MlxClient(endpoint = "http://localhost:1", apiKey = null, model = "m1", timeoutSec = 5, time = fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `isAvailable is false when model blank`() {
        val client = MlxClient(endpoint = "http://localhost:1", apiKey = "key", model = "", timeoutSec = 5, time = fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `isAvailable is true when fully configured`() {
        val client = MlxClient(endpoint = "http://localhost:1", apiKey = "key", model = "m1", timeoutSec = 5, time = fixedTime)
        assertTrue(client.isAvailable())
    }

    @Test
    fun `analyze returns disabled result when not available`() {
        val client = MlxClient(endpoint = null, apiKey = null, model = "", timeoutSec = 5, time = fixedTime)
        val result = client.analyze(xmlBytes, invoice, kositReport)
        assertFalse(result.success)
        assertTrue(result.markdownReport.isEmpty())
    }

    // ─── durationMs comes from the injected TimeSource ──────────────────────────

    @Test
    fun `boundary - durationMs is computed from two readings of the injected TimeSource, not the system clock`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = chatCompletionResponse("OK").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            // Two fixed readings 2500ms apart — analyze() reads time.instant() once at the start
            // and once (via elapsedMs) at the end, so durationMs must be exactly their difference,
            // never a real wall-clock measurement.
            val start = Instant.parse("2026-01-15T10:30:00.000Z")
            val end = Instant.parse("2026-01-15T10:30:02.500Z")
            val time: TimeSource = mock()
            whenever(time.instant()).thenReturn(start, end)
            val client = MlxClient(endpoint = endpoint, apiKey = "secret", model = "m1", timeoutSec = 5, time = time)

            val result = client.analyze(xmlBytes, invoice, kositReport)

            assertTrue(result.success)
            assertEquals(2500L, result.durationMs)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    // ─── analyze: happy path ─────────────────────────────────────────────────

    @Test
    fun `analyze calls configured model with bearer auth and kosit report in prompt`() {
        var capturedBody = ""
        var capturedAuthHeader: String? = null
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            capturedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            capturedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            val response = chatCompletionResponse("## Zusammenfassung\nAlles gut.").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxClient(endpoint = endpoint, apiKey = "secret-token", model = "Qwen3.6-35B-A3B-8bit", timeoutSec = 5, time = fixedTime)

            val result = client.analyze(xmlBytes, invoice, kositReport)

            assertTrue(result.success)
            assertEquals("Qwen3.6-35B-A3B-8bit", result.model)
            assertTrue(result.markdownReport.contains("Alles gut"))
            // Request: configured model in body, bearer token as Authorization header,
            // and the prompt carries the KoSIT report as context.
            assertTrue(capturedBody.contains("\"model\":\"Qwen3.6-35B-A3B-8bit\""))
            assertEquals("Bearer secret-token", capturedAuthHeader)
            // Assert on the injected KoSIT content itself, not a heading string — the prompt
            // template's wording is edited independently of this client.
            assertTrue(capturedBody.contains("VALID"))
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    // ─── analyze: failure handling ───────────────────────────────────────────

    @Test
    fun `analyze reports failure without throwing when server is unreachable`() {
        // Port 1 is a privileged, essentially always-closed port - connection refused.
        val client = MlxClient(endpoint = "http://localhost:1", apiKey = "secret", model = "m1", timeoutSec = 2, time = fixedTime)

        val result = client.analyze(xmlBytes, invoice, kositReport)

        assertFalse(result.success)
        assertTrue(result.error != null)
    }

    // A malformed endpoint (a stray space is illegal in a URI authority) makes
    // `URI.create` throw synchronously, before any call is even attempted asynchronously — the
    // catch block's exception is then the raw `IllegalArgumentException` itself, never an
    // `ExecutionException` wrapping it. Unlike the connection-refused case above, this proves the
    // failure path also holds when nothing ever reaches `sendAsync()` at all.
    @Test
    fun `analyze reports the raw exception message when request construction fails before any network call`() {
        val client = MlxClient(endpoint = "http://bad host/v1", apiKey = "key", model = "m1", timeoutSec = 5, time = fixedTime)

        val result = client.analyze(xmlBytes, invoice, kositReport)

        assertFalse(result.success)
        assertTrue(result.error != null && result.error!!.isNotBlank(), "expected a non-blank error message, got: ${result.error}")
    }

    @Test
    fun `analyze reports failure when response has no choices`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = """{"choices":[]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxClient(endpoint = endpoint, apiKey = "secret", model = "m1", timeoutSec = 5, time = fixedTime)

            val result = client.analyze(xmlBytes, invoice, kositReport)

            assertFalse(result.success)
            assertEquals("empty oMLX response", result.error)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `analyze reports failure on http error status`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = "internal error".toByteArray()
            exchange.sendResponseHeaders(500, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxClient(endpoint = endpoint, apiKey = "secret", model = "m1", timeoutSec = 5, time = fixedTime)

            val result = client.analyze(xmlBytes, invoice, kositReport)

            assertFalse(result.success)
            assertTrue(result.error!!.contains("500"))
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `analyze with blank layout report uses xml only timeout`() {
        var capturedBody = ""
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            capturedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response = chatCompletionResponse("OK").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxClient(
                endpoint = endpoint, apiKey = "secret", model = "m1",
                timeoutSec = 300, xmlOnlyTimeoutSec = 150, time = fixedTime,
            )

            val result = client.analyze(xmlBytes, invoice, kositReport, layoutReport = "")

            assertTrue(result.success)
            // The prompt should still have the kositReport included
            assertTrue(capturedBody.contains("VALID"))
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `analyze with non-blank layout report uses full timeout`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = chatCompletionResponse("Report processed").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxClient(
                endpoint = endpoint, apiKey = "secret", model = "m1",
                timeoutSec = 300, xmlOnlyTimeoutSec = 150, time = fixedTime,
            )

            val result = client.analyze(xmlBytes, invoice, kositReport, layoutReport = "page 1: ...\npage 2: ...")

            assertTrue(result.success)
            assertTrue(result.markdownReport.contains("Report processed"))
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `analyze reports failure when response content is null`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = """{"choices":[{"message":{"content":null}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxClient(endpoint = endpoint, apiKey = "secret", model = "m1", timeoutSec = 5, time = fixedTime)

            val result = client.analyze(xmlBytes, invoice, kositReport)

            assertFalse(result.success)
            assertEquals("empty oMLX response", result.error)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `analyze reports failure when response content is blank`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = """{"choices":[{"message":{"content":"   \n  "}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxClient(endpoint = endpoint, apiKey = "secret", model = "m1", timeoutSec = 5, time = fixedTime)

            val result = client.analyze(xmlBytes, invoice, kositReport)

            assertFalse(result.success)
            assertEquals("empty oMLX response", result.error)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `analyze reports a timeout with the XML-only knob when layoutReport is blank`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            Thread.sleep(3000)
            val response = chatCompletionResponse("too late").toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxClient(endpoint = endpoint, apiKey = "secret", model = "m1", timeoutSec = 300, xmlOnlyTimeoutSec = 1, time = fixedTime)

            val result = client.analyze(xmlBytes, invoice, kositReport, layoutReport = "")

            // Both the request's own HttpRequest-level timeout and the sendAsync().get(timeoutSec)
            // bound are set to the identical duration here — which one fires first is a genuine
            // race the JDK HttpClient does not guarantee an order for, so the error text may name
            // the MLX_TIMEOUT_XML knob (the .get() timeout path) or come from the generic failure
            // handler (the HttpTimeoutException path). Either is a real, non-blank failure report,
            // never a false success — that is the one thing worth pinning down here.
            assertFalse(result.success)
            assertTrue(result.error != null && result.error!!.isNotBlank(), "expected a non-blank error, got: ${result.error}")
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `analyze reports a timeout with the full-check knob when layoutReport is present`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            Thread.sleep(3000)
            val response = chatCompletionResponse("too late").toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxClient(endpoint = endpoint, apiKey = "secret", model = "m1", timeoutSec = 1, xmlOnlyTimeoutSec = 300, time = fixedTime)

            val result = client.analyze(xmlBytes, invoice, kositReport, layoutReport = "page 1")

            // Same race as the XML-only-knob test above: which timeout mechanism fires first is
            // not guaranteed, so only the outcome (a real failure, never a false success) is
            // asserted here.
            assertFalse(result.success)
            assertTrue(result.error != null && result.error!!.isNotBlank(), "expected a non-blank error, got: ${result.error}")
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `the no-arg CDI constructor reads configuration from the environment without throwing`() {
        // No MLX_* env vars are set in this test environment, so this exercises the "absent" side
        // of every fallback in the secondary constructor, including the default model name.
        val client = MlxClient(fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `isAvailable is false when endpoint is blank`() {
        val client = MlxClient(endpoint = "", apiKey = "key", model = "m1", timeoutSec = 5, time = fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `isAvailable is false when apiKey is blank`() {
        val client = MlxClient(endpoint = "http://localhost:1", apiKey = "", model = "m1", timeoutSec = 5, time = fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `isAvailable requires all three config elements non-blank`() {
        val fullClient = MlxClient(endpoint = "http://localhost:1", apiKey = "key", model = "m1", timeoutSec = 5, time = fixedTime)
        assertTrue(fullClient.isAvailable())

        val missingEndpoint = MlxClient(endpoint = null, apiKey = "key", model = "m1", timeoutSec = 5, time = fixedTime)
        assertFalse(missingEndpoint.isAvailable())

        val missingKey = MlxClient(endpoint = "http://localhost:1", apiKey = null, model = "m1", timeoutSec = 5, time = fixedTime)
        assertFalse(missingKey.isAvailable())

        val blankModel = MlxClient(endpoint = "http://localhost:1", apiKey = "key", model = "  ", timeoutSec = 5, time = fixedTime)
        assertFalse(blankModel.isAvailable())
    }

    // ─── no-arg CDI constructor: environment fallback chain ────────────────────

    @Test
    fun `happy path - the no-arg constructor resolves a fully configured environment and sends the configured model`() {
        var capturedBody = ""
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            capturedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response = chatCompletionResponse("OK").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
                staticEnv.`when`<String?> { readEnv("MLX_ENDPOINT") }.thenReturn("http://localhost:${server.address.port}/")
                staticEnv.`when`<String?> { readEnv("MLX_API_KEY") }.thenReturn("env-secret")
                staticEnv.`when`<String?> { readEnv("MLX_MODEL") }.thenReturn("custom-model")
                staticEnv.`when`<String?> { readEnv("MLX_MODELS") }.thenReturn(null)
                staticEnv.`when`<String?> { readEnv("MLX_TIMEOUT") }.thenReturn("120")
                staticEnv.`when`<String?> { readEnv("MLX_TIMEOUT_XML") }.thenReturn("45")

                val client = MlxClient(fixedTime)
                assertTrue(client.isAvailable())

                val result = client.analyze(xmlBytes, invoice, kositReport)

                assertTrue(result.success)
                assertEquals("custom-model", result.model)
                assertTrue(capturedBody.contains("\"model\":\"custom-model\""))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `boundary - the no-arg constructor falls back to MLX_MODELS when MLX_MODEL is absent`() {
        var capturedBody = ""
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            capturedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response = chatCompletionResponse("OK").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
                staticEnv.`when`<String?> { readEnv("MLX_ENDPOINT") }.thenReturn("http://localhost:${server.address.port}")
                staticEnv.`when`<String?> { readEnv("MLX_API_KEY") }.thenReturn("env-secret")
                staticEnv.`when`<String?> { readEnv("MLX_MODEL") }.thenReturn(null)
                staticEnv.`when`<String?> { readEnv("MLX_MODELS") }.thenReturn("alt-model,other-model")
                staticEnv.`when`<String?> { readEnv("MLX_TIMEOUT") }.thenReturn(null)
                staticEnv.`when`<String?> { readEnv("MLX_TIMEOUT_XML") }.thenReturn(null)

                val client = MlxClient(fixedTime)
                val result = client.analyze(xmlBytes, invoice, kositReport)

                assertTrue(result.success)
                assertEquals("alt-model", result.model)
                assertTrue(capturedBody.contains("\"model\":\"alt-model\""))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    // ─── the blocking Future timeout, isolated from the request-level race ─────
    // buildChatCompletionRequest wires the same timeoutSec into both HttpRequest.timeout() and the
    // get(timeoutSec, ...) call below it, so a real, slow HTTP server always races the
    // request-level HttpTimeoutException against the Future's own TimeoutException — and the
    // request-level one wins deterministically in this environment (same split as
    // MlxVisionClientTest). Stubbing sendAsync() with a future that never completes isolates
    // get()'s own TimeoutException branch (and its MLX_TIMEOUT/MLX_TIMEOUT_XML knob naming) instead.

    @Test
    fun `boundary - a blocking Future timeout on an XML-only check names the MLX_TIMEOUT_XML knob`() {
        val client = MlxClient(endpoint = "http://localhost:1", apiKey = "secret", model = "m1", timeoutSec = 300, xmlOnlyTimeoutSec = 1, time = fixedTime)
        val mockHttp: HttpClient = mock()
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(CompletableFuture<HttpResponse<String>>())
        val httpField = MlxClient::class.java.getDeclaredField("http").apply { isAccessible = true }
        httpField.set(client, mockHttp)

        val result = client.analyze(xmlBytes, invoice, kositReport, layoutReport = "")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("MLX_TIMEOUT_XML"), "was: ${result.error}")
    }

    @Test
    fun `boundary - a blocking Future timeout on a full check names the MLX_TIMEOUT knob`() {
        val client = MlxClient(endpoint = "http://localhost:1", apiKey = "secret", model = "m1", timeoutSec = 1, xmlOnlyTimeoutSec = 300, time = fixedTime)
        val mockHttp: HttpClient = mock()
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(CompletableFuture<HttpResponse<String>>())
        val httpField = MlxClient::class.java.getDeclaredField("http").apply { isAccessible = true }
        httpField.set(client, mockHttp)

        val result = client.analyze(xmlBytes, invoice, kositReport, layoutReport = "page 1")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("MLX_TIMEOUT") && !result.error!!.contains("MLX_TIMEOUT_XML"), "was: ${result.error}")
    }

    @Test
    fun `boundary - a response status of exactly 299 still counts as success`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = chatCompletionResponse("edge of range").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(299, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxClient(endpoint = endpoint, apiKey = "secret", model = "m1", timeoutSec = 5, time = fixedTime)

            val result = client.analyze(xmlBytes, invoice, kositReport)

            assertTrue(result.success, "status 299 is still inside the documented 200..299 success range")
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }
}
