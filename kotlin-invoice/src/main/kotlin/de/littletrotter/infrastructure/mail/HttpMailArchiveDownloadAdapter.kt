package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailArchiveDownload
import de.littletrotter.application.mail.port.inbound.MailArchiveDownloadQueryPort
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.temporal.ChronoUnit
import java.util.Optional

/**
 * Downloads the archive package through `GET /mail/api/journal/{id}/archive`. The package is binary,
 * so this adapter reads the raw body itself instead of going through the JSON-only [MailApiClient];
 * the invoice app never touches the mail component's bucket. 404 means "no package" and answers
 * `null`; every other failure is a [MailDispatchException] the caller can show.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailArchiveDownloadAdapter @Inject constructor(
    @param:ConfigProperty(name = "mail.base-url", defaultValue = "http://localhost:8090")
    private val baseUrl: String,
    @param:ConfigProperty(name = "mail.integration-token")
    private val integrationToken: Optional<String>,
) : MailArchiveDownloadQueryPort {

    /** The HTTP client, and the class's only test seam. */
    internal var client: HttpClient = MailBinaryGet.newClient()

    @Retry(retryOn = [MailDispatchException::class], maxRetries = 2, delay = 300, delayUnit = ChronoUnit.MILLIS)
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun findArchive(journalId: Long): MailArchiveDownload? {
        val response = MailBinaryGet.fetch(
            client,
            baseUrl,
            integrationToken.orElse(""),
            MailBinaryRequest("/journal/$journalId/archive", "application/gzip"),
        ) ?: return null
        return MailArchiveDownload(filenameOf(response, journalId), response.body())
    }

    private fun filenameOf(response: HttpResponse<ByteArray>, journalId: Long): String {
        val announced = response.headers().firstValue("Content-Disposition").orElse("")
        return FILENAME.find(announced)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: "mail-journal-$journalId-archive.tar.gz"
    }
}

private val FILENAME = Regex("""filename="([^"]+)"""")
