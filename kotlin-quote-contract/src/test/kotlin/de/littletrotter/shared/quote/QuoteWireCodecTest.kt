package de.littletrotter.shared.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class QuoteWireCodecTest {

    private val day = LocalDate.parse("2026-10-01")
    private val at = Instant.parse("2026-10-01T08:15:30Z")
    private val uuid = "11111111-2222-3333-4444-555555555555"

    private inline fun <reified T : Any> decodeWire(json: String?, what: String): T = QuoteWireCodec.decode(json, what, T::class.java)

    private inline fun <reified T : Any> roundTrip(value: T) {
        assertEquals(value, decodeWire<T>(QuoteWireCodec.encode(value), "test"))
    }

    private val party = QuotePartyView(
        customerId = uuid, name = "ACME", countryCode = "DE", customerType = "BUSINESS", documentLanguage = "DE",
        customerNumber = "K-1", street = "Weg 1", postalCode = "10115", city = "Berlin", vatId = "DE123", email = "a@b.de",
    )
    private val item = QuoteItemView(
        id = uuid, position = 1, description = "Work", quantity = BigDecimal("2.5000"), category = "SERVICE", unit = "HOUR",
        unitPrice = Money.of("10.50"), vatCategory = "STANDARD", vatRate = BigDecimal("19"), net = Money.of("26.25"),
        articleId = uuid, articleNumber = "A-1", customerReference = "ref",
    )
    private val totals = QuoteTotalsView(
        Money.of("26.25"), Money.of("4.99"), Money.of("31.24"),
        listOf(QuoteVatGroupView("STANDARD", BigDecimal("19"), Money.of("26.25"), Money.of("4.99"))),
    )

    @Test
    fun `views round-trip`() {
        val summary = QuoteSummaryView(uuid, "ACME", "DRAFT", day, day, Money.of("26.25"), "A-1", "S")
        roundTrip(summary)
        roundTrip(QuotePageView(listOf(summary), 1))
        roundTrip(party)
        roundTrip(item)
        roundTrip(totals)
        roundTrip(QuoteB2bValueView(3, "x"))
        roundTrip(
            QuoteDetailView(
                id = uuid, version = 4, status = "ACCEPTED", party = party, priceDisplay = "AUTO",
                quoteDate = day, validUntil = day, totals = totals, number = "A-1",
                subject = "s", introText = "i", closingText = "c",
                noteText = "n", showsGrossPrices = true, acceptedOn = day, customerOrderReference = "PO",
                rejectionReason = "r", invoiceNumber = "RE-1",
                items = listOf(item), b2bValues = listOf(QuoteB2bValueView(3, "x")),
            ),
        )
        roundTrip(QuoteLogEntryView(at, "anna", "NOTE", "text", "ref"))
        roundTrip(QuoteRankingRowView("193", "ACME", 2, Money.of("30.00")))
        roundTrip(QuoteAttachmentView(uuid, "INTERNAL", "a.pdf", "application/pdf", 42, "f".repeat(64), at))
        roundTrip(
            QuoteAttachmentView(uuid, "CUSTOMER", "agb.pdf", "application/pdf", 42, "f".repeat(64), at, uuid, 2, uuid, "AGB", 1, true),
        )
        val version = AttachmentLibraryVersionView(uuid, 2, "agb.pdf", "application/pdf", 42, "f".repeat(64), day, at, "anna")
        roundTrip(AttachmentLibraryEntryView(uuid, "AGB", "Bedingungen", version, listOf(version), 3, false))
        roundTrip(QuoteStatisticsView(mapOf("DRAFT" to 2L, "SENT" to 1L)))
        roundTrip(QuoteSettingsView(30))
        roundTrip(QuoteCustomerOptionView(uuid, "ACME", "K-1", "Berlin"))
        roundTrip(QuoteArticleOptionView(uuid, "A-1", "d", "SERVICE", "HOUR", Money.of("10.50"), "STANDARD", BigDecimal("19"), "ref"))
        roundTrip(QuoteHeaderFieldView(3, true, "d"))
        roundTrip(QuoteIdAnswer(uuid))
        roundTrip(QuoteIdAnswer(uuid, QuoteReductionAnswer("RASTERISED", 4096)))
        roundTrip(QuoteNumberAnswer("A-1"))
        roundTrip(QuoteErrorView(404, "missing"))
    }

    @Test
    fun `order, document and maintenance views round-trip`() {
        roundTrip(QuoteInvoiceOrderView(uuid, "REQUESTED", at))
        roundTrip(QuoteInvoiceOrderView(uuid, "CREATED", at, day, day, day, 42L, "RE-2026-0042", null))
        roundTrip(QuoteInvoiceOrderView(uuid, "REJECTED", at, rejectionReason = "Kunde fehlt"))
        roundTrip(QuoteDocumentView(uuid, "SENT", "DONE", at, at, "abc", true, null, true))
        roundTrip(QuoteDocumentView(uuid, "DRAFT", "FAILED", at, error = "boom"))
        roundTrip(ReplicaResyncView(uuid))
        roundTrip(DocumentBackfillView(7))
        roundTrip(RequestQuoteDocumentRequest("SENT"))
        roundTrip(RequestQuoteInvoiceRequest())
    }

    @Test
    fun `requests round-trip`() {
        val line = QuoteItemLineRequest("Work", BigDecimal("2"), "SERVICE", "HOUR", Money.of("10.50"), "STANDARD", BigDecimal("19"), uuid, uuid, "A-1", "ref")
        roundTrip(CreateQuoteRequest(uuid))
        roundTrip(CreateAttachmentLibraryEntryRequest("AGB", "d", "a.pdf", "application/pdf", "QQ==", day))
        roundTrip(AddAttachmentLibraryVersionRequest("a.pdf", "application/pdf", "QQ=="))
        roundTrip(AttachFromLibraryRequest(uuid, uuid))
        roundTrip(ReplaceLibraryVersionRequest(uuid))
        roundTrip(EditQuoteHeaderRequest(day, "NET", "s", "i", "c", "n", mapOf(3 to "x")))
        roundTrip(line)
        roundTrip(EditQuoteItemsRequest(listOf(line)))
        roundTrip(ReturnQuoteToDraftRequest("redo"))
        roundTrip(CancelQuoteRequest("no"))
        roundTrip(QuoteDispatchHandoverView(draftId = "d", composeUrl = "u"))
        roundTrip(QuoteSendGateView(QuoteSendGate.COPY_STALE.name))
        roundTrip(AcceptQuoteRequest(day, "PO"))
        roundTrip(RequestQuoteInvoiceRequest(day, "PO", null, day, day))
        roundTrip(QuoteInvoiceOrderAnswer(java.util.UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
        roundTrip(RejectQuoteRequest("too dear"))
        roundTrip(AddQuoteNoteRequest("hello"))
        roundTrip(AttachQuoteFileRequest("CUSTOMER", "a.pdf", "application/pdf", "AAEC"))
        roundTrip(AttachQuoteFileRequest("CUSTOMER", "a.pdf", "application/pdf", "AAEC", reduce = true))
        roundTrip(CreateAttachmentLibraryEntryRequest("AGB", "d", "a.pdf", "application/pdf", "QQ==", day, reduce = true))
        roundTrip(AddAttachmentLibraryVersionRequest("a.pdf", "application/pdf", "QQ==", reduce = true))
        roundTrip(UpdateQuoteSettingsRequest(30))
    }

    @Test
    fun `lists decode through a type reference`() {
        val rows = listOf(QuoteCustomerOptionView(uuid, "ACME"))
        val decoded = QuoteWireCodec.decode(QuoteWireCodec.encode(rows), "list", object : TypeReference<List<QuoteCustomerOptionView>>() {})
        assertEquals(rows, decoded)
    }

    @Test
    fun `dates and enums travel as text and keys are the property names`() {
        val json = QuoteWireCodec.encode(RejectQuoteRequest("r")) + QuoteWireCodec.encode(AcceptQuoteRequest(day))
        assertTrue(json.contains("\"acceptedOn\":\"2026-10-01\""))
        assertTrue(json.contains("\"reason\":\"r\""))
    }

    @Test
    fun `unknown fields are ignored and absent optional fields default`() {
        val read = decodeWire<QuoteSettingsView>("""{"validityDays":14,"future":true}""", "settings")
        assertEquals(14, read.validityDays)
        val handover = decodeWire<QuoteDispatchHandoverView>("""{"draftId":"d","composeUrl":"u","state":"COMPOSED"}""", "handover")
        assertEquals("d", handover.draftId)
    }

    @Test
    fun `upload requests without the reduce field store as sent`() {
        val attach = """{"kind":"CUSTOMER","fileName":"a.pdf","mimeType":"application/pdf","contentBase64":"AAEC"}"""
        assertFalse(decodeWire<AttachQuoteFileRequest>(attach, "attach").reduce)
        assertTrue(decodeWire<AttachQuoteFileRequest>(attach.dropLast(1) + ""","reduce":true}""", "attach").reduce)
        val create = """{"title":"t","description":"d","fileName":"a.pdf","mimeType":"application/pdf","contentBase64":"QQ=="}"""
        assertFalse(decodeWire<CreateAttachmentLibraryEntryRequest>(create, "create").reduce)
        val add = """{"fileName":"a.pdf","mimeType":"application/pdf","contentBase64":"QQ=="}"""
        assertFalse(decodeWire<AddAttachmentLibraryVersionRequest>(add, "add").reduce)
    }

    @Test
    fun `an answer without the reduction field decodes to not reported`() {
        assertNull(decodeWire<QuoteIdAnswer>("""{"id":"$uuid"}""", "answer").reduction)
        assertEquals(
            QuoteReductionAnswer("IMAGES_DOWNSAMPLED", 7),
            decodeWire<QuoteIdAnswer>("""{"id":"$uuid","reduction":{"stage":"IMAGES_DOWNSAMPLED","storedBytes":7}}""", "answer").reduction,
        )
    }

    @Test
    fun `blank payload is a violation`() {
        assertThrows<QuoteContractViolationException> { decodeWire<QuoteSettingsView>(null, "settings") }
        assertThrows<QuoteContractViolationException> { decodeWire<QuoteSettingsView>("  ", "settings") }
    }

    @Test
    fun `malformed or mismatching JSON is a violation naming the payload`() {
        val malformed = assertThrows<QuoteContractViolationException> { decodeWire<QuoteSettingsView>("{nope", "settings") }
        assertTrue(malformed.message!!.startsWith("settings"))
        assertThrows<QuoteContractViolationException> { decodeWire<QuoteSettingsView>("""{"other":1}""", "settings") }
    }

    @Test
    fun `minimal payloads fill every default`() {
        assertEquals(emptyList<QuoteLogEntryView>(), decodeWire<QuotePageView>("{}", "page").rows)
        assertNull(decodeWire<QuoteLogEntryView>("""{"occurredAt":"2026-10-01T08:15:30Z","actor":"a","kind":"NOTE","text":"t"}""", "log").reference)
        assertEquals(emptyMap<Int, String>(), decodeWire<EditQuoteHeaderRequest>("""{"validUntil":"2026-10-01","priceDisplay":"AUTO"}""", "header").b2bValues)
        assertEquals(emptyList<QuoteVatGroupView>(), decodeWire<QuoteTotalsView>("""{"net":1,"vat":0,"gross":1}""", "totals").groups)
    }

    @Test
    fun `paths are rooted under api`() {
        assertTrue(QuoteApiPaths.QUOTES.startsWith("/api/"))
        assertTrue(QuoteApiPaths.SETTINGS.startsWith("/api/"))
        assertTrue(QuoteApiPaths.LOOKUP_CUSTOMERS.startsWith("/api/"))
    }

    @Test
    fun `an amount travels as a bare number`() {
        val json = QuoteWireCodec.encode(QuoteSettingsAmountProbe(Money.of("10.5")))
        assertTrue(json.contains("\"price\":10.50"), json)
        assertEquals(Money.of("10.50"), QuoteWireCodec.decode(json, "probe", QuoteSettingsAmountProbe::class.java).price)
    }

    @Test
    fun `the dashboard round-trips with and without the optional averages`() {
        val full = QuoteKpisView(Money.of("60.00"), 2, Money.of("30.00"), Money.of("5.00"), 1, BigDecimal("2.5"))
        val empty = QuoteKpisView(Money.ZERO, 0, null, Money.ZERO, 0, null)

        roundTrip(
            QuoteDashboardView(
                availableYears = listOf(2026, 2025),
                kpis = full,
                kpisPreviousYear = empty,
                statusCounts = QuoteStatusCountsView(1, 2, 3, 4),
                quarters = listOf(QuoteQuarterView(1, Money.of("60.00"), 2)),
                trend = listOf(QuoteTrendPointView(day, Money.of("60.00"), 2)),
            ),
        )
        roundTrip(QuoteDashboardView(kpis = empty, kpisPreviousYear = empty, statusCounts = QuoteStatusCountsView(0, 0, 0, 0)))
    }
}

private data class QuoteSettingsAmountProbe(val price: Money)
