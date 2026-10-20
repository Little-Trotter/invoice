package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * Sits at the right edge, under the amount column, as the totals do on the printed document.
 * Net and VAT per tax rate as small right-aligned rows, then, after a hairline, the gross amount
 * in large type; a grey hint says when the customer sees gross prices. All amounts use tabular
 * figures so the digits line up from row to row.
 */
internal class QuoteTotalsPanel(private val host: TranslationProvider) : Div() {

    private val labels = QuoteLabels(host)

    init {
        addClassName("quote-totals")
    }

    fun render(detail: QuoteDetail) {
        removeAll()
        val totals = detail.totals
        add(line(host.tr("quote.totals.net"), labels.money(totals.net)))
        totals.groups.forEach { group ->
            val rate = labels.number(group.rate)
            add(line(host.tr("quote.totals.vatGroup", rate, labels.money(group.base)), labels.money(group.vat)))
        }
        add(Div().apply { addClassName("quote-totals__rule") })
        add(
            Div(
                Span(host.tr("quote.totals.gross")).apply { addClassName("quote-totals__gross-label") },
                Span(labels.money(totals.gross)).apply { addClassName("quote-totals__gross-amount") },
            ).apply { addClassName("quote-totals__gross") },
        )
        if (detail.showsGrossPrices) {
            add(Span(host.tr("quote.totals.grossHint")).apply { addClassName("quote-totals__hint") })
        }
    }

    private fun line(label: String, value: String): Div =
        Div(
            Span(label).apply { addClassName("quote-totals__label") },
            Span(value).apply { addClassName("quote-totals__value") },
        ).apply { addClassName("quote-totals__line") }
}
