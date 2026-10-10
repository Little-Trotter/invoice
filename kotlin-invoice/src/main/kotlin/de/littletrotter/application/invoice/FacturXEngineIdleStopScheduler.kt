package de.littletrotter.application.invoice

import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.shared.time.TimeSource
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * facturx-arc-improve.md §3.2 Stufe 2 / §7 — the "Green-IT-Feinschliff": releases the
 * Factur-X engine after [idleStopMinutes] without a FACTURX job, independent of which container
 * last called [EngineActivation.ensureRunning] (see [GenerationJobRepository
 * .existsRecentByEngine]'s own KDoc for why that check goes through the job table rather than an
 * in-process timestamp). The engine comes back on the next [DocumentJobPublisher.publish] call
 * for a FACTURX job — no separate wake-up path needed.
 *
 * No extra profile check here: `quarkus.scheduler.enabled` is off by default and only turned on
 * for the `worker` profile (application.properties) — the exact same reason
 * [GenerationJobProcessor]'s own `@Scheduled` methods need none either. Running this only where
 * the scheduler is enabled at all is also what keeps it from racing the switch-off path in
 * [de.littletrotter.ui.view.referencedata.ValidationSettingsTab] in the common single-tick sense —
 * both simply call the same idempotent [EngineActivation.releaseIdle].
 *
 * A switched-off capability already released the engine at the switch itself; this tick then has
 * nothing to do, since no [DocumentJobPublisher] call would have started the engine again while
 * [ValidationCapabilityId.FACTURX_ENGINE] stayed off.
 */
@ApplicationScoped
class FacturXEngineIdleStopScheduler @Inject constructor(
    private val validationSettingsGate: ValidationSettingsGate,
    private val engineActivation: EngineActivation,
    private val generationJobRepository: GenerationJobRepository,
    @param:ConfigProperty(name = "facturx.engine.idle-stop-minutes", defaultValue = "30")
    private val idleStopMinutes: Long,
    // Without the engine deployed there is nothing to idle-stop; skips the tick before
    // it can call EngineActivation at all.
    private val facturXDeployment: FacturXDeployment,
    private val time: TimeSource,
) {
    private val log = Logger.getLogger(javaClass)

    // Coarser than the idle window itself (30 min default) on purpose — this only has to catch
    // the threshold eventually, not to the minute; matching resetStaleDelegatedJobs' own 5m tick
    // in GenerationJobProcessor keeps the worker's set of periodic ticks easy to reason about.
    // Scheduler-tick process boundary, the sole catch-all of this flow: an
    // uncaught exception would kill this @Scheduled execution instead of just skipping the tick.
    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun releaseIfIdle() {
        if (!facturXDeployment.deployed) return
        if (!validationSettingsGate.isEnabled(ValidationCapabilityId.FACTURX_ENGINE)) return
        try {
            val since = time.nowUtc().minusMinutes(idleStopMinutes)
            if (!generationJobRepository.existsRecentByEngine(DocumentEngine.FACTURX, since)) {
                log.infof("No FACTURX job in the last %d minute(s) — releasing the idle Factur-X engine", idleStopMinutes)
                engineActivation.releaseIdle(DocumentEngine.FACTURX)
            }
        } catch (ex: Exception) {
            // AP-EXC-1: kept broad on purpose — body of a @Scheduled tick (every 5m); an uncaught
            // exception would kill that scheduled execution instead of just skipping this
            // evaluation. Already logged at warn.
            log.warn("Failed to evaluate Factur-X engine idle-stop", ex)
        }
    }
}
