package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailDraftAttachmentContent
import de.littletrotter.application.mail.port.inbound.MailDraftAttachmentQueryPort
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.temporal.ChronoUnit
import java.util.Optional

/**
 * Reads one staged attachment through `GET /mail/api/compose/{draftId}/attachments/{index}`. The
 * body is binary, so this adapter reads it raw instead of going through the JSON-only [MailApiClient];
 * the invoice app never touches the mail component's bucket. 404 means "gone" and answers `null`;
 * every other failure is a [MailDispatchException] the caller can show.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailDraftAttachmentAdapter @Inject constructor(
    @param:ConfigProperty(name = "mail.base-url", defaultValue = "http://localhost:8090")
    private val baseUrl: String,
    @param:ConfigProperty(name = "mail.integration-token")
    private val integrationToken: Optional<String>,
) : MailDraftAttachmentQueryPort {

    /** The HTTP client, and the class's only test seam. */
    internal var client: HttpClient = MailBinaryGet.newClient()

    @Retry(retryOn = [MailDispatchException::class], maxRetries = 2, delay = 300, delayUnit = ChronoUnit.MILLIS)
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun findAttachment(draftId: String, index: Int): MailDraftAttachmentContent? {
        val response = MailBinaryGet.fetch(
            client,
            baseUrl,
            integrationToken.orElse(""),
            MailBinaryRequest(
                path = "/compose/${URLEncoder.encode(draftId, Charsets.UTF_8)}/attachments/$index",
                accept = "*/*",
                label = "/compose/{draft}/attachments/$index",
            ),
        ) ?: return null
        return MailDraftAttachmentContent(
            filenameOf(response, index),
            response.headers().firstValue("Content-Type").orElse("application/octet-stream"),
            response.body(),
        )
    }

    // The RFC 5987 form carries the exact name; the quoted form is an ASCII fallback.
    private fun filenameOf(response: HttpResponse<ByteArray>, index: Int): String {
        val announced = response.headers().firstValue("Content-Disposition").orElse("")
        val extended = EXTENDED_FILENAME.find(announced)?.groupValues?.get(1)?.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
        return extended?.takeIf { it.isNotBlank() }
            ?: QUOTED_FILENAME.find(announced)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: "attachment-$index"
    }
}

private val QUOTED_FILENAME = Regex("""filename="([^"]+)"""")
private val EXTENDED_FILENAME = Regex("""filename\*=UTF-8''([^;\s]+)""", RegexOption.IGNORE_CASE)
