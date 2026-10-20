package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteNotSendableException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuoteRankingKind
import de.littletrotter.application.quote.port.inbound.QuoteSendGateState
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.quote.QuoteArticleOptionView
import de.littletrotter.shared.quote.QuoteAttachmentView
import de.littletrotter.shared.quote.QuoteCustomerOptionView
import de.littletrotter.shared.quote.QuoteDashboardView
import de.littletrotter.shared.quote.QuoteHeaderFieldView
import de.littletrotter.shared.quote.QuoteKpisView
import de.littletrotter.shared.quote.QuoteLogEntryView
import de.littletrotter.shared.quote.QuotePageView
import de.littletrotter.shared.quote.QuoteRankingRowView
import de.littletrotter.shared.quote.QuoteSendGateView
import de.littletrotter.shared.quote.QuoteSettingsView
import de.littletrotter.shared.quote.QuoteStatisticsView
import de.littletrotter.shared.quote.QuoteStatusCountsView
import de.littletrotter.shared.quote.QuoteWireCodec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

@DisplayName("Offer query adapters (GET /quote/api/…)")
class HttpQuoteQueryAdaptersTest {

    private val stub = QuoteStubServer()
    private val api = QuoteStubServer.client(stub.baseUrl)

    @AfterEach
    fun tearDown() = stub.close()

    @Test
    fun `list sends filter and window and reads the page`() {
        stub.respond("/quote/api/quotes", 200, QuoteWireCodec.encode(QuotePageView(listOf(QuoteFixtures.summary("SENT")), 9)))

        val page = HttpQuoteListQueryAdapter(api).findPage(QuoteListCriteria(QuoteStatus.SENT, "mu", 10, 5))

        assertEquals("GET", stub.last().method)
        assertEquals("status=SENT&text=mu&sort=QUOTE_DATE&dir=DESC&offset=10&limit=5", stub.last().query)
        assertEquals(9L, page.total)
        assertEquals(QuoteStatus.SENT, page.rows.single().status)
    }

    @Test
    fun `detail reads one offer by id and surfaces a missing one as not found`() {
        stub.respond("/quote/api/quotes/q-1", 200, QuoteWireCodec.encode(QuoteFixtures.detail()))
        stub.respond("/quote/api/quotes/gone", 404, """{"status":404,"message":"no such offer"}""")
        val adapter = HttpQuoteDetailQueryAdapter(api)

        assertEquals("Muster GmbH", adapter.find("q-1").party.name)
        assertEquals("/quote/api/quotes/q-1", stub.last().path)
        val e = assertThrows(QuoteNotFoundException::class.java) { adapter.find("gone") }
        assertEquals("no such offer", e.message)
    }

    @Test
    fun `send gate reads the gate of one offer and rejects a gate it does not know`() {
        stub.respond("/quote/api/quotes/q-1/send-gate", 200, QuoteWireCodec.encode(QuoteSendGateView("COPY_STALE")))
        val adapter = HttpQuoteSendGateQueryAdapter(api)

        assertEquals(QuoteSendGateState.COPY_STALE, adapter.gate("q-1"))
        assertEquals("GET", stub.last().method)
        assertEquals("/quote/api/quotes/q-1/send-gate", stub.last().path)

        stub.respond("/quote/api/quotes/q-2/send-gate", 200, QuoteWireCodec.encode(QuoteSendGateView("FUTURE")))
        assertThrows(QuoteServiceException::class.java) { adapter.gate("q-2") }
    }

    @Test
    fun `a refused send names the closed gate`() {
        stub.respond("/quote/api/quotes/q-1/send", 409, """{"status":409,"message":"quote.send.gate.NO_RECIPIENT_EMAIL"}""")

        val e = assertThrows(QuoteNotSendableException::class.java) { HttpSendQuoteAdapter(api).send("q-1") }

        assertEquals(QuoteSendGateState.NO_RECIPIENT_EMAIL, e.gate)
    }

