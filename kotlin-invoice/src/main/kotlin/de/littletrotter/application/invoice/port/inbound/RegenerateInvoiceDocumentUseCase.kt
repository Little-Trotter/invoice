package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Starts the document generation again for an issued invoice whose last job ended FAILED. */
interface RegenerateInvoiceDocumentUseCase : CommandEntryPoint {

    /**
     * Creates a new generation job for [invoiceId] and returns its id. Refused when the invoice is
     * not issued or its latest print job is not FAILED; while a job is still pending or running
     * the request is refused as well, so a double click never queues a second job.
     */
    fun regenerate(invoiceId: Long): Long
}
