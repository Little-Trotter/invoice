package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.router.Route
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import jakarta.inject.Inject

private const val HEADER_TAB = 0
private const val ITEMS_TAB = 1

/**
 * Edits an offer in draft or review in four tabs, in the invoice's order: Kopf, Positionen,
 * E-Angebot and Belege. The header is always the first tab and selected on entry; the query
 * parameter `tab=items` opens the positions instead. The E-Angebot caption counts the mandatory
 * references still missing. Any other status is read-only, so the view shows a notice with a link
 * to the detail instead of the tabs.
 *
 * The sticky bar saves the header with its texts and references. Lines and attachments are stored
 * the moment they are changed (dialog, inline edit, deletion, upload), so they never count as an
 * unsaved edit. The offer component stays the authority: a refusal is shown as a notification and
 * nothing is lost on screen.
 */
@Route(value = QuoteRoutes.EDIT, layout = MainLayout::class)
@PageTitle("Angebot bearbeiten")
class QuoteEditorView
@Inject
constructor(
    private val queries: QuoteEditorQueries,
    commands: QuoteEditorCommands,
    availability: QuoteAvailability,
    roles: AppRoleProvider,
) : VerticalLayout(), BeforeEnterObserver, TranslationProvider {

    internal val hero = QuoteCockpitHero.editor(this) { leave() }
    internal val notice = Div()
    internal val tabs = TabSheet()
    internal val references = QuoteReferencesTab(this) {
        header.refreshChips()
        refreshEquoteCaption()
        refreshDirty()
    }
    internal val equoteOpen = Span().apply { addClassName("quote-tab-open") }
    internal val header: QuoteHeaderTab = QuoteHeaderTab(this, { references.values() }) { refreshDirty() }
    internal val items = QuoteItemsTab(
        this,
        QuoteItemsTabPorts(queries.detail, queries.articles, commands.editItems, QuoteItemAttachmentPorts(queries.attachments, commands.attach, commands.assignAttachment, queries.library, commands.attachLibrary)),
    ) { onItemsSaved(it) }
    internal val attachments = QuoteAttachmentsTab(
        this,
        QuoteAttachmentsTabPorts(queries.attachments, commands.attach, commands.removeAttachment, commands.assignAttachment, queries.library, commands.attachLibrary),
    )
    internal val saveButton = AppButtons.primary(tr("app.save")) { save() }
    internal val doneButton = AppButtons.secondary(tr("quote.editor.done")) { leave() }
    internal val dirtyDot = Span("●").apply {
        addClassName("entry-dirty-dot")
        isVisible = false
    }
    internal val dirtyLabel = Span(tr("quote.editor.dirty")).apply {
        addClassName("lt-actionbar-sticky__label")
        isVisible = false
    }
    internal val saveBar = Div()

    private val editHeader = commands.editHeader
    private val access = QuoteAccess(availability, roles)
    private lateinit var quoteId: String

    override fun getComponent(): Component = this

    init {
        isPadding = true
        tabs.setWidthFull()
        saveButton.isEnabled = false
        tabs.add(tr("quote.editor.tab.header"), header)
        tabs.add(tr("quote.editor.tab.items"), items)
        tabs.add(equoteCaption(), references)
        tabs.add(tr("quote.editor.tab.attachments"), attachments)
        saveBar.addClassName("lt-actionbar-sticky")
        saveBar.add(Div(dirtyDot, dirtyLabel).apply { addClassName("lt-actionbar-sticky__state") }, Div(doneButton, saveButton).apply { addClassName("lt-actionbar-sticky__actions") })
        // The paperclip counts and the attachment choices must reflect what the other tab changed.
        tabs.addSelectedChangeListener {
            items.refreshAttachments()
            attachments.bind(quoteId, items.currentItems())
        }
        add(hero, notice, tabs, saveBar)
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        if (!access.admit(event, AppPermission.QUOTE_EDIT)) return
        event.enterQuote {
            quoteId = it
            val loaded = load()
            if (loaded) {
                tabs.selectedIndex = if (event.location.queryParameters.parameters[QuoteRoutes.TAB_PARAM]?.contains(QuoteRoutes.TAB_ITEMS) == true) ITEMS_TAB else HEADER_TAB
            }
            loaded
        }
    }

    private fun equoteCaption(): Component = HorizontalLayout(Span(tr("quote.editor.tab.equote")), equoteOpen).apply {
        isPadding = false
        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        addClassName("entry-tab-caption")
        addClassName("lt-gap-040")
    }

    /** Mirrors the invoice's E-Rechnung caption: the number of mandatory fields still empty, hidden at zero. */
    private fun refreshEquoteCaption() {
        val missing = references.missingMandatory().size
        equoteOpen.isVisible = missing > 0
        equoteOpen.text = if (missing > 0) "(" + tr("quote.editor.tab.equote.open", missing.toString()) + ")" else ""
    }

    private fun load(): Boolean {
        val detail = guarded { queries.detail.find(quoteId) } ?: return false
        hero.render(detail, mayEdit = false, maySend = false, mayRequestInvoice = false)
        val editable = detail.status == QuoteStatus.DRAFT || detail.status == QuoteStatus.REVIEW
        tabs.isVisible = editable
        saveBar.isVisible = editable
        notice.isVisible = !editable
        if (editable) bind(detail) else showNotice(detail)
        return true
    }

    private fun bind(detail: QuoteDetail) {
        val fields = guarded { queries.headerFields.headerFields(detail.id) } ?: emptyList()
        references.bind(fields, detail.b2bValues)
        refreshEquoteCaption()
        header.bind(detail)
        items.bind(detail)
        attachments.bind(detail.id, detail.items)
        refreshDirty()
    }

    private fun showNotice(detail: QuoteDetail) {
        notice.removeAll()
        notice.add(
            Span(tr("quote.editor.readOnly")),
            Span(" "),
            RouterLink(tr("quote.editor.toDetail"), QuoteDetailView::class.java, RouteParameters(QuoteRoutes.ID_PARAM, detail.id)),
        )
    }

    private fun dirty(): Boolean = header.isDirty()

    private fun refreshDirty() {
        val dirty = dirty()
        saveButton.isEnabled = dirty
        dirtyDot.isVisible = dirty
        dirtyLabel.isVisible = dirty
    }

    /** Leaves for the detail view; with unsaved edits the user decides first, so nothing is lost by a stray click. */
    private fun leave() {
        if (!dirty()) {
            toDetail()
            return
        }
        ConfirmDialog(tr("quote.editor.discard.title"), tr("quote.editor.discard.text"), tr("quote.editor.discard.confirm")) { toDetail() }.also { dialog ->
            dialog.setCancelable(true)
            dialog.setCancelText(tr("quote.editor.discard.keep"))
            dialog.open()
        }
    }

    private fun toDetail() {
        UI.getCurrent().navigate(QuoteRoutes.detail(quoteId))
    }

    private fun onItemsSaved(detail: QuoteDetail) {
        hero.render(detail, mayEdit = false, maySend = false, mayRequestInvoice = false)
        attachments.bind(detail.id, detail.items)
    }

    private fun save() {
        val command = header.toCommand(quoteId, references.values())
        if (command == null) {
            UiNotifications.warning(tr("quote.editor.validUntilRequired"))
            return
        }
        if (storeHeader(command)) load() else refreshDirty()
    }

    private fun storeHeader(command: EditQuoteHeaderCommand): Boolean {
        if (guarded { editHeader.editHeader(command) } == null) return false
        header.markSaved()
        UiNotifications.confirmOn(saveButton)
        if (references.missingMandatory().isNotEmpty()) UiNotifications.warning(tr("quote.editor.mandatoryMissing"))
        return true
    }
}
