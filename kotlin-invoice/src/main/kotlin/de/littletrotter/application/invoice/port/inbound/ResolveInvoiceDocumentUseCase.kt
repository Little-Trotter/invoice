package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/**
 * Input port used on every access to an invoice's document of record (viewer, download). Checks
 * the stored original, restores a byte-identical copy when one exists, otherwise starts a
 * regeneration — automatically, without a confirmation step.
 */
interface ResolveInvoiceDocumentUseCase : CommandEntryPoint {
    fun resolveForInvoice(invoiceId: Long): DocumentResolution
}
