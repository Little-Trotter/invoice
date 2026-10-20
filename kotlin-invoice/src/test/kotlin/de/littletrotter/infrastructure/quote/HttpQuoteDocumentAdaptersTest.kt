package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.shared.quote.QuoteDocumentView
import de.littletrotter.shared.quote.RequestQuoteDocumentRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import de.littletrotter.shared.quote.QuoteWireCodec as Codec

@DisplayName("Offer document adapters (/quote/api/quotes/{id}/documents…)")
class HttpQuoteDocumentAdaptersTest {

    private val stub = QuoteStubServer()
    private val api = QuoteStubServer.client(stub.baseUrl)
    private val base = "/quote/api/quotes/q-1/documents"

    @AfterEach
    fun tearDown() = stub.close()

    @Test
    fun `requesting posts the mark and returns the pending document id`() {
        stub.respond(base, 201, """{"id":"d-9"}""")

        assertEquals("d-9", HttpRequestQuoteDocumentAdapter(api).request("q-1", QuoteDocumentMark.DISPATCH))

        assertEquals("POST", stub.last().method)
        assertEquals(RequestQuoteDocumentRequest("DISPATCH"), Codec.decode(stub.last().body, "request", RequestQuoteDocumentRequest::class.java))
    }

    @Test
    fun `the list maps state, flags and timestamps`() {
        val done = QuoteDocumentView("d-1", "REVIEW", "COMPLETED", Instant.EPOCH, Instant.EPOCH, "h", true, null, true)
        val failed = QuoteDocumentView("d-2", "DRAFT", "FAILED", Instant.EPOCH, null, null, false, "Renderer nicht erreichbar")
        stub.respond(base, 200, Codec.encode(listOf(done, failed)))

        val documents = HttpQuoteDocumentQueryAdapter(api).documents("q-1")

        assertEquals("GET", stub.last().method)
        assertEquals(listOf(QuoteDocumentStatus.COMPLETED, QuoteDocumentStatus.FAILED), documents.map { it.status })
        assertTrue(documents[0].hasXml)
        assertEquals("Renderer nicht erreichbar", documents[1].error)
    }

    @Test
    fun `the pdf takes its name from the answer and falls back otherwise`() {
        val bytes = byteArrayOf(1, 2, 3)
        stub.respondBytes(
            "$base/d-1/pdf", 200, bytes, "application/pdf",
            mapOf("Content-Disposition" to "attachment; filename*=UTF-8''Angebot%20AN-1.pdf"),
        )
        stub.respondBytes("$base/d-2/pdf", 200, bytes, "application/pdf")
        val adapter = HttpQuoteDocumentFileQueryAdapter(api)

        val content = adapter.pdf("q-1", "d-1")

        assertEquals("Angebot AN-1.pdf", content.fileName)
        assertArrayEquals(bytes, content.bytes)
        assertEquals("offer.pdf", adapter.pdf("q-1", "d-2").fileName)
    }

    @Test
    fun `a missing pdf is not found`() {
        stub.respond("$base/d-1/pdf", 404, """{"status":404,"message":"document d-1 is not available"}""")
        assertThrows(QuoteNotFoundException::class.java) { HttpQuoteDocumentFileQueryAdapter(api).pdf("q-1", "d-1") }
    }

    @Test
    fun `the xml is delivered when present and null when the document has none`() {
        stub.respondBytes(
            "$base/d-1/xml", 200, "<q/>".toByteArray(), "text/xml",
            mapOf("Content-Disposition" to "attachment; filename=\"quotation.ubl.xml\""),
        )
        stub.respond("$base/d-2/xml", 404, """{"status":404,"message":"carries no machine-readable data"}""")
        val adapter = HttpQuoteDocumentFileQueryAdapter(api)

        assertEquals("quotation.ubl.xml", adapter.xml("q-1", "d-1")?.fileName)
        assertNull(adapter.xml("q-1", "d-2"))
        assertEquals("/quote/api/quotes/q-1/documents/d-2/xml", stub.last().path)
    }

    @Test
    fun `the validation report is read as the component's own wording`() {
        stub.respond("$base/d-1/validation-report", 200, """{"syntax":"UBL","valid":false,"errors":["BR-Q-03: Gültigkeit fehlt"]}""")
        stub.respond("$base/d-2/validation-report", 404, """{"status":404,"message":"no validation report"}""")
        val adapter = HttpQuoteValidationReportQueryAdapter(api)

        val report = adapter.report("q-1", "d-1")!!

        assertEquals(false, report.valid)
        assertEquals(listOf("BR-Q-03: Gültigkeit fehlt"), report.findings)
        assertNull(adapter.report("q-1", "d-2"))
    }

    @Test
    fun `document routes fill the document id as one path segment`() {
        assertEquals(
            "/api/quotes/q-1/documents/a%2Fb/pdf",
            QuoteRoutes.of("/{id}/documents/{did}/pdf", "q-1", "a/b"),
        )
    }
}
