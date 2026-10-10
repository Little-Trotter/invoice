package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import org.jboss.logging.MDC
import java.util.concurrent.TimeUnit

/** Result of one [GenerationJobFastPublishTransaction.publishOne] attempt; only [REJECTED_BUSY] is worth retrying. */
enum class FastPublishOutcome { PUBLISHED, SKIPPED, REJECTED_BUSY, FAILED }

// Guards against pathological cause cycles.
private const val MAX_CAUSE_DEPTH = 10

/** True when a bulkhead rejected the call anywhere in the cause chain (back-pressure, not a fault). */
internal fun Throwable.isBulkheadRejection(): Boolean =
    generateSequence(this) { it.cause?.takeIf { c -> c !== it } }.take(MAX_CAUSE_DEPTH)
        .any { it is org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException }

/**
 * PO-Grundsatz 29.08.2026 (CDI-Injektionen nie ausnehmen) — the transactional half of the "fast
 * path" [GenerationJobProcessor.onGenerationJobCreated] hands off to its [managedExecutor
 * ][GenerationJobProcessor.managedExecutor] worker thread, pulled out into its own bean instead of
 * [GenerationJobProcessor] calling back into its own `@Transactional` method through a
 * `Provider<GenerationJobProcessor>` self-reference (`selfProvider`, the pre-existing pattern this
 * class replaces). [publishOne]'s `@Transactional` is now reached the ordinary way any cross-bean
 * CDI call reaches an interceptor — through the callee's (this bean's) own CDI proxy, injected as
 * a genuinely distinct collaborator into [GenerationJobProcessor] rather than a lazy handle back
 * onto itself. No `Provider`, no self-reference, no field-injection workaround: a plain
 * constructor-injected bean is sufficient once the transactional operation lives on a bean other
 * than the one dispatching to it.
 */
