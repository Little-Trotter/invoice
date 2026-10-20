package de.littletrotter.application.system

import de.littletrotter.application.system.port.inbound.QueueDatabaseMaintenanceUseCase
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [QueueDatabaseMaintenanceUseCase] on top of the maintenance queue of [DatabaseMaintenanceService]. */
@ApplicationScoped
class QueueDatabaseMaintenance @Inject constructor(
    private val maintenanceService: DatabaseMaintenanceService,
) : QueueDatabaseMaintenanceUseCase {
    override fun queueIssuedGenerationJobsSweep(details: String) {
        maintenanceService.queueIssuedGenerationJobsSweep(details)
    }

    override fun queueRecalculation(details: String) {
        maintenanceService.queueRecalculation(details)
    }
}
