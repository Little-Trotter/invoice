package de.littletrotter.application.invoice

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.domain.event.GenerationJobExhausted
import de.littletrotter.domain.event.GenerationJobFailed
import de.littletrotter.domain.event.GenerationJobResultProcessingFailed
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.isIssued
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.ValidationStatus
import com.fasterxml.jackson.core.JsonProcessingException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * GenerationJobService's failure/retry/DLQ + stale-LLM-recovery responsibility, extracted
 * verbatim (see `markFailed`/`failExhaustedJob`/`markResultProcessingFailed`/
 * `resetStaleLlmPending`'s original KDoc on `GenerationJobService` for the L2/L3
 * background this still relies on).
 */
@ApplicationScoped
class GenerationJobRecoveryService @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
    private val invoiceRepository: InvoiceRepository,
    private val eventDispatcher: DomainEventDispatcher,
    private val llmResultHandler: GenerationJobLlmResultHandler,
    private val time: TimeSource,
) {
    private val log = Logger.getLogger(GenerationJobRecoveryService::class.java)

    /**
     * L2 — same [de.littletrotter.application.invoice.reloadManaged] re-read
     * [GenerationJobResultHandler.markCompleted] uses, and for the same reason: [job] may be detached.
     */
    @Transactional
    fun markFailed(job: GenerationJob, message: String? = null, kositReport: String? = null, extraResultRefs: Map<String, String>? = null): GenerationJob {
        val managedJob = generationJobRepository.reloadManaged(job)
        val finishedAt = time.nowUtc()
        // Same reclaim as markCompleted: a late-but-genuine FAILED result for a job
        // the app's own stale-DELEGATED reset already put back to PENDING must not be discarded.
        managedJob.reclaimAndTransitionTo(GenerationJobStatus.FAILED)
        // result_refs is a jsonb column; a bare message string is invalid JSON and the rollback
        // silently undoes the FAILED transition, so the wrapped {"error": ...} form is load-bearing.
        val refs = buildMap {
            message?.let { put("error", it) }
            extraResultRefs?.let { putAll(it) }
        }
        // Same durable-column write markCompleted does.
        managedJob.recordResult(
            resultRefs = if (refs.isEmpty()) null else GenerationJobResultRefsCodec.MAPPER.writeValueAsString(refs),
            kositReport = kositReport,
            llmStatus = extraResultRefs?.get(GenerationJobResultRefsCodec.RESULT_REF_LLM_STATUS),
            finishedAt = finishedAt,
        )
        val saved = generationJobRepository.save(managedJob)
        // validation-status-truthful-pending.md R3 — a failed job must never leave "Validierung
        // läuft" behind.
        val invoice = saved.invoice
        clearPendingValidationStatus(invoice)
        val invoiceId = invoice?.id
        if (invoiceId != null) {
            eventDispatcher.publishAfterCommit(
                GenerationJobFailed(
                    invoiceId = invoiceId,
                    invoiceNumber = invoice.invoiceNumber.value,
                    generationJobId = saved.id ?: -1,
                    reason = message ?: "Unbekannter Fehler",
                    issued = invoice.status.isIssued,
                )
            )
        }
        return saved
    }

    /** validation-status-truthful-pending.md R3 — shared by [markFailed] and
     *  [failExhaustedJob]: a job reaching a terminal FAILED state must never leave "Validierung
     *  läuft" (PENDING) behind on its invoice. Only clears a status THIS job put there. */
    private fun clearPendingValidationStatus(invoice: Invoice?) {
        if (invoice != null && invoice.validationStatus == ValidationStatus.PENDING) {
            invoice.transitionValidationStatusTo(ValidationStatus.UNAVAILABLE)
            invoiceRepository.save(invoice)
        }
    }

    /**
     * The attempt-budget-exhaustion transition, called from
     * [GenerationJobProcessor.pollPendingJobs] once [job]'s attempts has reached [maxAttempts].
     * Delegates the actual write to [GenerationJobRepository.failExhausted], a guarded bulk
     * `UPDATE`. Returns `false` (no-op) if the row was no longer PENDING by the time this ran.
     */
    @Transactional
    fun failExhaustedJob(job: GenerationJob, maxAttempts: Int): Boolean {
        val id = job.id ?: return false
        // The shared prefix constant, not a literal.
        val reason = "${GenerationJob.ATTEMPT_BUDGET_EXHAUSTED_REASON_PREFIX} after ${job.attempts} attempt(s) (max $maxAttempts)"
        if (!generationJobRepository.failExhausted(id, reason, time.nowUtc())) return false
        val invoice = job.invoice
        llmResultHandler.failPendingAnalysesForInvoice(invoice?.id)
        clearPendingValidationStatus(invoice)
        val invoiceId = invoice?.id
        if (invoiceId != null && invoice.status.isIssued) {
            eventDispatcher.publishAfterCommit(
                GenerationJobExhausted(
                    invoiceId = invoiceId,
                    invoiceNumber = invoice.invoiceNumber.value,
                    generationJobId = id,
                    attempts = job.attempts,
                    reason = reason,
                )
            )
        }
        return true
    }

    /**
     * Best-effort diagnostic write for
     * [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer]'s DLQ path.
     * Deliberately never throws.
     */
    @Transactional
    fun recordLastError(jobId: Long, message: String): Boolean =
        try {
            generationJobRepository.recordLastError(jobId, message)
        } catch (e: Exception) {
            // Sole catch-all of this best-effort write: see the KDoc above
            // ("Deliberately never throws"). Letting this diagnostic write fail-and-propagate
            // would nak the whole NATS message and redeliver it just to retry a log note, not the
            // genuine processing failure it is already reporting.
            log.warn("Failed to record last_error for GenerationJob $jobId (best effort, ignored): ${e.message}", e)
            false
        }

    /**
     * L3 (fix-generation-job-failure-visibility.md) — the DLQ "give up" transition. Delegates
     * the actual write to [GenerationJobRepository.failDlq], a guarded bulk `UPDATE`. On success,
     * also clears a still-PENDING validation status and resolves any PENDING LLM analyses, then
     * fires [GenerationJobResultProcessingFailed] for the message center. Deliberately never
     * throws (same contract as [recordLastError]).
     */
    @Transactional
    fun markResultProcessingFailed(jobId: Long, message: String): Boolean =
        try {
            doMarkResultProcessingFailed(jobId, message)
        } catch (e: Exception) {
            // Sole catch-all of this best-effort write: see this method's own
            // KDoc ("Deliberately never throws"), same non-redelivery reasoning as
            // recordLastError above.
            log.warn("Failed to persist DLQ terminal FAILED state for GenerationJob $jobId (best effort, falling back to recordLastError): ${e.message}", e)
            false
        }

    private fun doMarkResultProcessingFailed(jobId: Long, message: String): Boolean {
        if (!generationJobRepository.failDlq(jobId, message, time.nowUtc())) return false
        val job = generationJobRepository.findById(jobId) ?: return true
        val invoice = job.invoice
        llmResultHandler.failPendingAnalysesForInvoice(invoice?.id)
        clearPendingValidationStatus(invoice)
        val invoiceId = invoice?.id
        if (invoiceId != null && invoice.status.isIssued) {
            eventDispatcher.publishAfterCommit(
                GenerationJobResultProcessingFailed(
                    invoiceId = invoiceId,
                    invoiceNumber = invoice.invoiceNumber.value,
                    generationJobId = jobId,
                    reason = message,
                )
            )
        }
        return true
    }

    /**
     * Worker-restart recovery for an analysis stuck "PENDING" because the
     * deferred SECOND callback ([GenerationJobLlmResultHandler.mergeLlmResult]) never arrived (worker
     * crash/restart). Deliberately the simplest honest recovery: jobs whose `finishedAt` falls in
     * the bounded window `[now - staleCutoffMinutes - lookbackHours, now - staleCutoffMinutes)`
     * and whose `resultRefs.llmStatus` is still "PENDING" get flipped straight to "FAILED".
     */
    @Transactional
    fun resetStaleLlmPending(staleCutoffMinutes: Long = 15, lookbackHours: Long = 24): Int =
        resolveStaleLlmPendingForIds(findStaleLlmPendingCandidateIds(staleCutoffMinutes, lookbackHours))

    /**
     * The batched production entry point: [GenerationJobProcessor
     * .resetStaleLlmAnalyses] calls this instead of [resetStaleLlmPending] directly, committing in
     * [batchSize]-sized chunks, each in [LlmPendingRecoveryBatchTransaction]'s own, genuinely separate
     * transaction.
     */
    fun resetStaleLlmPendingBatched(
        batchTransaction: LlmPendingRecoveryBatchTransaction,
        staleCutoffMinutes: Long = 15,
        lookbackHours: Long = 24,
        batchSize: Int = 200,
    ): Int =
        findStaleLlmPendingCandidateIds(staleCutoffMinutes, lookbackHours)
            .chunked(batchSize)
            .sumOf { batchTransaction.resolveBatch(it) }

    private fun findStaleLlmPendingCandidateIds(staleCutoffMinutes: Long, lookbackHours: Long): List<Long> {
        val now = time.nowUtc()
        val cutoff = now.minusMinutes(staleCutoffMinutes)
        val earliest = now.minusHours(lookbackHours)
        return generationJobRepository.findCompletedBetween(earliest, cutoff).mapNotNull { it.id }
    }

    /**
     * The actual per-job recovery logic [resetStaleLlmPending] and
     * [LlmPendingRecoveryBatchTransaction.resolveBatch] both run. `internal` so
     * [LlmPendingRecoveryBatchTransaction], a distinct class in the same module, can call it.
     */
    internal fun resolveStaleLlmPendingForIds(ids: List<Long>): Int {
        var resolved = 0
        ids.forEach { id -> if (resolveOneStaleLlmPending(id)) resolved++ }
        return resolved
    }

    private fun resolveOneStaleLlmPending(id: Long): Boolean {
        val job = generationJobRepository.findById(id) ?: return false
        val existing: Map<String, String> = job.resultRefs?.let {
            // Narrowed to the JSON-parse failure this can realistically throw.
            try {
                GenerationJobResultRefsCodec.MAPPER.readValue(it, GenerationJobResultRefsCodec.MAP_TYPE)
            } catch (e: JsonProcessingException) {
                log.warn("Job ${job.id}: resultRefs is not a String map, skipping in stale-LLM recovery: ${e.message}", e)
                null
            }
        } ?: return false
        if (existing[GenerationJobResultRefsCodec.RESULT_REF_LLM_STATUS] != "PENDING") return false
        val resolvedRefs = existing + mapOf(
            GenerationJobResultRefsCodec.RESULT_REF_LLM_STATUS to "FAILED",
            "llmError" to "Worker neu gestartet — KI-Analyse nicht abgeschlossen",
        )
        job.updateResultRefs(GenerationJobResultRefsCodec.MAPPER.writeValueAsString(resolvedRefs))
        generationJobRepository.save(job)
        llmResultHandler.updateLlmAnalysisFromResult(job.invoice?.id, resolvedRefs)
        return true
    }
}
