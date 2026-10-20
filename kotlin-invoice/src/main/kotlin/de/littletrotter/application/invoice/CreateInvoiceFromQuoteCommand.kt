package de.littletrotter.application.invoice

import de.littletrotter.application.command.Command
import java.time.LocalDate
import java.util.UUID

/**
 * [orderId] is the invoice order the offer service placed; it is carried to the reply unchanged.
 * The delivery date and the service period are optional and end up on the invoice header as given.
 */
data class CreateInvoiceFromQuoteCommand(
    val quoteId: UUID,
    val quoteNumber: String,
    val acceptedOn: LocalDate,
    val customerOrderReference: String?,
    val actor: String,
    val orderId: UUID,
    val deliveryDate: LocalDate? = null,
    val servicePeriodFrom: LocalDate? = null,
    val servicePeriodTo: LocalDate? = null,
) : Command
