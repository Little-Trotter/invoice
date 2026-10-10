package de.littletrotter.infrastructure.quote

import de.littletrotter.shared.quote.RequestQuoteInvoiceRequest
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentCommand
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.quote.AcceptQuoteRequest
import de.littletrotter.shared.quote.AddQuoteNoteRequest
import de.littletrotter.shared.quote.AssignQuoteAttachmentRequest
import de.littletrotter.shared.quote.AttachQuoteFileRequest
import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover
import de.littletrotter.shared.quote.CancelQuoteRequest
import de.littletrotter.shared.quote.CreateQuoteRequest
import de.littletrotter.shared.quote.EditQuoteHeaderRequest
import de.littletrotter.shared.quote.EditQuoteItemsRequest
import de.littletrotter.shared.quote.RejectQuoteRequest
import de.littletrotter.shared.quote.ReturnQuoteToDraftRequest
import de.littletrotter.shared.quote.UpdateQuoteSettingsRequest
import de.littletrotter.shared.tax.VatCategory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.shared.quote.QuoteWireCodec as Codec

@DisplayName("Offer command adapters (POST/PUT/DELETE /quote/api/…)")
class HttpQuoteCommandAdaptersTest {

    private val stub = QuoteStubServer()
    private val api = QuoteStubServer.client(stub.baseUrl)
    private val day = LocalDate.of(2026, 11, 1)

    @AfterEach
    fun tearDown() = stub.close()

    private fun <T> bodyAs(type: Class<T>): T = Codec.decode(stub.last().body, "request", type)

    @Test
    fun `create posts the customer and returns the new id`() {
        stub.respond("/quote/api/quotes", 201, """{"id":"q-9"}""")

        assertEquals("q-9", HttpCreateQuoteAdapter(api).create("c-1"))

        assertEquals("POST", stub.last().method)
        assertEquals(CreateQuoteRequest("c-1"), bodyAs(CreateQuoteRequest::class.java))
    }

    @Test
    fun `a refused create reaches the caller with the component's explanation`() {
        stub.respond("/quote/api/quotes", 400, """{"status":400,"message":"customer has no address"}""")
        val e = assertThrows(QuoteServiceException::class.java) { HttpCreateQuoteAdapter(api).create("c-1") }
        assertEquals("customer has no address", e.message)
    }

    @Test
    fun `edit header puts the header request`() {
        stub.respond("/quote/api/quotes/q-1/header", 204, "")

        HttpEditQuoteHeaderAdapter(api).editHeader(EditQuoteHeaderCommand("q-1", day, QuotePriceDisplay.NET, "S", null, null, "N", mapOf(10 to "x")))

        assertEquals("PUT", stub.last().method)
        val body = bodyAs(EditQuoteHeaderRequest::class.java)
        assertEquals("NET", body.priceDisplay)
        assertEquals(day, body.validUntil)
        assertEquals("S", body.subject)
        assertEquals(mapOf(10 to "x"), body.b2bValues)
    }

    @Test
    fun `edit items puts the lines in order`() {
        stub.respond("/quote/api/quotes/q-1/items", 204, "")
        val line = QuoteItemInput("Beratung", BigDecimal("2"), InvoiceItemCategory.SERVICE, InvoiceItemUnit.HOUR, Money.of("50"), VatCategory.STANDARD, BigDecimal("19"))

        HttpEditQuoteItemsAdapter(api).editItems("q-1", listOf(line, line.copy(description = "Zweite")))

        assertEquals("PUT", stub.last().method)
        assertEquals(listOf("Beratung", "Zweite"), bodyAs(EditQuoteItemsRequest::class.java).lines.map { it.description })
    }

    @Test
    fun `submit posts without a body and returns the number`() {
        stub.respond("/quote/api/quotes/q-1/submit", 200, """{"number":"A-2026-1"}""")

        assertEquals("A-2026-1", HttpSubmitQuoteAdapter(api).submit("q-1"))

        assertEquals("", stub.last().body)
    }

    @Test
    fun `send posts without a body and translates the hand-over`() {
        stub.respond("/quote/api/quotes/q-1/send", 200, """{"state":"COMPOSED","draftId":"d-1","composeUrl":"mail/compose/d-1"}""")

        assertEquals(QuoteDispatchHandover.Composed("d-1", "mail/compose/d-1"), HttpSendQuoteAdapter(api).send("q-1"))

        assertEquals("", stub.last().body)
    }

    @Test
    fun `send surfaces the refusal of the component`() {
        stub.respond("/quote/api/quotes/q-1/send", 409, """{"status":409,"message":"Keine Kunden-E-Mail"}""")

        val e = org.junit.jupiter.api.Assertions.assertThrows(de.littletrotter.application.quote.QuoteServiceException::class.java) {
            HttpSendQuoteAdapter(api).send("q-1")
        }
        assertEquals("Keine Kunden-E-Mail", e.message)
    }

