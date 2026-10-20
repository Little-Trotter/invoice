package de.littletrotter.shared.quote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class QuoteEventsTest {

    private val eventId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val quoteId = UUID.fromString("66666666-7777-8888-9999-000000000000")
    private val at = Instant.parse("2026-10-06T08:00:00Z")

    private val orderId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val order = QuoteInvoiceOrderRequested(
        orderId = orderId, quoteId = quoteId, quoteNumber = "A-1", acceptedOn = LocalDate.parse("2026-10-06"),
        customerOrderReference = "PO-7", deliveryDate = null, servicePeriodFrom = LocalDate.parse("2026-09-01"),
        servicePeriodTo = LocalDate.parse("2026-09-30"), actor = "kunde", occurredAt = at,
    )
    private val created = InvoiceCreatedFromQuoteEvent(
        eventId = eventId, quoteId = quoteId, quoteNumber = "A-1", orderId = orderId, invoiceId = 42, invoiceNumber = "R-1", occurredAt = at,
    )
    private val rejected = InvoiceCreationFromQuoteRejectedEvent(
        eventId = eventId, quoteId = quoteId, quoteNumber = "A-1", orderId = orderId, reason = "no customer", occurredAt = at,
    )

    @Test
    fun `order round trips with a period and with a delivery date`() {
        assertEquals(order, QuoteInvoiceOrderRequested.decode(QuoteInvoiceOrderRequested.encode(order)))
        val dated = order.copy(deliveryDate = LocalDate.parse("2026-09-15"), servicePeriodFrom = null, servicePeriodTo = null)
        assertEquals(dated, QuoteInvoiceOrderRequested.decode(QuoteInvoiceOrderRequested.encode(dated)))
    }

    @Test
    fun `order writes absent optionals as explicit null`() {
        val bare = order.copy(customerOrderReference = null, servicePeriodFrom = null, servicePeriodTo = null)
        val bytes = QuoteInvoiceOrderRequested.encode(bare)
        val json = String(bytes)
        listOf("customerOrderReference", "deliveryDate", "servicePeriodFrom", "servicePeriodTo").forEach {
            assertEquals(true, json.contains("\"$it\":null"), it)
        }
        val decoded = QuoteInvoiceOrderRequested.decode(bytes)
        assertNull(decoded.customerOrderReference)
        assertNull(decoded.deliveryDate)
        assertNull(decoded.servicePeriodTo)
    }

    @Test
    fun `order payload stays far below the broker limit`() {
        val big = order.copy(customerOrderReference = "x".repeat(1000), actor = "a".repeat(200))
        assertEquals(true, QuoteInvoiceOrderRequested.encode(big).size < 32 * 1024)
    }

    @Test
    fun `order requires its identity and its dates`() {
        val json = String(QuoteInvoiceOrderRequested.encode(order))
        listOf("schemaVersion", "orderId", "quoteId", "quoteNumber", "acceptedOn", "actor", "occurredAt").forEach { field ->
            val without = json.replace("\"$field\"", "\"x$field\"")
            assertThrows<QuoteContractViolationException>(field) { QuoteInvoiceOrderRequested.decode(without.toByteArray()) }
        }
        val badPeriod = json.replace("2026-09-01", "soon")
        assertThrows<QuoteContractViolationException> { QuoteInvoiceOrderRequested.decode(badPeriod.toByteArray()) }
    }

    @Test
    fun `replies require the order id`() {
        val createdJson = String(InvoiceCreatedFromQuoteEvent.encode(created)).replace("\"orderId\"", "\"xorderId\"")
        assertThrows<QuoteContractViolationException> { InvoiceCreatedFromQuoteEvent.decode(createdJson.toByteArray()) }
        val rejectedJson = String(InvoiceCreationFromQuoteRejectedEvent.encode(rejected)).replace("\"orderId\"", "\"xorderId\"")
        assertThrows<QuoteContractViolationException> { InvoiceCreationFromQuoteRejectedEvent.decode(rejectedJson.toByteArray()) }
    }

    @Test
    fun `created round trips`() {
        assertEquals(created, InvoiceCreatedFromQuoteEvent.decode(InvoiceCreatedFromQuoteEvent.encode(created)))
    }

    @Test
    fun `rejected round trips`() {
        assertEquals(rejected, InvoiceCreationFromQuoteRejectedEvent.decode(InvoiceCreationFromQuoteRejectedEvent.encode(rejected)))
    }

    @Test
    fun `unknown schema version and unknown fields are tolerated`() {
        val json = """{"schemaVersion":7,"extra":1,"eventId":"$eventId","quoteId":"$quoteId","quoteNumber":"A-1","orderId":"$orderId",
            "reason":"x","occurredAt":"$at"}"""
        val decoded = InvoiceCreationFromQuoteRejectedEvent.decode(json.toByteArray())
        assertEquals(7, decoded.schemaVersion)
    }

    @Test
    fun `missing required field is a violation`() {
        val json = """{"schemaVersion":1,"eventId":"$eventId","quoteNumber":"A-1","orderId":"$orderId","invoiceId":1,
            "invoiceNumber":"R","occurredAt":"$at"}"""
        assertThrows<QuoteContractViolationException> { InvoiceCreatedFromQuoteEvent.decode(json.toByteArray()) }
        assertThrows<QuoteContractViolationException> { QuoteInvoiceOrderRequested.decode("{}".toByteArray()) }
        assertThrows<QuoteContractViolationException> {
            InvoiceCreatedFromQuoteEvent.decode(json.replace("\"quoteNumber\"", "\"quoteId\":\"$quoteId\",\"quoteNumber\"").replace("\"invoiceId\":1", "\"invoiceId\":\"x\"").toByteArray())
        }
    }

    @Test
    fun `malformed payloads and values are violations`() {
        assertThrows<QuoteContractViolationException> { QuoteInvoiceOrderRequested.decode(null) }
        assertThrows<QuoteContractViolationException> { QuoteInvoiceOrderRequested.decode(ByteArray(0)) }
        assertThrows<QuoteContractViolationException> { QuoteInvoiceOrderRequested.decode("{nope".toByteArray()) }
        assertThrows<QuoteContractViolationException> { QuoteInvoiceOrderRequested.decode("[]".toByteArray()) }
        val badDate = String(QuoteInvoiceOrderRequested.encode(order)).replace("2026-10-06\"", "xx\"")
        assertThrows<QuoteContractViolationException> { QuoteInvoiceOrderRequested.decode(badDate.toByteArray()) }
        val badUuid = String(QuoteInvoiceOrderRequested.encode(order)).replace(orderId.toString(), "zzz")
        assertThrows<QuoteContractViolationException> { QuoteInvoiceOrderRequested.decode(badUuid.toByteArray()) }
        val blankRef = String(QuoteInvoiceOrderRequested.encode(order)).replace("PO-7", " ")
        assertThrows<QuoteContractViolationException> { QuoteInvoiceOrderRequested.decode(blankRef.toByteArray()) }
    }

    @Test
    fun `stream and subject names are stable`() {
        assertEquals(3, QuoteContract.SCHEMA_VERSION)
        assertEquals("LITTLE_TROTTER_QUOTE_INVOICE_ORDER", QuoteStreams.INVOICE_ORDER)
        assertEquals("LITTLE_TROTTER_QUOTE_INVOICE", QuoteStreams.INVOICE_REPLY)
        assertEquals("quote.invoice-order.requested", QuoteSubjects.INVOICE_ORDER_REQUESTED)
        assertEquals("invoice.created-from-quote", QuoteSubjects.INVOICE_CREATED)
        assertEquals("invoice.creation-from-quote.rejected", QuoteSubjects.INVOICE_REJECTED)
        assertEquals(listOf(QuoteSubjects.INVOICE_CREATED, QuoteSubjects.INVOICE_REJECTED), QuoteSubjects.INVOICE_REPLY)
    }
}
