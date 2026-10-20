package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.compliance.ChainVerificationService
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService
import de.littletrotter.domain.system.DatabaseMaintenanceType
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import de.littletrotter.shared.time.TimeSource
import java.time.Duration

/**
 * Nightly trigger for [ChainVerificationService.verifyAllChains]. Runs via
 * `@Scheduled` with the same scheduler-enablement gate: `quarkus.scheduler.enabled=false` by
 * default, `%worker.quarkus.scheduler.enabled=true` (`application.properties`, "Scheduler
 * configuration" section) — fires once a day in the worker profile only, never in the
 * UI-serving container.
 *
 * `@Scheduled(every = "24h")` alone has no memory of when this last actually ran: a
 * process restart re-arms Quarkus's own in-JVM timer from zero, so without a persisted due check a
 * restart shortly after a real run would re-verify within minutes, and a long-hung process could
 * just as easily skip a day. [isDue] closes that gap using the SAME
 * [de.littletrotter.domain.system.DatabaseMaintenanceEvent] row [ChainVerificationService
 * .verifyAllChains] already records on every run — no new table needed, since
 * [DatabaseMaintenanceService.latestMaintenanceEvent] already indexes "most recent event of this
 * type". `null` (no event ever recorded — a fresh deployment) counts as due immediately, matching
 * the existing "run once on first startup" expectation; an elapsed-but-not-yet-24h event skips
 * this tick entirely, deferring to whenever the next tick finds it actually due.
 */
@ApplicationScoped
class ChainVerificationScheduler
@Inject
constructor(
    private val chainVerificationService: ChainVerificationService,
    private val maintenanceService: DatabaseMaintenanceService,
    private val time: TimeSource,
) {

    /** `SKIP` — a sweep still running over the unbounded (J3) journal when the next tick fires is
     *  never joined by a second, overlapping sweep. */
    @Scheduled(every = "24h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun verifyChains() {
        if (!isDue()) return
        val outcome = chainVerificationService.verifyAllChains()
        if (outcome.hasBreak) {
            LOG.errorf(
                "RS-12 nightly chain verification found a break — number_journal breaks: %d, invoice_audit_journal: %s",
                outcome.numberJournal.breaks.size,
                outcome.invoiceAudit.verification,
            )
        } else {
            LOG.infof(
                "RS-12 nightly chain verification passed — number_journal: %d rows, invoice_audit_journal: %d rows",
                outcome.numberJournal.checked,
                outcome.invoiceAudit.checked,
            )
        }
    }

    /** See this class's own KDoc. `internal` so its own unit test can assert the
     *  fresh-deployment/mid-interval/elapsed-interval cases directly against a fixed [time],
     *  without waiting a real 24 hours or re-deriving the outcome only through [verifyChains]'s
     *  side effects. */
    internal fun isDue(): Boolean {
        val last = maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION)
        return last == null || Duration.between(last.createdAt, time.nowUtc()) >= DUE_INTERVAL
    }

    companion object {
        private val LOG = Logger.getLogger(ChainVerificationScheduler::class.java)
        internal val DUE_INTERVAL: Duration = Duration.ofHours(24)
    }
}
