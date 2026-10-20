package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Input port for deleting a pool receipt; refused while any invoice references it. */
interface DeleteReceiptUseCase : CommandEntryPoint {
    fun checkDeleteEligibility(receiptId: Long): ReceiptDeleteCheck

    fun delete(receiptId: Long)
}
