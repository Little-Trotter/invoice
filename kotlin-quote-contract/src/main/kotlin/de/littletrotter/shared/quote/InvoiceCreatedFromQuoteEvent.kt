package de.littletrotter.shared.quote

import java.time.Instant
import java.util.UUID

/** The invoicing side created an invoice draft for an accepted offer. Published on [QuoteSubjects.INVOICE_CREATED]. */
data class InvoiceCreatedFromQuoteEvent(
    val schemaVersion: Int = QuoteContract.SCHEMA_VERSION,
    val eventId: UUID,
    val quoteId: UUID,
    val quoteNumber: String,
    val orderId: UUID,
    val invoiceId: Long,
    val invoiceNumber: String,
    val occurredAt: Instant,
) {
    companion object {
        private const val WHAT = "InvoiceCreatedFromQuoteEvent"

        /** Writes the wire shape field by field. */
        fun encode(event: InvoiceCreatedFromQuoteEvent): ByteArray {
            val root = QuoteWireJson.mapper.createObjectNode()
            QuoteWireJson.putEventHead(root, QuoteEventHead(event.schemaVersion, event.eventId, event.quoteId, event.quoteNumber))
            root.put("orderId", event.orderId.toString())
            root.put("invoiceId", event.invoiceId)
            root.put("invoiceNumber", event.invoiceNumber)
            root.put("occurredAt", event.occurredAt.toString())
            return QuoteWireJson.mapper.writeValueAsBytes(root)
        }

        /** Reads the wire shape; every field is required. */
        fun decode(bytes: ByteArray?): InvoiceCreatedFromQuoteEvent {
            val root = QuoteWireJson.readObject(bytes, WHAT)
            val head = QuoteWireJson.readEventHead(root, WHAT)
            return InvoiceCreatedFromQuoteEvent(
                schemaVersion = head.schemaVersion,
                eventId = head.eventId,
                quoteId = head.quoteId,
                quoteNumber = head.quoteNumber,
                orderId = QuoteWireJson.requiredUuid(root, "orderId", WHAT),
                invoiceId = QuoteWireJson.requiredLong(root, "invoiceId", WHAT),
                invoiceNumber = QuoteWireJson.requiredText(root, "invoiceNumber", WHAT),
                occurredAt = QuoteWireJson.requiredInstant(root, "occurredAt", WHAT),
            )
        }
    }
}
