package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider

private const val ATTR_TITLE = "title"

/**
 * Title row of the offer detail, built like the invoice cockpit: back arrow, the offer number in
 * large type, the quiet chips for status, answer and expiry, and on the right the action bar. The
 * status colours the left edge, so the state reads before any text is read.
 */
internal class QuoteCockpitHero private constructor(
    private val host: TranslationProvider,
    internal val actions: QuoteDetailActions?,
    private val onBack: () -> Unit,
) : Div() {

    /** Detail mode: the hero carries the status-dependent actions. */
    constructor(host: TranslationProvider, ports: QuoteLifecyclePorts, time: TimeSource, onChanged: () -> Unit) :
        this(host, QuoteDetailActions(host, ports, time, onChanged), { UI.getCurrent().navigate(QuoteRoutes.LIST) })

    internal val title = Span()
    internal val chips = Div()

    private val labels = QuoteLabels(host)

    init {
        setWidthFull()
        title.addClassName("ch-number")
        chips.addClassName("quote-status-chips")
    }

    /** Detail mode: title row with the actions the status and permissions allow. */
    fun render(detail: QuoteDetail, mayEdit: Boolean, maySend: Boolean, mayRequestInvoice: Boolean, export: Component? = null) {
        removeAll()
        className = "cockpit-hero ${heroClass(detail.status)}"
        title.text = host.tr("quote.detail.title", detail.number ?: host.tr("quote.list.noNumber"))
        title.element.setAttribute(ATTR_TITLE, title.text)
        renderChips(detail)
        actions?.render(detail, mayEdit, maySend, mayRequestInvoice, export)
        val identity = Div(backButton(), title, chips).apply { addClassName("ch-identity") }
        add(
            Div(identity).apply { addClassName("ch-header") }.also { header ->
                actions?.let { header.add(it) }
            },
        )
    }

    private fun renderChips(detail: QuoteDetail) {
        chips.removeAll()
        chips.add(labels.statusChip(detail.status))
    }

    private fun backButton(): Button =
        AppButtons.tertiary("", ActionIcons.BACK) { onBack() }.apply {
            addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_SMALL)
            element.setAttribute(ATTR_TITLE, host.tr("quote.detail.back"))
            element.setAttribute(ARIA_LABEL, host.tr("quote.detail.back"))
            addClassName("ch-back-btn")
            setId("quote-detail-back")
        }

    companion object {
        /**
         * Editor mode: the same title row without actions, because the editor's own save bar carries
         * them. The back arrow runs [onBack] so unsaved edits can be guarded.
         */
        fun editor(host: TranslationProvider, onBack: () -> Unit) = QuoteCockpitHero(host, null, onBack)

        /** The invoice cockpit's accent classes, reused so both pages colour their edge alike. */
        fun heroClass(status: QuoteStatus): String = when (status) {
            QuoteStatus.DRAFT -> "cockpit-hero--draft"
            QuoteStatus.REVIEW -> "cockpit-hero--review"
            QuoteStatus.SENT, QuoteStatus.ACCEPTED, QuoteStatus.INVOICED -> "cockpit-hero--sent"
            QuoteStatus.REJECTED, QuoteStatus.CANCELLED -> "cockpit-hero--cancelled"
        }
    }
}
