package de.littletrotter.application.invoice

/**
 * Lightweight event publisher abstraction for application->UI notifications.
 * Keeps application layer free of UI types for architecture tests.
 */
fun interface EventPublisher {
    fun publishJobFinished(jobId: Long, invoiceId: Long?, success: Boolean)
}
