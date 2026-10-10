package de.littletrotter.application.invoice

import de.littletrotter.domain.event.GenerationJobCreated
import de.littletrotter.domain.invoice.DocumentJobLane
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.event.TransactionPhase
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger
import org.jboss.logging.MDC
import io.quarkus.scheduler.Scheduled
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Boundaries — pollPendingJobs' single @Scheduled tick deliberately isolates each of its
// sub-steps (resetStaleDelegatedJobs / reopenExhaustedJobs / the PENDING scan / per-job republish
// / the LLM-recovery step) behind its own catch-all rather than one catch around the whole tick:
// one sub-step failing must not abort the others in the same tick (see each method's own KDoc).
// onGenerationJobCreated is a separate flow (an AFTER_SUCCESS event observer, not the scheduled
// tick) with its own, independently documented boundary. Every catch already logs with cause
// (deadlocks at WARN, everything else at ERROR), never a silent swallow.
@ApplicationScoped
class GenerationJobProcessor @Inject constructor(
    private val queryService: GenerationJobQueryService,
    private val publishingService: GenerationJobPublishingService,
    private val recoveryService: GenerationJobRecoveryService,
    private val documentJobPublisher: DocumentJobPublisher,
    // PO-Grundsatz 29.08.2026 — dedicated collaborator bean replacing the former
    // `Provider<GenerationJobProcessor>` self-reference: [onGenerationJobCreated] dispatches to
    // [GenerationJobFastPublishTransaction.publishOne] on a genuinely distinct bean, so its
    // `@Transactional` is reached the ordinary way any cross-bean CDI call reaches an interceptor.
    // See that class's own KDoc for the full reasoning this replaced.
    private val fastPublishTransaction: GenerationJobFastPublishTransaction,
    // PO-Grundsatz 29.08.2026 — typed as the concrete MicroProfile `ManagedExecutor` interface
    // rather than the plain JDK `java.util.concurrent.Executor`: a bare `Executor` injection
    // point has no single resolvable CDI bean of that exact type, which is what Qodana's CDI
    // inspector flagged as an "unsatisfied dependency". `@FastPathExecutor` (not Quarkus's own
    // unqualified default `ManagedExecutor` bean) — see [FastPathManagedExecutorProducer]'s own
    // KDoc: the unqualified default propagates the JTA Transaction context by default, which
    // silently broke [GenerationJobFastPublishTransaction.publishOne]'s own `@Transactional` (it saw a
    // stale, already-committed transaction from the `AFTER_SUCCESS` calling thread instead of
    // starting its own fresh one) — caught by `GenerationJobFastPublishIT` while fixing this very
    // injection point, not merely a Qodana-satisfying type swap. `ManagedExecutor` extends
    // `java.util.concurrent.ExecutorService` (itself an `Executor`), so every call site below
    // that only ever needs `.execute(...)` keeps working unchanged. `@param:` is used for the
    // same reason as `@ConfigProperty` elsewhere in this constructor: `@FastPathExecutor`'s
    // `@Target` includes FIELD as well as VALUE_PARAMETER (a CDI qualifier must be usable on
    // either), so Kotlin 2.4's default annotation-use-site-target would otherwise copy it onto
    // the backing final field too — CDI never reads the field (no `@Inject` there), only Qodana's
    // inspector would misread it as a second, illegal injection point.
    @param:FastPathExecutor
    private val managedExecutor: ManagedExecutor,
    // The attempt budget a stale-DELEGATED job may burn through (each stale-reset counts
    // one, see GenerationJobRepository.resetStaleDelegated's own KDoc) before pollPendingJobs fails
    // it outright instead of re-delegating it for another doomed cycle. Default 5 matches the
    // decision doc's own default.
    // `@param:` pins this to the constructor parameter only; the default
    // param-property annotation target (Kotlin 2.4) would otherwise also copy `@ConfigProperty`
    // onto the backing final field, which Qodana's CDI inspector misreads as an illegal
    // final-field injection even though CDI never reads the field (no `@Inject` there).
    @param:ConfigProperty(name = "invoice.jobs.max-attempts", defaultValue = "5")
    private val maxAttempts: Int,
    // Real CDI always resolves the one StaleDelegatedResetCoordinator bean. `null` (not a
    // plain-instance default like llmPendingRecoveryBatchTransaction below) because building a harmless
    // one needs a real GenerationJobRepository this class has no other reason to depend on — every
    // existing test constructor keeps compiling and behaving as "nothing to reset", the same
    // outcome an empty candidate list would have produced.
    private val staleDelegatedResetCoordinator: StaleDelegatedResetCoordinator? = null,
    // Real CDI always resolves the one LlmPendingRecoveryBatchTransaction bean; defaulted
    // to a plain instance built from [recoveryService] (its own single-arg constructor, no
    // CDI proxy) for the same "every existing test constructor keeps compiling" reason as above —
    // harmless outside a live container, see that class's own KDoc for why its `@Transactional`
    // is inert without one.
    private val llmPendingRecoveryBatchTransaction: LlmPendingRecoveryBatchTransaction = LlmPendingRecoveryBatchTransaction(recoveryService),
    // Real CDI always resolves the one sweep bean; null keeps every existing test constructor
    // meaning "nothing to sweep".
    private val issuedDocumentJobSweep: IssuedDocumentJobSweep? = null,
    // A bulkhead-rejected dispatch is back-pressure, not a fault: it is retried with a short doubling
    // backoff before the job is handed back to PENDING. The hourly recovery scan stays the emergency
    // path and is deliberately not shortened for this.
    @param:ConfigProperty(name = "invoice.jobs.dispatch-retry-attempts", defaultValue = "5")
    private val dispatchRetryAttempts: Int = DEFAULT_DISPATCH_RETRY_ATTEMPTS,
    @param:ConfigProperty(name = "invoice.jobs.dispatch-retry-initial-delay-ms", defaultValue = "200")
    private val dispatchRetryInitialDelayMs: Long = DEFAULT_DISPATCH_RETRY_INITIAL_DELAY_MS,
    // Own small executor for interactive jobs so queued bulk fast-path tasks cannot delay them.
    // Tests default to a same-thread executor, like the shared one.
    private val interactiveExecutor: InteractiveFastPathExecutor = InteractiveFastPathExecutor { it.run() },
) {

    /** Backoff sleeper; replaced in unit tests so retries run without real waiting. */
    internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }

    /** Secondary constructor for unit tests that don't need to inject a CDI-managed executor,
     *  a dedicated [GenerationJobFastPublishTransaction], or the attempt-budget config at all — defaults
     *  to a same-thread executor (via [DelegatingManagedExecutor]) so a directly invoked
     *  [onGenerationJobCreated] runs [GenerationJobFastPublishTransaction.publishOne] synchronously and
     *  deterministically rather than on a background thread, a plain (non-CDI-proxied, but
     *  otherwise identical) [GenerationJobFastPublishTransaction] built from the same two collaborators,
     *  and to [DEFAULT_MAX_ATTEMPTS]. */
    constructor(
        queryService: GenerationJobQueryService,
        publishingService: GenerationJobPublishingService,
        recoveryService: GenerationJobRecoveryService,
        documentJobPublisher: DocumentJobPublisher,
    ) : this(
        queryService,
        publishingService,
        recoveryService,
        documentJobPublisher,
        GenerationJobFastPublishTransaction(queryService, publishingService, documentJobPublisher),
        DelegatingManagedExecutor { it.run() },
        DEFAULT_MAX_ATTEMPTS,
    )

    /** Secondary constructor for unit tests that supply their own executor (e.g. to assert
     *  dispatch happens through it, or to simulate a saturated/shutting-down pool) but don't care
     *  about a custom [GenerationJobFastPublishTransaction] or the attempt-budget config either — defaults
     *  to [DEFAULT_MAX_ATTEMPTS], same as the 4-arg constructor above. Wraps the plain [Executor]
     *  via [DelegatingManagedExecutor] since the real constructor property is now typed
     *  [ManagedExecutor] — see that parameter's own KDoc. */
    constructor(
        queryService: GenerationJobQueryService,
        publishingService: GenerationJobPublishingService,
        recoveryService: GenerationJobRecoveryService,
        documentJobPublisher: DocumentJobPublisher,
        managedExecutor: Executor,
    ) : this(
        queryService,
        publishingService,
        recoveryService,
        documentJobPublisher,
        GenerationJobFastPublishTransaction(queryService, publishingService, documentJobPublisher),
        DelegatingManagedExecutor(managedExecutor),
        DEFAULT_MAX_ATTEMPTS,
    )

    /** Secondary constructor preserving the pre-refactor (executor, maxAttempts) test shape —
     *  same [DelegatingManagedExecutor] wrapping and plain [GenerationJobFastPublishTransaction] as the
     *  5-arg constructor above, but with an explicit attempt budget for testing. */
    constructor(
        queryService: GenerationJobQueryService,
        publishingService: GenerationJobPublishingService,
        recoveryService: GenerationJobRecoveryService,
        documentJobPublisher: DocumentJobPublisher,
        managedExecutor: Executor,
        maxAttempts: Int,
    ) : this(
        queryService,
        publishingService,
        recoveryService,
        documentJobPublisher,
        GenerationJobFastPublishTransaction(queryService, publishingService, documentJobPublisher),
        DelegatingManagedExecutor(managedExecutor),
        maxAttempts,
    )

    private val log = Logger.getLogger(GenerationJobProcessor::class.java)
    private val recoveryRunning = AtomicBoolean(false)

    companion object {
        // Shared default for every test constructor above and the real @ConfigProperty
        // fallback; single source of truth so the two never drift apart.
        internal const val DEFAULT_MAX_ATTEMPTS = 5
        internal const val DEFAULT_DISPATCH_RETRY_ATTEMPTS = 5
        internal const val DEFAULT_DISPATCH_RETRY_INITIAL_DELAY_MS = 200L

        // Bounds the poller's own wait on the republish ack, the same reasoning and value
        // as GenerationJobFastPublishTransaction.FAST_PATH_PUBLISH_TIMEOUT_SECONDS: comfortably above the
        // ack wait DocumentJobDispatchGate itself already bounds this call to
        // (invoice.publish.timeout-ms), short enough to fail visibly instead of hanging the tick.
        internal const val REPUBLISH_ACK_TIMEOUT_SECONDS = 3L

        // fetchPendingWithRetry's own backoff: 200ms, then x3 each attempt (200 -> 600 -> 1800).
        private const val INITIAL_RETRY_DELAY_MS = 200L
        private const val MAX_QUERY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MULTIPLIER = 3
    }

    @Scheduled(every = "{invoice.jobs.recovery-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun pollPendingJobs() {
        // SKIP covers scheduler invocations only. The asynchronous startup pass uses the same
        // guard so one process never scans twice concurrently; cross-process safety remains
        // the repository's atomic PENDING-to-DELEGATED claim.
        if (!recoveryRunning.compareAndSet(false, true)) return
        try {
            scanPendingJobs()
        } finally {
            recoveryRunning.set(false)
        }
    }

    private fun scanPendingJobs() {
        resetStaleDelegatedJobs()
        reopenExhaustedJobs()
        requestMissingIssuedDocumentJobs()

        val jobs = try {
            fetchPendingWithRetry()
        } catch (ex: Exception) {
            log.error("Failed to query pending generation jobs, skipping this poll", ex)
            return
        }

        if (jobs.isEmpty()) {
            return
        }

        jobs.forEach { job ->
            republishSingleJob(job)
        }
    }

    private fun fetchPendingWithRetry(): List<de.littletrotter.domain.invoice.GenerationJob> {
        var attempt = 0
        var delayMs = INITIAL_RETRY_DELAY_MS
        while (true) {
            try {
                return queryService.findPendingWithInvoice()
            } catch (ex: Exception) {
                attempt++
                log.warnf(ex, "Attempt %d to query pending generation jobs failed", attempt)
                if (attempt >= MAX_QUERY_ATTEMPTS) {
                    throw ex
                }
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ex
                }
                delayMs *= RETRY_BACKOFF_MULTIPLIER
            }
        }
    }

    private fun republishSingleJob(job: de.littletrotter.domain.invoice.GenerationJob) {
        MDC.put("jobId", job.id?.toString() ?: "?")
        try {
            val invoice = job.invoice
            if (invoice == null) {
                log.warnf("GenerationJob %d has no associated invoice — skipping re-publish", job.id)
                return
            }
            MDC.put("invoiceNumber", invoice.invoiceNumber.value)
            if (job.attempts >= maxAttempts) {
                log.errorf(
                    "GenerationJob %d (invoice=%s) exhausted its attempt budget (%d/%d) — failing instead of re-publishing",
                    job.id, invoice.invoiceNumber.value, job.attempts, maxAttempts,
                )
                recoveryService.failExhaustedJob(job, maxAttempts)
                return
            }
            if (!publishingService.tryMarkDelegated(job)) {
                log.debugf("GenerationJob %d already delegated by another thread — skipping", job.id)
                return
            }
            log.infof(
                "Re-publishing GenerationJob %d (invoice=%s) to NATS subject [document-jobs]",
                job.id, invoice.invoiceNumber.value,
            )
            try {
                val ack = publishWithBackpressureRetry(job)
                publishingService.recordDelegatedStreamSeq(job, ack.seqno)
            } catch (ex: Exception) {
                // The claim was taken, so it must be handed back: left DELEGATED with no stream
                // sequence it would only be reset by the hourly emergency scan.
                handBackClaim(job)
                throw ex
            }
        } catch (ex: Exception) {
            if (isPostgresDeadlock(ex)) {
                log.warnf("Deadlock detected re-publishing GenerationJob %d — retried on the next poll: %s", job.id, ex.message)
            } else {
                log.errorf(ex, "Failed to re-publish GenerationJob %d to NATS", job.id)
            }
        } finally {
            MDC.remove("jobId")
            MDC.remove("invoiceNumber")
        }
    }

    /**
     * Publishes a claimed job. The poller thread has no outer transaction, so a bulkhead rejection
     * (back-pressure) can be retried in place with a bounded doubling backoff; any other failure,
     * or an exhausted budget, propagates to the caller, which hands the claim back.
     */
    private fun publishWithBackpressureRetry(job: de.littletrotter.domain.invoice.GenerationJob): io.nats.client.api.PublishAck {
        var attempt = 1
        var delayMs = dispatchRetryInitialDelayMs
        while (true) {
            try {
                return documentJobPublisher.publish(job).get(REPUBLISH_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (ex: Exception) {
                if (!ex.isBulkheadRejection() || attempt >= dispatchRetryAttempts) {
                    throw ex
                }
                log.warnf("Dispatch of GenerationJob %d rejected by the bulkhead (attempt %d/%d) — retrying in %d ms", job.id, attempt, dispatchRetryAttempts, delayMs)
                sleeper(delayMs)
                attempt++
                delayMs *= 2
            }
        }
    }

    private fun handBackClaim(job: de.littletrotter.domain.invoice.GenerationJob) {
        try {
            publishingService.revertToPending(job)
        } catch (ex: Exception) {
            log.errorf(ex, "Could not hand the claim of GenerationJob %d back to PENDING — the stale-DELEGATED reset will", job.id)
        }
    }

    /**
     * Fired once
     * [GenerationJobCreationService.createJob]'s transaction has committed a new PENDING job
     * (`TransactionPhase.AFTER_SUCCESS`, see [GenerationJobCreated]'s own KDoc). This observer
     * method itself runs *synchronously*, on whatever thread completed the transaction — which
     * may well be the Vaadin/UI request thread. CDI transactional observers cannot be
     * `@ObservesAsync` (transaction phases only apply to synchronous `fire()`), so the hop onto a
     * worker thread has to happen explicitly, right here, rather than by annotation — that is
     * exactly what [managedExecutor] is for.
     *
     * Scheduling itself is defensive: [Executor.execute] can throw (e.g. a saturated/shutting-down
     * pool) and that exception must never propagate back into the caller that just created the
     * job — the poller remains the fallback for a job whose fast-path publish never even got
     * scheduled.
     */
    fun onGenerationJobCreated(@Observes(during = TransactionPhase.AFTER_SUCCESS) event: GenerationJobCreated) {
        try {
            // fastPublishTransaction, a genuinely distinct CDI bean — see its own KDoc and this class's
            // `fastPublishTransaction` constructor parameter for why publishOne's @Transactional is only
            // actually reached this way (a same-instance self-call would bypass Jakarta
            // interceptors entirely).
            val executor: Executor =
                if (event.lane == DocumentJobLane.INTERACTIVE) interactiveExecutor else managedExecutor
            executor.execute { publishFastPathWithBackpressureRetry(event.jobId) }
        } catch (ex: Exception) {
            // Kept broad on purpose — see this method's own KDoc: this observer runs
            // synchronously on the thread that just committed the job-creation transaction (often
            // the Vaadin/UI thread), and scheduling can throw (saturated pool); that must never
            // propagate back into the caller. Already logs at error; the poller is the documented
            // fallback.
            log.errorf(ex, "Failed to schedule fast-path publish for GenerationJob %d — leaving to poller", event.jobId)
        }
    }

    /**
     * Runs on the executor worker, never on the request thread. Every attempt is its own
     * transaction ([GenerationJobFastPublishTransaction.publishOne] cannot retry inside itself), so
     * the backoff lives here. A job still rejected after the budget stays PENDING for the poller.
     */
    private fun publishFastPathWithBackpressureRetry(jobId: Long) {
        var attempt = 1
        var delayMs = dispatchRetryInitialDelayMs
        while (fastPublishTransaction.publishOne(jobId) == FastPublishOutcome.REJECTED_BUSY) {
            if (attempt >= dispatchRetryAttempts) {
                log.warnf("Fast-path publish of GenerationJob %d still rejected by the bulkhead after %d attempts — leaving to poller", jobId, attempt)
                return
            }
            try {
                sleeper(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            attempt++
            delayMs *= 2
        }
    }

    /**
     * Resets DELEGATED jobs that never started and are older than 2 minutes back to PENDING,
     * so the same [scanPendingJobs] pass re-publishes them.  This covers the case where
     * producer.send() exhausted its retries and left the job stranded in DELEGATED.
     *
     * No longer its own `@Scheduled` method: it used to run on a fixed 5-minute timer
     * independent of [pollPendingJobs]'s configurable `invoice.jobs.recovery-interval`, which meant
     * two timers for the one "recover a stuck generation job" concern, drifting apart the moment
     * that interval was ever configured away from its 5-minute default. Called from
     * [scanPendingJobs] instead, so it fires exactly once per recovery-interval tick, same as the
     * PENDING scan it feeds.
     *
     * The `staleCutoffMinutes = 2` below
     * must stay **>=** the JetStream stream's `duplicateWindow` (set on `NatsStreamSetup`'s stream
     * configuration — 2 min per the plan). [DocumentJobPublisher.publish] sets a
     * stable `Nats-Msg-Id` per job (`"job-$jobId"`) precisely so a re-publish of the same job this
     * function triggers is deduped by the broker, not enqueued twice. If this cutoff were ever
     * tuned *below* the dedup window, a legitimate stale-job republish could still fall inside the
     * broker's dedup window and get silently swallowed as a duplicate instead of actually
     * re-delivering a stranded job — see the matching comment on `DocumentJobPublisher.publish`.
     *
     * This `staleCutoffMinutes = 2` (120s) and kotlin-xpdf's own worker-side
     * `JobConsumer.ackWaitSeconds` (also 120s by default — the same number today, coincidentally,
     * per that field's own KDoc) are two genuinely independent staleness mechanisms this method
     * must be read together with, not tuned in isolation. This reset closes the gap its own
     * `GenerationJob.reclaimAndTransitionTo` safety net was only ever a net for: this cutoff no
     * longer decides the reset by itself — it only decides which rows are even *candidates*;
     * [staleDelegatedResetCoordinator] then checks each candidate's own message against the real
     * `document-jobs` stream and resets only the ones whose message genuinely no longer exists
     * there (see [StaleDelegatedResetCoordinator]'s own KDoc). A job merely queued behind a
     * backlog, or still legitimately in flight under `JobConsumer`'s heartbeat, is left `DELEGATED`
     * — JetStream still owns it.
     */
    private fun resetStaleDelegatedJobs() {
        val coordinator = staleDelegatedResetCoordinator ?: return
        try {
            val reset = coordinator.resetStale(staleCutoffMinutes = 2)
            if (reset > 0) {
                log.infof(
                    "Reset %d stale DELEGATED job(s) back to PENDING for re-publication (message no longer in the stream)",
                    reset,
                )
            }
        } catch (ex: Exception) {
            // Kept broad on purpose — this runs as the first step of scanPendingJobs, the
            // body of pollPendingJobs's @Scheduled tick; a reset failure must not stop the PENDING
            // scan that follows it in the same tick. Already logs at error.
            log.error("Failed to reset stale DELEGATED jobs", ex)
        }
    }

    /**
     * Reopens every `FAILED` job whose attempt budget was exhausted while no document was
     * ever produced for its invoice (see [GenerationJobPublishingService.reopenExhaustedJobsWithoutDocument]'s
     * own KDoc for the exact, narrow condition). Called unconditionally, every tick — the old
     * worker-liveness gate is gone: with [resetStaleDelegatedJobs]'s new rule, "no worker" can no
     * longer burn a budget in the first place (an undelivered message is never a reset candidate —
     * see [StaleDelegatedResetDecider]), so reopening no longer risks recreating the stuck state the
     * old gate was guarding against; a reopened job that genuinely has no worker to pick it up simply
     * stays queued, the same as any other `DELEGATED` job.
     */
    private fun requestMissingIssuedDocumentJobs() {
        try {
            val created = issuedDocumentJobSweep?.requestMissingCreditNoteJobs() ?: 0
            if (created > 0) {
                log.infof("Requested %d missing credit note document job(s)", created)
            }
        } catch (ex: Exception) {
            // Kept broad on purpose — same @Scheduled-tick reasoning as resetStaleDelegatedJobs.
            log.error("Failed to request missing credit note document jobs", ex)
        }
    }

    private fun reopenExhaustedJobs() {
        try {
            val reopened = publishingService.reopenExhaustedJobsWithoutDocument()
            if (reopened > 0) {
                log.infof("Reopened %d attempt-budget-exhausted GenerationJob(s)", reopened)
            }
        } catch (ex: Exception) {
            // Kept broad on purpose — same @Scheduled-tick reasoning as
            // resetStaleDelegatedJobs above.
            log.error("Failed to reopen attempt-budget-exhausted GenerationJobs", ex)
        }
    }

    /**
     * Worker-restart recovery for the deferred oMLX stage: an analysis
     * accepted via the FIRST callback (`resultRefs.llmStatus == "PENDING"`) but never resolved by
     * the deferred stage's SECOND callback (kotlin-xpdf worker crash/restart mid-analysis, or the
     * result message itself getting lost) would otherwise stay "PENDING" forever — see
     * [GenerationJobRecoveryService.resetStaleLlmPending]'s own KDoc for why 15 minutes/FAILED (not a
     * persistent retry queue) is the deliberately simplest honest recovery here: comfortably
     * above the ~1.5-2 min oMLX round trip this is meant to catch stragglers of, short enough that
     * an operator or a re-print notices within the same working session.
     *
     * Kept as its own fixed-interval `@Scheduled` method, deliberately NOT folded into
     * [pollPendingJobs]/[scanPendingJobs] the way [resetStaleDelegatedJobs] above was: this checks a
     * genuinely different condition on a genuinely different aggregate. [scanPendingJobs] recovers
     * [de.littletrotter.domain.invoice.GenerationJob] rows stuck in PENDING/DELEGATED — the
     * document-generation job itself. This method resolves
     * [de.littletrotter.domain.invoice.InvoiceLlmAnalysis] rows stuck in `PENDING` — the deferred,
     * asynchronous oMLX analysis *of* an already-generated document, which by design keeps running
     * (and can keep timing out) well after its owning job already reached `COMPLETED`. A GenerationJob
     * that never needed re-publishing at all can still have a stale LLM analysis, and folding this
     * into the PENDING-job scan would make its 15-minute cutoff hostage to whatever
     * `invoice.jobs.recovery-interval` happens to be configured to, for a condition that has nothing
     * to do with that interval's own concern.
     */
    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun resetStaleLlmAnalyses() {
        try {
            // The batched entry point (200 ids per transaction via
            // llmPendingRecoveryBatchTransaction), not resetStaleLlmPending's own single unbounded
            // transaction — see GenerationJobRecoveryService.resetStaleLlmPendingBatched's own KDoc.
            val resolved = recoveryService.resetStaleLlmPendingBatched(llmPendingRecoveryBatchTransaction, staleCutoffMinutes = 15)
            if (resolved > 0) {
                log.infof("Resolved %d stale PENDING LLM analysis result(s) to FAILED", resolved)
            }
        } catch (ex: Exception) {
            // Kept broad on purpose — same @Scheduled-tick reasoning as
            // resetStaleDelegatedJobs above. Already logs at error.
            log.error("Failed to reset stale PENDING LLM analyses", ex)
        }
    }
}
