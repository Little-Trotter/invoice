package de.littletrotter.shared

/** Versioned, instance-scoped CORE NATS hint for durable database maintenance work. */
data class MaintenanceWakeup(val version: Int, val requestId: Long) {
    init {
        require(version == CURRENT_VERSION) { "Unsupported maintenance wake-up version: $version" }
        require(requestId > 0) { "Maintenance wake-up requestId must be positive" }
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val SUBJECT_PREFIX = "maintenance.v1"

        fun subject(instance: String): String {
            require(instance.matches(Regex("[A-Za-z0-9_-]+"))) {
                "Maintenance wake-up instance must match [A-Za-z0-9_-]+"
            }
            return "$SUBJECT_PREFIX.$instance.requested"
        }
    }
}
