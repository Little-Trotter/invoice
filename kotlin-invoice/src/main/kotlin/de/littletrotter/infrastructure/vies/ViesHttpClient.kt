package de.littletrotter.infrastructure.vies

import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.application.masterdata.VatIdVerificationPort
import de.littletrotter.application.masterdata.VatIdVerificationResult
import de.littletrotter.domain.value.VatIdValidator
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * EU VIES REST API client for VAT ID verification.
 *
 * The VIES terms of use forbid bulk queries; verification is triggered only on explicit
 * user request (a button in RecipientView), never automatically or by a scheduler.
 * Unreachability (timeout, 5xx, no HTTP response) does not change the recipient's status —
 * unreachability is not a statement about the VAT ID.
 *
 * State transitions:
 * - HTTP 200 + `{"isValid": true}` → VERIFIED (trader name/address, when VIES delivers them)
 * - HTTP 200 + `{"isValid": false, "userError": "INVALID"}` → FAILED
 * - HTTP 200 + `{"isValid": false}` with any other (or missing) `userError` → UNREACHABLE(reason)
 * - HTTP 200 without an `isValid` field → UNREACHABLE(null) (an answer we do not understand is
 *   not a statement about the VAT id — see [parseValidStatus])
 * - HTTP 200 + malformed JSON → UNREACHABLE(null)
 * - HTTP 4xx, 5xx, timeout, connection error → UNREACHABLE(null)
 */
