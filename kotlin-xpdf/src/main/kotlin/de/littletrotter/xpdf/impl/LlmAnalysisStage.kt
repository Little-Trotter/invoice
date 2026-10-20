package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.LlmValidationPort
import de.littletrotter.xpdf.infrastructure.messaging.ResultPublisher
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The oMLX analysis as its own, decoupled worker stage. Before this class existed,
 * [PdfExportServiceImpl.completeExport] called the model synchronously and the print job did not
 * count as done until the ~1.5-2 min oMLX round trip finished; a busy local model then serialized
 * every queued job behind it and the fixed 300s [de.littletrotter.xpdf.MlxClient] timeout could
 * tear down a job that had only been *waiting* in that queue, never actually running.
 *
 * Now [PdfExportServiceImpl.completeExport] ends the print job right after the deterministic
 * stages (PDF, syntax, KoSIT, PDF/A, veraPDF) and reports `llmStatus: "PENDING"` on that FIRST
 * result callback when an analysis was requested and oMLX is configured (see [isAvailable]). This
 * class then runs the actual model call *afterwards*, off [executor] — a dedicated single-thread
 * pool whose own queue serializes every call onto the one local model: the
 * 300s [LlmValidationPort] timeout now only ever bounds a single in-flight call, never the wait
 * for a busy queue. The outcome (stored report + model/duration/error) is reported through a
 * SECOND, later message on the exact same NATS result path
 * ([de.littletrotter.xpdf.infrastructure.messaging.ResultPublisher]) — see [publishOutcome]'s own KDoc for why that message
 * needs a status distinct from the job-level "COMPLETED"/"FAILED" the first callback already used.
 */
@ApplicationScoped
class LlmAnalysisStage @Inject constructor(
    private val mlxClient: LlmValidationPort?,
    private val documentStore: DocumentStore,
    private val resultPublisher: ResultPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** `internal` so tests can substitute a same-thread (or otherwise instrumented) [Executor] —
     *  both to assert two [schedule] calls actually run one-after-another rather than racing, and
     *  to make [runAnalysis]'s side effects observable synchronously in a unit test. */
    internal var executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-analysis-stage").apply { isDaemon = true }
    }

    fun isAvailable(): Boolean = mlxClient?.isAvailable() == true

    /**
     * Schedules the deferred oMLX run for [jobId] on [executor]. Never throws — a scheduling
     * failure (a saturated/shutting-down executor) is logged and swallowed rather than
     * propagated, the same defensive stance [de.littletrotter.xpdf.infrastructure.messaging.JobConsumer] already takes on
     * its own heartbeat scheduling: the print job itself already completed successfully via the
     * FIRST callback by the time this is ever called, so a failure here must never look like it
     * failed the print job — it only ever leaves the analysis stuck at "PENDING", which a
     * stale-recovery scan (kotlin-invoice side) eventually resolves.
     */
    fun schedule(request: DeferredLlmAnalysisRequest) {
        try {
            executor.execute { runAnalysis(request) }
        } catch (e: Exception) {
            log.error("Failed to schedule deferred oMLX analysis for job {}: {}", request.jobId, e.message)
        }
    }

    private fun runAnalysis(request: DeferredLlmAnalysisRequest) {
        val jobId = request.jobId
        val client = mlxClient
        if (client == null) {
            // Should not happen — schedule() is only ever reached once isAvailable() already
            // returned true — but a defensive, explicit FAILED result beats a silently lost job
            // on a state that should be impossible.
            publishOutcome(LlmAnalysisOutcome(jobId, success = false, model = "oMLX", llmReportKey = "", error = "oMLX client unavailable", durationMs = 0))
            return
        }
        log.info("Running deferred oMLX improvement-specification analysis for job {}", jobId)
        val result = client.analyze(request.xmlBytes, request.invoice, request.kositReportJson, request.layoutReport)
        var storedReportKey = ""
        if (result.success && result.markdownReport.isNotBlank()) {
            try {
                documentStore.put(request.llmReportKey, result.markdownReport.toByteArray(Charsets.UTF_8), "text/markdown")
                storedReportKey = request.llmReportKey
                log.info("Stored oMLX improvement specification at {} (job {})", request.llmReportKey, jobId)
            } catch (e: Exception) {
                // A store failure must not silently claim COMPLETED with no reachable report —
                // the second callback below reports FAILED instead, with the store failure as the
                // reason (result.success stays whatever the model itself returned, but a report
                // nobody can read is no better than one that never came back).
                log.error("Failed to store oMLX report for job {}: {}", jobId, e.message)
                publishOutcome(
                    LlmAnalysisOutcome(
                        jobId,
                        success = false,
                        model = result.model,
                        llmReportKey = "",
                        error = "report generated but could not be stored: ${e.message}",
                        durationMs = result.durationMs,
                    ),
                )
                return
            }
        }
        publishOutcome(
            LlmAnalysisOutcome(
                jobId = jobId,
                success = result.success,
                model = result.model,
                llmReportKey = storedReportKey,
                error = result.error,
                durationMs = result.durationMs,
            ),
        )
    }

    /**
     * The Nats-Msg-Id idiom — [ResultPublisher.publishResult] derives
     * its dedup message id as `"result-$jobId-$status"`. The FIRST (early) callback for this same
     * [jobId] already used status "COMPLETED" (or "FAILED", for a job whose deterministic stages
     * themselves failed), so this SECOND callback needs a status value neither of those ever use —
     * otherwise it would either collide inside NATS's own dedup window (silently dropped) or be
     * indistinguishable from the job-level result once it reaches
     * `GenerationJobResultConsumer` in kotlin-request.invoice.
     * "LLM_COMPLETED"/"LLM_FAILED" satisfy both: a distinct message id, and a distinct `when`
     * branch on the reading side that merges into the existing job row instead of re-running
     * completion (which would duplicate validation findings and re-derive the request.invoice's
     * validation status a second time from the same report).
     */
    private fun publishOutcome(outcome: LlmAnalysisOutcome) {
        val status = if (outcome.success) "LLM_COMPLETED" else "LLM_FAILED"
        val resultRefs = buildMap {
            put("llmStatus", if (outcome.success) "COMPLETED" else "FAILED")
            put("llmModel", outcome.model)
            if (outcome.llmReportKey.isNotBlank()) put("llmReportKey", outcome.llmReportKey)
            if (!outcome.error.isNullOrBlank()) put("llmError", outcome.error)
            put("llmDurationMs", outcome.durationMs.toString())
        }
        try {
            // correlationId is intentionally null: the LLM_COMPLETED/LLM_FAILED branch in
            // GenerationJobResultConsumer only ever merges resultRefs, it never reads
            // correlationId (that's only used for the HTTP-poll status cache on the job-level
            // COMPLETED/FAILED branches) — and performExport never threaded
            // correlationId through to this stage in the first place, so there is nothing to pass.
            resultPublisher.publishResult(outcome.jobId, status, resultRefs, "", null)
        } catch (e: Exception) {
            // Unlike JobConsumer's own "never lose a result silently" stance, there is no
            // NATS job message left to nak here — the print job already completed successfully
            // via the first callback. Logged so an operator can see the gap; the invoice's LLM
            // analysis simply stays PENDING until a stale-recovery scan
            // (kotlin-invoice side) resolves it.
            log.error("Failed to publish deferred oMLX result for job {}: {}", outcome.jobId, e.message)
        }
    }
}

