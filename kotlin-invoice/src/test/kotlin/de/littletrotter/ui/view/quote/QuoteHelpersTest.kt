package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.ui.component.ChipTone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class QuoteHelpersTest {

    @Test
    fun `route constants equal the route annotations of the views`() {
        assertEquals(QuoteRoutes.LIST, routeOf(QuoteListView::class.java))
        assertEquals(QuoteRoutes.DETAIL, routeOf(QuoteDetailView::class.java))
        assertEquals(QuoteRoutes.EDIT, routeOf(QuoteEditorView::class.java))
        assertEquals("quotes/AN-2026-0001", QuoteRoutes.detail("AN-2026-0001"))
        assertEquals("quotes/AN-2026-0001/edit", QuoteRoutes.edit("AN-2026-0001"))
        listOf(QuoteListView::class.java, QuoteDetailView::class.java, QuoteEditorView::class.java)
            .forEach { assertTrue(it.getAnnotation(PageTitle::class.java) != null) }
    }

    private fun routeOf(view: Class<out Component>): String = view.getAnnotation(Route::class.java).value

    @Test
    fun `every status has a chip tone`() {
        assertEquals(
            listOf(
                ChipTone.NEUTRAL, ChipTone.REVIEW, ChipTone.ACCENT, ChipTone.OK, ChipTone.CANCELLATION, ChipTone.ERROR,
            ),
            QuoteStatus.known.map { QuoteLabels.statusTone(it) },
        )
    }

    @Test
    fun `an unknown status reads neutral with its own question-mark symbol`() {
        assertEquals(ChipTone.NEUTRAL, QuoteLabels.statusTone(QuoteStatus.UNKNOWN))
        assertEquals(com.vaadin.flow.component.icon.VaadinIcon.QUESTION_CIRCLE_O, QuoteLabels.statusGlyph(QuoteStatus.UNKNOWN))
        assertEquals(null, QuoteActionRules.requestMark(QuoteStatus.UNKNOWN))
    }

    @Test
    fun `every document status has a chip tone`() {
        val expected = mapOf(
            QuoteDocumentStatus.REQUESTED to ChipTone.ACCENT,
            QuoteDocumentStatus.COMPLETED to ChipTone.OK,
            QuoteDocumentStatus.FAILED to ChipTone.ERROR,
        )
        assertEquals(QuoteDocumentStatus.entries.toSet(), expected.keys)
        QuoteDocumentStatus.entries.forEach { assertEquals(expected.getValue(it), QuoteLabels.documentStatusTone(it)) }
    }

    @Test
    fun `a detail line converts to an input keeping identity and article link`() {
        val input = quoteItem(2).toInput()

        assertEquals("i-2", input.id)
        assertEquals("a-1", input.articleId)
        assertEquals("A-1", input.articleNumber)
        assertEquals("K-77", input.customerReference)
        assertEquals("Kundenwerk", input.customerArticleName)
        assertEquals(BigDecimal("2.50"), input.quantity)
        assertEquals(InvoiceItemCategory.SERVICE, input.category)
        assertEquals(InvoiceItemUnit.HOUR, input.unit)
        assertEquals(Money.of("100.00"), input.unitPrice)
        assertEquals(VatCategory.STANDARD, input.vatCategory)
        assertEquals(BigDecimal("19.00"), input.vatRate)
        assertEquals("Beratung 2", input.description)
    }

    @Test
    fun `the preview amounts round to cents`() {
        val (net, vat, gross) = previewAmounts(BigDecimal("3"), BigDecimal("3.333"), BigDecimal("19"))

        assertEquals("10.00", net.toPlainString())
        assertEquals("1.90", vat.toPlainString())
        assertEquals("11.90", gross.toPlainString())
    }

    @Test
    fun `document copies per status`() {
        assertEquals(QuoteDocumentMark.DRAFT, QuoteActionRules.requestMark(QuoteStatus.DRAFT))
        assertEquals(QuoteDocumentMark.REVIEW, QuoteActionRules.requestMark(QuoteStatus.REVIEW))
        assertEquals(null, QuoteActionRules.requestMark(QuoteStatus.SENT))
        assertEquals(null, QuoteActionRules.requestMark(QuoteStatus.ACCEPTED))
        assertEquals(null, QuoteActionRules.requestMark(QuoteStatus.REJECTED))
        assertEquals(null, QuoteActionRules.requestMark(QuoteStatus.CANCELLED))
    }

    @Test
    fun `actions per status`() {
        assertEquals(emptyList<QuoteAction>(), QuoteActionRules.available(quoteDetail(QuoteStatus.ACCEPTED), true))
        assertEquals(emptyList<QuoteAction>(), QuoteActionRules.available(quoteDetail(QuoteStatus.REJECTED), true))
        assertEquals(emptyList<QuoteAction>(), QuoteActionRules.available(quoteDetail(QuoteStatus.CANCELLED), true))
        assertEquals(
            listOf(QuoteAction.ACCEPT, QuoteAction.REJECT),
            QuoteActionRules.available(quoteDetail(QuoteStatus.SENT), true),
        )
        assertEquals(emptyList<QuoteAction>(), QuoteActionRules.available(quoteDetail(QuoteStatus.REVIEW), false))
        assertEquals(
            listOf(QuoteAction.EDIT, QuoteAction.RETURN_TO_DRAFT, QuoteAction.CANCEL),
            QuoteActionRules.available(quoteDetail(QuoteStatus.REVIEW), true),
        )
    }

    @Test
    fun `a reviewed offer offers the send as its leading action`() {
        val actions = QuoteActionRules.available(quoteDetail(QuoteStatus.REVIEW), true, maySend = true)
        assertEquals(
            listOf(QuoteAction.SEND, QuoteAction.EDIT, QuoteAction.RETURN_TO_DRAFT, QuoteAction.CANCEL),
            actions,
        )
        assertEquals(QuoteAction.SEND, QuoteActionRules.layout(actions).primary)
        assertEquals(QuoteAction.EDIT, QuoteActionRules.layout(actions - QuoteAction.SEND).primary)
        assertEquals(emptyList<QuoteAction>(), QuoteActionRules.available(quoteDetail(QuoteStatus.DRAFT), false, maySend = true))
    }

    @Test
    fun `the invoice order is offered after accepting and only with both rights`() {
        val sent = QuoteActionRules.available(quoteDetail(QuoteStatus.SENT), true, mayRequestInvoice = true)
        assertEquals(listOf(QuoteAction.ACCEPT, QuoteAction.REQUEST_INVOICE, QuoteAction.REJECT), sent)
        assertEquals(listOf(QuoteAction.REQUEST_INVOICE), QuoteActionRules.available(quoteDetail(QuoteStatus.ACCEPTED), true, mayRequestInvoice = true))
        assertEquals(emptyList<QuoteAction>(), QuoteActionRules.available(quoteDetail(QuoteStatus.ACCEPTED), false, mayRequestInvoice = true))
        assertEquals(QuoteAction.REQUEST_INVOICE, QuoteActionRules.layout(listOf(QuoteAction.REQUEST_INVOICE)).primary)
    }

    @Test
    fun `date rules of the invoice order`() {
        val d = java.time.LocalDate.of(2026, 9, 1)
        assertEquals(null, invoiceOrderDatesError(null, null))
        assertEquals(null, invoiceOrderDatesError(d, d.plusDays(1)))
        assertEquals("quote.invoiceOrder.dialog.error.periodBothEnds", invoiceOrderDatesError(null, d))
        assertEquals("quote.invoiceOrder.dialog.error.periodBothEnds", invoiceOrderDatesError(d, null))
        assertEquals("quote.invoiceOrder.dialog.error.periodOrder", invoiceOrderDatesError(d.plusDays(1), d))
    }
}
