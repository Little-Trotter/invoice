package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import de.littletrotter.shared.time.TimeSource
import java.time.Duration

/**
 * Daily retention trigger for MessageCenterService.cleanup. Runs via `@Scheduled`
 * with the same scheduler-enablement gate: `quarkus.scheduler.enabled=false` by default,
 * `%worker.quarkus.scheduler.enabled=true` — fires once a day in the worker profile only, never
 * in the UI-serving container (`application.properties`, "Scheduler configuration" section). The
 * Quarkus scheduler extension (`quarkus-scheduler`, `pom.xml`) is already on the classpath, so no
 * new dependency or alternative maintenance/startup hook was needed here.
 *
 * `@Scheduled(every = "24h")` alone has no memory of when this last actually ran: a
 * restart re-arms Quarkus's own in-JVM timer from zero, so without a persisted due check a restart
 * shortly after a real cleanup would re-run within minutes, and a long-hung process could just as
 * easily skip a day. Unlike [ChainVerificationScheduler] (which already had a maintenance-event
 * record to read), MessageCenterService.cleanup recorded nothing at all before this —
 * [DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION] and the [recordCleanup] call below are new,
 * reusing the SAME existing, already-indexed
 * [de.littletrotter.domain.system.DatabaseMaintenanceEvent] table rather than adding a dedicated
 * one purely for this due-check. `null` (no event ever recorded — a fresh deployment) counts as
 * due immediately, matching the existing "run once on first startup" expectation; an
 * elapsed-but-not-yet-24h event skips this tick entirely, deferring to whenever the next tick
 * finds it actually due.
 */
@ApplicationScoped
class SystemMessageRetentionScheduler
@Inject
constructor(
    private val messageCenterService: MessageCenterService,
    private val maintenanceService: DatabaseMaintenanceService,
    private val time: TimeSource,
) {

    @Scheduled(every = "24h")
    fun cleanup() {
        if (!isDue()) return
        val deleted = messageCenterService.cleanup()
        if (deleted > 0) {
            LOG.infof("Message Center retention cleanup deleted %d message(s)", deleted)
        }
        recordCleanup(deleted)
    }

    /** See this class's own KDoc. `internal` so its own unit test can assert the
     *  fresh-deployment/mid-interval/elapsed-interval cases directly against a fixed [time],
     *  without waiting a real 24 hours or re-deriving the outcome only through cleanup's side
     *  effects. */
    internal fun isDue(): Boolean {
        val last = maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION)
        return last == null || Duration.between(last.createdAt, time.nowUtc()) >= DUE_INTERVAL
    }

    /** The due-state record itself. A failed messageCenterService.cleanup call throws
     *  before reaching here, so no event is recorded for it and the next tick still sees the old
     *  (now overdue) last-run and retries — the same "no silent, permanently stale due-state on
     *  failure" behavior [ChainVerificationScheduler] gets for free from
     *  [de.littletrotter.application.compliance.ChainVerificationService] recording on every run,
     *  break or not. */
    private fun recordCleanup(deleted: Int) {
        maintenanceService.recordMaintenanceEvent(
            DatabaseMaintenanceEvent(
                createdAt = time.nowUtc(),
                type = DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION,
                status = DatabaseMaintenanceStatus.SUCCESS,
                details = "$deleted message(s) deleted",
            ),
        )
    }

    companion object {
        private val LOG = Logger.getLogger(SystemMessageRetentionScheduler::class.java)
        internal val DUE_INTERVAL: Duration = Duration.ofHours(24)
    }
}
