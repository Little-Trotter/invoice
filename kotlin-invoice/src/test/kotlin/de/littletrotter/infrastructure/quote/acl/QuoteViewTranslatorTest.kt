package de.littletrotter.infrastructure.quote.acl

import java.time.LocalDate
import de.littletrotter.shared.quote.QuoteInvoiceOrderView
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderStatus
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteValidationReport
import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.infrastructure.quote.QuoteFixtures
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.quote.QuoteArticleOptionView
import de.littletrotter.shared.quote.QuoteAttachmentView
import de.littletrotter.shared.quote.QuoteCustomerOptionView
import de.littletrotter.shared.quote.QuoteDispatchHandoverView
import de.littletrotter.shared.quote.QuoteDocumentView
import de.littletrotter.shared.quote.QuoteHeaderFieldView
import de.littletrotter.shared.quote.QuoteLogEntryView
import de.littletrotter.shared.quote.QuotePageView
import de.littletrotter.shared.quote.QuoteSettingsView
import de.littletrotter.shared.quote.QuoteDashboardView
import de.littletrotter.shared.quote.QuoteKpisView
import de.littletrotter.shared.quote.QuoteQuarterView
import de.littletrotter.shared.quote.QuoteRankingRowView
import de.littletrotter.shared.quote.QuoteStatusCountsView
import de.littletrotter.shared.quote.QuoteTrendPointView
import de.littletrotter.shared.quote.QuoteStatisticsView
import de.littletrotter.shared.tax.VatCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

@DisplayName("QuoteViewTranslator (contract -> local model)")
class QuoteViewTranslatorTest {

    @Test
    fun `a hand-over maps by state and rejects missing fields and unknown states`() {
        assertEquals(
            QuoteDispatchHandover.Composed("d", "u"),
            QuoteViewTranslator.toDispatchHandover(QuoteDispatchHandoverView("COMPOSED", draftId = "d", composeUrl = "u")),
        )
        assertEquals(
            QuoteDispatchHandover.DocumentPending("x"),
            QuoteViewTranslator.toDispatchHandover(QuoteDispatchHandoverView("DOCUMENT_PENDING", documentId = "x")),
        )
        listOf(
            QuoteDispatchHandoverView("COMPOSED", draftId = "d"),
            QuoteDispatchHandoverView("COMPOSED", composeUrl = " ", draftId = "d"),
            QuoteDispatchHandoverView("DOCUMENT_PENDING"),
            QuoteDispatchHandoverView("SENT"),
        ).forEach { assertThrows(QuoteServiceException::class.java) { QuoteViewTranslator.toDispatchHandover(it) } }
    }

    @Test
    fun `every status name maps to its local constant`() {
        QuoteStatus.entries.forEach { assertEquals(it, QuoteViewTranslator.toSummary(QuoteFixtures.summary(status = it.name)).status) }
    }

    @Test
    fun `a summary keeps every field and turns the net total into money`() {
        val s = QuoteViewTranslator.toSummary(QuoteFixtures.summary())
        assertEquals("q-1", s.id)
        assertEquals(Money.of("100.50"), s.netTotal)
        assertEquals("A-1", s.number)
        assertEquals("Betreff", s.subject)
        assertEquals("Muster GmbH", s.customerName)
        assertEquals(QuoteFixtures.DAY.plusDays(30), s.validUntil)
    }

    @Test
    fun `a page maps its rows and total`() {
        val page = QuoteViewTranslator.toPage(QuotePageView(listOf(QuoteFixtures.summary()), 7))
        assertEquals(7L, page.total)
        assertEquals(1, page.rows.size)
    }

    @Test
    fun `every price display and vat category maps through the detail`() {
        QuotePriceDisplay.entries.forEach {
            assertEquals(it, QuoteViewTranslator.toDetail(QuoteFixtures.detail(priceDisplay = it.name)).priceDisplay)
        }
        VatCategory.entries.forEach {
            val d = QuoteViewTranslator.toDetail(QuoteFixtures.detail(vatCategory = it.name))
            assertEquals(it, d.items.single().vatCategory)
            assertEquals(it, d.totals.groups.single().category)
        }
    }

