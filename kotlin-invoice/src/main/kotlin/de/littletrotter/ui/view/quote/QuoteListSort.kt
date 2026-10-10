package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.quote.port.inbound.QuoteSortDirection
import de.littletrotter.application.quote.port.inbound.QuoteSortField
import de.littletrotter.application.quote.port.inbound.QuoteSummary

/** Grid column keys of the offer list and their mapping to the order the offer component applies. */
internal object QuoteListSort {

    const val KEY_NUMBER = "number"
    const val KEY_CUSTOMER = "customer"
    const val KEY_SUBJECT = "subject"
    const val KEY_STATUS = "status"
    const val KEY_DATE = "date"
    const val KEY_VALID_UNTIL = "validUntil"
    const val KEY_NET = "net"

    /** The order of an untouched list: newest offer first. */
    val DEFAULT: Pair<QuoteSortField, QuoteSortDirection> = QuoteSortField.QUOTE_DATE to QuoteSortDirection.DESC

    /** An unknown key sorts by offer date, like the untouched list. */
    fun fieldForKey(key: String?): QuoteSortField = when (key) {
        KEY_NUMBER -> QuoteSortField.NUMBER
        KEY_CUSTOMER -> QuoteSortField.CUSTOMER
        KEY_SUBJECT -> QuoteSortField.SUBJECT
        KEY_STATUS -> QuoteSortField.STATUS
        KEY_VALID_UNTIL -> QuoteSortField.VALID_UNTIL
        KEY_NET -> QuoteSortField.NET
        else -> QuoteSortField.QUOTE_DATE
    }

    /** The column that carries [field]; the inverse of [fieldForKey]. */
    fun keyForField(field: QuoteSortField): String = when (field) {
        QuoteSortField.NUMBER -> KEY_NUMBER
        QuoteSortField.CUSTOMER -> KEY_CUSTOMER
        QuoteSortField.SUBJECT -> KEY_SUBJECT
        QuoteSortField.STATUS -> KEY_STATUS
        QuoteSortField.VALID_UNTIL -> KEY_VALID_UNTIL
        QuoteSortField.NET -> KEY_NET
        QuoteSortField.QUOTE_DATE -> KEY_DATE
    }

    /** The grid's sort state as an order; no sort at all returns [DEFAULT]. */
    fun fromGrid(orders: List<GridSortOrder<QuoteSummary>>): Pair<QuoteSortField, QuoteSortDirection> {
        val order = orders.firstOrNull() ?: return DEFAULT
        val direction = if (order.direction == SortDirection.ASCENDING) QuoteSortDirection.ASC else QuoteSortDirection.DESC
        return fieldForKey(order.sorted.key) to direction
    }
}
