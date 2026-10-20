package de.littletrotter.xpdf.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.shared.ValidationToolchain
import de.littletrotter.shared.VeraPdfResult
import de.littletrotter.shared.VeraPdfValidatorPort
import de.littletrotter.xpdf.readEnv
import jakarta.enterprise.context.ApplicationScoped
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections
import java.util.HexFormat
import java.util.UUID

/**
 * PDF/A-3 conformance through the veraPDF REST service, a separate container with its own memory
 * limit. The PDF is streamed to the service; neither this JVM nor its PDF library ever holds the
 * document for this check.
 *
 * A check that could not be performed (service unreachable, timeout, an HTTP error, unreadable
 * answer, a stored object whose digest differs from the expected one) is [de.littletrotter.shared
 * .KositOutcome.ERROR], never `INVALID`: "the check could not run" and "the document is wrong" are
 * different statements.
 */
@ApplicationScoped
class VeraPdfRestClient(
    /** Base URL of the service. Config: `VERAPDF_URL`. */
    private val baseUrl: String = VeraPdfRestSettings.fromEnvironment().baseUrl,
    /** Pinned engine version named in every finding. Config: `VERAPDF_VERSION`. Never `latest`. */
    private val pinnedVersion: String = VeraPdfRestSettings.fromEnvironment().pinnedVersion,
    /** Validation profile flavour; targets PDF/A-3b. */
    private val flavour: String = "3b",
    /** Deadline for one validation run. Config: `VERAPDF_TIMEOUT` in seconds. */
    private val timeoutSeconds: Long = VeraPdfRestSettings.fromEnvironment().timeoutSeconds,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
) : VeraPdfValidatorPort {

    private val mapper = jacksonObjectMapper()
    private val parser = VeraPdfReportParser("veraPDF $pinnedVersion")

    override fun validate(pdfBytes: ByteArray): VeraPdfResult =
        validate(ByteArrayInputStream(pdfBytes), expectedSha256 = null)

    /**
     * Streams [pdf] to the service and returns its verdict. When [expectedSha256] is given, the
     * digest of the bytes actually sent must equal it; otherwise the verdict would describe a
     * different document than the one that was asked for. The caller closes [pdf].
     */
    fun validate(pdf: InputStream, expectedSha256: String?): VeraPdfResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val boundary = "lt-" + UUID.randomUUID()
        val body = SequenceInputStream(
            Collections.enumeration(
                listOf(
                    ByteArrayInputStream(multipartHead(boundary)),
                    DigestInputStream(pdf, digest),
                    ByteArrayInputStream("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)),
                ),
            ),
        )
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/api/validate/$flavour"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream { body })
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            interpret(response, HexFormat.of().formatHex(digest.digest()), expectedSha256)
        } catch (ex: HttpTimeoutException) {
            parser.engineError("veraPDF-Lauf nach ${timeoutSeconds}s abgebrochen (Timeout): ${ex.message}")
        } catch (ex: IOException) {
            parser.engineError("veraPDF-Dienst nicht erreichbar ($baseUrl): ${ex.message}")
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            parser.engineError("veraPDF-Aufruf unterbrochen: ${ex.message}")
        }
    }

    private fun interpret(response: HttpResponse<String>, actualSha256: String, expectedSha256: String?): VeraPdfResult {
        if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
            return parser.engineError("Prüfsumme des Dokuments weicht ab (erwartet $expectedSha256, gefunden $actualSha256)")
        }
        if (response.statusCode() != HTTP_OK) {
            return parser.engineError(
                "veraPDF-Dienst antwortete mit HTTP ${response.statusCode()}: ${response.body().take(ERROR_PREVIEW_LENGTH)}",
            )
        }
        return try {
            parser.parse(response.body())
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            parser.engineError("veraPDF-Ausgabe nicht auswertbar: ${ex.message}")
        }
    }

    /** The version the running service reports, or `null` when it does not answer. */
    fun serviceVersion(): String? {
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/api/info"))
            .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
            .header("Accept", "application/json")
            .GET()
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != HTTP_OK) null else versionIn(response.body())
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    // The service answers with a small JSON document; the version is the first "version" member.
    // An answer without one still proves the service is up, and the pinned version is then the
    // only one that can be named.
    private fun versionIn(body: String): String =
        try {
            mapper.readTree(body).findValue("version")?.asText()?.takeIf { it.isNotBlank() } ?: pinnedVersion
        } catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
            pinnedVersion
        }

    private fun multipartHead(boundary: String): ByteArray =
        ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"document.pdf\"\r\n" +
            "Content-Type: application/pdf\r\n\r\n").toByteArray(Charsets.UTF_8)

    companion object {
        const val DEFAULT_URL = "http://verapdf:8080"
        internal const val DEFAULT_TIMEOUT_SECONDS = 120L
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val PROBE_TIMEOUT_SECONDS = 10L
        private const val HTTP_OK = 200
        private const val ERROR_PREVIEW_LENGTH = 500
    }
}

/** The environment-driven settings of [VeraPdfRestClient]; the lookup is a parameter so a test can
 *  supply the variables without touching the process environment. */
internal data class VeraPdfRestSettings(val baseUrl: String, val pinnedVersion: String, val timeoutSeconds: Long) {
    companion object {
        fun fromEnvironment(): VeraPdfRestSettings = from(::readEnv)

        fun from(lookup: (String) -> String?): VeraPdfRestSettings = VeraPdfRestSettings(
            baseUrl = lookup("VERAPDF_URL") ?: VeraPdfRestClient.DEFAULT_URL,
            pinnedVersion = lookup("VERAPDF_VERSION") ?: ValidationToolchain.VERAPDF_DEFAULT_VERSION,
            timeoutSeconds = lookup("VERAPDF_TIMEOUT")?.toLongOrNull() ?: VeraPdfRestClient.DEFAULT_TIMEOUT_SECONDS,
        )
    }
}
