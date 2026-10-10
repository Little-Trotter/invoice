package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout

/**
 * One row of an [openLegendDialog] popover: [tile] identifies the state visually — the same tile
 * component the column's own cells render, so the legend and the grid never drift apart — and
 * [sentence] spells it out in one sentence. [word] is the state's visible name for a tile that
 * carries it only as `aria-label` (a glyph tile); a written chip already shows it and passes none. The "tile + word + one sentence" idiom was
 * introduced for the Validierung column's legend.
 */
data class LegendRow(val tile: Component, val sentence: String, val word: String? = null)

/**
 * PO follow-up (2026-09-17) — generalises the original `openValidationLegend` (which
 * only ever served the Validierung column) into the one dialog builder every narrowed tile
 * column's header button ([tileColumnHeader]) opens: a titled, Esc-closable popover listing [rows]
 * in the order given (the caller decides — lifecycle order, not alphabetical).
 */
fun openLegendDialog(title: String, closeLabel: String, rows: List<LegendRow>) {
    val dialog = Dialog()
    dialog.headerTitle = title
    dialog.isCloseOnEsc = true

    val list = VerticalLayout().apply {
        isPadding = false
        isSpacing = true
    }
    rows.forEach { row ->
        list.add(
            HorizontalLayout(*listOfNotNull(
                row.tile,
                row.word?.let { Span(it).apply { addClassName("legend-word") } },
                Span(row.sentence),
            ).toTypedArray()).apply {
                isPadding = false
                isSpacing = true
                defaultVerticalComponentAlignment = Alignment.CENTER
            },
        )
    }
    dialog.add(list)
    dialog.footer.add(Button(closeLabel) { dialog.close() })
    dialog.open()
}
