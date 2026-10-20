package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.ui.component.LegendRow
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.openLegendDialog
import de.littletrotter.ui.component.tileColumnHeader

/** Status column header of the offer list: the symbol opens the legend, as in the invoice list. */
internal fun buildQuoteStatusColumnHeader(tr: (String) -> String, onOpen: () -> Unit): Component =
    tileColumnHeader(
        icon = VaadinIcon.FLAG,
        titleWord = tr("quote.column.status"),
        ariaLabel = "${tr("quote.column.status")} — ${tr("quote.status.legend.open")}",
        onClick = onOpen,
    )

/** One row per offer status, each shown as the chip the list uses, with a one-sentence explanation. */
internal fun openQuoteStatusLegend(tr: (String) -> String) {
    openLegendDialog(
        title = tr("quote.status.legend.title"),
        closeLabel = tr("app.close"),
        rows = QuoteStatus.known.map { status ->
            LegendRow(
                tile = StatusChip(tr("quote.status.${status.name}"), QuoteLabels.statusTone(status)),
                sentence = tr("quote.status.legend.${status.name}.sentence"),
                word = tr("quote.status.${status.name}"),
            )
        },
    )
}
