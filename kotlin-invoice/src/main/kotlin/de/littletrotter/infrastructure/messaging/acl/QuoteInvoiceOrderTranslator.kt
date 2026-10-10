package de.littletrotter.infrastructure.messaging.acl

import de.littletrotter.application.invoice.CreateInvoiceFromQuoteCommand
import de.littletrotter.shared.quote.QuoteInvoiceOrderRequested

/**
 * The anti-corruption layer between the published offer language (`de.littletrotter.shared.quote`)
 * and the invoicing application, on the inbound (invoice order) side: the only place that turns a
 * [QuoteInvoiceOrderRequested] into the application's own command.
 */
object QuoteInvoiceOrderTranslator {

    fun toCommand(event: QuoteInvoiceOrderRequested): CreateInvoiceFromQuoteCommand =
        CreateInvoiceFromQuoteCommand(
            quoteId = event.quoteId,
            quoteNumber = event.quoteNumber,
            acceptedOn = event.acceptedOn,
            customerOrderReference = event.customerOrderReference,
            actor = event.actor,
            orderId = event.orderId,
            deliveryDate = event.deliveryDate,
            servicePeriodFrom = event.servicePeriodFrom,
            servicePeriodTo = event.servicePeriodTo,
        )
}
