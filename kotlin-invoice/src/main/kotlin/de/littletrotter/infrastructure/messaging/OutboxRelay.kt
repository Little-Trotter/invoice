package de.littletrotter.infrastructure.messaging

import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.api.PublishAck
import io.nats.client.PublishOptions
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.event.TransactionPhase
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Publishes committed [OutboxRepository] rows to JetStream.
 *
 * Guarantees: at-least-once — a row is only marked PUBLISHED after the stream acknowledged it, so
 * a crash in between publishes it again; idempotent — the row id is the JetStream message id, so
 * the stream drops that repeat inside its de-duplication window. A failing row never blocks the
 * rows behind it and is retried on the next run until `outbox.max-attempts`, then parked as FAILED.
 *
 * A run starts after a producer's transaction committed ([onAppended]), once at startup and
 * whenever a lost broker connection is back, which picks up whatever a crash or an outage left
 * behind. A run that leaves rows pending (a failed publish or a failed run) schedules another one
 * with a growing pause (1 s up to 30 s): the broker reports a reconnect before its persistent
 * streams are loaded again, so the run triggered by the reconnect alone can fail, and without a
 * retry the rows would wait for the next unrelated write. A row parked as FAILED is no longer
 * pending and causes no retry. While the broker connection is down a run touches no row and
 * schedules no retry, so an outage does not use up the attempts of the rows; the reconnect starts
 * the next run. While nothing fails there is no timer. Runs are serialised inside
 * the process and happen on a worker thread, so a producer never waits for NATS.
 */
@Startup
@ApplicationScoped
class OutboxRelay @Inject constructor(
    private val repository: OutboxRepository,
    private val connection: Connection,
    private val jetStream: JetStream,
    private val reconnects: NatsReconnectListener,
    @param:ConfigProperty(name = "outbox.batch-size", defaultValue = "100")
    private val batchSize: Int,
    @param:ConfigProperty(name = "outbox.max-attempts", defaultValue = "10")
    private val maxAttempts: Int,
) {

    private val log = Logger.getLogger(OutboxRelay::class.java)
    private val runLock = ReentrantLock()

    /** Set by a run that left rows pending; read by the worker right after the run. */
    @Volatile
    private var leftPending = false

    private val retryScheduled = AtomicBoolean(false)

    @Volatile
    private var retryDelayMillis = FIRST_RETRY_MILLIS

    /** Test seam: the worker that [trigger] hands a run to. */
    internal var executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "outbox-relay").apply { isDaemon = true }
    }

    /** Test seam: runs a retry after the given pause in milliseconds. */
    internal var retryScheduler: (Long, Runnable) -> Unit = { delayMillis, task ->
        retryTimer.value.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
    }

    private val retryTimer: Lazy<ScheduledExecutorService> = lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "outbox-retry").apply { isDaemon = true }
        }
    }

    @PostConstruct
    fun recoverOnStartup() {
        reconnects.onReconnected = ::trigger
        trigger()
    }

    @PreDestroy
    fun shutdown() {
        (executor as? ExecutorService)?.shutdown()
        if (retryTimer.isInitialized()) retryTimer.value.shutdownNow()
    }

    fun onAppended(@Observes(during = TransactionPhase.AFTER_SUCCESS) event: OutboxAppended) {
        log.debugf("Outbox row for %s committed", event.subject)
        trigger()
    }

    /** Schedules a run; returns immediately. */
    fun trigger() {
        executor.execute {
            if (runSafely()) retryDelayMillis = FIRST_RETRY_MILLIS else scheduleRetry()
        }
    }

    /** One run; false when it failed or left rows pending. */
    private fun runSafely(): Boolean =
        try {
            relayPending()
            !leftPending
        } catch (e: java.sql.SQLException) {
            log.error("Outbox relay run failed; rows stay pending for the next run", e)
            false
        }

    /** At most one retry waits at a time; each further one doubles the pause up to a ceiling. */
    private fun scheduleRetry() {
        if (!retryScheduled.compareAndSet(false, true)) return
        val delay = retryDelayMillis
        retryDelayMillis = (delay * 2).coerceAtMost(MAX_RETRY_MILLIS)
        log.debugf("Outbox rows left pending, next run in %d ms", delay)
        retryScheduler(delay) {
            retryScheduled.set(false)
            trigger()
        }
    }

    /** Publishes every pending row, batch by batch; returns the number published. */
    fun relayPending(): Int = runLock.withLock {
        leftPending = false
        if (connection.status != Connection.Status.CONNECTED) {
            log.debug("Broker connection is down - outbox rows stay pending until it is back")
            return@withLock 0
        }
        var published = 0
        do {
            val batch = repository.findPending(batchSize)
            val done = batch.count { publish(it) }
            published += done
            // A batch with failures would be read again at once; stop and leave it to the next run.
        } while (batch.size == batchSize && done == batchSize)
        published
    }

    private fun publish(entry: OutboxEntry): Boolean =
        try {
            val options = PublishOptions.builder().messageId(entry.id.toString()).build()
            val ack: PublishAck = jetStream.publish(entry.subject, entry.payload.toByteArray(Charsets.UTF_8), options)
            if (ack.isDuplicate) log.debugf("Outbox row %d was already in the stream", entry.id)
            repository.markPublished(entry.id)
            true
        } catch (e: IOException) {
            fail(entry, e)
        } catch (e: JetStreamApiException) {
            fail(entry, e)
        }

    private fun fail(entry: OutboxEntry, e: Exception): Boolean {
        log.warnf(e, "Outbox row %d (%s) was not published", entry.id, entry.subject)
        repository.markFailed(entry.id, e.message ?: e.javaClass.simpleName, maxAttempts)
        // The attempt just recorded parks the row when it reaches the limit; a parked row is not retried.
        if (entry.attempts + 1 < maxAttempts) leftPending = true
        return false
    }

    private companion object {
        const val FIRST_RETRY_MILLIS = 1_000L
        const val MAX_RETRY_MILLIS = 30_000L
    }
}
