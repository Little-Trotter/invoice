package de.littletrotter.ui.view.system

import de.littletrotter.application.system.port.inbound.QueueDatabaseMaintenanceUseCase
import de.littletrotter.application.system.port.inbound.MaintenanceEventQueryPort
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Dependencies of the System-tab maintenance buttons and status line. Required, not nullable or
 * defaulted: a missing service must never let the click handler silently no-op while still
 * showing a "queued" success notification, so every caller supplies real instances.
 */
@Dependent
class StatusPanelMaintenance @Inject constructor(
    val databaseMaintenanceService: QueueDatabaseMaintenanceUseCase,
    val maintenanceEventQueryPort: MaintenanceEventQueryPort,
    // Operator-only: the two maintenance triggers are hidden unless the instance enables them
    // (env LITTLETROTTER_FEATURES_MAINTENANCE_ACTIONS_ENABLED); the status line stays visible.
    @ConfigProperty(name = "littletrotter.features.maintenance-actions.enabled", defaultValue = "false")
    val maintenanceActionsEnabled: Boolean,
)
