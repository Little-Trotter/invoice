package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.Invoice
import io.nats.client.api.PublishAck
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException
import org.jboss.logging.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The ack of one dispatched generation job. Deliberately a plain holder and not a
 * `CompletionStage` itself: SmallRye Fault Tolerance treats a method returning `CompletionStage`
 * as asynchronous and guards the *completion of the stage* instead of the method body — a
 * `@Bulkhead` then grows a waiting queue whose queued calls run on whichever thread completes
 * the previous one (here: the NATS callback thread, inside a JPA transaction), and a `@Timeout`
 * races the broker ack rather than the work. Returning a non-stage type keeps the guard on
 * [DocumentJobDispatchGate.dispatch] synchronous: a semaphore bulkhead around the method body.
 */
class DispatchedJob(val ack: CompletableFuture<PublishAck>)

/**
 * The one guarded seam every generation-job dispatch passes through, between
 * [DocumentJobPublisher.publish] (the public entry point with its optimistic-lock reload) and
 * [DocumentJobPublishTransaction.publishTransactional] (the transactional work). It is a bean of its
 * own so the CDI interceptor applies: a self-invocation inside [DocumentJobPublisher] would not
 * be proxied.
 *
 * - `@Bulkhead` bounds how many dispatches serialise an invoice at once — the place
 *   [InvoiceDtoMapper.mapToDto]'s per-call memory cost is paid in this process. The limit comes
 *   from `invoice.generation.bulkhead.max-concurrent-calls` (see `application.properties` and
 *   `docker-compose.yml`'s `LT_INVOICE_MEMORY_LIMIT` comment for the sizing). A full bulkhead
 *   throws [BulkheadException] to the caller immediately: every caller
 *   already handles a publish exception, and the job's PENDING row persisted before the call
 *   keeps the work durable. A rejection is back-pressure: the fast path hands its claim
 *   back ([GenerationJobService.revertToPending]) and its executor task re-invokes it with
 *   bounded backoff in a fresh transaction; the poller retries in place with the same backoff
 *   and reverts the claim once the budget is spent.
 * - [dispatchInteractive] is the same seam with an own, separately sized bulkhead for the interactive lane.
 * - Deliberately no `@Retry`: [DocumentJobPublishTransaction.publishTransactional] is `@Transactional`
 *   and *joins* the fast path's own transaction ([GenerationJobFastPublishTransaction.publishOne]), so a
 *   failed attempt has already marked that transaction rollback-only — a second attempt would
 *   reach the broker while its own database work is rolled back, leaving a message on the stream
 *   for a job that stays PENDING. The retry for a dispatch lives in the callers: the fast path's
 *   executor task re-runs the whole transaction, the poller retries on its own transaction-free
 *   thread; the hourly scan with its stale-claim reset is only the emergency path. [DocumentJobPublisher.publish] keeps the one retry that is
 *   safe there, the optimistic-lock reload.
 * - The broker ack is bounded by `invoice.publish.timeout-ms` on the returned future, uniformly
 *   for the fast path, the poller and the UI — a plain future timeout, not an interrupting
 *   `@Timeout` that could land in the middle of the JPA transaction. An ack that never arrives
 *   fails the future; the fast path reverts its claim, the poller's stale-job reset covers the
 *   rest. jnats de-duplicates a re-sent job by its message id, so a retry after a late ack
 *   never produces a second job on the stream.
 */
@ApplicationScoped
class DocumentJobDispatchGate {

    private val publishTransaction: DocumentJobPublishTransaction
    private val ackTimeoutMs: Long
    private val log = Logger.getLogger(DocumentJobDispatchGate::class.java)

    @Inject
    constructor(
        publishTransaction: DocumentJobPublishTransaction,
        @ConfigProperty(name = "invoice.publish.timeout-ms", defaultValue = "2000") ackTimeoutMs: Long,
    ) {
        this.publishTransaction = publishTransaction
        this.ackTimeoutMs = ackTimeoutMs
    }

    /** Plain-unit-test path ([DocumentJobPublisher]'s own non-CDI constructor) — no interceptors. */
    constructor(publishTransaction: DocumentJobPublishTransaction) : this(publishTransaction, DEFAULT_ACK_TIMEOUT_MS)

    @Bulkhead(20)
    fun dispatch(invoice: Invoice, job: GenerationJob, skipValidation: Boolean): DispatchedJob =
        publishWithAckTimeout(invoice, job, skipValidation)

    /**
     * Dispatch of a job a user waits for. Same work and ack handling as [dispatch], but guarded by
     * its own bulkhead (`invoice.generation.interactive-bulkhead.max-concurrent-calls`) so a burst
     * of bulk dispatches that fills the bulk bulkhead can never reject an interactive one. A
     * separate method rather than a parameter: the interceptor binds one bulkhead per method.
     */
    @Bulkhead(4)
    fun dispatchInteractive(invoice: Invoice, job: GenerationJob, skipValidation: Boolean): DispatchedJob =
        publishWithAckTimeout(invoice, job, skipValidation)

    private fun publishWithAckTimeout(invoice: Invoice, job: GenerationJob, skipValidation: Boolean): DispatchedJob {
        val ack = publishTransaction.publishTransactional(invoice, job, skipValidation)
            .orTimeout(ackTimeoutMs, TimeUnit.MILLISECONDS)
        ack.whenComplete { _, ex ->
            if (ex is TimeoutException) {
                log.warnf("No NATS ack for GenerationJob %d within %d ms", job.id ?: -1, ackTimeoutMs)
            }
        }
        return DispatchedJob(ack)
    }

    companion object {
        const val DEFAULT_ACK_TIMEOUT_MS = 2000L
    }
}
