package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.MailCaller
import de.littletrotter.application.mail.MailCallerProvider
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.MailNotFoundException
import de.littletrotter.infrastructure.http.serviceRequest
import de.littletrotter.shared.mail.MailContractViolationException
import de.littletrotter.shared.mail.MailWireCodec
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

/**
 * The single place that talks HTTP to the mail component's `/mail/api/…`.
 *
 * Every call carries the bearer token (proves the call comes from this application) and the
 * acting user's identity headers (what the component authorises by), so no individual adapter can
 * forget either. Built on the JDK `HttpClient`; bodies and answers pass through the contract's own
 * codec, never reflection over local types, so a renamed Kotlin property cannot rename a wire field.
 *
 * Failures never degrade: an unreachable component, a non-2xx answer or an answer that does not
 * match the contract all surface as [MailDispatchException] with a message the UI can show as-is.
 */
@ApplicationScoped
class MailApiClient @Inject constructor(
    /** Service-to-service base URL (`http://email-app:8080` on the internal network), never a browser origin. */
    @param:ConfigProperty(name = "mail.base-url", defaultValue = "http://localhost:8090")
    private val baseUrl: String,
    /**
     * [Optional] because SmallRye turns an empty value into `null`, which would fail the whole
     * deployment for the legitimate "not configured" shape of this property.
     */
    @param:ConfigProperty(name = "mail.integration-token")
    private val integrationToken: Optional<String>,
    private val callerProvider: MailCallerProvider,
) {

    private val log = Logger.getLogger(MailApiClient::class.java)

    /** Test seam, assigned directly by the adapter tests (same module) so a stub server can answer. */
    internal var client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** GET [path] with [query] and decode the answer as [type]. */
    fun <T> get(path: String, query: List<Pair<String, String>>, what: String, type: TypeReference<T>): T =
        decode(execute("GET", path + queryString(query), null), what, type)

    /** POST [body] (may be `null`) to [path] and decode the answer as [type]. */
    fun <T> post(path: String, body: String?, what: String, type: TypeReference<T>): T =
        decode(execute("POST", path, body), what, type)

    /** PUT [body] to [path] and decode the answer as [type]. */
    fun <T> put(path: String, body: String, what: String, type: TypeReference<T>): T =
        decode(execute("PUT", path, body), what, type)

    /** POST that returns the raw JSON answer, for callers with their own field-by-field reader. */
    fun postRaw(path: String, body: String): String = execute("POST", path, body)

    private fun execute(method: String, pathAndQuery: String, body: String?): String {
        val token = integrationToken.orElse("").trim()
        if (token.isBlank()) {
            throw MailDispatchException("MAIL_INTEGRATION_TOKEN is not configured — the mail API cannot be called")
        }
        val caller = callerProvider.currentCaller()
        val request = serviceRequest(
            "${baseUrl.trimEnd('/')}$API_PREFIX$pathAndQuery",
            method,
            body,
            listOf("Accept" to "application/json", "Authorization" to "Bearer $token") + identityHeaders(caller),
        )
        val response = send(request)
        if (response.statusCode() !in SUCCESS_RANGE) {
            log.warnf("Mail API %s %s rejected with HTTP %d: %s", method, pathAndQuery.substringBefore('?'), response.statusCode(), response.body().take(BODY_EXCERPT_CHARS))
            val message = "The email component answered HTTP ${response.statusCode()} on $method ${pathAndQuery.substringBefore('?')}"
            throw if (response.statusCode() == HTTP_NOT_FOUND) MailNotFoundException(message) else MailDispatchException(message)
        }
        return response.body()
    }

    /**
     * Identity headers for [caller]. `X-Lt-Email` is omitted when no address is known: an empty
     * header would be indistinguishable from a deliberately empty one on the receiving side.
     */
    private fun identityHeaders(caller: MailCaller): List<Pair<String, String>> =
        buildList {
            add(USER_HEADER to caller.userName)
            add(GROUPS_HEADER to caller.groups.joinToString(","))
            caller.email?.let { add(EMAIL_HEADER to it) }
        }

    private fun send(request: HttpRequest): HttpResponse<String> =
        try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            // A malformed mail.base-url (IllegalArgumentException from URI.create) is a configuration
            // bug and propagates instead of being reported as "unreachable".
            throw MailDispatchException("The email component at $baseUrl is not reachable: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MailDispatchException("The call to the email component was interrupted", e)
        }

    private fun <T> decode(body: String, what: String, type: TypeReference<T>): T =
        try {
            MailWireCodec.decode(body, what, type)
        } catch (e: MailContractViolationException) {
            throw MailDispatchException("The email component's answer for $what is not readable: ${e.message}", e)
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

        /** The caller's own mailbox, from the OIDC e-mail claim; `X-Lt-User` is only a display name. */
        const val EMAIL_HEADER = "X-Lt-Email"
        private const val API_PREFIX = "/mail/api"
        private const val BODY_EXCERPT_CHARS = 300
        private const val HTTP_NOT_FOUND = 404
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val SUCCESS_RANGE = 200..299
    }
}
