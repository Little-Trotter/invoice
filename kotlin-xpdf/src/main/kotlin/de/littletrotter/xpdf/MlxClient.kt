package de.littletrotter.xpdf

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.LlmModelResult
import de.littletrotter.shared.LlmValidationPort
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Deterministic, non-creative output for a review/correction task. */
private const val MLX_TEMPERATURE = 0.1

/** Generous ceiling for a full ZUGFeRD-XML improvement report. */
private const val MLX_MAX_TOKENS = 16384

private const val HTTP_STATUS_SUCCESS_MIN = 200
private const val HTTP_STATUS_SUCCESS_MAX = 299

/**
 * oMLX REST client (OpenAI-compatible) for ZUGFeRD XML review and improvement suggestions.
 * Configuration via MLX_ENDPOINT, MLX_API_KEY, MLX_MODEL environment variables.
 * Two timeout knobs (MLX_TIMEOUT, MLX_TIMEOUT_XML) account for cold-start weight loading.
 */
@ApplicationScoped
class MlxClient internal constructor(
    private val endpoint: String?,
    private val apiKey: String?,
    private val model: String,
    private val timeoutSec: Long,
    private val xmlOnlyTimeoutSec: Long = 300L,
    private val time: TimeSource,
) : LlmValidationPort {

    /** CDI entry point: configuration from the environment, "now" from the one injected
     *  [TimeSource] — an `@Inject`-annotated constructor is required here since ArC would
     *  otherwise not know which of this class's two constructors to call. */
    @Inject
    constructor(time: TimeSource) : this(
        endpoint = readEnv("MLX_ENDPOINT")?.trimEnd('/'),
        apiKey = readEnv("MLX_API_KEY"),
        model = readEnv("MLX_MODEL") ?: readEnv("MLX_MODELS")?.split(",")?.firstOrNull()?.trim().orEmpty().ifBlank { "Qwen3.6-35B-A3B-8bit" },
        timeoutSec = readEnv("MLX_TIMEOUT")?.toLongOrNull() ?: 300L,
        xmlOnlyTimeoutSec = readEnv("MLX_TIMEOUT_XML")?.toLongOrNull() ?: 300L,
        time = time,
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    private val http: HttpClient = newOmlxHttpClient()

    override fun isAvailable(): Boolean =
        !endpoint.isNullOrBlank() && !apiKey.isNullOrBlank() && model.isNotBlank()

    override fun analyze(
        xmlBytes: ByteArray,
        invoice: InvoiceDto,
        kositReport: String,
        layoutReport: String,
    ): LlmModelResult {
        if (!isAvailable()) return LlmModelResult.disabled()

        val xmlText = xmlBytes.toString(Charsets.UTF_8)
        val prompt = LlmCorrectionSupport.buildImprovementPrompt(xmlText, invoice, kositReport, layoutReport)

        // Both defaults cover cold-start loading of weights (V3: a not-yet-loaded model spent
        // its first call on weight loading alone, which exceeded the old 120s XML-only budget);
        // the split knob remains so an operator can give XML-only checks a smaller budget once
        // their model is warm.
        val effectiveTimeoutSec = if (layoutReport.isBlank()) xmlOnlyTimeoutSec else timeoutSec
        return callModel(prompt, invoice, effectiveTimeoutSec)
    }

    private fun callModel(prompt: String, invoice: InvoiceDto, timeoutSec: Long): LlmModelResult {
        val start = time.instant()
        log.info("Sending ZUGFeRD XML + KoSIT report to oMLX ($model) for invoice ${invoice.invoiceNumber.value}, timeout=${timeoutSec}s")
        return try {
            runMlxRequest(prompt, timeoutSec, start)
        } catch (ex: Exception) {
            exceptionResult(ex, start)
        }
    }

    private fun buildRequestBody(prompt: String): String = mapper.writeValueAsString(
        mapOf(
            "model" to model,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
            "temperature" to MLX_TEMPERATURE,
            "max_tokens" to MLX_MAX_TOKENS,
            "stream" to false,
        )
    )

    /** The oMLX call itself and its response, once [callModel]'s own outer `catch (ex: Exception)`
     *  is already in place — kept as one function (not folded into [callModel]) so the
     *  TimeoutException handling around just the HTTP send stays scoped correctly. */
    private fun runMlxRequest(prompt: String, timeoutSec: Long, start: Instant): LlmModelResult {
        val requestBody = buildRequestBody(prompt)
        val req = buildChatCompletionRequest(endpoint, apiKey, requestBody, timeoutSec)
        // Enforced via sendAsync().get(timeout) rather than relying solely on the
        // per-request HttpRequest timeout: a hung local model must not be able to block
        // the caller (the NATS job-processing thread) beyond this hard bound.
        val response = try {
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .get(timeoutSec, TimeUnit.SECONDS)
        } catch (ex: TimeoutException) {
            return timeoutResult(timeoutSec, start, ex)
        }
        return interpretResponse(response, start)
    }

    private fun interpretResponse(response: HttpResponse<String>, start: Instant): LlmModelResult {
        if (response.statusCode() !in HTTP_STATUS_SUCCESS_MIN..HTTP_STATUS_SUCCESS_MAX) {
            log.warn("oMLX ($model) returned HTTP ${response.statusCode()}")
            return failureResult(start, "oMLX HTTP ${response.statusCode()}")
        }
        val apiResponse: MlxChatCompletionResponse = mapper.readValue(response.body())
        val rawResponse = apiResponse.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (rawResponse.isBlank()) {
            return failureResult(start, "empty oMLX response")
        }
        return LlmModelResult(model = model, markdownReport = rawResponse, success = true, durationMs = elapsedMs(start))
    }

    // V3 — name the lever, not just the symptom: a local model that has not been used
    // yet loads its weights on the first call, which regularly exceeds budget.
    private fun timeoutResult(timeoutSec: Long, start: Instant, ex: TimeoutException): LlmModelResult {
        val knob = if (timeoutSec == xmlOnlyTimeoutSec) "MLX_TIMEOUT_XML" else "MLX_TIMEOUT"
        log.warn(
            "oMLX ($model) call timed out after ${timeoutSec}s — raise $knob if the model was cold: {}",
            ex.message,
        )
        return failureResult(
            start,
            "Zeitüberschreitung nach ${timeoutSec}s. Bei einem noch nicht geladenen " +
                "Modell kann der erste Aufruf deutlich länger dauern — $knob erhöhen oder das " +
                "Modell vorwärmen.",
        )
    }

    private fun exceptionResult(ex: Exception, start: Instant): LlmModelResult {
        val cause = (ex as? java.util.concurrent.ExecutionException)?.cause ?: ex
        log.warn("oMLX call to $model failed: ${cause.message}")
        return failureResult(start, cause.message ?: "unknown error")
    }

    private fun failureResult(start: Instant, message: String): LlmModelResult = LlmModelResult(
        model = model, markdownReport = "", success = false,
        durationMs = elapsedMs(start), error = message,
    )

    private fun elapsedMs(start: Instant): Long = Duration.between(start, time.instant()).toMillis()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MlxChatCompletionResponse(val choices: List<MlxChoice> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MlxChoice(val message: MlxMessage)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MlxMessage(val content: String? = null)
}
