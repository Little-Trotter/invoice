package de.littletrotter.infrastructure.mail

import de.littletrotter.application.invoice.port.outbound.MailJournalAttachment
import de.littletrotter.application.invoice.port.outbound.MailJournalAttachmentRestorePort
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Optional

/**
 * Asks the mail component for the attachment it sent, over the same authenticated HTTP seam the
 * compose handover uses. Every failure — unknown copy, component down, bad token — answers `null`
 * ("not restorable from mail"): the caller then falls back to a reproduction instead of failing a
 * document download.
 *
 * Registered only with `mail.enabled=true`; otherwise [MailJournalRestoreUnavailable] answers.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailJournalAttachmentRestoreAdapter @Inject constructor(
    @param:ConfigProperty(name = "mail.base-url", defaultValue = "http://localhost:8090")
    private val baseUrl: String,
    @param:ConfigProperty(name = "mail.integration-token")
    private val integrationToken: Optional<String>,
) : MailJournalAttachmentRestorePort {

    private val log = Logger.getLogger(HttpMailJournalAttachmentRestoreAdapter::class.java)

    /** The HTTP client, and the class's only test seam. */
    internal var client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun findAttachment(referenceKey: String, sha256: String): MailJournalAttachment? {
        val token = integrationToken.orElse("").trim()
        if (token.isBlank()) {
            log.warn("MAIL_INTEGRATION_TOKEN is not configured — the mail journal cannot be asked for a copy")
            return null
        }
        val response = send(request(referenceKey, sha256, token))
        return if (response?.statusCode() == HTTP_OK) toAttachment(response) else null.also { logRefusal(response) }
    }

    /** 404 is the ordinary "no identical copy" answer; anything else is worth a line. */
    private fun logRefusal(response: HttpResponse<ByteArray>?) {
        if (response != null && response.statusCode() != HTTP_NOT_FOUND) {
            log.warnf("Mail journal copy request answered HTTP %d", response.statusCode())
        }
    }

    private fun send(request: HttpRequest): HttpResponse<ByteArray>? =
        try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: IOException) {
            log.warnf("The email component at %s is not reachable for a journal copy: %s", baseUrl, e.message)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("The journal copy request was interrupted")
            null
        }

    private fun toAttachment(response: HttpResponse<ByteArray>): MailJournalAttachment? {
        val journalId = response.headers().firstValue(HEADER_JOURNAL_ID).orElse("").toLongOrNull()
        if (journalId == null) {
            log.warn("Mail journal copy answered without a usable $HEADER_JOURNAL_ID header; ignored")
            return null
        }
        return MailJournalAttachment(response.body(), journalId, response.headers().firstValue(HEADER_JOURNAL_FILE).orElse(""))
    }

    private fun request(referenceKey: String, sha256: String, token: String): HttpRequest {
        val query = "referenceKey=${encode(referenceKey)}&sha256=${encode(sha256)}"
        return HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}$ATTACHMENT_PATH?$query"))
            .header("Accept", "application/octet-stream")
            .header("Authorization", "Bearer $token")
            .GET()
            .timeout(REQUEST_TIMEOUT)
            .build()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

private const val ATTACHMENT_PATH = "/mail/api/journal/attachment"
private const val HEADER_JOURNAL_ID = "X-Mail-Journal-Id"
private const val HEADER_JOURNAL_FILE = "X-Mail-Journal-File"
private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
