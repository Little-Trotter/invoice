package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.application.quote.port.inbound.QuoteAvailabilityQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.GridScrollBox
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.inject.Inject
import java.time.Instant

/**
 * One offer in full, laid out like the invoice cockpit: the title row with chips and actions above
 * five tabs in the invoice's order: Übersicht, Positionen, Belege, Dokumente and Protokoll. The
 * tabs are built once and only refilled on a reload, so the place in a tab survives an action.
 */
@Route(value = QuoteRoutes.DETAIL, layout = MainLayout::class)
@PageTitle("Angebot")
class QuoteDetailView
@Inject
constructor(
    private val entry: QuoteEntry,
    logPorts: QuoteLogPorts,
    lifecycle: QuoteLifecyclePorts,
    private val documentPorts: QuoteDocumentPorts,
    availability: QuoteAvailabilityQueryPort,
    roles: AppRoleQueryPort,
    time: TimeSource,
) : VerticalLayout(), BeforeEnterObserver, TranslationProvider {

    internal val hero = QuoteCockpitHero(this, lifecycle, documentPorts, time) { reload() }
    internal val orderStatus = QuoteInvoiceOrderStatusPanel(this)
    internal val overview = QuoteOverviewPanel(this)
    internal val items = QuoteItemsGrid(this)
    internal val itemsTotals = QuoteTotalsPanel(this)
    internal val attachments = QuoteAttachmentsReadPanel(this, documentPorts.attachments)
    internal val documents = QuoteDocumentsPanel(this, documentPorts) { renderHero() }
    internal val log = QuoteProtocolPanel(this, logPorts.query, logPorts.addNote)
    internal val tabs = TabSheet()
    internal val editItemsButton = AppButtons.tertiary(tr("quote.action.EDIT"), ActionIcons.EDIT) {
        UI.getCurrent().navigate(QuoteRoutes.edit(quoteNumber), QuoteRoutes.itemsTab())
    }.apply { setId("quote-items-edit") }
    private val editItemsBar = ActionBar(editItemsButton).apply { addClassName("item-toolbar-row") }

    private val access = QuoteAccess(availability, roles)
    private val attachmentPorts = QuoteItemAttachmentPorts(documentPorts.attachments)
    private lateinit var quoteId: String
    private lateinit var quoteNumber: String
    private var shown: QuoteDetail? = null

    /** Latest successful dispatch from the protocol; kept so poll-driven hero refreshes do not reload the log. */
    private var lastSentAt: Instant? = null

    override fun getComponent(): Component = this

    init {
        isPadding = true
        tabs.setWidthFull()
        tabs.add(tr("quote.detail.tab.overview"), overview)
        tabs.add(tr("quote.detail.tab.items"), itemsTab())
        tabs.add(tr("quote.detail.tab.attachments"), attachments)
        tabs.add(tr("quote.detail.tab.documents"), documents)
        tabs.add(tr("quote.detail.tab.log"), log)
        items.onAttachments = { item -> QuoteItemAttachmentsDialog(this, quoteId, item, attachmentPorts) { }.open() }
        add(hero, orderStatus, tabs)
    }

    private fun itemsTab(): Component = VerticalLayout(editItemsBar, GridScrollBox(items.grid), itemsTotals).apply {
        isPadding = false
        setHorizontalComponentAlignment(Alignment.END, itemsTotals)
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        if (!access.admit(event, AppPermission.QUOTE_VIEW)) return
        entry.enter(event, QuoteRoutes::detail) { detail ->
            quoteId = detail.id
            render(detail)
            true
        }
    }

    private fun reload() {
        load()
    }

    private fun load(): Boolean {
        val detail = guarded { entry.detail.find(quoteId) } ?: return false
        render(detail)
        return true
    }

    /** Also runs on each poll tick of the documents tab, so the dispatch-copy status line and the send gate stay current. */
    private fun renderHero() {
        val detail = shown ?: return
        hero.render(
            detail, access.mayEdit(), access.maySend(), access.mayRequestInvoice(),
            buildQuoteWorkbookLink(this, documentPorts.workbook, detail),
            lastSentAt, access.mayViewMailJournal(),
        )
    }

    private fun render(detail: QuoteDetail) {
        quoteNumber = detail.requireNumber()
        shown = detail
        lastSentAt = log.show(detail.id, access.mayEdit())
            .filter { it.kind == QuoteLogKind.SEND }.maxOfOrNull { it.occurredAt }
        renderHero()
        orderStatus.render(detail)
        // Same rule as the hero's edit action, so the shortcuts never offer what the header would not.
        val mayEdit = QuoteAction.EDIT in QuoteActionRules.available(detail, access.mayEdit())
        overview.render(detail, if (mayEdit) ({ UI.getCurrent().navigate(QuoteRoutes.edit(quoteNumber)) }) else null)
        editItemsBar.isVisible = mayEdit
        items.render(detail.items, guarded { documentPorts.attachments.attachments(detail.id) } ?: emptyList())
        itemsTotals.render(detail)
        attachments.bind(detail.id)
        documents.bind(detail.id, detail.status, access.mayEdit())
    }
}
