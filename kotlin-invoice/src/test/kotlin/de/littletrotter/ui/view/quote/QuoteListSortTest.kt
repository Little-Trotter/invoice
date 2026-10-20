package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.quote.port.inbound.QuoteSortDirection
import de.littletrotter.application.quote.port.inbound.QuoteSortField
import de.littletrotter.application.quote.port.inbound.QuoteSummary
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class QuoteListSortTest {

    @Test
    fun `each column key maps to its sort field and unknown keys to the offer date`() {
        assertEquals(QuoteSortField.NUMBER, QuoteListSort.fieldForKey("number"))
        assertEquals(QuoteSortField.CUSTOMER, QuoteListSort.fieldForKey("customer"))
        assertEquals(QuoteSortField.SUBJECT, QuoteListSort.fieldForKey("subject"))
        assertEquals(QuoteSortField.STATUS, QuoteListSort.fieldForKey("status"))
        assertEquals(QuoteSortField.QUOTE_DATE, QuoteListSort.fieldForKey("date"))
        assertEquals(QuoteSortField.VALID_UNTIL, QuoteListSort.fieldForKey("validUntil"))
        assertEquals(QuoteSortField.NET, QuoteListSort.fieldForKey("net"))
        assertEquals(QuoteSortField.QUOTE_DATE, QuoteListSort.fieldForKey("bogus"))
        assertEquals(QuoteSortField.QUOTE_DATE, QuoteListSort.fieldForKey(null))
    }

    @Test
    fun `every sort field has a column key that maps back to it`() {
        QuoteSortField.entries.forEach {
            assertEquals(it, QuoteListSort.fieldForKey(QuoteListSort.keyForField(it)))
        }
    }

    @Test
    fun `the grid sort state becomes field and direction, none becomes newest first`() {
        val grid = Grid<QuoteSummary>()
        val column = grid.addColumn { it.customerName }.setKey("customer")

        assertEquals(
            QuoteSortField.CUSTOMER to QuoteSortDirection.ASC,
            QuoteListSort.fromGrid(listOf(GridSortOrder(column, SortDirection.ASCENDING))),
        )
        assertEquals(
            QuoteSortField.CUSTOMER to QuoteSortDirection.DESC,
            QuoteListSort.fromGrid(listOf(GridSortOrder(column, SortDirection.DESCENDING))),
        )
        assertEquals(QuoteSortField.QUOTE_DATE to QuoteSortDirection.DESC, QuoteListSort.fromGrid(emptyList()))
    }
}
