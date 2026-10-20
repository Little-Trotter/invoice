package de.littletrotter.application.system.port.outbound

/** Best-effort post-commit hint; the durable maintenance event remains the source of truth. */
fun interface MaintenanceWakeupPublisher {
    fun publish(requestId: Long)
}
