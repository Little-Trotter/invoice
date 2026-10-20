package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import java.time.LocalDate

/** Input port for changing a pool receipt's description and document date (both required). */
interface EditReceiptDetailsUseCase : CommandEntryPoint {
    fun updateDetails(receiptId: Long, description: String?, documentDate: LocalDate?)
}
