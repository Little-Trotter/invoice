package de.littletrotter.application.invoice

import de.littletrotter.application.system.maintenanceBestEffort
import io.quarkus.arc.Arc
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger

/**
 * Adds one asynchronous pending-generation recovery pass when scheduling is enabled.
 *
 * App and worker use the same image, so enablement must be checked at runtime, not through a
 * build-profile condition. The supported UI profile disables scheduling; the worker enables it.
 * The scheduler supplies a request context for periodic scans. Startup instead dispatches through
 * the transaction-cleared executor and opens its own context. Both paths reuse the processor's
 * in-flight guard, atomic claim and attempt-budget rules if they overlap.
 */
@Startup
@ApplicationScoped
class GenerationJobRecoveryStartup @Inject constructor(
    private val generationJobProcessor: GenerationJobProcessor,
    @param:FastPathExecutor private val managedExecutor: ManagedExecutor,
    @param:ConfigProperty(name = "quarkus.scheduler.enabled", defaultValue = "false")
    private val schedulerEnabled: Boolean,
) {

    private val log = Logger.getLogger(GenerationJobRecoveryStartup::class.java)

    /** Test seam and explicit per-scan request-context lifecycle. */
    internal var requestContextFactory: () -> AutoCloseable = {
        val context = Arc.container().requestContext()
        context.activate()
        AutoCloseable { context.terminate() }
    }

    /**
     * A committed PENDING job must be recoverable after a worker restart without waiting for the
     * first periodic tick. The executor is deliberately the same transaction-cleared
     * [FastPathExecutor] used by the post-commit fast path; the scan itself then enters a fresh
     * request context before touching JPA.
     */
    @Startup
    fun onStart() {
        if (!schedulerEnabled) {
            return
        }
        scheduleScan("startup")
    }

    private fun scheduleScan(reason: String) {
        maintenanceBestEffort({ ex ->
            // A shutting-down/saturated executor must not prevent application startup or stop
            // the scheduler. Durable PENDING state remains available to the next tick.
            log.errorf(ex, "Could not schedule pending generation recovery %s scan", reason)
        }) {
            managedExecutor.execute {
                maintenanceBestEffort({ ex -> log.errorf(ex, "Pending generation recovery %s scan failed", reason) }) {
                    requestContextFactory().use {
                        generationJobProcessor.pollPendingJobs()
                    }
                }
            }
        }
    }
}
