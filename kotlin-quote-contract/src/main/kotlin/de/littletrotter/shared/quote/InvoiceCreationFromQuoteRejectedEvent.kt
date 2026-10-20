package de.littletrotter.shared.quote

import java.time.Instant
import java.util.UUID

/** The invoicing side could not create an invoice draft. Published on [QuoteSubjects.INVOICE_REJECTED]. */
data class InvoiceCreationFromQuoteRejectedEvent(
    val schemaVersion: Int = QuoteContract.SCHEMA_VERSION,
    val eventId: UUID,
    val quoteId: UUID,
    val quoteNumber: String,
    val orderId: UUID,
    val reason: String,
    val occurredAt: Instant,
) {
    companion object {
        private const val WHAT = "InvoiceCreationFromQuoteRejectedEvent"

        /** Writes the wire shape field by field. */
        fun encode(event: InvoiceCreationFromQuoteRejectedEvent): ByteArray {
            val root = QuoteWireJson.mapper.createObjectNode()
            QuoteWireJson.putEventHead(root, QuoteEventHead(event.schemaVersion, event.eventId, event.quoteId, event.quoteNumber))
            root.put("orderId", event.orderId.toString())
            root.put("reason", event.reason)
            root.put("occurredAt", event.occurredAt.toString())
            return QuoteWireJson.mapper.writeValueAsBytes(root)
        }

        /** Reads the wire shape; every field is required. */
        fun decode(bytes: ByteArray?): InvoiceCreationFromQuoteRejectedEvent {
            val root = QuoteWireJson.readObject(bytes, WHAT)
            val head = QuoteWireJson.readEventHead(root, WHAT)
            return InvoiceCreationFromQuoteRejectedEvent(
                schemaVersion = head.schemaVersion,
                eventId = head.eventId,
                quoteId = head.quoteId,
                quoteNumber = head.quoteNumber,
                orderId = QuoteWireJson.requiredUuid(root, "orderId", WHAT),
                reason = QuoteWireJson.requiredText(root, "reason", WHAT),
                occurredAt = QuoteWireJson.requiredInstant(root, "occurredAt", WHAT),
            )
        }
    }
}
