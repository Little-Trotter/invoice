package de.littletrotter.application.system

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.shared.time.TimeSource
import java.time.Duration
import java.time.Instant

/**
 * "Worker verbunden" must not flicker to "kein Worker verbunden" merely because the
 * operator's refresh click happened to land between two JetStream pull requests. Remembers the
 * last instant [observe] was told the worker consumer looked active
 * (`numWaiting > 0 || numAckPending > 0`, computed by the caller) and reports it connected for
 * [WINDOW] after that instant, not only while the raw signal is currently true.
 *
 * Not a new poller (Green-IT rule): [observe] only runs when something already calls
 * [JobPipelineStatusService.currentStatus] — the panel's own initial render or refresh click.
 * `@ApplicationScoped` so the remembered instant survives between those calls; [time] is
 * constructor-injected so a unit test can advance time without waiting on it.
 */
@ApplicationScoped
class WorkerPresenceWindow @Inject constructor(private val time: TimeSource) {

    @Volatile
    private var lastSeenAt: Instant? = null

    /** [active] is the caller's own raw JetStream reading for this call. Returns whether the
     *  worker should be reported connected right now — either because [active] is `true`, or
     *  because it was `true` within the last [WINDOW]. */
    fun observe(active: Boolean): Boolean {
        val now = time.instant()
        if (active) {
            lastSeenAt = now
            return true
        }
        val seenAt = lastSeenAt ?: return false
        return Duration.between(seenAt, now) <= WINDOW
    }

    companion object {
        val WINDOW: Duration = Duration.ofSeconds(60)
    }
}