    @Test
    fun `byNumber reads one offer by its number and surfaces a missing one as not found`() {
        stub.respond("/quote/api/quotes/by-number/AN-2026-0001", 200, QuoteWireCodec.encode(QuoteFixtures.detail()))
        stub.respond("/quote/api/quotes/by-number/AN-0000-0000", 404, """{"status":404,"message":"no such offer"}""")
        val adapter = HttpQuoteByNumberQueryAdapter(api)

        assertEquals("Muster GmbH", adapter.find("AN-2026-0001").party.name)
        assertEquals("/quote/api/quotes/by-number/AN-2026-0001", stub.last().path)
        assertThrows(QuoteNotFoundException::class.java) { adapter.find("AN-0000-0000") }
    }

    @Test
    fun `log sends the kinds and maps the entries`() {
        stub.respond("/quote/api/quotes/q-1/log", 200, QuoteWireCodec.encode(listOf(QuoteLogEntryView(Instant.EPOCH, "anna", "SEND", "sent", null))))
        val adapter = HttpQuoteLogQueryAdapter(api)

        val entries = adapter.log("q-1", setOf(QuoteLogKind.SEND, QuoteLogKind.NOTE))

        assertEquals("kind=NOTE&kind=SEND", stub.last().query)
        assertEquals(QuoteLogKind.SEND, entries.single().kind)
        adapter.log("q-1", emptySet())
        assertEquals(null, stub.last().query)
    }

    @Test
    fun `statistics read the counts under the statistics route`() {
        stub.respond("/quote/api/quotes/statistics", 200, QuoteWireCodec.encode(QuoteStatisticsView(mapOf("DRAFT" to 3L))))

        val stats = HttpQuoteStatisticsQueryAdapter(api).statistics()

        assertEquals(3L, stats.countsByStatus[QuoteStatus.DRAFT])
    }

    @Test
    fun `dashboard sends the selection and reads the figures`() {
        val zero = QuoteKpisView(Money.ZERO, 0, null, Money.ZERO, 0, null)
        stub.respond(
            "/quote/api/quotes/statistics/dashboard",
            200,
            QuoteWireCodec.encode(QuoteDashboardView(listOf(2025), zero, zero, QuoteStatusCountsView(1, 0, 2, 0))),
        )

        val dashboard = HttpQuoteStatisticsDashboardAdapter(api).dashboard(QuoteStatisticsSelection(2025, quarter = 2))

        assertEquals("year=2025&quarter=2", stub.last().query)
        assertEquals(listOf(2025), dashboard.availableYears)
        assertEquals(2, dashboard.statusCounts.sent)
    }

    @Test
    fun `ranking sends kind and limit and reads the rows`() {
        stub.respond(
            "/quote/api/quotes/statistics/ranking",
            200,
            QuoteWireCodec.encode(listOf(QuoteRankingRowView("c1", "Acme", 3, Money.of("30.00")))),
        )

        val rows = HttpQuoteRankingAdapter(api).ranking(QuoteRankingKind.ARTICLE, QuoteStatisticsSelection(2025, month = 4), 5)

        assertEquals("year=2025&month=4&kind=ARTICLE&limit=5", stub.last().query)
        assertEquals("Acme", rows.single().name)
        assertEquals(Money.of("30.00"), rows.single().net)
    }

    @Test
    fun `settings read the validity`() {
        stub.respond("/quote/api/settings", 200, QuoteWireCodec.encode(QuoteSettingsView(21)))
        assertEquals(21L, HttpQuoteSettingsQueryAdapter(api).settings().validityDays)
        assertEquals(null, HttpQuoteSettingsQueryAdapter(api).settings().updatedAt)
    }

    @Test
    fun `settings carry the last change when the component reports it`() {
        val at = java.time.Instant.parse("2026-10-01T08:15:00Z")
        stub.respond("/quote/api/settings", 200, QuoteWireCodec.encode(QuoteSettingsView(21, "NONE", at)))

        val settings = HttpQuoteSettingsQueryAdapter(api).settings()

        assertEquals(at, settings.updatedAt)
    }

