package de.littletrotter.application.system

import de.littletrotter.application.system.port.inbound.MaintenanceEventQueryPort
import de.littletrotter.application.system.port.inbound.MaintenanceEventView
import de.littletrotter.domain.system.DatabaseMaintenanceType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService

/** Implements [MaintenanceEventQueryPort] on top of [DatabaseMaintenanceService]'s event lookup. */
@ApplicationScoped
class MaintenanceEventQueries @Inject constructor(
    private val maintenanceService: DatabaseMaintenanceService,
) : MaintenanceEventQueryPort {

    override fun latestEvent(type: DatabaseMaintenanceType): MaintenanceEventView? =
        maintenanceService.latestMaintenanceEvent(type)?.let { MaintenanceEventView(it.status, it.details) }
}
