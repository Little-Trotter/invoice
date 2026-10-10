package de.littletrotter.infrastructure.vies

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import de.littletrotter.application.masterdata.VatIdVerificationResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for ViesHttpClient adapter.
 *
 * Uses a local HttpServer to mock the VIES API. Tests verify:
 * - HTTP 200 + `{"isValid": true}` → adapter returns VERIFIED
 * - HTTP 200 + `{"isValid": false}` → adapter returns FAILED
 * - HTTP 200 without `isValid` → adapter returns UNREACHABLE (an answer we cannot read is not a
 *   statement about the VAT id)
 * - HTTP 200 + malformed JSON → adapter returns UNREACHABLE
 * - HTTP 500 → adapter returns UNREACHABLE
 * - HTTP 400 → adapter returns UNREACHABLE
 * - Timeout → adapter returns UNREACHABLE
 * - Connection refused → adapter returns UNREACHABLE
 * - Formally invalid VAT ID never reaches HTTP (0 requests to server)
 */
@DisplayName("VIES HTTP Client Adapter")
class ViesHttpClientTest {

    private lateinit var server: HttpServer
    private val requestCounter = AtomicInteger(0)
    private lateinit var adapter: ViesHttpClient
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setup() {
        // Start a local HTTP server on ephemeral port (0 = let OS choose)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()

        val baseUrl = "http://127.0.0.1:${server.address.port}/vies"

        // Create adapter with the local server URL
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build()
        adapter = ViesHttpClient(
            timeoutSeconds = 1,
            baseUrl = baseUrl,
            objectMapper = objectMapper,
            httpClient = httpClient
        )
    }

    @AfterEach
    fun teardown() {
        server.stop(0)
    }

    /**
     * HTTP 200 + `{"isValid": true}` → adapter returns VERIFIED.
     */
    @Test
    @DisplayName("HTTP 200 with valid: true returns VERIFIED")
    fun http200ValidTrue() {
        server.createContext("/vies/ms/DE/vat/123456789") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val response = """{"isValid":true,"userError":"","name":"Example GmbH"}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
        }

        val result = adapter.verify("DE", "123456789")
        assertInstanceOf(VatIdVerificationResult.VERIFIED::class.java, result)
        assertEquals(1, requestCounter.get(), "Expected 1 HTTP request")
    }

    /**
     * Verbatim body captured from the production REST API on 04.09.2026
     * (`GET /rest-api/ms/DE/vat/100`). The suite's own stubs used to answer `{"valid": …}`, a field
     * the service has never sent — which is precisely why the adapter reading `valid` stayed green
     * here while reporting FAILED for every VAT id in production. Pinning a real body is the guard
     * that a hand-written stub cannot be.
     */
    @Test
    @DisplayName("a real VIES body is read through its isValid field")
    fun readsRealResponseShape() {
        val realBody = """
            {
              "isValid" : false,
              "requestDate" : "2026-09-04T18:48:52.243Z",
              "userError" : "INVALID",
              "name" : "---",
              "address" : "---",
              "requestIdentifier" : "",
              "originalVatNumber" : "100",
              "vatNumber" : "100",
              "viesApproximate" : { "name" : "---", "matchName" : 3 }
            }
        """.trimIndent()
        server.createContext("/vies/ms/DE/vat/100") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, realBody.toByteArray().size.toLong())
            exchange.responseBody.write(realBody.toByteArray())
            exchange.responseBody.close()
        }

        val result = adapter.verify("DE", "100")

        assertInstanceOf(VatIdVerificationResult.FAILED::class.java, result)
    }

    /**
     * PO report 04.09.2026 — both arguments become URL PATH SEGMENTS, and `URI.create` rejects a
     * space outright. That exception used to fall into this adapter's catch-all and surface as
     * UNREACHABLE, so a stray space in the customer dialog read as "VIES is down". The adapter
     * normalises its own input now; the stub answers only on the CLEAN path, so a request arriving
     * at all is the proof.
     */
    @Test
    @DisplayName("spaces and lower case in the arguments still hit the clean VIES path")
    fun normalisesArgumentsIntoThePath() {
        server.createContext("/vies/ms/DE/vat/136695976") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val response = """{"isValid":true}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
        }