@ApplicationScoped
class ViesHttpClient(
    @param:ConfigProperty(name = "invoice.vies.timeout", defaultValue = "10")
    private val timeoutSeconds: Long,
    @param:ConfigProperty(name = "invoice.vies.base-url", defaultValue = "https://ec.europa.eu/taxation_customs/vies/rest-api")
    private val baseUrl: String,
    private val objectMapper: ObjectMapper,
    // No default: the only two call sites are this file's own `@Inject constructor`
    // (which always passes `null` explicitly) and tests (which always pass a real HttpClient).
    // A default value here compiled to Kotlin's synthetic `$default` overload, whose own bytecode
    // no caller in this codebase can reach — dead code, not a real optional-arg use.
    private val httpClient: HttpClient?,
) : VatIdVerificationPort {

    private val client: HttpClient by lazy { httpClient ?: defaultHttpClient(timeoutSeconds) }
    private val logger: Logger = Logger.getLogger(ViesHttpClient::class.java)

    @Inject
    constructor(
        @ConfigProperty(name = "invoice.vies.timeout", defaultValue = "10")
        timeoutSeconds: Long,
        @ConfigProperty(name = "invoice.vies.base-url", defaultValue = "https://ec.europa.eu/taxation_customs/vies/rest-api")
        baseUrl: String,
        objectMapper: ObjectMapper,
    ) : this(timeoutSeconds, baseUrl, objectMapper, null)

    /**
     * Verify a VAT ID against the EU VIES registry.
     *
     * The API is called with the country code and VAT ID number (without prefix).
     * The response is parsed to extract the `valid` boolean.
     *
     * @param countryCode ISO-3166-1 alpha-2 country code
     * @param vatId VAT ID without country code prefix
     * @return VERIFIED if `valid: true`, FAILED if `valid: false`, UNREACHABLE on error
     */
    override fun verify(countryCode: String, vatId: String): VatIdVerificationResult {
        // Both values become URL PATH SEGMENTS below, and URI.create rejects a space outright.
        // Until 04.09.2026 that exception fell into the catch further down and left the user with
        // "VIES unreachable" for what was really a stray space in the input — the service was fine,
        // the request never went out. Normalising here as well as in the caller keeps that class
        // of input from ever reaching URI.create again, and anything still unusable afterwards is
        // rejected as a bad argument instead of being reported as an outage.
        val country = VatIdValidator.normalize(countryCode)
        val number = VatIdValidator.normalize(vatId)
        require(country.matches(SAFE_SEGMENT) && number.matches(SAFE_SEGMENT)) {
            "VAT id is not usable as a URL path segment: country='$countryCode', vatId='$vatId'"
        }
        return sendVerify(country, number)
    }

    private fun sendVerify(countryCode: String, vatId: String): VatIdVerificationResult = try {
        val url = "$baseUrl/ms/$countryCode/vat/$vatId"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        when {
            response.statusCode() == HTTP_OK -> parseValidStatus(response.body())
            else -> VatIdVerificationResult.UNREACHABLE(null)
        }
    } catch (_: Exception) {
        // Timeout, connection error, or any other exception — status remains unchanged.
        VatIdVerificationResult.UNREACHABLE(null)
    }

    /**
     * Reads the verdict out of the VIES response.
     *
     * The field is `isValid`. This used to read `valid`, which the REST API has never sent — a
     * captured production response carries
     * `isValid, requestDate, userError, name, address, requestIdentifier, originalVatNumber,
     * vatNumber, viesApproximate` and no `valid` at all. `path("valid")` therefore returned a
     * missing node on EVERY call, `asBoolean(false)` turned that into `false`, and the adapter
     * reported FAILED for every VAT id ever checked, valid or not. The unit tests did not catch it
     * because their stubs were written from the same wrong assumption (PO report 04.09.2026,
     * "die VIES-Abfrage funktioniert nicht").
     *
     * A body without `isValid` now yields UNREACHABLE rather than FAILED: not understanding the
     * answer is not a statement about the VAT id, and that distinction is the whole point of
     * [VatIdVerificationResult.UNREACHABLE]. Silently reading a missing field as "invalid" is what
     * hid this defect for as long as it did.
     *
     * `vat-id-field-ergonomics` (14.09.2026) — VIES answers `isValid: false` for far more than an
     * actually-invalid VAT id: `MS_UNAVAILABLE`, `TIMEOUT`, `MS_MAX_CONCURRENT_REQ`,
     * `GLOBAL_MAX_CONCURRENT_REQ`, `SERVICE_UNAVAILABLE`, `INVALID_INPUT`, `IP_BLOCKED`,
     * `VAT_BLOCKED`, `INVALID_REQUESTER_INFO` all carry `isValid: false` too, and every one of
     * them used to surface identically to a genuinely rejected VAT id. Only `userError: INVALID`
     * is an actual verdict on the VAT id; everything else is the service itself having a bad day,
     * carried through as [VatIdVerificationResult.UNREACHABLE]'s `reason` so the caller can name
     * the cause instead of a generic "unreachable".
     */
    private fun parseValidStatus(body: String): VatIdVerificationResult = try {
        val json = objectMapper.readTree(body)
        val verdict = json.path("isValid")
        val userError = json.path("userError").asText("").trim().ifBlank { null }
        when {
            verdict.isMissingNode || verdict.isNull -> VatIdVerificationResult.UNREACHABLE(null)
            verdict.asBoolean(false) -> VatIdVerificationResult.VERIFIED(cleanViesText(json.path("name")), cleanViesText(json.path("address")))
            userError == "INVALID" -> VatIdVerificationResult.FAILED
            else -> {
                if (userError != null) {
                    logger.warn("VIES answered isValid=false with userError=$userError — treating as unreachable, not invalid")
                }
                VatIdVerificationResult.UNREACHABLE(userError)
            }
        }
    } catch (_: Exception) {
        // Malformed JSON — treat as unreachable (not a statement about validity).
        VatIdVerificationResult.UNREACHABLE(null)
    }

    /** VIES answers a trader name/address it does not have as the literal placeholder `"---"`, or
     *  blank — both normalise to `null` here so callers never have to special-case the placeholder
     *  themselves. */
    private fun cleanViesText(node: com.fasterxml.jackson.databind.JsonNode): String? =
        node.asText("").trim().takeIf { it.isNotBlank() && it != "---" }

    companion object {
        /** What may appear in a VIES path segment: the country prefix and the number itself.
         *  Deliberately narrow — VAT numbers are alphanumeric, with `+` and `*` reserved for the
         *  Irish format. Anything else means the value never went through the normalisation. */
        private val SAFE_SEGMENT = Regex("[A-Z0-9+*]+")

        private const val HTTP_OK = 200

        /**
         * Default HTTP client with timeouts configured. Can be overridden in tests.
         */
        fun defaultHttpClient(timeoutSeconds: Long): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build()
    }
}
