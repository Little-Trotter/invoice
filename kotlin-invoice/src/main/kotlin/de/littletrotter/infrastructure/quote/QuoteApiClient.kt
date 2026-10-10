package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteCaller
import de.littletrotter.application.quote.QuoteCallerProvider
import de.littletrotter.application.quote.QuoteNoNumberRangeException
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.QuoteWriteGuard
import de.littletrotter.infrastructure.http.serviceRequest
import de.littletrotter.shared.quote.QuoteContractViolationException
import de.littletrotter.shared.quote.QuoteErrorView
import de.littletrotter.shared.quote.QuoteWireCodec
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional

/** Raw answer of a binary download: the bytes and the headers that name them. */
class QuoteRawAnswer(val bytes: ByteArray, val contentType: String?, val contentDisposition: String?)

/**
 * The single place that talks HTTP to the offer component's `/quote/api/…`.
 *
 * Every call carries the bearer token (proves the call comes from this application) and the
 * acting user's identity headers (what the component authorises and audits by), so no adapter can
 * forget either. Bodies and answers pass through the contract's own codec, never reflection over
 * local types, so a renamed Kotlin property cannot rename a wire field.
 *
 * Failures never degrade: an unreachable component, a refusal or an answer that does not match the
 * contract surface as [QuoteServiceException]. A refusal carries the component's own explanation
 * (400 validation, 409 life-cycle rule) so the UI can show why an action was not possible.
 */
@ApplicationScoped
class QuoteApiClient @Inject constructor(
    /** Service-to-service base URL (`http://quote-app:8080` on the internal network), never a browser origin. */
    @param:ConfigProperty(name = "quote.base-url", defaultValue = "http://localhost:8095")
    private val baseUrl: String,
    /**
     * [Optional] because SmallRye turns an empty value into `null`, which would fail the whole
     * deployment for the legitimate "not configured" shape of this property.
     */
    @param:ConfigProperty(name = "quote.integration-token")
    private val integrationToken: Optional<String>,
    private val callerProvider: QuoteCallerProvider,
    // Defaulted (null): Arc injects the real bean; component tests without it read "module on".
    private val writeGuard: QuoteWriteGuard? = null,
) {

    private val log = Logger.getLogger(QuoteApiClient::class.java)

    /** Test seam, assigned directly by the adapter tests (same module) so a stub server can answer. */
    internal var client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** GET [path] with [query] and decode the answer as [type]. */
    fun <T> get(path: String, query: List<Pair<String, String>>, what: String, type: TypeReference<T>): T =
        decode(text(execute("GET", path + queryString(query), null)), what, type)

    /** GET the binary content at [path]. */
    fun getBytes(path: String): QuoteRawAnswer =
        execute("GET", path, null).let {
            QuoteRawAnswer(
                it.body(),
                it.headers().firstValue("Content-Type").orElse(null),
                it.headers().firstValue("Content-Disposition").orElse(null),
            )
        }

    /** POST [body] (may be `null`) to [path] and decode the answer as [type]. */
    fun <T> post(path: String, body: String?, what: String, type: TypeReference<T>): T =
        decode(text(execute("POST", path, body, guarded = true)), what, type)

    /** POST to [path] whose success answer carries no content. */
    fun postNoContent(path: String, body: String?) {
        execute("POST", path, body, guarded = true)
    }

    /**
     * PUT [body] to [path] whose success answer carries no content. [guarded] `false` is for the
     * offer defaults, which stay editable while the module is off so they can be prepared.
     */
    fun put(path: String, body: String, guarded: Boolean = true) {
        execute("PUT", path, body, guarded)
    }

    /** DELETE [path]. */
    fun delete(path: String) {
        execute("DELETE", path, null, guarded = true)
    }

    private fun text(response: HttpResponse<ByteArray>): String = String(response.body(), Charsets.UTF_8)

    private fun execute(
        method: String,
        pathAndQuery: String,
        body: String?,
        guarded: Boolean = false,
    ): HttpResponse<ByteArray> {
        if (guarded) writeGuard?.requireEnabled()
        val token = integrationToken.orElse("").trim()
        if (token.isBlank()) {
            throw QuoteServiceException("QUOTE_INTEGRATION_TOKEN ist nicht konfiguriert — die Angebotskomponente kann nicht aufgerufen werden")
        }
        val caller = callerProvider.currentCaller()
        val request = serviceRequest(
            "${baseUrl.trimEnd('/')}$API_PREFIX$pathAndQuery",
            method,
            body,
            listOf("Authorization" to "Bearer $token") + identityHeaders(caller),
        )
        val response = send(request)
        if (response.statusCode() !in SUCCESS_RANGE) {
            throw refusal(method, pathAndQuery.substringBefore('?'), response)
        }
        return response
    }

    private fun refusal(method: String, path: String, response: HttpResponse<ByteArray>): QuoteServiceException {
        val raw = String(response.body(), Charsets.UTF_8)
        log.warnf("Quote API %s %s rejected with HTTP %d: %s", method, path, response.statusCode(), raw.take(BODY_EXCERPT_CHARS))
        val explanation = explanationOf(raw)
        val message = explanation
            ?: "Die Angebotskomponente hat die Anfrage $method $path mit HTTP ${response.statusCode()} beantwortet"
        return when {
            response.statusCode() == HTTP_NOT_FOUND -> QuoteNotFoundException(message)
            response.statusCode() == HTTP_CONFLICT && explanation == NUMBER_RANGE_MISSING_CODE -> QuoteNoNumberRangeException()
            else -> QuoteServiceException(message)
        }
    }

    /** The component's own message when the body is its error shape; `null` for anything else (proxy pages, empty bodies). */
    private fun explanationOf(raw: String): String? =
        try {
            QuoteWireCodec.decode(raw, "error answer", QuoteErrorView::class.java).message.takeIf { it.isNotBlank() }
        } catch (expected: QuoteContractViolationException) {
            null
        }

    private fun identityHeaders(caller: QuoteCaller): List<Pair<String, String>> =
        listOf(USER_HEADER to caller.userName, GROUPS_HEADER to caller.groups.joinToString(","))

    private fun send(request: HttpRequest): HttpResponse<ByteArray> =
        try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: IOException) {
            // A malformed quote.base-url (IllegalArgumentException from URI.create) is a configuration
            // bug and propagates instead of being reported as "unreachable".
            throw QuoteServiceException("Die Angebotskomponente unter $baseUrl ist nicht erreichbar: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw QuoteServiceException("Der Aufruf der Angebotskomponente wurde unterbrochen", e)
        }

    private fun <T> decode(body: String, what: String, type: TypeReference<T>): T =
        try {
            QuoteWireCodec.decode(body, what, type)
        } catch (e: QuoteContractViolationException) {
            throw QuoteServiceException("Die Antwort der Angebotskomponente zu $what ist nicht lesbar: ${e.message}", e)
        }

    private fun queryString(query: List<Pair<String, String>>): String =
        if (query.isEmpty()) {
            ""
        } else {
            query.joinToString("&", prefix = "?") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        }

    companion object {
        const val USER_HEADER = "X-Lt-User"
        const val GROUPS_HEADER = "X-Lt-Groups"
        private const val API_PREFIX = "/quote"
        private const val BODY_EXCERPT_CHARS = 300
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409

        /** The reason the offer component gives when no number range is replicated. */
        private const val NUMBER_RANGE_MISSING_CODE = "quote.numberRange.missing"
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val SUCCESS_RANGE = 200..299
    }
}
