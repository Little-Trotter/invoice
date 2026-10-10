package de.littletrotter.application.system

/** Best-effort post-commit hint; the durable maintenance event remains the source of truth. */
fun interface MaintenanceWakeupPublisher {
    fun publish(requestId: Long)
}
