package de.littletrotter.shared.quote

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The user asked for an invoice draft from an offer. Published on [QuoteSubjects.INVOICE_ORDER_REQUESTED].
 *
 * The order is an addressable command with its own [orderId], which doubles as the broker message id
 * and is echoed by both answers. The event carries only the offer reference and the service dates: an
 * offer is immutable from review on, so the invoicing side reads the content through the offer REST
 * API at consumption time and can never act on a stale copy. A delivery date and a service period
 * exclude each other; a period has both ends or none.
 */
data class QuoteInvoiceOrderRequested(
    val schemaVersion: Int = QuoteContract.SCHEMA_VERSION,
    val orderId: UUID,
    val quoteId: UUID,
    val quoteNumber: String,
    val acceptedOn: LocalDate,
    val customerOrderReference: String?,
    val deliveryDate: LocalDate?,
    val servicePeriodFrom: LocalDate?,
    val servicePeriodTo: LocalDate?,
    val actor: String,
    val occurredAt: Instant,
) {
    companion object {
        private const val WHAT = "QuoteInvoiceOrderRequested"
        private const val ORDER_REFERENCE = "customerOrderReference"
        private const val DELIVERY_DATE = "deliveryDate"
        private const val PERIOD_FROM = "servicePeriodFrom"
        private const val PERIOD_TO = "servicePeriodTo"

        /** Writes the wire shape field by field; an absent optional value is an explicit null. */
        fun encode(event: QuoteInvoiceOrderRequested): ByteArray {
            val root = QuoteWireJson.mapper.createObjectNode()
            root.put("schemaVersion", event.schemaVersion)
            root.put("orderId", event.orderId.toString())
            root.put("quoteId", event.quoteId.toString())
            root.put("quoteNumber", event.quoteNumber)
            root.put("acceptedOn", event.acceptedOn.toString())
            putOptional(root, ORDER_REFERENCE, event.customerOrderReference)
            putOptional(root, DELIVERY_DATE, event.deliveryDate?.toString())
            putOptional(root, PERIOD_FROM, event.servicePeriodFrom?.toString())
            putOptional(root, PERIOD_TO, event.servicePeriodTo?.toString())
            root.put("actor", event.actor)
            root.put("occurredAt", event.occurredAt.toString())
            return QuoteWireJson.mapper.writeValueAsBytes(root)
        }

        /** Reads the wire shape; the order reference and the service dates are optional, everything else is required. */
        fun decode(bytes: ByteArray?): QuoteInvoiceOrderRequested {
            val root = QuoteWireJson.readObject(bytes, WHAT)
            return QuoteInvoiceOrderRequested(
                schemaVersion = QuoteWireJson.requiredLong(root, "schemaVersion", WHAT).toInt(),
                orderId = QuoteWireJson.requiredUuid(root, "orderId", WHAT),
                quoteId = QuoteWireJson.requiredUuid(root, "quoteId", WHAT),
                quoteNumber = QuoteWireJson.requiredText(root, "quoteNumber", WHAT),
                acceptedOn = QuoteWireJson.requiredDate(root, "acceptedOn", WHAT),
                customerOrderReference = QuoteWireJson.optionalText(root, ORDER_REFERENCE, WHAT),
                deliveryDate = QuoteWireJson.optionalDate(root, DELIVERY_DATE, WHAT),
                servicePeriodFrom = QuoteWireJson.optionalDate(root, PERIOD_FROM, WHAT),
                servicePeriodTo = QuoteWireJson.optionalDate(root, PERIOD_TO, WHAT),
                actor = QuoteWireJson.requiredText(root, "actor", WHAT),
                occurredAt = QuoteWireJson.requiredInstant(root, "occurredAt", WHAT),
            )
        }

        private fun putOptional(root: ObjectNode, field: String, value: String?) {
            if (value == null) root.putNull(field) else root.put(field, value)
        }
    }
}
