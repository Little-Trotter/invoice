package de.littletrotter.application.system.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/**
 * Input port for the System screen's two maintenance buttons. Both only queue a request; the
 * single sequential maintenance worker picks it up on its next poll, so the click never waits for
 * the sweep itself.
 */
interface QueueDatabaseMaintenanceUseCase : CommandEntryPoint {
    /** Queues a sweep that creates generation jobs for issued invoices that have none. */
    fun queueIssuedGenerationJobsSweep(details: String)

    /** Queues a recalculation of the totals of every invoice flagged as needing it. */
    fun queueRecalculation(details: String)
}
