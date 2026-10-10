package de.littletrotter.application.system

/** Post-commit hint that a durable maintenance event was queued. */
data class MaintenanceJobQueued(val requestId: Long) {
    init {
        require(requestId > 0) { "Maintenance requestId must be positive" }
    }
}
