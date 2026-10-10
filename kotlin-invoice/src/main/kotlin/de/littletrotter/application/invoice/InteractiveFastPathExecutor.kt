package de.littletrotter.application.invoice

import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small executor of its own for the fast-path publish of interactive jobs. The shared fast-path
 * executor can hold hundreds of queued bulk tasks (an import creates one job per invoice); a job
 * a user waits for must not sit behind them.
 *
 * Bounded on purpose: a full queue rejects the task, the caller logs it, and the poller, which
 * orders interactive jobs first, publishes the job on its next tick. Like the shared fast-path
 * executor it carries no transaction context, so the publish opens its own transaction.
 */
@ApplicationScoped
class InteractiveFastPathExecutor : Executor {
    private val delegate: Executor
    private val owned: ExecutorService?

    @Inject
    constructor(
        @ConfigProperty(name = "invoice.jobs.interactive-fast-path-threads", defaultValue = "2") threads: Int,
        @ConfigProperty(name = "invoice.jobs.interactive-fast-path-queue", defaultValue = "64") queueCapacity: Int,
    ) {
        val counter = AtomicInteger()
        val factory = ThreadFactory { task ->
            Thread(task, "interactive-fast-path-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        val pool = ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity), factory,
        )
        this.owned = pool
        this.delegate = pool
    }

    /** Plain-unit-test path: runs on the given executor and owns no threads. */
    internal constructor(delegate: Executor) {
        this.owned = null
        this.delegate = delegate
    }

    override fun execute(command: Runnable) = delegate.execute(command)

    @PreDestroy
    fun shutdown() {
        val pool = owned ?: return
        pool.shutdown()
        if (!pool.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val SHUTDOWN_WAIT_SECONDS = 5L
    }
}
