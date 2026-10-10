package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.ActionBar
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.component.grid.Grid
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.view.AbstractCrudView

/**
 * Generic CRUD tab base for reference data entities. Extracts boilerplate:
 * - Grid setup and data provider creation pattern
 * - Toolbar with New button and optional custom controls
 * - Search field and entry counter integration
 * - Common refresh pattern with filtering and counts
 *
 * Subclasses must implement:
 * - [configureGrid] to set up grid columns and form fields
 * - [getSearchPlaceholder] for the search field placeholder text
 * - [getValidationInfoKey] for the info paragraph i18n key
 */
abstract class AbstractReferencedataCrudTab<E : Any, F>(
    protected val grid: Grid<E>,
) : AbstractCrudView<E, F>(), ReferenceDataHeaderActions {

    protected var searchFilter: String = ""
    protected lateinit var counterSpan: Span
    protected var totalCount: Long = 0
    protected var inactiveCount: Long = 0

    /**
     * Initialize the tab with common structure: info paragraph, grid configuration, and toolbar.
     * Call this from the subclass's init block.
     */
    protected fun initializeTab() {
        setSizeFull()
        isPadding = false
        isSpacing = false
        getValidationInfoKey()?.let { add(Paragraph(tr(it))) }
        configureGrid()
        add(buildContent())
    }

    /**
     * Subclasses must configure the grid columns and form fields here.
     */
    protected abstract fun configureGrid()

    /**
     * Return the i18n key for the validation info paragraph shown at the top of the tab, or null
     * for screens that show none (UI-3: B2bProfilesTab, which does not fit the base).
     */
    protected abstract fun getValidationInfoKey(): String?

    /**
     * Return the placeholder text for the search field. Override in subclasses.
     */
    protected abstract fun getSearchPlaceholder(): String

    /**
     * Build the toolbar. Override to add custom controls (e.g., filter checkboxes).
     * Default implementation includes only the New button.
     */
    protected open fun buildToolbar(): HorizontalLayout =
        ActionBar(AppButtons.primary(tr("referenceData.new"), ActionIcons.ADD) { openEditor(null) })

    /**
     * Build the main content layout: toolbar on top, grid below.
     */
    protected fun buildContent(): VerticalLayout {
        return VerticalLayout(buildToolbar(), GridWrapper(grid)).also {
            it.setSizeFull()
            it.isPadding = false
        }
    }

    /**
     * Install the search field and entry counter in the header actions slot.
     */
    override fun installHeaderActions(actionsSlot: Div) {
        counterSpan = installSearchAndCounter(actionsSlot, tr(getSearchPlaceholder())) { query ->
            searchFilter = query
            refreshGridData()
        }
        counterSpan.text = entryCounterText(totalCount, inactiveCount)
    }

    /**
     * Helper to refresh the grid with count functions and data provider creation.
     * Handles filter normalization, counting (total and inactive), data provider setup,
     * and counter span update in one call.
     */
    protected fun refreshGridWithCounts(
        countTotal: (String?) -> Long,
        countInactive: (String?) -> Long,
        createProvider: (String?) -> DataProvider<E, Void>,
    ) {
        val filter = searchFilter.lowercase()
        totalCount = countTotal(filter.blankToNull())
        inactiveCount = countInactive(filter.blankToNull())
        grid.dataProvider = createProvider(filter.blankToNull())
        if (::counterSpan.isInitialized) counterSpan.text = entryCounterText(totalCount, inactiveCount)
    }
}