        val result = adapter.verify("de", " 136 695 976 ")

        assertInstanceOf(VatIdVerificationResult.VERIFIED::class.java, result)
        assertEquals(1, requestCounter.get(), "the request must reach the clean path, not fail on URI.create")
    }

    /**
     * Anything still unusable after normalisation is a caller bug, and saying so beats reporting a
     * healthy service as unreachable — the exact confusion this whole fix is about.
     */
    @Test
    @DisplayName("a VAT id that cannot be a path segment is rejected, not reported as an outage")
    fun rejectsUnusableArguments() {
        assertThrows(IllegalArgumentException::class.java) { adapter.verify("DE", "1234/5678") }
        assertThrows(IllegalArgumentException::class.java) { adapter.verify("DE", "") }
        assertEquals(0, requestCounter.get(), "nothing may go on the wire")
    }

    /**
     * HTTP 200 + `{"isValid": false, "userError": "INVALID"}` → adapter returns FAILED. `INVALID`
     * is the only `userError` that is an actual verdict on the VAT id — every other `isValid:
     * false` case (see [unreachableForNonInvalidUserError]) is a service condition, not a
     * rejection of the id.
     */
    @Test
    @DisplayName("HTTP 200 with valid: false and userError INVALID returns FAILED")
    fun http200ValidFalse() {
        server.createContext("/vies/ms/FR/vat/INVALID123") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val response = """{"isValid":false,"userError":"INVALID","name":""}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
        }

        val result = adapter.verify("FR", "INVALID123")
        assertInstanceOf(VatIdVerificationResult.FAILED::class.java, result)
        assertEquals(1, requestCounter.get(), "Expected 1 HTTP request")
    }

    /**
     * `vat-id-field-ergonomics` (14.09.2026) — every `userError` other than `INVALID` (and a
     * missing one) means VIES itself has a problem, not that it rejected the VAT id. Before this
     * task all of these read identically to a genuinely invalid VAT id.
     */
    @ParameterizedTest(name = "userError {0} returns UNREACHABLE with that reason")
    @MethodSource("nonInvalidUserErrors")
    fun unreachableForNonInvalidUserError(userError: String) {
        server.createContext("/vies/ms/DE/vat/900000000") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val response = """{"isValid":false,"userError":"$userError"}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
        }

        val result = adapter.verify("DE", "900000000")

        val unreachable = assertInstanceOf(VatIdVerificationResult.UNREACHABLE::class.java, result)
        assertEquals(userError, unreachable.reason)
    }

    /** `isValid: false` with no `userError` at all → UNREACHABLE with a `null` reason. */
    @Test
    @DisplayName("isValid false without a userError returns UNREACHABLE with a null reason")
    fun unreachableWithoutUserError() {
        server.createContext("/vies/ms/DE/vat/900000001") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val response = """{"isValid":false}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
        }

        val result = adapter.verify("DE", "900000001")

        val unreachable = assertInstanceOf(VatIdVerificationResult.UNREACHABLE::class.java, result)
        assertNull(unreachable.reason)
    }

    /**
     * VERIFIED carries the trader name/address VIES returned; VIES's own placeholder `"---"`
     * (and a blank value) normalise to `null` rather than being shown as the trader's real name.
     */
    @Test
    @DisplayName("VERIFIED carries the trader name/address, with the --- placeholder mapped to null")
    fun verifiedCarriesNameAndAddress() {
        server.createContext("/vies/ms/FR/vat/12345678901") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val response = """{"isValid":true,"userError":"VALID","name":"Exemple SARL","address":"---"}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
        }

        val result = adapter.verify("FR", "12345678901")

        val verified = assertInstanceOf(VatIdVerificationResult.VERIFIED::class.java, result)
        assertEquals("Exemple SARL", verified.name)
        assertNull(verified.address, "the --- placeholder must not be shown as a real address")
    }

    /**
     * P5 (PO 14.09.2026, explicit) — every one of the 27 VIES-covered prefixes must be checkable;
     * this proves the request path `/ms/{prefix}/vat/{number}` is built correctly for all of them,
     * not just the handful the other tests happen to use.
     */
    @ParameterizedTest(name = "request path for prefix {0}")
    @MethodSource("viesPrefixes")
    fun requestPathIsCorrectForEveryViesPrefix(prefix: String) {
        server.createContext("/vies/ms/$prefix/vat/123456789") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val response = """{"isValid":true}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
        }

        val result = adapter.verify(prefix, "123456789")

        assertInstanceOf(VatIdVerificationResult.VERIFIED::class.java, result)
        assertEquals(1, requestCounter.get(), "the request for prefix $prefix must reach /ms/$prefix/vat/123456789")
    }

    companion object {
        @JvmStatic
        fun nonInvalidUserErrors(): List<String> = listOf(
            "MS_UNAVAILABLE", "TIMEOUT", "INVALID_INPUT", "MS_MAX_CONCURRENT_REQ",
            "GLOBAL_MAX_CONCURRENT_REQ", "SERVICE_UNAVAILABLE", "IP_BLOCKED", "VAT_BLOCKED",
            "INVALID_REQUESTER_INFO",
        )

        @JvmStatic
        fun viesPrefixes(): List<String> = listOf(
            "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "EL", "ES", "FI", "FR", "HR", "HU",
            "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK",
        )
    }

    /**
     * HTTP 200 without a verdict field → UNREACHABLE.
     *
     * This case asserted FAILED until 04.09.2026, and that expectation was itself part of the
     * defect: reading an unreadable answer as "invalid VAT id" is what let the adapter query the
     * wrong field name (`valid` instead of `isValid`) for its whole life without a single test
     * turning red. Not understanding the response says nothing about the VAT id.
     */
    @Test
    @DisplayName("HTTP 200 without a verdict field returns UNREACHABLE")
    fun http200MissingValidField() {
        server.createContext("/vies/ms/IT/vat/12345678901") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val response = """{"userError":"","name":"Some Company"}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
        }

        val result = adapter.verify("IT", "12345678901")
        assertInstanceOf(VatIdVerificationResult.UNREACHABLE::class.java, result)
        assertEquals(1, requestCounter.get(), "Expected 1 HTTP request")
    }

    /**
     * HTTP 200 + malformed JSON → adapter returns UNREACHABLE.
     */
    @Test
    @DisplayName("HTTP 200 with malformed JSON returns UNREACHABLE")
    fun http200MalformedJson() {
        server.createContext("/vies/ms/BE/vat/987654321") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val response = """not valid json at all"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
        }

        val result = adapter.verify("BE", "987654321")
        assertInstanceOf(VatIdVerificationResult.UNREACHABLE::class.java, result)
        assertEquals(1, requestCounter.get(), "Expected 1 HTTP request")
    }

    /**
     * HTTP 500 → adapter returns UNREACHABLE.
     */
    @Test
    @DisplayName("HTTP 500 returns UNREACHABLE")
    fun http500() {
        server.createContext("/vies/ms/ES/vat/WHATEVER") { exchange ->
            requestCounter.incrementAndGet()
            exchange.sendResponseHeaders(500, 0)
            exchange.responseBody.close()
        }

        val result = adapter.verify("ES", "WHATEVER")
        assertInstanceOf(VatIdVerificationResult.UNREACHABLE::class.java, result)
        assertEquals(1, requestCounter.get(), "Expected 1 HTTP request")
    }

    /**
     * HTTP 400 → adapter returns UNREACHABLE.
     */
    @Test
    @DisplayName("HTTP 400 returns UNREACHABLE")
    fun http400() {
        server.createContext("/vies/ms/NL/vat/BADREQUEST") { exchange ->
            requestCounter.incrementAndGet()
            exchange.sendResponseHeaders(400, 0)
            exchange.responseBody.close()
        }

        val result = adapter.verify("NL", "BADREQUEST")
        assertInstanceOf(VatIdVerificationResult.UNREACHABLE::class.java, result)
        assertEquals(1, requestCounter.get(), "Expected 1 HTTP request")
    }

    /**
     * Connection refused (point at a closed port) → adapter returns UNREACHABLE.
     */
    @Test
    @DisplayName("Connection refused returns UNREACHABLE")
    fun connectionRefused() {
        // Point adapter at a port that is definitely not listening
        val closedPortAdapter = ViesHttpClient(
            timeoutSeconds = 1,
            baseUrl = "http://127.0.0.1:1",  // port 1 is typically reserved/unreachable
            objectMapper = objectMapper,
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
        )

        val result = closedPortAdapter.verify("DE", "123456789")
        assertInstanceOf(VatIdVerificationResult.UNREACHABLE::class.java, result)
        // No HTTP request should reach our test server
        assertEquals(0, requestCounter.get(), "Expected 0 HTTP requests to test server")
    }

    /**
     * Formally invalid VAT ID never reaches HTTP layer.
     * VatIdValidator rejects it; adapter is never called in the UI, but this test
     * verifies that if an invalid ID were somehow passed to the adapter, the
     * HTTP request is made (the port doesn't validate). The *UI* validation prevents it.
     *
     * This test documents the contract: format validation is the *UI*'s responsibility,
     * not the adapter's. The adapter accepts any input and calls the API.
     */
    @Test
    @DisplayName("Formally invalid VAT ID still reaches HTTP (validation is UI's job)")
    fun invalidFormatStillCallsHttp() {
        server.createContext("/vies/ms/ZZ/vat/INVALID") { exchange ->
            requestCounter.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val response = """{"isValid":false,"userError":"INVALID_INPUT","name":""}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
        }

        // Format validation is UI's job; adapter accepts any input
        val result = adapter.verify("ZZ", "INVALID")
        // Adapter makes the call; format validation didn't happen at adapter level
        assertEquals(1, requestCounter.get(), "Adapter should call HTTP regardless of format (validation is UI's job)")
    }

    /**
     * The CDI-facing 3-arg `@Inject constructor` (which every other test bypasses by
     * supplying its own [httpClient]) delegates to the primary constructor with `httpClient =
     * null`, so [ViesHttpClient.defaultHttpClient] builds the real client lazily on first use.
     * Pointed at a reserved, always-refusing port so it stays fast and deterministic.
     */
    @Test
    @DisplayName("the CDI constructor without an injected HttpClient still builds a working default one")
    fun cdiConstructorBuildsDefaultHttpClient() {
        val adapter = ViesHttpClient(
            timeoutSeconds = 1,
            baseUrl = "http://127.0.0.1:1",
            objectMapper = objectMapper,
        )

        val result = adapter.verify("DE", "123456789")

        assertInstanceOf(VatIdVerificationResult.UNREACHABLE::class.java, result)
    }

    /**
     * Binding rule 2's timeout case, and the only test that exercises `invoice.vies.timeout`
     * itself: the server answers, but far too late. Distinct from `connectionRefused` — there
     * the connection never opens, here it opens and then stalls, which is the failure mode a
     * hanging registry actually produces. The adapter is built with `timeoutSeconds = 1`
     * (see [setup]), so a 3-second handler is comfortably past it while keeping the test fast.
     *
     * Regression value: `connectTimeout` was once hardcoded to 10s and ignored the configured
     * value entirely; a test that never waits cannot notice that.
     */
    @Test
    @DisplayName("a response slower than the configured timeout returns UNREACHABLE")
    fun responseSlowerThanTimeoutIsUnreachable() {
        server.createContext("/vies/ms/DE/vat/123456789") { exchange ->
            requestCounter.incrementAndGet()
            Thread.sleep(3_000)
            val response = """{"isValid":true}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }

        val result = adapter.verify("DE", "123456789")

        assertInstanceOf(
            VatIdVerificationResult.UNREACHABLE::class.java, result,
            "a stalled registry is not a statement about the VAT id — the status must stay unchanged",
        )
        assertEquals(1, requestCounter.get(), "the request was sent; it just never came back in time")
    }
}
