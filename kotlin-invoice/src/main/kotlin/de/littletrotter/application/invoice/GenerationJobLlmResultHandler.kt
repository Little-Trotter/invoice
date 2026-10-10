package de.littletrotter.application.invoice

import de.littletrotter.domain.event.InvoiceLlmAnalysisResolved
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceLlmAnalysisRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.time.TimeSource
import com.fasterxml.jackson.core.JsonProcessingException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * GenerationJobService's LLM-callback result handling, extracted verbatim (see
 * `mergeLlmResult`/`updateLlmAnalysisFromResult`'s original KDoc on `GenerationJobService` for
 * the two-callback background this still relies on).
 */
@ApplicationScoped
class GenerationJobLlmResultHandler @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
    private val llmAnalysisRepository: InvoiceLlmAnalysisRepository,
    private val invoiceRepository: InvoiceRepository,
    private val eventDispatcher: GenerationJobEventDispatcher,
    private val time: TimeSource,
) {
    private val log = Logger.getLogger(GenerationJobLlmResultHandler::class.java)

    /**
     * Merges the deferred oMLX result (the SECOND, later callback) into
     * [job]'s existing `resultRefs` JSON, overlaying only the keys [llmResultRefs] actually
     * carries on top of whatever the FIRST callback ([GenerationJobResultHandler.markCompleted])
     * already wrote. Deliberately never re-derives validation status or re-inserts findings.
     */
    @Transactional
    fun mergeLlmResult(job: GenerationJob, llmResultRefs: Map<String, String>): GenerationJob {
        val existing: Map<String, String> = job.resultRefs?.let {
            try {
                GenerationJobResultRefsCodec.MAPPER.readValue(it, GenerationJobResultRefsCodec.MAP_TYPE)
            } catch (e: JsonProcessingException) {
                // AP-EXC-1: narrowed — the only realistic failure of readValue here is malformed
                // JSON in the stored column.
                log.warn("Job ${job.id}: existing resultRefs is not a String map, discarding on merge: ${e.message}", e)
                emptyMap()
            }
        } ?: emptyMap()
        val merged = existing + llmResultRefs
        job.resultRefs = GenerationJobResultRefsCodec.MAPPER.writeValueAsString(merged)
        // The deferred callback's own llmStatus becomes the durable column value too,
        // same as the first callback (markCompleted) already does. Never touches job.status.
        llmResultRefs[GenerationJobResultRefsCodec.RESULT_REF_LLM_STATUS]?.let { job.llmStatus = it }
        return generationJobRepository.save(job)
    }

    /**
     * V3 — closes the `InvoiceLlmAnalysis` status chain from the completed job's resultRefs. A
     * job that ran the LLM resolves the record to COMPLETED or FAILED, a job that did not run it
     * resolves a still-PENDING record to DISABLED — no record is ever left PENDING forever.
     * Idempotent: an already-terminal record is left at its value.
     */
    @Transactional
    fun updateLlmAnalysisFromResult(invoiceId: Long?, resultRefs: Map<String, String>) {
        if (invoiceId == null) return
        val analyses = llmAnalysisRepository.findByInvoiceId(invoiceId)
        if (analyses.isEmpty()) return
        val status = resultRefs[GenerationJobResultRefsCodec.RESULT_REF_LLM_STATUS]
        if (status.isNullOrBlank()) return  // legacy producer predates V3 — nothing to write
        val modelName = resultRefs["llmModel"]
        val error = resultRefs["llmError"]
        val durationMs = resultRefs["llmDurationMs"]?.toLongOrNull()

        // analyses is already guaranteed non-empty by the isEmpty() early-return above,
        // so analyses.first() (not firstOrNull()) makes that invariant structural.
        val target = modelName?.let { m -> analyses.firstOrNull { it.modelName == m } }
            ?: analyses.firstOrNull { it.status == LlmAnalysisStatus.PENDING }
            ?: analyses.first()

        target.modelName = modelName ?: target.modelName
        applyLlmOutcome(target, status, durationMs, error)
        llmAnalysisRepository.save(target)

        // Only the two model-reported terminal outcomes are message-center-worthy.
        if (status == "COMPLETED" || status == "FAILED") {
            fireResolvedEventIfInvoiceKnown(invoiceId, target)
        }
    }

    private fun applyLlmOutcome(target: InvoiceLlmAnalysis, status: String, durationMs: Long?, error: String?) {
        when (status) {
            "COMPLETED" -> {
                target.status = LlmAnalysisStatus.COMPLETED
                target.analyzedAt = time.nowUtc()
                target.summary = llmRunNote("fertig", durationMs)
                target.issues = null
            }
            "FAILED" -> {
                target.status = LlmAnalysisStatus.FAILED
                target.analyzedAt = time.nowUtc()
                target.issues = error ?: "LLM-Prüfung fehlgeschlagen"
                target.summary = llmRunNote("fehlgeschlagen", durationMs)
            }
            // The FIRST (early) callback already reports "PENDING"; a no-op.
            "PENDING" -> Unit
            // "DISABLED" / "NOT_REQUESTED": a completed job that didn't run the LLM resolves a
            // still-PENDING record to DISABLED. Already-terminal records stay untouched.
            else -> if (target.status == LlmAnalysisStatus.PENDING) {
                target.status = LlmAnalysisStatus.DISABLED
                target.analyzedAt = time.nowUtc()
                target.summary = llmRunNote("nicht angefordert", durationMs)
            }
        }
    }

    private fun fireResolvedEventIfInvoiceKnown(invoiceId: Long, target: InvoiceLlmAnalysis) {
        val invoiceNumber = invoiceRepository.findById(invoiceId)?.invoiceNumber?.value ?: return
        eventDispatcher.fireAsyncAfterCommit(
            InvoiceLlmAnalysisResolved(
                invoiceId = invoiceId,
                invoiceNumber = invoiceNumber,
                modelName = target.modelName,
                status = target.status,
            )
        )
    }

    /** V3 — a failed job never ran the LLM, so every still-PENDING analysis for that invoice is
     *  marked FAILED rather than left unfinished. */
    @Transactional
    fun failPendingAnalysesForInvoice(invoiceId: Long?) {
        if (invoiceId == null) return
        llmAnalysisRepository.findByInvoiceId(invoiceId)
            .filter { it.status == LlmAnalysisStatus.PENDING }
            .forEach { analysis ->
                analysis.status = LlmAnalysisStatus.FAILED
                analysis.analyzedAt = time.nowUtc()
                analysis.issues = analysis.issues ?: "Erstellung fehlgeschlagen — Analyse nicht ausgeführt"
                llmAnalysisRepository.save(analysis)
            }
    }

    private fun llmRunNote(outcome: String, durationMs: Long?): String =
        if (durationMs != null && durationMs > 0) "KI-Prüfung: $outcome ($durationMs ms)" else "KI-Prüfung: $outcome"
}
