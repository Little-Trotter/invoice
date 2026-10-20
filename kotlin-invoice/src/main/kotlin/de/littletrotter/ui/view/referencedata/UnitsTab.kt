package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.i18n.UiTranslations

/**
 * Read-only overview of [InvoiceItemUnit] and the UN/ECE Rec 20 (+ Rec 21) code each one
 * maps to for ZUGFeRD/XRechnung export (BR-CL-23). Units are a fixed enum, not
 * editable master data, so this is a plain static grid rather than a CRUD tab like the
 * other reference-data tabs.
 *
 * [InvoiceItemUnit] has no active flag (it's a fixed enum, no toggle at all), so
 * the header counter is a plain count; the search field filters the in-memory list client-side
 * (label, symbol, UN/ECE code) — trivial to add since [configureGrid] already builds the full list
 * up front.
 */
class UnitsTab : VerticalLayout(), ReferenceDataHeaderActions {

    private val grid = DataTable(InvoiceItemUnit::class.java, false)
    private val allUnits: List<InvoiceItemUnit> = InvoiceItemUnit.entries.sortedBy { it.domain.ordinal }
    private var searchFilter: String = ""
    private lateinit var counterSpan: Span

    init {
        setSizeFull()
        isPadding = false
        isSpacing = false
        add(Paragraph(UiTranslations.tr(this, "referenceData.units.info")))
        configureGrid()
        add(GridWrapper(grid))
        applyFilterToGrid()
    }

    override fun installHeaderActions(actionsSlot: Div) {
        counterSpan = installSearchAndCounter(actionsSlot, UiTranslations.tr(this, "referenceData.units.searchPlaceholder")) { query ->
            searchFilter = query
            applyFilterToGrid()
        }
        applyFilterToGrid()
    }

    private fun configureGrid() {
        grid.setSizeFull()
        grid.addColumn { UiTranslations.tr(this, it.domain.labelKey) }
            .setHeader(UiTranslations.tr(this, "referenceData.units.domain"))
            .asCompactColumn()
            .isSortable = true
        grid.addColumn { UiTranslations.tr(this, it.labelKey) }
            .setHeader(UiTranslations.tr(this, "referenceData.units.label"))
            .setSortable(true)
            .asTextColumn(TextColumnWidth.MEDIUM)
        grid.addColumn(InvoiceItemUnit::symbol)
            .setHeader(UiTranslations.tr(this, "referenceData.units.symbol"))
            .asCompactColumn()
        grid.addColumn(InvoiceItemUnit::uneceCode)
            .setHeader(UiTranslations.tr(this, "referenceData.units.uneceCode"))
            .asCompactColumn()
            .isSortable = true
    }

    private fun applyFilterToGrid() {
        val filter = searchFilter.trim().lowercase()
        val visible = if (filter.isBlank()) allUnits else allUnits.filter { unit ->
            UiTranslations.tr(this, unit.labelKey).lowercase().contains(filter) ||
                unit.symbol.lowercase().contains(filter) ||
                unit.uneceCode.lowercase().contains(filter)
        }
        grid.setItems(visible)
        if (::counterSpan.isInitialized) counterSpan.text = entryCounterText(visible.size.toLong())
    }
}
