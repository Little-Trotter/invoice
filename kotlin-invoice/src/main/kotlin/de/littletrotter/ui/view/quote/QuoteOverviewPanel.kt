package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.Section
import de.littletrotter.ui.component.addressLines
import de.littletrotter.ui.component.overviewAddress
import de.littletrotter.ui.component.overviewCards
import de.littletrotter.ui.component.overviewField
import de.littletrotter.ui.component.overviewFieldGrid
import de.littletrotter.ui.component.overviewLead
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * The "Übersicht" tab: the customer and the offer as two calm blocks (small grey label above the
 * value) and the totals below. A field that is not set is not rendered at all, so the page never
 * shows rows of dashes.
 */
internal class QuoteOverviewPanel(private val host: TranslationProvider) : Div() {

    internal val totals = QuoteTotalsPanel(host)

    private val labels = QuoteLabels(host)

    init {
        setWidthFull()
        addClassName("quote-overview")
    }

    /** [onEdit] is only given when the offer may be edited; the button then opens the editor on the header. */
    fun render(detail: QuoteDetail, onEdit: (() -> Unit)? = null) {
        removeAll()
        totals.render(detail)
        if (onEdit != null) {
            add(
                ActionBar(AppButtons.tertiary(host.tr("quote.action.EDIT"), ActionIcons.EDIT) { onEdit() }.apply {
                    setId("quote-overview-edit")
                }).apply { addClassName("item-toolbar-row") },
            )
        }
        val cards = overviewCards(
            Section(host.tr("quote.overview.customer"), content = customerBlock(detail)),
            Section(host.tr("quote.overview.quote"), content = quoteBlock(detail)),
        )
        add(cards, totals)
    }

    private fun customerBlock(detail: QuoteDetail): Component {
        val party = detail.party
        val address = overviewAddress(addressLines(party.street, party.postalCode, party.city, party.countryCode))
        val meta = overviewFieldGrid()
        field("quote.field.customerNumber", party.customerNumber)?.let(meta::add)
        field("quote.field.vatId", party.vatId)?.let(meta::add)
        field("quote.field.language", party.documentLanguage)?.let(meta::add)
        return Div(*listOfNotNull<Component>(overviewLead(party.name), address, meta).toTypedArray())
    }

    private fun quoteBlock(detail: QuoteDetail): Component {
        val box = overviewFieldGrid()
        field("quote.field.subject", detail.subject, wide = true)?.let(box::add)
        field("quote.field.date", labels.date(detail.quoteDate))?.let(box::add)
        field("quote.field.validUntil", labels.date(detail.validUntil))?.let(box::add)
        field("quote.field.priceDisplay", labels.priceDisplay(detail.priceDisplay))?.let(box::add)
        if (detail.acceptedOn != null) {
            field("quote.field.acceptedOn", labels.date(detail.acceptedOn))?.let(box::add)
            field("quote.field.orderReference", detail.customerOrderReference)?.let(box::add)
        }
        if (detail.rejectionReason != null) {
            field("quote.field.rejectionReason", detail.rejectionReason, wide = true)?.let(box::add)
        }
        field("quote.field.invoice", detail.invoiceNumber)?.let(box::add)
        return box
    }

    private fun field(labelKey: String, value: String?, wide: Boolean = false): Div? =
        overviewField(host.tr(labelKey), value, wide)
}
