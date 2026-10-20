package de.littletrotter.ui.navigation

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.Focusable
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.Shortcuts
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.server.Command
import com.vaadin.flow.server.VaadinSession
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * The "Gehe zu …" command palette: type, filter, Enter, land on any navigation target
 * from [NavItem] (including all ten reference-data sub-tabs) in under two seconds.
 * Navigation targets only — no action commands (deliberate scope cut, konzept §6).
 *
 * Opened via [open], called both from the magnifier button in the navbar and from the global
 * Ctrl+K/Cmd+K shortcut registered once in [de.littletrotter.ui.MainLayout]. Pure index/filter/
 * recents logic lives in `CommandPaletteIndex.kt`, not here — this class is UI wiring only:
 * rendering the result list, keyboard navigation (ArrowUp/Down/Enter/Esc), the
 * `aria-activedescendant`/`aria-live` accessibility wiring, and focus restore on close.
 */
internal class CommandPaletteDialog(
    private val indexProvider: () -> List<CommandPaletteEntry> = ::buildCommandPaletteIndex,
    private val resolveText: (LabelKey) -> String = ::resolveLabel,
    // Seams for tests: the default performs the real router navigation, a test can
    // substitute a capturing lambda instead of needing a fully CDI-constructed target view.
    private val navigate: (String) -> Unit = { path -> UI.getCurrent()?.navigate(path) },
    // The same seam for a target that is not a Flow route: the mail component's UI
    // is served same-origin under /mail/…, so a full page load is the correct (and only) way there.
    // UI.page is assigned unconditionally in UI's own constructor and getPage() never returns null
    // (same proof StatusPanel's `UI.getCurrent()?.page?.reload()` already documents, flow-server's
    // UI.java) — only the UI.getCurrent() null-check below is a real branch.
) : TranslationProvider {

    private val dlg = AppDialog("", AppDialog.Size.S)
    override fun getComponent(): Component = dlg

    /**
     * The permission gate [de.littletrotter.ui.MainLayout] wires up in `initHeader()`
     * once the injected role provider is available (`commandPalette.itemFilter = { item -> ... }`);
     * defaults to "everything passes" so a palette built without a caller (e.g. in tests) still
     * shows the full index. Re-evaluated on every [renderResults] call rather than baked into a
     * cached index at construction — roles are per-session, and a single long-lived
     * [CommandPaletteDialog] instance must never keep showing/navigating to a target the caller has
     * since lost permission for.
     */
    var itemFilter: (NavItem) -> Boolean = { true }

    private val searchField = TextField().apply {
        setWidthFull()
        valueChangeMode = ValueChangeMode.EAGER
        element.setAttribute("role", "combobox")
        element.setAttribute("aria-expanded", "true")
        element.setAttribute("aria-autocomplete", "list")
    }
    private val resultsContainer = Div().apply {
        addClassName("command-palette-results")
        element.setAttribute("role", "listbox")
    }

    // Screen-reader announcement of the result count on every filter change; visually
    // hidden via the repo's existing `.sr-only` utility (styles.css), not a new one.
    private val statusRegion = Span().apply {
        addClassName("sr-only")
        element.setAttribute("aria-live", "polite")
    }

    /** The result rows currently rendered in [resultsContainer] (group headers excluded), same
     *  order/index as [displayedEntries] — tracked separately from `resultsContainer.children`
     *  (a `Stream`, awkward to index into) purely to drive [updateSelectionHighlight]. */
    private val resultRows = mutableListOf<Div>()
    private var displayedEntries: List<CommandPaletteEntry> = emptyList()
    private var selectedIndex: Int = -1

    /** The component focus returns to when the dialog closes (Esc, outside click, or a
     *  navigating Enter) — set per [open] call; `null` restores nothing (the global keyboard
     *  shortcut has no single well-defined trigger element to return to). */
    private var restoreFocusTarget: Component? = null

    init {
        dlg.headerTitle = tr("nav.palette.title")
        searchField.placeholder = tr("nav.palette.placeholder")
        searchField.element.setAttribute("aria-label", tr("nav.palette.title"))

        searchField.addValueChangeListener { event -> renderResults(event.value ?: "") }

        // Explicit `Command {}` disambiguates against the `ShortcutEventListener` overload — a
        // bare lambda is ambiguous between the two single-abstract-method types.
        Shortcuts.addShortcutListener(dlg, Command { moveSelection(1) }, Key.ARROW_DOWN).listenOn(searchField)
        Shortcuts.addShortcutListener(dlg, Command { moveSelection(-1) }, Key.ARROW_UP).listenOn(searchField)
        Shortcuts.addShortcutListener(dlg, Command { activateSelected() }, Key.ENTER).listenOn(searchField)

        dlg.add(searchField, resultsContainer, statusRegion)
        dlg.isCloseOnEsc = true
        dlg.addOpenedChangeListener { event ->
            if (!event.isOpened) {
                val target = restoreFocusTarget
                restoreFocusTarget = null
                if (target is Focusable<*>) target.focus()
            }
        }
    }

    /** Opens the palette; [returnFocusTo] is the component to restore focus to on close — pass
     *  the triggering button for a click-driven open, leave `null` for the keyboard shortcut. */
    fun open(returnFocusTo: Component? = null) {
        restoreFocusTarget = returnFocusTo
        searchField.value = ""
        renderResults("")
        dlg.open()
        searchField.focus()
    }

    private fun renderResults(query: String) {
        val allEntries = indexProvider().filter { entry -> itemFilter(entry.navItem) }
        val recentRoutePaths = currentRecents()
        displayedEntries = if (query.isBlank()) {
            buildDisplayList(allEntries, recentRoutePaths)
        } else {
            filterCommandPaletteEntries(allEntries, query, resolveText)
        }
        selectedIndex = if (displayedEntries.isEmpty()) -1 else 0

        resultsContainer.removeAll()
        resultRows.clear()

        val recentCount = recentEntryCount(query, allEntries, recentRoutePaths)
        if (recentCount > 0) {
            resultsContainer.add(groupHeader(tr("nav.palette.recentHeader")))
        }

        displayedEntries.forEachIndexed { index, entry -> renderResultRow(query, recentCount, index, entry) }

        if (displayedEntries.isEmpty()) {
            resultsContainer.add(
                Div(Span(tr("nav.palette.noResults"))).apply { addClassName("command-palette-empty") },
            )
        }

        updateSelectionHighlight()
        statusRegion.text = tr("nav.palette.resultCount", displayedEntries.size)
    }

    /** Only entries that are both recent AND still in the index count towards the "recents"
     *  block (buildDisplayList already drops stale/unknown recent route paths the same way). */
    private fun recentEntryCount(query: String, allEntries: List<CommandPaletteEntry>, recentRoutePaths: List<String>): Int =
        if (query.isBlank()) recentRoutePaths.count { path -> allEntries.any { it.routePath == path } } else 0

    private fun renderResultRow(query: String, recentCount: Int, index: Int, entry: CommandPaletteEntry) {
        if (index >= recentCount) {
            val previousEntry = displayedEntries.getOrNull(index - 1)
            val startsNewSection = index == recentCount || previousEntry?.section != entry.section
            if (query.isBlank() && startsNewSection) {
                resultsContainer.add(groupHeader(resolveText(LabelKey.NavSectionKey(entry.section))))
            }
        }
        val row = resultRow(entry, resultRows.size)
        resultRows.add(row)
        resultsContainer.add(row)
    }

    private fun groupHeader(text: String): Div =
        Div(Span(text)).apply {
            addClassName("command-palette-group-header")
            element.setAttribute("role", "presentation")
        }

    private fun resultRow(entry: CommandPaletteEntry, index: Int): Div =
        Div().apply {
            addClassName("command-palette-result")
            element.setAttribute("id", rowId(index))
            element.setAttribute("role", "option")
            add(Span(resolveText(entry.labelKey)).apply { addClassName("command-palette-result-label") })
            entry.contextKey?.let { contextKey ->
                add(Span(resolveText(contextKey)).apply { addClassName("command-palette-result-context") })
            }
            addClickListener { navigateTo(entry) }
        }

    private fun rowId(index: Int): String = "command-palette-result-$index"

    private fun moveSelection(delta: Int) {
        if (displayedEntries.isEmpty()) return
        selectedIndex = (selectedIndex + delta).mod(displayedEntries.size)
        updateSelectionHighlight()
    }

    private fun updateSelectionHighlight() {
        resultRows.forEachIndexed { index, row ->
            val selected = index == selectedIndex
            row.element.classList.set("selected", selected)
            row.element.setAttribute("aria-selected", selected.toString())
        }
        if (selectedIndex in resultRows.indices) {
            searchField.element.setAttribute("aria-activedescendant", rowId(selectedIndex))
        } else {
            searchField.element.removeAttribute("aria-activedescendant")
        }
    }

    private fun activateSelected() {
        val entry = displayedEntries.getOrNull(selectedIndex) ?: return
        navigateTo(entry)
    }

    private fun navigateTo(entry: CommandPaletteEntry) {
        recordVisit(entry.routePath)
        dlg.close()
        navigate(entry.routePath)
    }

    private fun currentRecents(): List<String> =
        VaadinSession.getCurrent()?.getAttribute(CommandPaletteRecents::class.java)?.asList() ?: emptyList()

    private fun recordVisit(routePath: String) {
        val session = VaadinSession.getCurrent() ?: return
        val current = session.getAttribute(CommandPaletteRecents::class.java) ?: CommandPaletteRecents()
        session.setAttribute(CommandPaletteRecents::class.java, current.withVisit(routePath))
    }
}