    @Test
    fun `a full detail keeps party, totals, terms, links and lines`() {
        val d = QuoteViewTranslator.toDetail(QuoteFixtures.detail())
        assertEquals(3L, d.version)
        assertEquals("K-1", d.party.customerNumber)
        assertEquals("DE123", d.party.vatId)
        assertEquals("a@b.de", d.party.email)
        assertEquals("Weg 1", d.party.street)
        assertEquals("12345", d.party.postalCode)
        assertEquals("Ort", d.party.city)
        assertEquals(Money.of("119"), d.totals.gross)
        assertEquals(Money.of("19"), d.totals.vat)
        assertEquals(Money.of("100"), d.totals.groups.single().base)
        assertEquals("PO-1", d.customerOrderReference)
        assertEquals("RE-1", d.invoiceNumber)
        assertEquals("R", d.rejectionReason)
        assertEquals("I", d.introText)
        assertEquals("C", d.closingText)
        assertEquals("N", d.noteText)
        assertTrue(d.showsGrossPrices)
        assertEquals(10, d.b2bValues.single().btCode)
        assertEquals("AN-1", d.items.single().articleNumber)
        assertEquals(Money.of("50"), d.items.single().unitPrice)
        assertEquals(Money.of("100"), d.items.single().net)
        assertEquals(InvoiceItemCategory.SERVICE, d.items.single().category)
        assertEquals(InvoiceItemUnit.HOUR, d.items.single().unit)
    }

    @Test
    fun `a sparse detail keeps its absent optionals absent`() {
        val d = QuoteViewTranslator.toDetail(QuoteFixtures.detail(full = false))
        assertNull(d.number)
        assertNull(d.acceptedOn)
    }

    @Test
    fun `log kinds and attachment kinds map by name`() {
        QuoteLogKind.entries.forEach {
            assertEquals(it, QuoteViewTranslator.toLogEntry(QuoteLogEntryView(Instant.EPOCH, "anna", it.name, "t", "ref")).kind)
        }
        QuoteAttachmentKind.entries.forEach {
            assertEquals(it, QuoteViewTranslator.toAttachment(QuoteAttachmentView("a", it.name, "f.pdf", "application/pdf", 3, "h", Instant.EPOCH)).kind)
        }
        val entry = QuoteViewTranslator.toLogEntry(QuoteLogEntryView(Instant.EPOCH, "anna", "NOTE", "t", "ref"))
        assertEquals("anna", entry.actor)
        assertEquals("ref", entry.reference)
        val attachment = QuoteViewTranslator.toAttachment(QuoteAttachmentView("a", "INTERNAL", "f.pdf", "application/pdf", 3, "h", Instant.EPOCH))
        assertEquals(3L, attachment.sizeBytes)
        assertEquals("f.pdf", attachment.fileName)
    }

    @Test
    fun `statistics list every status, absent ones as zero`() {
        val stats = QuoteViewTranslator.toStatistics(QuoteStatisticsView(mapOf("SENT" to 4L)))
        assertEquals(QuoteStatus.entries.toSet(), stats.countsByStatus.keys)
        assertEquals(4L, stats.countsByStatus[QuoteStatus.SENT])
        assertEquals(0L, stats.countsByStatus[QuoteStatus.DRAFT])
    }