@ApplicationScoped
class GenerationJobFastPublishTransaction @Inject constructor(
    private val queryService: GenerationJobQueryService,
    private val publishingService: GenerationJobPublishingService,
    private val documentJobPublisher: DocumentJobPublisher,
) {
    private val log = Logger.getLogger(GenerationJobFastPublishTransaction::class.java)

    companion object {
        // Bounds how long the fast path waits for a publish ack before giving up on this
        // attempt and handing the job back to the poller (see publishOne's KDoc). Comfortably
        // above a healthy round-trip (tens of ms) and short enough that a genuinely unreachable
        // NATS is detected without waiting for the configurable recovery scan.
        //
        // Also above the ack wait DocumentJobDispatchGate bounds underneath this call
        // (invoice.publish.timeout-ms, 2s by default). That bound must fire before this outer
        // .get() does, or this fast path would give up on a call still legitimately in flight
        // underneath it. If either value changes, re-check that this constant still exceeds it.
        private const val FAST_PATH_PUBLISH_TIMEOUT_SECONDS = 3L
    }

    /**
     * Publishes exactly one job immediately after creation, reusing the same
     * PENDING→DELEGATED claim ([GenerationJobService.tryMarkDelegated]) and
     * [DocumentJobPublisher.publish] path [GenerationJobProcessor.pollPendingJobs] already uses
     * for recovery: whichever of {this fast path, the poller} reaches the claim first is the only
     * one that ever publishes for a given job.
     *
     * Runs on [GenerationJobProcessor.managedExecutor] (see
     * [GenerationJobProcessor.onGenerationJobCreated]) — never inline on the thread that committed
     * the creating transaction. That worker thread starts with **no** ambient transaction or CDI
     * request context at all (unlike [GenerationJobProcessor.pollPendingJobs], a `@Scheduled`
     * method Quarkus activates a request context for automatically), so `@Transactional` here is
     * not optional: without it, [GenerationJobService.findByIdWithInvoice]'s query itself fails
     * (`ContextNotActiveException`), and even fixing that alone would not be enough — the loaded
     * `invoice`/`job` would still be detached the moment this method's transaction ends, and
     * [DocumentJobPublisher.publish]'s own lazy reads (recipient addresses, etc.) would throw
     * `LazyInitializationException` since each `@Transactional` service call otherwise opens and
     * closes its *own* short-lived session. One transaction spanning the whole method is what
     * keeps the entity graph attached from the initial fetch through the actual publish call. That
     * transaction only actually takes effect because [GenerationJobProcessor] reaches this method
     * through this bean's own CDI proxy (a genuine cross-bean call) — the whole reason this method
     * lives on its own bean rather than back on [GenerationJobProcessor] itself, see this class's
     * own KDoc.
     *
     * A publish failure (including NATS being unreachable — bounded by
     * [FAST_PATH_PUBLISH_TIMEOUT_SECONDS]) is logged and **not** retried inside this method: it
     * joins one transaction, and a rejected attempt marks that transaction rollback-only (see
     * [DocumentJobDispatchGate]). The claim is handed back ([GenerationJobService.revertToPending])
     * and the outcome is reported: [FastPublishOutcome.REJECTED_BUSY] (bulkhead back-pressure) is
     * re-invoked by [GenerationJobProcessor.onGenerationJobCreated]'s executor task with backoff,
     * each time in a fresh transaction; other failures stay PENDING for the next poller tick.
     * The hourly recovery scan remains the emergency path only.
     */
    // Failure handling is consolidated in one helper instead of one catch per step:
    // publishOne runs fire-and-forget on managedExecutor with nothing awaiting the result, so
    // catching and logging is the only way any failure becomes visible at all. The claim step hands
    // the job back only once the PENDING→DELEGATED claim was actually taken, so the claim is
    // returned to the poller only when there is a claim to return.
    @Transactional
    fun publishOne(jobId: Long): FastPublishOutcome {
        MDC.put("jobId", jobId.toString())
        try {
            val claimedJob = try {
                claim(jobId)
            } catch (ex: Exception) {
                return failed(ex, jobId, null)
            } ?: return FastPublishOutcome.SKIPPED
            return try {
                publish(claimedJob)
                FastPublishOutcome.PUBLISHED
            } catch (ex: Exception) {
                failed(ex, jobId, claimedJob)
            }
        } finally {
            MDC.remove("jobId")
            MDC.remove("invoiceNumber")
        }
    }

    private fun failed(ex: Exception, jobId: Long, claimedJob: GenerationJob?): FastPublishOutcome {
        val revertSuffix = if (claimedJob != null) " — reverting to PENDING for the poller to retry" else ""
        // Fix U — a Postgres deadlock (40P01) is already self-healing via revertToPending
        // below; see PostgresDeadlockDetection's own KDoc for why this is WARN, not ERROR.
        if (isPostgresDeadlock(ex)) {
            log.warnf("Deadlock detected publishing GenerationJob %d%s: %s", jobId, revertSuffix, ex.message)
        } else {
            log.errorf(ex, "Fast-path publish failed for GenerationJob %d%s", jobId, revertSuffix)
        }
        claimedJob?.let { publishingService.revertToPending(it) }
        return if (ex.isBulkheadRejection()) FastPublishOutcome.REJECTED_BUSY else FastPublishOutcome.FAILED
    }

    /** Takes the PENDING→DELEGATED claim; null when there is nothing to publish or the poller won. */
    private fun claim(jobId: Long): GenerationJob? {
        val job = queryService.findByIdWithInvoice(jobId)
        val invoice = job?.invoice
        val skipReason = when {
            job == null -> "no longer exists — nothing to do"
            invoice == null -> "has no associated invoice — leaving to poller"
            else -> null
        }
        if (job == null || invoice == null) {
            log.debugf("Fast-path publish: GenerationJob %d %s", jobId, skipReason)
            return null
        }
        MDC.put("invoiceNumber", invoice.invoiceNumber.value)
        // Idempotency guard: only publish if we could atomically claim the PENDING→DELEGATED
        // transition — if the poller already won that race, there is nothing left to do here.
        if (!publishingService.tryMarkDelegated(job)) {
            log.debugf("Fast-path publish: GenerationJob %d already delegated (poller won the race) — skipping", jobId)
            return null
        }
        log.infof(
            "Fast-path publishing GenerationJob %d (invoice=%s) to NATS subject [document-jobs]",
            jobId, invoice.invoiceNumber.value,
        )
        return job
    }

    private fun publish(job: GenerationJob) {
        // publish() maps the invoice through repositories before it touches NATS, which needs the
        // active persistence context of this method's transaction (the executor worker thread has
        // none of its own). All job options come off the persisted `job`, the same values the
        // poller's republish reads.
        val ack = documentJobPublisher.publish(job)
            .get(FAST_PATH_PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Recorded synchronously inside the same transaction: the ack is the only point where the
        // stream position is learned. A failure here reverts the claim like any publish failure.
        publishingService.recordDelegatedStreamSeq(job, ack.seqno)
    }
}