    @Test
    fun `customer lookup sends text and limit`() {
        stub.respond("/quote/api/lookup/customers", 200, QuoteWireCodec.encode(listOf(QuoteCustomerOptionView("c-1", "Muster", "K-1", "Ort"))))

        val options = HttpQuoteCustomerLookupQueryAdapter(api).customers("mus ter", 7)

        assertEquals("text=mus+ter&limit=7", stub.last().query)
        assertEquals("K-1", options.single().customerNumber)
    }

    @Test
    fun `article lookup is scoped to the offer and sends text and limit`() {
        val view = QuoteArticleOptionView("a-1", "AN-1", "Beratung", "SERVICE", "HOUR", Money.of("50"), "STANDARD", BigDecimal("19"), null)
        stub.respond("/quote/api/quotes/q-1/articles", 200, QuoteWireCodec.encode(listOf(view)))

        val options = HttpQuoteArticleLookupQueryAdapter(api).articles("q-1", InvoiceItemCategory.SERVICE, "", 20)

        assertEquals("category=SERVICE&text=&limit=20", stub.last().query)
        assertEquals(Money.of("50"), options.single().unitPrice)
    }

    @Test
    fun `header fields are read per offer`() {
        stub.respond("/quote/api/quotes/q-1/header-fields", 200, QuoteWireCodec.encode(listOf(QuoteHeaderFieldView(10, true, null))))
        assertEquals(10, HttpQuoteHeaderFieldQueryAdapter(api).headerFields("q-1").single().btCode)
    }

    @Test
    fun `attachment list maps the metadata`() {
        val view = QuoteAttachmentView("a-1", "CUSTOMER", "a.pdf", "application/pdf", 4, "h", Instant.EPOCH)
        stub.respond("/quote/api/quotes/q-1/attachments", 200, QuoteWireCodec.encode(listOf(view)))
        assertEquals("a.pdf", HttpQuoteAttachmentQueryAdapter(api).attachments("q-1").single().fileName)
    }

    @Test
    fun `attachment content takes name and type from the answer headers`() {
        val bytes = byteArrayOf(1, 2, 3)
        stub.respondBytes(
            "/quote/api/quotes/q-1/attachments/a-1/content", 200, bytes, "application/pdf",
            mapOf("Content-Disposition" to "attachment; filename*=UTF-8''Angebot%20%C3%9Cberblick.pdf"),
        )

        val content = HttpQuoteAttachmentQueryAdapter(api).content("q-1", "a-1")

        assertEquals("Angebot Überblick.pdf", content.fileName)
        assertEquals("application/pdf", content.mimeType)
        assertArrayEquals(bytes, content.bytes)
    }

    @Test
    fun `attachment content tolerates a plain filename and falls back when none is given`() {
        stub.respondBytes("/quote/api/quotes/q-1/attachments/a-1/content", 200, byteArrayOf(1), "text/plain", mapOf("Content-Disposition" to "attachment; filename=\"n.txt\""))
        stub.respondBytes("/quote/api/quotes/q-1/attachments/a-2/content", 200, byteArrayOf(1), "text/plain")
        val adapter = HttpQuoteAttachmentQueryAdapter(api)

        assertEquals("n.txt", adapter.content("q-1", "a-1").fileName)
        assertEquals("attachment", adapter.content("q-1", "a-2").fileName)
    }

    @Test
    fun `attachment content without a media type is offered as octet-stream`() {
        stub.respondBytes("/quote/api/quotes/q-1/attachments/a-1/content", 200, byteArrayOf(1), "")
        assertEquals("application/octet-stream", HttpQuoteAttachmentQueryAdapter(api).content("q-1", "a-1").mimeType)
    }

    @Test
    fun `routes encode identifiers as single path segments`() {
        assertEquals("/api/quotes/a%20b%2Fc/attachments/x%2By", QuoteRoutes.of("/{id}/attachments/{aid}", "a b/c", "x+y"))
        assertEquals("/api/quotes/q-1", QuoteRoutes.byId("q-1"))
        assertEquals("/api/quotes/by-number/AN%2F1", QuoteRoutes.byNumber("AN/1"))
    }
}