    @Test
    fun `dashboard keeps the figures and orders quarters and trend`() {
        val kpis = QuoteKpisView(Money.of("90.00"), 3, Money.of("30.00"), Money.of("5.00"), 2, java.math.BigDecimal("2.5"))
        val empty = QuoteKpisView(Money.ZERO, 0, null, Money.ZERO, 0, null)
        val dashboard = QuoteViewTranslator.toDashboard(
            QuoteDashboardView(
                availableYears = listOf(2026, 2025),
                kpis = kpis,
                kpisPreviousYear = empty,
                statusCounts = QuoteStatusCountsView(1, 2, 3, 4),
                quarters = listOf(QuoteQuarterView(4, Money.of("1.00"), 1), QuoteQuarterView(2, Money.of("2.00"), 2)),
                trend = listOf(
                    QuoteTrendPointView(java.time.LocalDate.of(2026, 5, 1), Money.of("3.00"), 1),
                    QuoteTrendPointView(java.time.LocalDate.of(2026, 3, 1), Money.of("4.00"), 2),
                ),
            ),
        )

        assertEquals(listOf(2026, 2025), dashboard.availableYears)
        assertEquals(Money.of("90.00"), dashboard.kpis.sentVolume)
        assertEquals(3, dashboard.kpis.sentCount)
        assertEquals(Money.of("30.00"), dashboard.kpis.averageSent)
        assertEquals(java.math.BigDecimal("2.5"), dashboard.kpis.avgDaysToSend)
        assertEquals(Money.of("5.00"), dashboard.kpis.inProgressAmount)
        assertEquals(2, dashboard.kpis.inProgressCount)
        assertNull(dashboard.kpisPreviousYear.averageSent)
        assertNull(dashboard.kpisPreviousYear.avgDaysToSend)
        assertEquals(listOf(1, 2, 3, 4), listOf(dashboard.statusCounts.draft, dashboard.statusCounts.review, dashboard.statusCounts.sent, dashboard.statusCounts.cancelled))
        assertEquals(10, dashboard.statusCounts.total)
        assertEquals(listOf(2, 4), dashboard.quarters.map { it.quarter })
        assertEquals(listOf(3, 5), dashboard.trend.map { it.day.monthValue })
    }

    @Test
    fun `a ranking row keeps key, name, count and net`() {
        val row = QuoteViewTranslator.toRankingRow(QuoteRankingRowView("k1", "Acme", 4, Money.of("12.00")))

        assertEquals("k1", row.key)
        assertEquals("Acme", row.name)
        assertEquals(4, row.count)
        assertEquals(Money.of("12.00"), row.net)
    }

    @Test
    fun `settings, options and header fields map one to one`() {
        assertEquals(14L, QuoteViewTranslator.toSettings(QuoteSettingsView(14)).validityDays)
        assertEquals(QuoteXmlFormat.NONE, QuoteViewTranslator.toSettings(QuoteSettingsView(14, "NONE")).xmlFormat)
        assertThrows(QuoteServiceException::class.java) { QuoteViewTranslator.toSettings(QuoteSettingsView(14, "CII")) }
        val customer = QuoteViewTranslator.toCustomerOption(QuoteCustomerOptionView("c", "N", "K-1", "Ort"))
        assertEquals("K-1", customer.customerNumber)
        assertEquals("Ort", customer.city)
        val article = QuoteViewTranslator.toArticleOption(
            QuoteArticleOptionView("a", "AN", "Desc", "PRODUCT", "PIECE", Money.of("9.9"), "REVERSE_CHARGE", BigDecimal("0"), "REF", "Kundenrohr"),
        )
        assertEquals(VatCategory.REVERSE_CHARGE, article.vatCategory)
        assertEquals(InvoiceItemCategory.PRODUCT, article.category)
        assertEquals(InvoiceItemUnit.PIECE, article.unit)
        assertEquals(Money.of("9.90"), article.unitPrice)
        assertEquals("REF", article.customerReference)
        assertEquals("Kundenrohr", article.customerArticleName)
        val field = QuoteViewTranslator.toHeaderField(QuoteHeaderFieldView(10, true, "d"))
        assertTrue(field.mandatory)
        assertEquals("d", field.defaultValue)
        assertEquals(10, field.btCode)
    }

    @Test
    fun `an unknown enum name is reported with its field`() {
        val e = assertThrows(QuoteServiceException::class.java) { QuoteViewTranslator.toSummary(QuoteFixtures.summary(status = "ARCHIVED")) }
        assertEquals("The offer component sent an unknown status 'ARCHIVED'", e.message)
    }

    @Test
    fun `a document maps its state and flags`() {
        val view = QuoteDocumentView("d-1", "DISPATCH", "COMPLETED", Instant.EPOCH, Instant.EPOCH, "h", true, null, true)

        val document = QuoteViewTranslator.toDocument(view)

        assertEquals(QuoteDocumentMark.DISPATCH, document.mark)
        assertEquals(QuoteDocumentStatus.COMPLETED, document.status)
        assertTrue(document.hasValidationReport)
        assertTrue(document.hasXml)
        assertThrows(QuoteServiceException::class.java) { QuoteViewTranslator.toDocument(view.copy(mark = "FINAL")) }
        assertThrows(QuoteServiceException::class.java) { QuoteViewTranslator.toDocument(view.copy(status = "LOST")) }
    }

