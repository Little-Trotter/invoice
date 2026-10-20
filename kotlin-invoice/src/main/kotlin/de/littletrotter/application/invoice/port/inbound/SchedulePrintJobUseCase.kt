package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Creates the interactive print job of an invoice and hands it to the document pipeline. */
interface SchedulePrintJobUseCase : CommandEntryPoint {

    /**
     * Creates a print job for [invoiceId] and publishes it. [llmOptimize] is stored on the job so every
     * publisher sends the same payload. Returns the id of the created job, `null` when the job carries none.
     */
    fun schedulePrintJob(invoiceId: Long, llmOptimize: Boolean): Long?
}