    @Test
    fun `return to draft cancel and note post their reason`() {
        stub.respond("/quote/api/quotes/q-1/return-to-draft", 204, "")
        HttpReturnQuoteToDraftAdapter(api).returnToDraft("q-1", "Preis prüfen")
        assertEquals(ReturnQuoteToDraftRequest("Preis prüfen"), bodyAs(ReturnQuoteToDraftRequest::class.java))

        stub.respond("/quote/api/quotes/q-1/cancel", 204, "")
        HttpCancelQuoteAdapter(api).cancel("q-1", "Kunde abgesprungen")
        assertEquals(CancelQuoteRequest("Kunde abgesprungen"), bodyAs(CancelQuoteRequest::class.java))

        stub.respond("/quote/api/quotes/q-1/notes", 204, "")
        HttpAddQuoteNoteAdapter(api).addNote("q-1", "Rückruf")
        assertEquals(AddQuoteNoteRequest("Rückruf"), bodyAs(AddQuoteNoteRequest::class.java))
        assertEquals("/quote/api/quotes/q-1/notes", stub.last().path)
    }

    @Test
    fun `accept and reject post to their own routes`() {
        stub.respond("/quote/api/quotes/q-1/accept", 204, "")
        stub.respond("/quote/api/quotes/q-1/reject", 204, "")
        val acceptAdapter = HttpAcceptQuoteAdapter(api)
        val rejectAdapter = HttpRejectQuoteAdapter(api)

        acceptAdapter.accept("q-1", day, "PO-7")
        assertEquals("/quote/api/quotes/q-1/accept", stub.last().path)
        assertEquals(AcceptQuoteRequest(day, "PO-7"), bodyAs(AcceptQuoteRequest::class.java))

        acceptAdapter.accept("q-1", day, null)
        assertNull(bodyAs(AcceptQuoteRequest::class.java).customerOrderReference)

        rejectAdapter.reject("q-1", "zu teuer")
        assertEquals("/quote/api/quotes/q-1/reject", stub.last().path)
        assertEquals(RejectQuoteRequest("zu teuer"), bodyAs(RejectQuoteRequest::class.java))
    }

    @Test
    fun `attach sends the file base64 and returns the attachment id`() {
        stub.respond("/quote/api/quotes/q-1/attachments", 201, """{"id":"a-1"}""")

        val id = HttpAttachQuoteFileAdapter(api).attach("q-1", QuoteAttachmentUpload(QuoteAttachmentKind.CUSTOMER, "a.txt", "text/plain", "hi".toByteArray()))

        assertEquals("a-1", id)
        assertEquals(AttachQuoteFileRequest("CUSTOMER", "a.txt", "text/plain", "aGk="), bodyAs(AttachQuoteFileRequest::class.java))
    }

    @Test
    fun `attach carries the item reference`() {
        stub.respond("/quote/api/quotes/q-1/attachments", 201, """{"id":"a-2"}""")

        HttpAttachQuoteFileAdapter(api).attach("q-1", QuoteAttachmentUpload(QuoteAttachmentKind.INTERNAL, "a.txt", "text/plain", "hi".toByteArray(), "i-2"))

        assertEquals(AttachQuoteFileRequest("INTERNAL", "a.txt", "text/plain", "aGk=", "i-2"), bodyAs(AttachQuoteFileRequest::class.java))
    }

    @Test
    fun `assign puts the item reference and release sends null`() {
        stub.respond("/quote/api/quotes/q-1/attachments/a-1/item", 204, "")

        HttpAssignQuoteAttachmentAdapter(api).assign(AssignQuoteAttachmentCommand("q-1", "a-1", "i-2"))
        assertEquals("PUT", stub.last().method)
        assertEquals("/quote/api/quotes/q-1/attachments/a-1/item", stub.last().path)
        assertEquals(AssignQuoteAttachmentRequest("i-2"), bodyAs(AssignQuoteAttachmentRequest::class.java))

        HttpAssignQuoteAttachmentAdapter(api).assign(AssignQuoteAttachmentCommand("q-1", "a-1", null))
        assertNull(bodyAs(AssignQuoteAttachmentRequest::class.java).itemId)
    }

    @Test
    fun `remove deletes the attachment route`() {
        stub.respond("/quote/api/quotes/q-1/attachments/a-1", 204, "")

        HttpRemoveQuoteAttachmentAdapter(api).remove("q-1", "a-1")

        assertEquals("DELETE", stub.last().method)
        assertEquals("/quote/api/quotes/q-1/attachments/a-1", stub.last().path)
    }

    @Test
    fun `update settings puts the validity and the xml format`() {
        stub.respond("/quote/api/settings", 204, "")

        HttpUpdateQuoteSettingsAdapter(api).update(45, QuoteXmlFormat.NONE)

        assertEquals("PUT", stub.last().method)
        assertEquals(UpdateQuoteSettingsRequest(45, "NONE"), bodyAs(UpdateQuoteSettingsRequest::class.java))
    }

    @Test
    fun `request invoice posts the order and returns the order id`() {
        val orderId = java.util.UUID.randomUUID()
        stub.respond("/quote/api/quotes/q-1/invoice-order", 200, """{"orderId":"$orderId"}""")

        val answer = HttpRequestQuoteInvoiceAdapter(api).request(
            "q-1",
            QuoteInvoiceOrderRequest(day, "PO-7", null, day.plusDays(1), day.plusDays(9)),
        )

        assertEquals(orderId.toString(), answer)
        assertEquals("/quote/api/quotes/q-1/invoice-order", stub.last().path)
        assertEquals(
            RequestQuoteInvoiceRequest(day, "PO-7", null, day.plusDays(1), day.plusDays(9)),
            bodyAs(RequestQuoteInvoiceRequest::class.java),
        )
    }
}