/**
 * Everything [LlmAnalysisStage.schedule]/[LlmAnalysisStage.runAnalysis] need for one deferred
 * oMLX run: the one request `PdfExportServiceImpl`'s completion stage already assembles
 * from the just-generated artifacts before handing it off to the dedicated single-thread executor.
 */
data class DeferredLlmAnalysisRequest(
    val jobId: String,
    val invoice: InvoiceDto,
    val xmlBytes: ByteArray,
    val kositReportJson: String,
    val layoutReport: String,
    val llmReportKey: String,
) {
    // Content-based equality for the byte payload — a data class would otherwise compare the
    // ByteArray by identity (ArrayInDataClass).
    override fun equals(other: Any?): Boolean =
        other is DeferredLlmAnalysisRequest &&
            jobId == other.jobId &&
            invoice == other.invoice &&
            xmlBytes.contentEquals(other.xmlBytes) &&
            kositReportJson == other.kositReportJson &&
            layoutReport == other.layoutReport &&
            llmReportKey == other.llmReportKey

    override fun hashCode(): Int {
        var result = jobId.hashCode()
        result = 31 * result + invoice.hashCode()
        result = 31 * result + xmlBytes.contentHashCode()
        result = 31 * result + kositReportJson.hashCode()
        result = 31 * result + layoutReport.hashCode()
        result = 31 * result + llmReportKey.hashCode()
        return result
    }
}

/**
 * The result of one deferred oMLX run, as [LlmAnalysisStage.publishOutcome] reports it on the
 * second NATS result message: bundles the fields [LlmAnalysisStage.runAnalysis]'s three
 * call sites (client unavailable / store failure / model result) already assemble together.
 */
data class LlmAnalysisOutcome(
    val jobId: String,
    val success: Boolean,
    val model: String,
    val llmReportKey: String,
    val error: String?,
    val durationMs: Long,
)
