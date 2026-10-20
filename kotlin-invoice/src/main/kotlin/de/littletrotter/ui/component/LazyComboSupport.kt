package de.littletrotter.ui.component

import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.data.provider.CallbackDataProvider
import com.vaadin.flow.data.provider.DataProvider
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort

/**
 * UI-8a — the one implementation of "type to search a page at a time" for a [ComboBox] backed by
 * a table that can outgrow a single eager page: `findAllRecipients()`/`findAllArticles()` used to
 * load one capped page (60/1000 rows) and, for articles, filter the category client-side on top —
 * silently dropping every entry beyond the cap. See the
 * finding this closes.
 *
 * [fetch]/[count] receive the ComboBox's own typed filter, normalised through [blankToNull] so
 * every caller sees exactly two states — "no filter" (`null`) or "a real filter text" — instead of
 * also having to special-case an empty string the user's backspacing produced.
 *
 * `offset`/`limit` are handed through exactly as the ComboBox's [CallbackDataProvider] query
 * reports them; converting them to a page index (where the backing service is page-indexed) is the
 * caller's job — the same `offset / limit` idiom already used by
 * [de.littletrotter.ui.view.createGridDataProvider] and `NewInvoiceDialog`'s recipient combo.
 *
 * [idGetter], when given, is passed on to [CallbackDataProvider] as its item-id function: a page
 * backed by a `data class` read model (e.g. [RecipientSummaryDto]) then compares/selects by that
 * id instead of full structural equality, so a value set from a detached instance (a grid row) can
 * still select the matching item once the lazy provider has fetched its own copy.
 *
 * Returns the [DataProvider] it just installed — always filtered by [String], regardless of
 * [T] — so a caller (a test driving [fetch]/[count] directly with a chosen offset/limit/filter)
 * has it with its real type, instead of reflecting the field [setItems] stored it in.
 */
internal fun <T : Any> ComboBox<T>.lazyItems(
    count: (filter: String?) -> Int,
    fetch: (filter: String?, offset: Int, limit: Int) -> List<T>,
    idGetter: ((T) -> Any)? = null,
): DataProvider<T, String> {
    val provider = if (idGetter == null) {
        CallbackDataProvider<T, String>(
            { query -> fetch(query.filter.orElse(null).blankToNull(), query.offset, query.limit).stream() },
            { query -> count(query.filter.orElse(null).blankToNull()) },
        )
    } else {
        CallbackDataProvider(
            { query -> fetch(query.filter.orElse(null).blankToNull(), query.offset, query.limit).stream() },
            { query -> count(query.filter.orElse(null).blankToNull()) },
            idGetter,
        )
    }
    setItems(provider)
    return provider
}

/**
 * QOD-3 DuplicatedCode — the recipient combo's own [lazyItems] wiring, shared by
 * [de.littletrotter.ui.view.invoice.InvoiceListView]'s recipientFilter and
 * [de.littletrotter.ui.view.invoice.PrintJobListView]'s customerFilter: both used to duplicate the
 * identical offset→pageIndex conversion and [RecipientQueryPort] calls verbatim. Both views inject
 * a [RecipientQueryPort], so one helper serves both. Compares by [RecipientSummaryDto.id]
 * (see [lazyItems]'s own KDoc on [idGetter]).
 */
internal fun ComboBox<RecipientSummaryDto>.recipientItems(recipientQuery: RecipientQueryPort) {
    lazyItems(
        count = { filter -> recipientQuery.countRecipients(filter).toInt() },
        fetch = { filter, offset, limit ->
            val pageIndex = if (limit == 0) 0 else offset / limit
            recipientQuery.findRecipients(pageIndex, limit, filter)
        },
        idGetter = { it.id },
    )
}
