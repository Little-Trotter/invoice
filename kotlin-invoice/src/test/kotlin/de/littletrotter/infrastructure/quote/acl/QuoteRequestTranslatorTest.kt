package de.littletrotter.infrastructure.quote.acl

import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.application.quote.port.inbound.QuoteRankingKind
import de.littletrotter.application.quote.port.inbound.QuoteSortDirection
import de.littletrotter.application.quote.port.inbound.QuoteSortField
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatCategory
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("QuoteRequestTranslator (local command -> contract request)")
class QuoteRequestTranslatorTest {

    @Test
    fun `list parameters carry status, text and window`() {
        val params = QuoteRequestTranslator.toListParameters(QuoteListCriteria(QuoteStatus.SENT, "muster", 50, 25))
        assertEquals(listOf("status" to "SENT", "text" to "muster", "sort" to "QUOTE_DATE", "dir" to "DESC", "offset" to "50", "limit" to "25"), params)
    }

    @Test
    fun `default list parameters leave out status and blank text`() {
        val params = QuoteRequestTranslator.toListParameters(QuoteListCriteria(text = "  "))
        assertEquals(listOf("sort" to "QUOTE_DATE", "dir" to "DESC", "offset" to "0", "limit" to "50"), params)
    }

    @Test
    fun `list parameters carry the requested order by enum name`() {
        val params = QuoteRequestTranslator.toListParameters(
            QuoteListCriteria(sortField = QuoteSortField.NET, sortDirection = QuoteSortDirection.ASC),
        )
        assertEquals(listOf("sort" to "NET", "dir" to "ASC", "offset" to "0", "limit" to "50"), params)
    }

    @Test
    fun `log parameters repeat kind in enum order, none for an empty set`() {
        assertEquals(emptyList<Pair<String, String>>(), QuoteRequestTranslator.toLogParameters(emptySet()))
        assertEquals(
            listOf("kind" to "NOTE", "kind" to "SEND"),
            QuoteRequestTranslator.toLogParameters(setOf(QuoteLogKind.SEND, QuoteLogKind.NOTE)),
        )
    }

    @Test
    fun `statistics parameters carry the year and only the chosen narrowing`() {
        assertEquals(listOf("year" to "2026"), QuoteRequestTranslator.toStatisticsParameters(QuoteStatisticsSelection(2026)))
        assertEquals(
            listOf("year" to "2026", "quarter" to "3", "status" to "DRAFT", "customerType" to "BUSINESS"),
            QuoteRequestTranslator.toStatisticsParameters(
                QuoteStatisticsSelection(2026, quarter = 3, status = QuoteStatus.DRAFT, customerType = CustomerType.BUSINESS),
            ),
        )
        assertEquals(
            listOf("year" to "2026", "month" to "7"),
            QuoteRequestTranslator.toStatisticsParameters(QuoteStatisticsSelection(2026, month = 7)),
        )
    }

    @Test
    fun `ranking parameters add kind and limit to the selection`() {
        assertEquals(
            listOf("year" to "2026", "kind" to "CUSTOMER", "limit" to "10"),
            QuoteRequestTranslator.toRankingParameters(QuoteRankingKind.CUSTOMER, QuoteStatisticsSelection(2026), 10),
        )
    }

    @Test
    fun `every price display goes out by name`() {
        QuotePriceDisplay.entries.forEach {
            val request = QuoteRequestTranslator.toHeaderRequest(EditQuoteHeaderCommand("q", LocalDate.of(2026, 11, 1), it))
            assertEquals(it.name, request.priceDisplay)
        }
    }

    @Test
    fun `a header command keeps texts, date and business-term values`() {
        val request = QuoteRequestTranslator.toHeaderRequest(
            EditQuoteHeaderCommand("q", LocalDate.of(2026, 11, 1), QuotePriceDisplay.AUTO, "S", "I", "C", "N", mapOf(10 to "x")),
        )
        assertEquals(LocalDate.of(2026, 11, 1), request.validUntil)
        assertEquals("S", request.subject)
        assertEquals("I", request.introText)
        assertEquals("C", request.closingText)
        assertEquals("N", request.noteText)
        assertEquals(mapOf(10 to "x"), request.b2bValues)
    }

    @Test
    fun `every vat category goes out by name and money as Money`() {
        VatCategory.entries.forEach {
            val line = QuoteRequestTranslator.toItemsRequest(
                listOf(QuoteItemInput("d", BigDecimal("2"), InvoiceItemCategory.UNIT_PRICE, InvoiceItemUnit.SQUARE_METER, Money.of("12.5"), it, BigDecimal("19"), "i-1", "a-1", "AN", "REF", "Kundenrohr")),
            ).lines.single()
            assertEquals(it.name, line.vatCategory)
            assertEquals(Money.of("12.5"), line.unitPrice)
            assertEquals("i-1", line.id)
            assertEquals("a-1", line.articleId)
            assertEquals("AN", line.articleNumber)
            assertEquals("REF", line.customerReference)
            assertEquals("Kundenrohr", line.customerArticleName)
            assertEquals("UNIT_PRICE", line.category)
            assertEquals("SQUARE_METER", line.unit)
        }
    }

    @Test
    fun `an attachment goes out base64 with its kind name`() {
        QuoteAttachmentKind.entries.forEach {
            val request = QuoteRequestTranslator.toAttachRequest(QuoteAttachmentUpload(it, "a.txt", "text/plain", "hi".toByteArray()))
            assertEquals(it.name, request.kind)
            assertEquals("aGk=", request.contentBase64)
            assertEquals("a.txt", request.fileName)
            assertEquals("text/plain", request.mimeType)
        }
    }
}
