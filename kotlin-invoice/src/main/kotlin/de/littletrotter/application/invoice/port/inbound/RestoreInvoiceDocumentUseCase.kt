package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/**
 * Input port for the explicit "restore document" action. Same resolution as the access path, and
 * additionally clears the reproduction tag when a byte-identical original is found again later.
 */
interface RestoreInvoiceDocumentUseCase : CommandEntryPoint {
    fun restore(invoiceId: Long): DocumentResolution
}
