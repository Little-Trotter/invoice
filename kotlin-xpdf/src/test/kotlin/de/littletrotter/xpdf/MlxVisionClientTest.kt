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
 * [MlxVisionClient] against a local HTTP stub standing in for oMLX's OpenAI-compatible
 * `/v1/chat/completions` endpoint (vision content). No real vision-capable oMLX instance is
 * reachable from this environment.
 */
class MlxVisionClientTest {

    private val invoice = InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-2026-00042"))
    private val pngBytes = byteArrayOf(1, 2, 3, 4)
    private val fixedTime: TimeSource = ClockTimeSource.fixedAt(Instant.parse("2026-01-15T10:30:00Z"))

    private fun chatCompletionResponse(content: String): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"choices":[{"message":{"content":"$escaped"}}]}"""
    }

    // ─── isAvailable ─────────────────────────────────────────────────────────

    @Test
    fun `isAvailable is false when endpoint missing`() {
        val client = MlxVisionClient(endpoint = null, apiKey = "key", model = "vision-model", timeoutSec = 5, time = fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `isAvailable is false when api key missing`() {
        val client = MlxVisionClient(endpoint = "http://localhost:1", apiKey = null, model = "vision-model", timeoutSec = 5, time = fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `isAvailable is false when model blank (no default vision model assumed)`() {
        val client = MlxVisionClient(endpoint = "http://localhost:1", apiKey = "key", model = "", timeoutSec = 5, time = fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `isAvailable is true when fully configured`() {
        val client = MlxVisionClient(endpoint = "http://localhost:1", apiKey = "key", model = "vision-model", timeoutSec = 5, time = fixedTime)
        assertTrue(client.isAvailable())
    }

    @Test
    fun `inspect returns disabled when not available`() {
        val client = MlxVisionClient(endpoint = null, apiKey = null, model = "", timeoutSec = 5, time = fixedTime)
        val result = client.inspect(pngBytes, invoice, "")
        assertFalse(result.success)
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
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            // Two fixed readings 1200ms apart — inspect() reads time.instant() once at the start
            // and once (via elapsedMs) at the end, so durationMs must be exactly their difference,
            // never a real wall-clock measurement.
            val start = Instant.parse("2026-01-15T10:30:00.000Z")
            val end = Instant.parse("2026-01-15T10:30:01.200Z")
            val time: TimeSource = mock()
            whenever(time.instant()).thenReturn(start, end)
            val client = MlxVisionClient(endpoint = endpoint, apiKey = "secret", model = "vision-model", timeoutSec = 5, time = time)

            val result = client.inspect(pngBytes, invoice)

            assertTrue(result.success)
            assertEquals(1200L, result.durationMs)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `inspect reports failure on connection error`() {
        val client = MlxVisionClient(endpoint = "http://localhost:1", apiKey = "key", model = "vision-model", timeoutSec = 1, time = fixedTime)
        val result = client.inspect(pngBytes, invoice, "")
        assertFalse(result.success)
        assertTrue(result.error != null)
    }

    @Test
    fun `isAvailable with blank endpoint`() {
        val client = MlxVisionClient(endpoint = "", apiKey = "key", model = "model", timeoutSec = 5, time = fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `isAvailable with blank apiKey`() {
        val client = MlxVisionClient(endpoint = "http://localhost:1", apiKey = "", model = "model", timeoutSec = 5, time = fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `inspect returns disabled result when not available`() {
        val client = MlxVisionClient(endpoint = null, apiKey = null, model = "", timeoutSec = 5, time = fixedTime)
        val result = client.inspect(pngBytes, invoice)
        assertFalse(result.success)
        assertTrue(result.markdownReport.isEmpty())
    }

    // ─── inspect: happy path ─────────────────────────────────────────────────

    @Test
    fun `inspect sends image as base64 data URI alongside the prompt text`() {
        var capturedBody = ""
        var capturedAuthHeader: String? = null
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            capturedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            capturedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            val response = chatCompletionResponse("## Visueller Eindruck\n9/10").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxVisionClient(endpoint = endpoint, apiKey = "secret-token", model = "vision-model", timeoutSec = 5, time = fixedTime)

            val result = client.inspect(pngBytes, invoice, layoutReport = "Verletzungen: keine")

            assertTrue(result.success)
            assertEquals("vision-model", result.model)
            assertTrue(result.markdownReport.contains("9/10"))
            assertEquals("Bearer secret-token", capturedAuthHeader)
            assertTrue(capturedBody.contains("\"model\":\"vision-model\""))
            assertTrue(capturedBody.contains("\"type\":\"image_url\""))
            assertTrue(capturedBody.contains("data:image/png;base64,"))
            assertTrue(capturedBody.contains("Verletzungen: keine"))
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    // ─── inspect: failure handling ───────────────────────────────────────────

    @Test
    fun `inspect reports failure without throwing when server is unreachable`() {
        val client = MlxVisionClient(endpoint = "http://localhost:1", apiKey = "secret", model = "vision-model", timeoutSec = 2, time = fixedTime)

        val result = client.inspect(pngBytes, invoice)

        assertFalse(result.success)
        assertTrue(result.error != null)
    }

    // Same distinction as MlxClientTest's equivalent case: a malformed endpoint fails
    // `URI.create` synchronously, so the caught exception is the raw `IllegalArgumentException`
    // itself, never an `ExecutionException` wrapping it from `sendAsync()`/`get()`.
    @Test
    fun `inspect reports the raw exception message when request construction fails before any network call`() {
        val client = MlxVisionClient(endpoint = "http://bad host/v1", apiKey = "key", model = "vision-model", timeoutSec = 5, time = fixedTime)

        val result = client.inspect(pngBytes, invoice)

        assertFalse(result.success)
        assertTrue(result.error != null && result.error!!.isNotBlank(), "expected a non-blank error message, got: ${result.error}")
    }

    @Test
    fun `inspect reports failure when response has no choices`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = """{"choices":[]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxVisionClient(endpoint = endpoint, apiKey = "secret", model = "vision-model", timeoutSec = 5, time = fixedTime)

            val result = client.inspect(pngBytes, invoice)

            assertFalse(result.success)
            assertEquals("empty oMLX vision response", result.error)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `inspect reports failure when response content is null`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = """{"choices":[{"message":{"content":null}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxVisionClient(endpoint = endpoint, apiKey = "secret", model = "vision-model", timeoutSec = 5, time = fixedTime)

            val result = client.inspect(pngBytes, invoice)

            assertFalse(result.success)
            assertEquals("empty oMLX vision response", result.error)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `inspect reports failure when response content is blank`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = """{"choices":[{"message":{"content":"   "}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxVisionClient(endpoint = endpoint, apiKey = "secret", model = "vision-model", timeoutSec = 5, time = fixedTime)

            val result = client.inspect(pngBytes, invoice)

            assertFalse(result.success)
            assertEquals("empty oMLX vision response", result.error)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `inspect reports a timeout distinctly when the server never answers in time`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            Thread.sleep(3000)
            val response = chatCompletionResponse("too late").toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxVisionClient(endpoint = endpoint, apiKey = "secret", model = "vision-model", timeoutSec = 1, time = fixedTime)

            val result = client.inspect(pngBytes, invoice)

            assertFalse(result.success)
            assertTrue(result.error!!.contains("timed out"), "was: ${result.error}")
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `boundary - inspect reports the blocking Future timeout distinctly, not a request-level HttpTimeoutException`() {
        // buildChatCompletionRequest wires the same timeoutSec into both HttpRequest.timeout()
        // and the get(timeoutSec, ...) call below it, so a real, slow HTTP server always races
        // the request-level HttpTimeoutException against the Future's own TimeoutException — and
        // the request-level one wins deterministically in this environment (same split as
        // the "before any network call" case above). Stubbing sendAsync() with a future that
        // never completes isolates get()'s own TimeoutException branch instead.
        val client = MlxVisionClient(endpoint = "http://localhost:1", apiKey = "secret", model = "vision-model", timeoutSec = 1, time = fixedTime)
        val mockHttp: HttpClient = mock()
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(CompletableFuture<HttpResponse<String>>())
        val httpField = MlxVisionClient::class.java.getDeclaredField("http").apply { isAccessible = true }
        httpField.set(client, mockHttp)

        val result = client.inspect(pngBytes, invoice)

        assertFalse(result.success)
        assertTrue(result.error!!.contains("timed out"), "was: ${result.error}")
    }

    @Test
    fun `the no-arg CDI constructor reads configuration from the environment without throwing`() {
        // No MLX_VISION_* / MLX_* env vars are set in this test environment, so this exercises
        // the "absent" side of every fallback in the secondary constructor; isAvailable() must
        // come back false rather than throwing, since MLX_VISION_MODEL has no default.
        val client = MlxVisionClient(fixedTime)
        assertFalse(client.isAvailable())
    }

    @Test
    fun `boundary - the no-arg CDI constructor prefers MLX_VISION_ env vars and trims a trailing slash`() {
        mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { readEnv("MLX_VISION_ENDPOINT") }.thenReturn("http://vision:9000/")
            staticEnv.`when`<String?> { readEnv("MLX_ENDPOINT") }.thenReturn("http://text:9000/")
            staticEnv.`when`<String?> { readEnv("MLX_VISION_API_KEY") }.thenReturn("vision-key")
            staticEnv.`when`<String?> { readEnv("MLX_API_KEY") }.thenReturn("text-key")
            staticEnv.`when`<String?> { readEnv("MLX_VISION_MODEL") }.thenReturn("vision-model")
            staticEnv.`when`<String?> { readEnv("MLX_VISION_TIMEOUT") }.thenReturn("45")

            val client = MlxVisionClient(fixedTime)

            assertTrue(client.isAvailable())
        }
    }

    @Test
    fun `boundary - the no-arg CDI constructor falls back to the shared MLX_ vars and default timeout`() {
        mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { readEnv("MLX_VISION_ENDPOINT") }.thenReturn(null)
            staticEnv.`when`<String?> { readEnv("MLX_ENDPOINT") }.thenReturn("http://text:9000")
            staticEnv.`when`<String?> { readEnv("MLX_VISION_API_KEY") }.thenReturn(null)
            staticEnv.`when`<String?> { readEnv("MLX_API_KEY") }.thenReturn("text-key")
            staticEnv.`when`<String?> { readEnv("MLX_VISION_MODEL") }.thenReturn("vision-model")
            staticEnv.`when`<String?> { readEnv("MLX_VISION_TIMEOUT") }.thenReturn("not-a-number")

            val client = MlxVisionClient(fixedTime)

            assertTrue(client.isAvailable())
        }
    }

    @Test
    fun `boundary - inspect treats http 299 as success`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = chatCompletionResponse("edge ok").toByteArray()
            exchange.sendResponseHeaders(299, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxVisionClient(endpoint = endpoint, apiKey = "secret", model = "vision-model", timeoutSec = 5, time = fixedTime)
            val result = client.inspect(pngBytes, invoice)
            assertTrue(result.success)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `boundary - inspect treats http 300 as failure`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = "redirected".toByteArray()
            exchange.sendResponseHeaders(300, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxVisionClient(endpoint = endpoint, apiKey = "secret", model = "vision-model", timeoutSec = 5, time = fixedTime)
            val result = client.inspect(pngBytes, invoice)
            assertFalse(result.success)
            assertTrue(result.error!!.contains("300"))
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `inspect reports failure on http error status`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val response = "internal error".toByteArray()
            exchange.sendResponseHeaders(500, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val endpoint = "http://localhost:${server.address.port}"
            val client = MlxVisionClient(endpoint = endpoint, apiKey = "secret", model = "vision-model", timeoutSec = 5, time = fixedTime)

            val result = client.inspect(pngBytes, invoice)

            assertFalse(result.success)
            assertTrue(result.error!!.contains("500"))
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }
}
