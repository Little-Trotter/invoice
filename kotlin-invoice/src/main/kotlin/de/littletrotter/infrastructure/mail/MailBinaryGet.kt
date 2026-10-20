package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailDispatchException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One binary read: [path] below `/mail/api`, the [accept] header, and the [label] used in errors. */
internal data class MailBinaryRequest(val path: String, val accept: String, val label: String = path)

/**
 * The one way this application fetches a binary body from the mail component. The JSON-only
 * [MailApiClient] cannot carry bytes, and every binary read (archive package, staged attachment)
 * must answer the same way: 200 is the response, 404 is `null` ("nothing there"), and a missing
 * token, an unreachable component or any other status is a [MailDispatchException] the caller can
 * show. Keeping that in one place means a second binary endpoint cannot drift from the first.
 */
internal object MailBinaryGet {

    private const val HTTP_OK = 200
    private const val HTTP_NOT_FOUND = 404
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

    /** A client with the connect timeout and redirect policy every binary read uses. */
    fun newClient(): HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * `GET <baseUrl>/mail/api<path>`; the 200 response, or `null` on 404. The request's label names
     * the call in error messages, so a path carrying an identifier worth keeping out of logs is masked.
     */
    fun fetch(client: HttpClient, baseUrl: String, token: String, call: MailBinaryRequest): HttpResponse<ByteArray>? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/mail/api${call.path}"))
            .header("Accept", call.accept)
            .header("Authorization", "Bearer ${bearerOf(token)}")
            .GET()
            .timeout(REQUEST_TIMEOUT)
            .build()
        val response = send(client, baseUrl, request)
        return when (response.statusCode()) {
            HTTP_OK -> response
            HTTP_NOT_FOUND -> null
            else -> throw MailDispatchException("The email component answered HTTP ${response.statusCode()} on GET ${call.label}")
        }
    }

    private fun bearerOf(token: String): String =
        token.trim().ifBlank {
            throw MailDispatchException("MAIL_INTEGRATION_TOKEN is not configured — the mail API cannot be called")
        }

    private fun send(client: HttpClient, baseUrl: String, request: HttpRequest): HttpResponse<ByteArray> =
        try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: IOException) {
            throw MailDispatchException("The email component at $baseUrl is not reachable: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MailDispatchException("The call to the email component was interrupted", e)
        }
}
