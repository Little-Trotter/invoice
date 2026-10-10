package de.littletrotter.ui.view.referencedata

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.ui.i18n.UiTranslations

/**
 * Implemented by reference-data tabs that populate the shared
 * [ReferenceDataSubTabHeader]'s [ReferenceDataSubTabHeader.actionsSlot] themselves (a search
 * field and/or an entry counter). `ReferenceDataView.buildTabPanel` (private, so not linkable
 * from here) calls [installHeaderActions] exactly once, right after constructing the tab, passing
 * the header's own slot.
 *
 * A free-standing interface — not folded into [de.littletrotter.ui.view.AbstractCrudView] —
 * because [UnitsTab] wants the identical header wiring without being a CRUD view at all, while
 * [ValidationSettingsTab] deliberately opts out (see its own KDoc: three fixed rows, a counter
 * would be constant noise). Every CRUD tab implements it via [installSearchAndCounter] below.
 */
fun interface ReferenceDataHeaderActions {
    fun installHeaderActions(actionsSlot: Div)
}

/**
 * The actual search field + entry counter, factored out once so every reference-data
 * tab shares the identical header toolbar instead of ten hand-rolled copies. [onSearch] fires with
 * the trimmed query on every value change; callers re-run their own existing filter/refresh logic
 * there (server-side `filterText` for the lazy-paginated tabs, an in-memory list filter for
 * [UnitsTab]). The returned [Span] is the counter — set its text via [entryCounterText] whenever
 * the visible row count changes (initial load, a search, or a save/delete).
 */
fun installSearchAndCounter(
    actionsSlot: Div,
    searchPlaceholder: String,
    onSearch: (String) -> Unit,
): Span {
    val search = TextField().apply {
        placeholder = searchPlaceholder
        isClearButtonVisible = true
        width = "16rem"
        addValueChangeListener { onSearch(value?.trim().orEmpty()) }
    }
    val counter = Span().apply { addClassNames("lt-text-secondary", "lt-nowrap") }
    actionsSlot.removeAll()
    actionsSlot.add(search, counter)
    actionsSlot.addClassName("lt-header-actions-slot")
    return counter
}

/**
 * "N Einträge" — or "N Einträge, M inaktiv" once [inactiveCount] is known. Pass `null` for
 * entities without an active flag (plain count); pass the actual count — including `0` — for
 * entities that have one, so the number is always trustworthy rather than merely present when
 * non-zero.
 */
fun entryCounterText(total: Long, inactiveCount: Long? = null): String =
    if (inactiveCount != null)
        UiTranslations.tr("referenceData.counter.withInactive", total, inactiveCount)
    else
        UiTranslations.tr("referenceData.counter.total", total)
