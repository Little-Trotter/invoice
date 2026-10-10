package de.littletrotter.application.system

import de.littletrotter.application.invoice.FastPathExecutor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.event.TransactionPhase
import jakarta.inject.Inject
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger

/** Publishes a best-effort wake-up only after the maintenance queue transaction commits. */
@ApplicationScoped
class MaintenanceJobQueuedObserver @Inject constructor(
    private val wakeupPublisher: MaintenanceWakeupPublisher,
    @param:FastPathExecutor private val managedExecutor: ManagedExecutor,
) {
    private val log = Logger.getLogger(MaintenanceJobQueuedObserver::class.java)

    fun onMaintenanceJobQueued(@Observes(during = TransactionPhase.AFTER_SUCCESS) event: MaintenanceJobQueued) {
        maintenanceBestEffort({ ex -> log.warn("Could not schedule maintenance wake-up publication", ex) }) {
            managedExecutor.execute {
                maintenanceBestEffort({ ex -> log.warn("Maintenance wake-up publication failed", ex) }) {
                    wakeupPublisher.publish(event.requestId)
                }
            }
        }
    }
}
