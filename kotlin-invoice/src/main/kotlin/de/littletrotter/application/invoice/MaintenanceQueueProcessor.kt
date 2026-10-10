package de.littletrotter.application.invoice

import de.littletrotter.application.system.MaintenanceDrainLease
import de.littletrotter.application.system.MaintenanceExecutionFence
import de.littletrotter.application.system.MaintenanceExecutionFencedException
import de.littletrotter.application.system.MaintenanceOperationOutcome
import de.littletrotter.application.system.MaintenanceOperations
import de.littletrotter.application.system.MaintenanceQueuePort
import io.quarkus.arc.Arc
import io.quarkus.scheduler.Scheduled
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Drains durable maintenance requests in bounded, cross-process serialized batches. */
@ApplicationScoped
class MaintenanceQueueProcessor @Inject constructor(
    private val queue: MaintenanceQueuePort,
    private val operations: MaintenanceOperations,
    @param:FastPathExecutor private val managedExecutor: ManagedExecutor,
    @param:ConfigProperty(name = "invoice.maintenance.drain-batch-size", defaultValue = "10")
    private val batchSize: Int,
    @param:ConfigProperty(name = "quarkus.scheduler.enabled", defaultValue = "false")
    private val schedulerEnabled: Boolean,
) {
    private val log = Logger.getLogger(MaintenanceQueueProcessor::class.java)
    private val drainScheduled = AtomicBoolean(false)
    private val drainRequested = AtomicBoolean(false)

    init {
        require(batchSize > 0) { "invoice.maintenance.drain-batch-size must be greater than zero" }
    }

    internal constructor(
        queue: MaintenanceQueuePort,
        operations: MaintenanceOperations,
        executor: Executor,
        batchSize: Int = 10,
        schedulerEnabled: Boolean = true,
    ) : this(queue, operations, DelegatingManagedExecutor(executor), batchSize, schedulerEnabled)

    /** Test seam and explicit lifecycle for executor tasks that did not originate in a request. */
    internal var requestContextFactory: () -> AutoCloseable = {
        val context = Arc.container().requestContext()
        context.activate()
        AutoCloseable { context.terminate() }
    }

    /**
     * Coalesces wake-up hints into a bounded asynchronous drain and returns promptly. Durable
     * PostgreSQL state remains authoritative when executor scheduling fails.
     */
    fun requestDrain() {
        // App and worker share the same image. Message observers can exist in either process, so
        // notification handling uses the same runtime scheduler gate as periodic recovery.
        if (!schedulerEnabled) return
        drainRequested.set(true)
        scheduleDrainIfNeeded()
    }

    @Scheduled(
        every = "{invoice.maintenance.recovery-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun pollPendingMaintenanceJobs() {
        requestDrain()
    }

    /** Starts durable recovery immediately in worker processes instead of waiting for one tick. */
    @Startup
    fun onStart() {
        requestDrain()
    }

    private fun scheduleDrainIfNeeded() {
        if (!drainScheduled.compareAndSet(false, true)) return
        try {
            managedExecutor.execute(::runScheduledDrain)
        } catch (ex: java.util.concurrent.RejectedExecutionException) {
            drainScheduled.set(false)
            log.error("Could not schedule maintenance drain; durable PENDING rows remain for recovery", ex)
        }
    }

    // Process boundary — sole catch-all of this flow: the entry point of the async executor task
    // itself, nothing awaits its result, so this is the only place a failure anywhere inside the
    // drain (arbitrary maintenance operations, DB access) becomes visible at all. Logged with
    // cause; durable queue state stays recoverable on the next tick.
    private fun runScheduledDrain() {
        var reachedBatchLimit = false
        try {
            requestContextFactory().use {
                drainRequested.set(false)
                reachedBatchLimit = drainOneBatch()
            }
        } catch (ex: Exception) {
            log.error("Maintenance drain failed; durable queue state remains recoverable", ex)
        } finally {
            drainScheduled.set(false)
            // A request can arrive while this drain owns the in-process guard. Re-check it after
            // releasing the guard to close that lost-wakeup window. An empty follow-up is harmless
            // when the last item happened to land exactly on the batch boundary.
            if (drainRequested.get() || reachedBatchLimit) scheduleDrainIfNeeded()
        }
    }

    /** Returns true only when another bounded pass should be scheduled immediately. */
    private fun drainOneBatch(): Boolean {
        val attempt = queue.withExclusiveDrainLease {
            logRecoveryTerminalizations(recoverInterruptedClaims())
            runClaimLoop()
        }
        return when (attempt) {
            MaintenanceQueuePort.LeaseAttempt.Busy -> false
            is MaintenanceQueuePort.LeaseAttempt.Acquired -> attempt.result
        }
    }

    private fun logRecoveryTerminalizations(recovery: MaintenanceDrainLease.RecoveryResult) {
        if (recovery.terminalizedVersionedClaims > 0) {
            log.errorf(
                "Terminalized %d interrupted versioned maintenance claim(s) with unknown partial effects",
                recovery.terminalizedVersionedClaims,
            )
        }
        if (recovery.terminalizedHistoricalClaims > 0) {
            log.errorf(
                "Terminalized %d historical RUNNING maintenance request(s) with unknown effects; they were not replayed",
                recovery.terminalizedHistoricalClaims,
            )
        }
    }

    /** Claims and executes up to [batchSize] jobs; true when the batch limit was reached (more
     *  work likely remains), false when the queue emptied, a claim was blocked, or a job failed —
     *  extracted out of [drainOneBatch]'s lease lambda to keep that method's own cognitive
     *  complexity down. */
    private fun MaintenanceDrainLease.runClaimLoop(): Boolean {
        var processed = 0
        var stopped = false
        while (!stopped && processed < batchSize) {
            verifyOwnership()
            when (val claimed = claimNext()) {
                MaintenanceDrainLease.ClaimResult.Empty -> stopped = true
                is MaintenanceDrainLease.ClaimResult.Blocked -> {
                    log.error(claimed.details)
                    stopped = true
                }
                is MaintenanceDrainLease.ClaimResult.Claimed -> {
                    processed++
                    if (!runClaimedJob(claimed)) stopped = true
                }
            }
        }
        return !stopped
    }

    /** Executes one claimed job, logs its outcome, and reports whether draining should continue
     *  (false only on [MaintenanceOperationOutcome.Failed]). */
    private fun MaintenanceDrainLease.runClaimedJob(claimed: MaintenanceDrainLease.ClaimResult.Claimed): Boolean {
        val outcome = execute(claimed.job, this)
        verifyOwnership()
        complete(claimed.job, outcome)
        return when (outcome) {
            is MaintenanceOperationOutcome.Succeeded -> {
                log.infof("Maintenance job %d (%s) finished: %s", claimed.job.eventId, claimed.job.type, outcome.summary)
                true
            }
            is MaintenanceOperationOutcome.Failed -> {
                log.errorf("Maintenance job %d (%s) failed: %s", claimed.job.eventId, claimed.job.type, outcome.summary)
                false
            }
        }
    }

    // Process boundary — sole catch-all of this flow: operations.execute dispatches to an arbitrary
    // MaintenanceOperations handler with no shared checked-exception contract; every failure not
    // re-thrown above (the fencing signal) is translated into a typed MaintenanceOperationOutcome
    // instead of ever propagating a bare exception out of a claimed job.
    private fun execute(
        job: MaintenanceDrainLease.ClaimedMaintenanceJob,
        fence: MaintenanceExecutionFence,
    ): MaintenanceOperationOutcome =
        try {
            operations.execute(job.type, fence)
        } catch (ex: MaintenanceExecutionFencedException) {
            throw ex
        } catch (ex: Exception) {
            MaintenanceOperationOutcome.Failed(
                "Maintenance handler crashed: ${ex.message ?: ex.javaClass.simpleName}",
            )
        }
}
