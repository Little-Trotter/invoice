package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.port.outbound.RequestQuoteInvoiceRemote
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteInvoiceOrderAnswer
import de.littletrotter.shared.quote.QuoteWireCodec
import de.littletrotter.shared.quote.RequestQuoteInvoiceRequest
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes/{id}/invoice-order`. No retry: a repeated call after a lost answer would be refused as a second open order. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpRequestQuoteInvoiceAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : RequestQuoteInvoiceRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun request(quoteId: String, order: QuoteInvoiceOrderRequest): String =
        apiClient.post(
            QuoteRoutes.of(QuoteApiPaths.REQUEST_INVOICE, quoteId),
            QuoteWireCodec.encode(
                RequestQuoteInvoiceRequest(
                    acceptedOn = order.acceptedOn,
                    customerOrderReference = order.customerOrderReference,
                    deliveryDate = order.deliveryDate,
                    servicePeriodFrom = order.servicePeriodFrom,
                    servicePeriodTo = order.servicePeriodTo,
                ),
            ),
            "invoice order",
            object : TypeReference<QuoteInvoiceOrderAnswer>() {},
        ).orderId.toString()
}