    @Test
    fun `the short report form reads valid and errors`() {
        assertEquals(QuoteValidationReport(true), QuoteViewTranslator.toValidationReport("""{"syntax":"UBL","valid":true,"checks":["XSD"]}"""))
        assertEquals(
            QuoteValidationReport(false, listOf("BR-Q-01 fehlt")),
            QuoteViewTranslator.toValidationReport("""{"valid":false,"errors":["BR-Q-01 fehlt"]}"""),
        )
        assertEquals(QuoteValidationReport(false), QuoteViewTranslator.toValidationReport("""{"valid":false}"""))
    }

    @Test
    fun `the full report lists errors with their rule and ignores warnings`() {
        val invalid = """{"status":"INVALID","summary":"Prüfung fehlgeschlagen","details":[
            {"severity":"ERROR","message":"Pflichtfeld fehlt","ruleId":"BR-Q-02"},
            {"severity":"WARNING","message":"Hinweis"},
            {"severity":"fatal","message":"Kaputt"}]}"""
        assertEquals(
            QuoteValidationReport(false, listOf("BR-Q-02: Pflichtfeld fehlt", "Kaputt")),
            QuoteViewTranslator.toValidationReport(invalid),
        )
        assertEquals(
            QuoteValidationReport(false, listOf("Nur die Zusammenfassung")),
            QuoteViewTranslator.toValidationReport("""{"status":"ERROR","summary":"Nur die Zusammenfassung"}"""),
        )
        assertEquals(QuoteValidationReport(false), QuoteViewTranslator.toValidationReport("""{"status":"ERROR"}"""))
        assertEquals(QuoteValidationReport(true), QuoteViewTranslator.toValidationReport("""{"status":"VALID","details":[{"severity":"ERROR","message":"x"}]}"""))
    }

    @Test
    fun `an unreadable report is reported instead of guessed`() {
        assertThrows(QuoteServiceException::class.java) { QuoteViewTranslator.toValidationReport("not json") }
        assertThrows(QuoteServiceException::class.java) { QuoteViewTranslator.toValidationReport("""{"checks":[]}""") }
        assertThrows(QuoteServiceException::class.java) { QuoteViewTranslator.toValidationReport("""{"valid":"yes"}""") }
        assertThrows(QuoteServiceException::class.java) { QuoteViewTranslator.toValidationReport("") }
    }

    @Test
    fun `an invoice order is translated with its status, dates and answer`() {
        val view = QuoteInvoiceOrderView(
            orderId = "o-1",
            status = "CREATED",
            requestedAt = Instant.EPOCH,
            deliveryDate = LocalDate.of(2026, 9, 30),
            servicePeriodFrom = LocalDate.of(2026, 9, 1),
            servicePeriodTo = LocalDate.of(2026, 9, 30),
            invoiceId = 5L,
            invoiceNumber = "RE-5",
            rejectionReason = "kein Nummernkreis",
        )

        val order = QuoteViewTranslator.toDetail(QuoteFixtures.detail(invoiceOrder = view)).invoiceOrder!!

        assertEquals("o-1", order.orderId)
        assertEquals(QuoteInvoiceOrderStatus.CREATED, order.status)
        assertEquals(Instant.EPOCH, order.requestedAt)
        assertEquals(LocalDate.of(2026, 9, 30), order.deliveryDate)
        assertEquals(LocalDate.of(2026, 9, 1), order.servicePeriodFrom)
        assertEquals(LocalDate.of(2026, 9, 30), order.servicePeriodTo)
        assertEquals(5L, order.invoiceId)
        assertEquals("RE-5", order.invoiceNumber)
        assertEquals("kein Nummernkreis", order.rejectionReason)
    }

    @Test
    fun `a detail without an order has none and an unknown order status is reported`() {
        assertNull(QuoteViewTranslator.toDetail(QuoteFixtures.detail()).invoiceOrder)
        val unknown = QuoteInvoiceOrderView("o-1", "LOST", Instant.EPOCH)

        org.junit.jupiter.api.Assertions.assertThrows(QuoteServiceException::class.java) {
            QuoteViewTranslator.toDetail(QuoteFixtures.detail(invoiceOrder = unknown))
        }
    }
}
