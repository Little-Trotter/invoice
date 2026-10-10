package de.littletrotter.application.invoice

import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.repository.InvoiceLlmAnalysisRepository
import de.littletrotter.domain.repository.InvoiceRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.ObservesAsync
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * Asynchronously creates pending LLM analysis records when an invoice is finalized.
 * Creates one record for the configured oMLX model (from MLX_MODEL env).
 * The analysis record (status=PENDING) is persisted immediately so the UI can show progress.
 * Runs in a separate thread – failures are logged but do not affect the finalization transaction.
 *
 * This is the entry point that starts the whole LLM analysis path for an invoice, so
 * this is where the deployment-level [LlmAnalysisFeatureConfig] guard sits: with it off, nothing
 * downstream (the PENDING record, later the export job's `llmOptimize` request, the eventual
 * model call) ever happens for this invoice. See [LlmAnalysisFeatureConfig]'s kdoc for how this
 * differs from the runtime `OMLX_LLM_CHECK` switch that `DocumentJobPublisher` also consults.
 */
@ApplicationScoped
class InvoiceLlmAnalysisEventHandler @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val llmAnalysisRepository: InvoiceLlmAnalysisRepository,
    // Nullable with an enabled-by-default fallback so existing tests that construct this handler
    // with just the two repositories above keep exercising pre-P3 behavior unchanged.
    private val llmAnalysisFeatureConfig: LlmAnalysisFeatureConfig? = null,
) {

    private val log = Logger.getLogger(InvoiceLlmAnalysisEventHandler::class.java)

    // Model name to analyze, read from MLX_MODEL environment variable.
    // Must match the model configured in kotlin-xpdf via MlxClient (MLX_MODEL env).
    // MLX_MODELS (legacy multi-model list) is honored as a fallback: the first entry wins.
    // The actual precedence/parsing logic is factored out into resolveAnalysisModel
    // below (same "generic pure-function module" split as InvoiceDateRules.kt): the environment
    // in this constructor's own scope is untestable branch-by-branch (System.getenv is
    // effectively immutable within a running JVM), but the decision it feeds only needs the two
    // raw strings, which a test can supply directly.
    private val analysisModels: List<String> = listOf(
        resolveAnalysisModel(System.getenv("MLX_MODEL"), System.getenv("MLX_MODELS")),
    )

    @Transactional
    fun onInvoiceFinalized(@ObservesAsync event: InvoiceFinalized) {
        if (llmAnalysisFeatureConfig?.enabled == false) {
            // Deployment-level switch is off (e.g. the Pi target, which ships without
            // a local model at all): skip silently, do not even create a PENDING record. A
            // PENDING row that can never be resolved would just sit in the UI forever.
            log.debugf(
                "LLM analysis skipped for invoice %d: littletrotter.features.llm-analysis.enabled=false",
                event.invoiceId,
            )
            return
        }
        try {
            val existing = llmAnalysisRepository.findByInvoiceId(event.invoiceId)
            val existingByModel = existing.groupBy { it.modelName }

            // Create missing analyses for each model
            analysisModels.forEach { modelName ->
                if (existingByModel[modelName]?.any { it.status != LlmAnalysisStatus.PENDING } == true) {
                    // Model already has a non-PENDING analysis, skip
                    return@forEach
                }

                val invoice = invoiceRepository.findById(event.invoiceId) ?: run {
                    log.warnf("LLM analysis skipped for model %s: invoice %d not found", modelName, event.invoiceId)
                    return@forEach
                }

                val analysis = existingByModel[modelName]?.firstOrNull()
                    ?: InvoiceLlmAnalysis().apply {
                        this.invoice = invoice
                        this.modelName = modelName
                    }
                analysis.status = LlmAnalysisStatus.PENDING
                llmAnalysisRepository.save(analysis)
            }

            log.infof("LLM analysis queued for invoice %d (%s) across %d model(s)",
                event.invoiceId, event.invoiceNumber, analysisModels.size)
        } catch (ex: Exception) {
            // Sole catch-all of this flow: an @ObservesAsync CDI observer (class
            // KDoc: "failures are logged but do not affect the finalization transaction"). Already
            // logged at error with the invoice id as business context; no counter exists for this
            // handler.
            log.errorf(ex, "Failed to queue LLM analysis for invoice %d: %s", event.invoiceId, ex.message)
        }
    }
}

/**
 * Pure precedence/parsing logic behind [InvoiceLlmAnalysisEventHandler.analysisModels]:
 * [mlxModel] (MLX_MODEL) wins outright when non-blank; otherwise the first non-empty, trimmed
 * entry of [mlxModels]' (MLX_MODELS, legacy multi-model list) comma-separated list; otherwise the
 * hardcoded default. Factored out of the constructor so the decision can be driven by explicit
 * strings in a test instead of `System.getenv`, which cannot be varied per test case.
 */
internal fun resolveAnalysisModel(mlxModel: String?, mlxModels: String?): String =
    mlxModel?.takeIf { it.isNotBlank() }
        ?: mlxModels?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Qwen3.6-35B-A3B-8bit"
