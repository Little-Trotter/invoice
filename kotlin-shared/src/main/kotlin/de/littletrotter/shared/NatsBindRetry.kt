package de.littletrotter.shared

import io.nats.client.JetStreamApiException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps trying to bind a consumer to a stream that another component owns. That component is
 * deployed separately and may start long after this one, so a single attempt at boot would leave
 * the consumer unbound until the next restart. A missing stream or an unreachable broker is
 * therefore retried with a fixed delay until it works or [close] is called; any other JetStream
 * error is handed to the caller's give-up handler because waiting would not change it.
 *
 * The retry thread is only created once the first attempt has failed, so a healthy start carries
 * no extra thread.
 */
class NatsBindRetry(
    private val description: String,
    private val delay: Duration = DEFAULT_DELAY,
    private val schedulerFactory: () -> ScheduledExecutorService = ::daemonScheduler,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(NatsBindRetry::class.java)
    private var scheduler: ScheduledExecutorService? = null
    private var closed = false

    /**
     * Runs [attempt] now and, while it fails for a reason that time can cure, again after every
     * delay. The first failure is logged at WARN once; [attempt] logs its own success.
     * [onGiveUp] receives a failure that retrying cannot cure.
     */
    fun bind(onGiveUp: (Exception) -> Unit, attempt: () -> Unit) {
        run(onGiveUp, attempt, first = true)
    }

    private fun run(onGiveUp: (Exception) -> Unit, attempt: () -> Unit, first: Boolean) {
        try {
            attempt()
        } catch (e: IOException) {
            retryLater(e, onGiveUp, attempt, first)
        } catch (e: JetStreamApiException) {
            if (e.apiErrorCode == STREAM_NOT_FOUND) retryLater(e, onGiveUp, attempt, first) else onGiveUp(e)
        }
    }

    @Synchronized
    private fun retryLater(cause: Exception, onGiveUp: (Exception) -> Unit, attempt: () -> Unit, first: Boolean) {
        if (closed) {
            return
        }
        if (first) {
            log.warn("Could not bind {} yet ({}); retrying every {} s", description, cause.message, delay.seconds)
        }
        val executor = scheduler ?: schedulerFactory().also { scheduler = it }
        executor.schedule({ run(onGiveUp, attempt, first = false) }, delay.toMillis(), TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun close() {
        closed = true
        scheduler?.shutdownNow()
    }

    companion object {
        /** JetStream API error code for "stream not found". */
        const val STREAM_NOT_FOUND = 10059
        private val DEFAULT_DELAY: Duration = Duration.ofSeconds(30)

        private fun daemonScheduler(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "nats-bind-retry").also { it.isDaemon = true }
        }
    }
}
