package de.littletrotter.xpdf

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PdfVisionInspectionPort
import de.littletrotter.shared.PdfVisionResult
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Deterministic, non-creative output for a vision spot-check. */
private const val MLX_VISION_TEMPERATURE = 0.1

/** A vision spot-check verdict is short prose — no need for [MlxClient]'s larger ceiling. */
private const val MLX_VISION_MAX_TOKENS = 2048

private const val HTTP_STATUS_SUCCESS_MIN = 200
private const val HTTP_STATUS_SUCCESS_MAX = 299

/**
 * oMLX REST client (OpenAI-compatible `/v1/chat/completions`, vision content) for the
 * optional visual PDF spot-check (Approach B). Separate from [MlxClient]: the text model
 * configured for ZUGFeRD XML review cannot be assumed to support image input, so this uses
 * its own, explicitly-configured model — disabled unless an operator sets one.
 *
 * Configuration via environment variables:
 *   MLX_VISION_ENDPOINT – base URL; falls back to MLX_ENDPOINT (same oMLX server) if unset
 *   MLX_VISION_API_KEY  – bearer token; falls back to MLX_API_KEY if unset
 *   MLX_VISION_MODEL    – vision-capable model name — NO default; must be explicitly
 *                         configured, since the text model (MLX_MODEL) is not assumed to
 *                         support image input
 *   MLX_VISION_TIMEOUT  – timeout in seconds, default 300 (5 min, local model cold-start)
 */
@ApplicationScoped
class MlxVisionClient internal constructor(
    private val endpoint: String?,
    private val apiKey: String?,
    private val model: String,
    private val timeoutSec: Long,
    private val time: TimeSource,
) : PdfVisionInspectionPort {

    /** CDI entry point: configuration from the environment, "now" from the one injected
     *  [TimeSource] — an `@Inject`-annotated constructor is required here since ArC would
     *  otherwise not know which of this class's two constructors to call. */
    @Inject
    constructor(time: TimeSource) : this(
        endpoint = (readEnv("MLX_VISION_ENDPOINT") ?: readEnv("MLX_ENDPOINT"))?.trimEnd('/'),
        apiKey = readEnv("MLX_VISION_API_KEY") ?: readEnv("MLX_API_KEY"),
        model = readEnv("MLX_VISION_MODEL").orEmpty(),
        timeoutSec = readEnv("MLX_VISION_TIMEOUT")?.toLongOrNull() ?: 300L,
        time = time,
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    private val http: HttpClient = newOmlxHttpClient()

    override fun isAvailable(): Boolean =
        !endpoint.isNullOrBlank() && !apiKey.isNullOrBlank() && model.isNotBlank()

    override fun inspect(
        pngBytes: ByteArray,
        invoice: InvoiceDto,
        layoutReport: String,
    ): PdfVisionResult {
        if (!isAvailable()) return PdfVisionResult.disabled()

        val prompt = PdfVisionSupport.buildInspectionPrompt(invoice, layoutReport)
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)
        val start = time.instant()

        log.info("Sending rendered PDF page to oMLX vision ($model) for invoice ${invoice.invoiceNumber.value}, timeout=${timeoutSec}s")
        return try {
            runMlxVisionRequest(prompt, dataUri, start)
        } catch (ex: Exception) {
            val cause = (ex as? java.util.concurrent.ExecutionException)?.cause ?: ex
            log.warn("oMLX vision call to $model failed: ${cause.message}")
            failureResult(start, cause.message ?: "unknown error")
        }
    }

    private fun buildRequestBody(prompt: String, dataUri: String): String = mapper.writeValueAsString(
        mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to prompt),
                        mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUri)),
                    ),
                )
            ),
            "temperature" to MLX_VISION_TEMPERATURE,
            "max_tokens" to MLX_VISION_MAX_TOKENS,
            "stream" to false,
        )
    )

    /** The oMLX vision call itself and its response, once [inspect]'s own outer
     *  `catch (ex: Exception)` is already in place — kept as one function (not folded into
     *  [inspect]) so the TimeoutException handling around just the HTTP send stays scoped
     *  correctly. */
    private fun runMlxVisionRequest(prompt: String, dataUri: String, start: Instant): PdfVisionResult {
        val requestBody = buildRequestBody(prompt, dataUri)
        val req = buildChatCompletionRequest(endpoint, apiKey, requestBody, timeoutSec)
        val response = try {
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .get(timeoutSec, TimeUnit.SECONDS)
        } catch (ex: TimeoutException) {
            log.warn("oMLX vision ($model) call timed out after ${timeoutSec}s: {}", ex.message)
            return failureResult(start, "oMLX vision timed out after ${timeoutSec}s")
        }
        return interpretResponse(response, start)
    }

    private fun interpretResponse(response: HttpResponse<String>, start: Instant): PdfVisionResult {
        if (response.statusCode() !in HTTP_STATUS_SUCCESS_MIN..HTTP_STATUS_SUCCESS_MAX) {
            log.warn("oMLX vision ($model) returned HTTP ${response.statusCode()}")
            return failureResult(start, "oMLX vision HTTP ${response.statusCode()}")
        }
        val apiResponse: MlxVisionChatCompletionResponse = mapper.readValue(response.body())
        val rawResponse = apiResponse.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (rawResponse.isBlank()) {
            return failureResult(start, "empty oMLX vision response")
        }
        return PdfVisionResult(model = model, markdownReport = rawResponse, success = true, durationMs = elapsedMs(start))
    }

    private fun failureResult(start: Instant, message: String): PdfVisionResult = PdfVisionResult(
        model = model, markdownReport = "", success = false,
        durationMs = elapsedMs(start), error = message,
    )

    private fun elapsedMs(start: Instant): Long = Duration.between(start, time.instant()).toMillis()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MlxVisionChatCompletionResponse(val choices: List<MlxVisionChoice> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MlxVisionChoice(val message: MlxVisionMessage)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MlxVisionMessage(val content: String? = null)
}
