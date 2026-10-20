package de.littletrotter.application.system.port.inbound

import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType

/** Input port for the System screen's maintenance status lines. */
fun interface MaintenanceEventQueryPort {

    /** The most recent event of [type], `null` when none was recorded yet. */
    fun latestEvent(type: DatabaseMaintenanceType): MaintenanceEventView?
}

/** What the System screen shows of a maintenance event: its status and the free-text details. */
data class MaintenanceEventView(
    val status: DatabaseMaintenanceStatus,
    val details: String?,
)
