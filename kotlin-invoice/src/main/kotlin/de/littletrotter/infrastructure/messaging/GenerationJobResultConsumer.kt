package de.littletrotter.infrastructure.messaging

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.application.cache.NoopInvoiceCachePort
import de.littletrotter.application.cache.port.outbound.InvoiceCachePort
import de.littletrotter.application.invoice.GenerationJobLlmResultHandler
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.GenerationJobRecoveryService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.domain.exception.GenerationJobNotFoundException
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.shared.GenerationJobResultRefs
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.documentjob.DocumentJobResult
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.jboss.logging.Logger

@ApplicationScoped
class GenerationJobResultConsumer @Inject constructor(
    private val queryService: GenerationJobQueryService,
    private val resultHandler: GenerationJobResultHandler,
    private val llmResultHandler: GenerationJobLlmResultHandler,
    private val recoveryService: GenerationJobRecoveryService,
    // The Vaadin UI push used to be fired from here directly, alongside
    // GenerationJobResultHandler.markCompleted/GenerationJobRecoveryService.markFailed's own domain-event
    // firing — two buses for one
    // outcome, one of which could be (and was) forgotten independently of the other. UI push is
    // now a plain listener on those same domain events
    // (de.littletrotter.application.invoice.GenerationJobUiPushHandler); this consumer no longer
    // knows the UI bus exists.
    private val cache: InvoiceCachePort,
    /** Nullable on purpose: every test constructor below stays usable without wiring a
     *  real Valkey. A `null` exchange resolves a present [DocumentJobResult.kositReportRef] the
     *  same way a genuine Valkey outage would ([resolveKositReport]'s own KDoc) — the documented
     *  "report unavailable" degrade path, not a crash. */
    private val payloadExchange: PayloadExchange?,
    /**
     * Bug production — the Hibernate `Session` backing this request-scoped
     * [EntityManager] outlives every individual `@Transactional` call [consume] makes
     * ([GenerationJobQueryService.findById]/`markCompleted`/etc are each their own transaction, but
     * all of them share the ONE session bound to the CDI request context this consumer runs in —
     * see [GenerationJobNatsAdapter.receive]'s own KDoc for how that request context gets
     * activated). When an attempt's flush fails (e.g. a concurrent writer already bumped an
     * `Invoice`'s `@Version`), Hibernate has already incremented that entity's in-memory version
     * before the rollback undoes the DB row — and the very next `findById` call in this method's
     * retry loop hands back the SAME poisoned instance from the session's first-level cache
     * instead of a fresh read, so every retry fails identically. [clearSessionBeforeAttempt]
     * evicts everything from that cache before EVERY attempt (including the first, in case an
     * earlier message already left something stale behind), forcing a genuinely fresh read each
     * time. Nullable so every existing unit-test constructor below keeps working without wiring a
     * real [EntityManager] (each passes `null` explicitly); real CDI always supplies one via the
     * primary constructor. Deliberately NOT defaulted (`= null`): a Kotlin default-value
     * parameter compiles to an extra synthetic bridge constructor purely for callers that omit
     * the argument — nothing here ever does, every call site below passes all five arguments
     * explicitly, so that bridge would be permanently dead, untestable bytecode under this file's
     * 100 % line/branch gate.
     */
    private val entityManager: EntityManager?,
) {

    private companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    /** Backwards-compatible for tests that only provide the four collaborators. */
    constructor(
        queryService: GenerationJobQueryService,
        resultHandler: GenerationJobResultHandler,
        llmResultHandler: GenerationJobLlmResultHandler,
        recoveryService: GenerationJobRecoveryService,
    ) : this(queryService, resultHandler, llmResultHandler, recoveryService, NoopInvoiceCachePort(), null, null)

    /** For tests that want a (fake/mock) [PayloadExchange] wired without also providing
     *  a cache; distinct overload from the one below (unambiguous by argument type). */
    constructor(
        queryService: GenerationJobQueryService,
        resultHandler: GenerationJobResultHandler,
        llmResultHandler: GenerationJobLlmResultHandler,
        recoveryService: GenerationJobRecoveryService,
        payloadExchange: PayloadExchange,
    ) : this(queryService, resultHandler, llmResultHandler, recoveryService, NoopInvoiceCachePort(), payloadExchange, null)

    /** Backwards-compatible for tests that provide the collaborators + cache but no Valkey wiring. */
    constructor(
        queryService: GenerationJobQueryService,
        resultHandler: GenerationJobResultHandler,
        llmResultHandler: GenerationJobLlmResultHandler,
        recoveryService: GenerationJobRecoveryService,
        cache: InvoiceCachePort,
    ) : this(queryService, resultHandler, llmResultHandler, recoveryService, cache, null, null)

    private val log = Logger.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    // Adapter-style: called by external messaging bridge or tests. Keeping the method public allows testability
    fun consume(raw: String) {
        val payload = parsePayload(raw) ?: return
        val jobId = extractJobId(payload, raw) ?: return

        var attempt = 0
        var backoffMs = 100L
        // Every exit is explicit: a successful attempt returns, and the catch block returns once
        // attempt reaches MAX_RETRY_ATTEMPTS — a loop condition here would be a dead branch.
        while (true) {
            try {
                processAttempt(payload, jobId)
                return
            } catch (e: Exception) {
                // AP-EXC-1: kept broad on purpose — this is the retry-with-backoff loop for the
                // actual NATS message handler (GenerationJobNatsAdapter.receive -> consume); it spans
                // several DB writes and business-logic calls (markCompleted/markFailed/
                // updateLlmAnalysisFromResult/...) whose realistic failure modes are numerous and not
                // usefully narrowable. Already logs at warn per attempt and error once giving up, and
                // durably records the failure via recordLastError (the closest thing to a counter
                // this consumer has) before returning without rethrowing.
                attempt++
                log.warn("Processing generation job result failed (attempt $attempt): ${e.message}")
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    giveUpAfterRetries(jobId, attempt, raw, e)
                    return
                }
                sleepBackoff(backoffMs)
                backoffMs *= 2
            }
        }
    }

    /** Parses [raw] into a [DocumentJobResult], DLQ-simulating (logging) and returning `null` on
     *  malformed input instead of propagating. */
    private fun parsePayload(raw: String): DocumentJobResult? =
        try {
            DocumentJobWireCodec.decodeResult(raw)
        } catch (e: JsonProcessingException) {
            // AP-EXC-1: narrowed — readValue only throws JsonProcessingException. This is scoped
            // specifically to "malformed payload"; any other, genuinely unexpected exception now
            // propagates instead of being folded into the same DLQ-simulated log lines, and is
            // caught by GenerationJobNatsAdapter.receive's own (deliberately broad) message-handler
            // catch one level up.
            log.warn("Malformed generation job result, DLQ (simulated): ${e.message}")
            log.error("DLQ: malformed payload -> $raw")
            null
        }

    /** Resolves [DocumentJobResult.id] to a numeric job id, DLQ-simulating and returning `null`
     *  when it is missing/non-numeric. */
    private fun extractJobId(payload: DocumentJobResult, raw: String): Long? =
        payload.id?.toLongOrNull() ?: run {
            log.warn("Received job result without numeric id, DLQ (simulated): $raw")
            log.error("DLQ: missing id -> $raw")
            null
        }

    /** One retry attempt: loads the job, short-circuits redelivered/already-terminal results,
     *  and dispatches by status. Throws on any failure so [consume]'s retry loop can back off. */
    private fun processAttempt(payload: DocumentJobResult, jobId: Long) {
        clearSessionBeforeAttempt()
        val job = queryService.findById(jobId)
            ?: throw GenerationJobNotFoundException(jobId)
        val statusUpper = payload.status?.uppercase()

        // Idempotent result processing. A re-delivered or old-cycle
        // first-callback result (COMPLETED/FAILED) for a job that already reached a
        // terminal state must be a no-op: never a second write, never a duplicate
        // ValidationFinding batch. Keyed on job.status (the authoritative terminal marker)
        // plus correlationId for the log line only — a genuinely different print cycle
        // always has its own, different jobId, so status alone is what actually decides
        // this.
        if (isTerminalOrRedelivered(job, statusUpper, payload.correlationId)) {
            return
        }

        val refs = GenerationJobResultRefs.fromStringMap(payload.resultRefs)
        when (statusUpper) {
            "COMPLETED" -> handleCompletedResult(job, jobId, payload, refs)
            "LLM_COMPLETED", "LLM_FAILED" -> handleLlmResult(job, jobId, payload)
            "FAILED" -> handleFailedResult(job, jobId, payload, refs)
            else -> log.warn("Unknown job result status for job $jobId: ${payload.status}")
        }
    }

    /**
     * L3 (fix-generation-job-failure-visibility.md) — persists a terminal, user-visible FAILED
     * state (jobs panel/invoice detail/message center all read GenerationJob.status), not just
     * the best-effort diagnostic note [GenerationJobRecoveryService.recordLastError] alone used to leave
     * behind — before this fix the job stayed DELEGATED/RUNNING ("Weitergeleitet"/"In
     * Bearbeitung") forever, with the error visible only in a tooltip.
     */
    private fun giveUpAfterRetries(jobId: Long, attempt: Int, raw: String, e: Exception) {
        log.error("DLQ: processing_failed -> $raw (error=${e.message})")
        val giveUpMessage = "processing failed after $attempt attempts: ${e.message}"
        // markResultProcessingFailed never throws (own KDoc); a false return means the job was
        // already terminal or never delegated at all, in which case recordLastError's own
        // best-effort diagnostic write is still the right fallback.
        val persistedTerminalFailure = recoveryService.markResultProcessingFailed(jobId, giveUpMessage)
        if (!persistedTerminalFailure) {
            recoveryService.recordLastError(jobId, giveUpMessage)
        }
    }

    private fun sleepBackoff(backoffMs: Long) {
        try {
            Thread.sleep(backoffMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun isMainResultStatus(statusUpper: String?): Boolean = statusUpper == "COMPLETED" || statusUpper == "FAILED"

    private fun isMainJobStatusTerminal(status: GenerationJobStatus): Boolean =
        status == GenerationJobStatus.COMPLETED || status == GenerationJobStatus.FAILED

    private fun isLlmResultStatus(statusUpper: String?): Boolean = statusUpper == "LLM_COMPLETED" || statusUpper == "LLM_FAILED"

    private fun isLlmJobStatusTerminal(llmStatus: String?): Boolean = llmStatus == "COMPLETED" || llmStatus == "FAILED"

    private fun isTerminalOrRedelivered(job: GenerationJob, statusUpper: String?, correlationId: String?): Boolean {
        if (isMainResultStatus(statusUpper) && isMainJobStatusTerminal(job.status)) {
            log.infof(
                "Ignoring redelivered/old-cycle %s result for already-terminal GenerationJob %d (status=%s, correlationId=%s)",
                statusUpper, job.id, job.status, correlationId,
            )
            return true
        }
        if (isLlmResultStatus(statusUpper) && isLlmJobStatusTerminal(job.llmStatus)) {
            log.infof(
                "Ignoring redelivered deferred LLM result for GenerationJob %d — llmStatus already terminal (%s)",
                job.id, job.llmStatus,
            )
            return true
        }
        return false
    }

    private fun handleCompletedResult(
        job: GenerationJob,
        jobId: Long,
        payload: DocumentJobResult,
        refs: GenerationJobResultRefs,
    ) {
        val resultRefsJson = payload.resultRefs?.let { mapper.writeValueAsString(it) }
        val completed = resultHandler.markCompleted(
            job,
            resultRefs = resultRefsJson,
            kositReport = resolveKositReport(payload, jobId),
            llmStatus = refs.llmStatus,
        )
        llmResultHandler.updateLlmAnalysisFromResult(
            job.invoice?.id, payload.resultRefs ?: emptyMap(),
        )
        log.info("Marked GenerationJob $jobId as COMPLETED")
        if (completed.trigger == GenerationJobTrigger.SEND) {
            resultHandler.resolveSendOutcome(completed)
        }
        payload.correlationId?.let { corrId ->
            val statusJson = mapper.writeValueAsString(
                mapOf("status" to "COMPLETED", "resultRefs" to payload.resultRefs, "jobId" to jobId)
            )
            cache.cacheJobStatus(corrId, statusJson)
        }
    }

    private fun handleLlmResult(
        job: GenerationJob,
        jobId: Long,
        payload: DocumentJobResult,
    ) {
        val llmRefs = payload.resultRefs ?: emptyMap()
        llmResultHandler.mergeLlmResult(job, llmRefs)
        llmResultHandler.updateLlmAnalysisFromResult(job.invoice?.id, llmRefs)
        log.info("Merged deferred oMLX result for GenerationJob $jobId (${payload.status})")
        cache.evictOverviewPages()
    }

    private fun handleFailedResult(
        job: GenerationJob,
        jobId: Long,
        payload: DocumentJobResult,
        refs: GenerationJobResultRefs,
    ) {
        val llmRefs = refs.onlyLlmStatus()?.toStringMap()
        val workerErrorText = refs.error ?: "external worker reported failure (no error detail)"
        val failed = recoveryService.markFailed(
            job,
            message = workerErrorText,
            kositReport = resolveKositReport(payload, jobId),
            extraResultRefs = llmRefs,
        )
        llmResultHandler.failPendingAnalysesForInvoice(job.invoice?.id)
        log.info("Marked GenerationJob $jobId as FAILED: $workerErrorText")
        if (failed.trigger == GenerationJobTrigger.SEND) {
            resultHandler.resolveSendOutcome(failed)
        }
        cache.evictOverviewPages()
        payload.correlationId?.let { corrId ->
            val statusJson = mapper.writeValueAsString(
                mapOf("status" to "FAILED", "jobId" to jobId)
            )
            cache.cacheJobStatus(corrId, statusJson)
        }
    }

    /**
     * Bug production — see [entityManager]'s own KDoc for why this must run before
     * EVERY attempt in [consume]'s retry loop, not just retries after the first failure.
     */
    private fun clearSessionBeforeAttempt() {
        entityManager?.clear()
    }

    /**
     * Resolves [DocumentJobResult.kositReportRef] via [payloadExchange] before persisting.
     * The DB keeps holding the full report exactly as before; only the wire changed.
     *
     * `null` covers three cases that all resolve to the exact same, already-existing UI state:
     * no report was ever produced (the previous behavior — [DocumentJobResult.kositReportRef] itself
     * is `null`), a genuine Valkey outage ([PayloadExchange.get] degrades to `null`, see its own
     * KDoc), or the ref key having expired/been evicted before this consumer read it. The last
     * two are logged as a WARN (the first is not — it is normal, not a degradation); a missing
     * report is never a processing failure — `job.kositReport.isNullOrBlank()` already reads as
     * a "nicht erfolgreich" chip (reason: report missing) everywhere in the UI
     * ([de.littletrotter.ui.view.invoice.resolveValidationOutcome]), so no separate marker string
     * is needed on top of `null`.
     */
    private fun resolveKositReport(payload: DocumentJobResult, jobId: Long): String? {
        val ref = payload.kositReportRef ?: return null
        val resolved = payloadExchange?.get(ref.key)
        if (resolved == null) {
            log.warn("KoSIT report ref ${ref.key} expired/missing for job $jobId — report marked unavailable")
        }
        return resolved
    }
}
