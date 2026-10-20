package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.shared.GermanTime
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.lifecycleHeroClass
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.chFact
import de.littletrotter.ui.view.invoice.chFactRail
import de.littletrotter.ui.view.invoice.chLeadFact
import de.littletrotter.ui.view.mail.MailJournalView
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val ATTR_TITLE = "title"
private const val CH_FACT = "ch-fact"
private const val CH_FACT_LEAD = "ch-fact--lead"

private val SENT_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/**
 * Header of the offer detail, built like the invoice cockpit so both pages read the same way: the
 * first row carries back arrow, offer number, status chip and the gross amount; the second names the
 * recipient and holds the action bar, the forward action rightmost; the third is a fact rail whose
 * lead fact says what matters for the current status (validity, dispatch, answer, running copy) and
 * whose plain facts name the recipient type and country. The status colours the left edge, so the
 * state reads before any text is read.
 */
internal class QuoteCockpitHero private constructor(
    private val host: TranslationProvider,
    internal val actions: QuoteDetailActions?,
    private val time: TimeSource?,
    private val onBack: () -> Unit,
) : Div() {

    /** Detail mode: the hero carries the status-dependent actions. */
    constructor(host: TranslationProvider, ports: QuoteLifecyclePorts, documents: QuoteDocumentPorts, time: TimeSource, onChanged: () -> Unit) :
        this(host, QuoteDetailActions(host, ports, documents, time, onChanged), time, { UI.getCurrent().navigate(QuoteRoutes.LIST) })

    internal val title = Span()
    internal val chips = Div()

    /** Editor mode: the slot where the editor places its lifecycle action, in the detail view's action position. */
    internal val editorActions = Div().apply { addClassNames("quote-actions", "ch-actions") }

    private val labels = QuoteLabels(host)

    init {
        setWidthFull()
        title.addClassName("ch-number")
        chips.addClassName("quote-status-chips")
    }

    /** Detail mode: header, recipient with the actions the status and permissions allow, and the fact rail. */
    fun render(
        detail: QuoteDetail,
        mayEdit: Boolean,
        maySend: Boolean,
        mayRequestInvoice: Boolean,
        export: Component? = null,
        lastSentAt: Instant? = null,
        mayOpenJournal: Boolean = false,
    ) {
        removeAll()
        className = "cockpit-hero ${heroClass(detail.status)}"
        title.text = host.tr("quote.detail.title", detail.requireNumber())
        title.element.setAttribute(ATTR_TITLE, title.text)
        renderChips(detail)
        actions?.render(detail, mayEdit, maySend, mayRequestInvoice, export)
        add(headerRow(detail), partiesRow(detail))
        buildFactRail(detail, lastSentAt, mayOpenJournal)?.let { add(it) }
    }

    private fun headerRow(detail: QuoteDetail): Div {
        val identity = Div(backButton(), title, chips).apply { addClassName("ch-identity") }
        val kpi = Div(
            Span(host.tr("quote.detail.kpi.gross")).apply { addClassName("ch-kpi-label") },
            Span(labels.money(detail.totals.gross)).apply { addClassName("ch-gross") },
        ).apply { addClassName("ch-kpi") }
        return Div(identity, kpi).apply { addClassName("ch-header") }
    }

    /** The offer has no sender of its own, so only the recipient is named; the actions share the row. */
    private fun partiesRow(detail: QuoteDetail): Div {
        val name = detail.party.name
        val recipient = Div(
            Span(host.tr("quote.detail.party.to")).apply { addClassName("ch-party-role") },
            Span(name).apply {
                addClassName("ch-party-name")
                element.setAttribute(ATTR_TITLE, name)
            },
        ).apply { addClassName("ch-party") }
        val row = Div(Div(recipient).apply { addClassName("ch-parties") }).apply { addClassName("ch-footer") }
        row.add(actions ?: editorActions)
        return row
    }

    /** `null` when the status offers neither a lead nor a fact, so no empty strip is drawn. */
    private fun buildFactRail(detail: QuoteDetail, lastSentAt: Instant?, mayOpenJournal: Boolean): Div? {
        val leads = listOfNotNull(buildProgressLead()) + statusLeads(detail, lastSentAt, mayOpenJournal)
        val terminal = detail.status == QuoteStatus.REJECTED || detail.status == QuoteStatus.CANCELLED
        val facts = if (terminal) emptyList() else partyFacts(detail)
        return chFactRail(leads, facts)
    }

    private fun statusLeads(detail: QuoteDetail, lastSentAt: Instant?, mayOpenJournal: Boolean): List<Span> =
        when (detail.status) {
            QuoteStatus.DRAFT, QuoteStatus.REVIEW -> listOf(validUntilFact(detail))
            QuoteStatus.SENT -> listOfNotNull(sentFact(detail, lastSentAt, mayOpenJournal, withJournal = true), validUntilFact(detail))
            QuoteStatus.ACCEPTED -> listOfNotNull(acceptedFact(detail), invoiceFact(detail), sentFact(detail, lastSentAt, mayOpenJournal, withJournal = false))
            QuoteStatus.REJECTED -> listOf(rejectedFact(detail))
            QuoteStatus.CANCELLED -> listOf(chLeadFact(QuoteLabels.statusGlyph(QuoteStatus.CANCELLED), labels.status(QuoteStatus.CANCELLED)))
            QuoteStatus.UNKNOWN -> emptyList()
        }

    private fun valueSpan(text: String): Span = Span(text).apply { addClassName("ch-fact-value") }

    private fun validUntilFact(detail: QuoteDetail): Span =
        chLeadFact(VaadinIcon.CALENDAR, host.tr("quote.detail.rail.validUntil")).also { it.add(valueSpan(labels.date(detail.validUntil))) }

    private fun acceptedFact(detail: QuoteDetail): Span =
        chLeadFact(QuoteLabels.statusGlyph(QuoteStatus.ACCEPTED), host.tr("quote.detail.rail.accepted")).also { fact ->
            detail.acceptedOn?.let { fact.add(valueSpan(labels.date(it))) }
        }

    private fun rejectedFact(detail: QuoteDetail): Span =
        chLeadFact(QuoteLabels.statusGlyph(QuoteStatus.REJECTED), host.tr("quote.detail.rail.rejected")).also { fact ->
            detail.rejectionReason?.takeIf { it.isNotBlank() }?.let { fact.add(valueSpan(it)) }
        }

    /** "Rechnung <number>", linking to the invoice when the order is answered with one; `null` while there is none. */
    private fun invoiceFact(detail: QuoteDetail): Span? {
        val order = detail.invoiceOrder
        val number = detail.invoiceNumber ?: order?.invoiceNumber ?: return null
        val invoiceId = order?.invoiceId
        return chLeadFact(ActionIcons.INVOICE, host.tr("quote.detail.rail.invoice")).also { fact ->
            fact.add(
                if (invoiceId == null) {
                    valueSpan(number)
                } else {
                    RouterLink(number, InvoiceDetailView::class.java, RouteParameters("id", invoiceId.toString()))
                        .apply { addClassName("ch-fact-link") }
                },
            )
        }
    }

    /** When the offer last went out, with the way into the dispatch history for those who may open it. */
    private fun sentFact(detail: QuoteDetail, sentAt: Instant?, mayOpenJournal: Boolean, withJournal: Boolean): Span? {
        sentAt ?: return null
        val fact = chLeadFact(ActionIcons.SEND_MAIL, host.tr("quote.detail.rail.sent"))
        fact.add(valueSpan(SENT_AT_FORMAT.format(sentAt.atZone(GermanTime.ZONE))))
        if (withJournal && mayOpenJournal) {
            fact.add(
                RouterLink(host.tr("quote.detail.rail.journal"), MailJournalView::class.java).apply {
                    setQueryParameters(QueryParameters.simple(mapOf("reference" to detail.requireNumber())))
                    addClassName("ch-fact-link")
                    setId("quote-mail-history-journal")
                },
            )
        }
        return fact
    }

    /** The running status text of a dispatch copy being made; its button stays in place, disabled. */
    private fun buildProgressLead(): Span? {
        val seconds = actions?.copySecondsInFlight ?: return null
        return Span(Span().apply { addClassName("ch-fact-dot") }, Span(host.tr("quote.send.copyInProgress", seconds))).apply {
            addClassNames(CH_FACT, CH_FACT_LEAD, "ch-fact--progress")
            setId("quote-dispatch-copy-status")
            element.setAttribute("role", "status")
        }
    }

    private fun partyFacts(detail: QuoteDetail): List<Span> {
        val type = detail.party.customerType
        val typeLabel = UiTranslations.trOrNull(host.getComponent(), "quote.detail.rail.customerType.$type") ?: type
        return listOf(
            chFact(host.tr("quote.detail.rail.customerType"), typeLabel),
            chFact(host.tr("quote.detail.rail.country"), detail.party.countryCode),
        )
    }

    private fun renderChips(detail: QuoteDetail) {
        chips.removeAll()
        chips.add(labels.statusChip(detail.status, detail.validUntil, time?.today() ?: detail.validUntil))
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
         * Editor mode: the same header row without actions, because the editor's own save bar carries
         * them. The back arrow runs [onBack] so unsaved edits can be guarded.
         */
        fun editor(host: TranslationProvider, onBack: () -> Unit) = QuoteCockpitHero(host, null, null, onBack)

        /** The invoice cockpit's accent classes, reused so both pages colour their edge alike. */
        fun heroClass(status: QuoteStatus): String = lifecycleHeroClass(QuoteLabels.lifecycleMeaning(status))
    }
}
