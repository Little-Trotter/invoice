package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.provider.DataProvider
import de.littletrotter.application.invoice.port.inbound.AuditJournalQueryPort
import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.journalBand
import de.littletrotter.ui.component.showJournalCount
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.journal.JournalCardViewportFit
import de.littletrotter.ui.view.journal.JournalEntryDialog
import de.littletrotter.ui.view.journal.JournalGridCallbacks
import de.littletrotter.ui.view.journal.JournalPanelParts
import de.littletrotter.ui.view.journal.JournalTranslations
import de.littletrotter.ui.view.journal.composer.JournalComposerContext

/**
 * The invoice detail view's read-only "Journal" tab: that invoice's own history from
 * `invoice_audit_journal`, one line per business action ([InvoiceAuditActionLine]), newest first by
 * default — an auditor looks for the latest change first — sortable by
 * "Zeitpunkt". Same self-contained-panel shape as [InvoiceExportPanel] — [render] rebuilds
 * this panel's content for a given invoice id, wired into [InvoiceDetailTabs.buildJournalPanel]
 * the same way [InvoiceExportPanel]'s own `.render(...)` is wired via `refreshExportPanel`.
 *
 * Same reading model as the global [de.littletrotter.ui.view.system.JournalPanel] and the same
 * shared cells ([JournalPanelParts]/[de.littletrotter.ui.view.journal
 * .JournalActionGridColumns]); only the invoice column is dropped — every row here belongs to the
 * invoice already on screen. A `CANCELLATION` business_tx that also touches the OTHER invoice (original
 * ↔ credit note) shows only THIS invoice's own side here (`AuditJournalQueryPort
 * .findActionsByInvoice` scopes the technical-row query by `invoiceId`) — see
 * [de.littletrotter.ui.view.journal.composer.CancellationFamilyComposer]'s own KDoc.
 *
 * Grid pagination is lazy via [DataProvider.fromCallbacks] (same idiom as
 * [de.littletrotter.ui.view.invoice.ReceiptPoolView.refresh]) — this invoice's own history is
 * fetched a page at a time, never loaded in full. The small, batched name lookups a "Was genau"
 * sentence needs (a receipt's file name, a recipient's display name) are resolved by
 * [auditJournalQueryService] itself, never by this panel injecting a domain repository directly.
 *
 * The card fills the viewport down to the bottom edge of the browser window
 * and the grid scrolls inside it (lazy pages, [PAGE_SIZE] rows each) instead of the page growing:
 * the card is a flex column (grid · empty state) whose height is the client-measured
 * `--journal-card-height`, at least its CSS `min-height`. The measurement is one small script
 * registered once per panel instance on attach ([viewportFitRegistrations]), re-run on window
 * resize (rAF-throttled) and whenever the Protokoll tab becomes visible, and unregistered on
 * detach — no polling, no server round trip.
 *
 * Title and entry count live in the grid's own top header row, joined across
 * all columns (one flat band, [buildBand]); the column labels are the second header row beneath.
 * The band is built once in [configureGrid]: the grid instance outlives every [render].
 *
 * [initialSortDirection]/[onSortDirectionChanged] let the direction survive
 * [InvoiceDetailTabs.buildJournalPanel] rebuilding this panel on every data refresh — the panel
 * instance itself is thrown away and rebuilt each time, so its own state cannot carry the choice
 * across that rebuild the way a longer-lived component could.
 */
class InvoiceJournalPanel(
    private val auditJournalQueryService: AuditJournalQueryPort,
    initialSortDirection: SortDirection = SortDirection.DESC,
    private val onSortDirectionChanged: (SortDirection) -> Unit = {},
) : VerticalLayout(), TranslationProvider {

    /** `internal` (not `private`) so Karibu tests can read `grid.dataProvider.fetch(Query())`
     *  directly with a properly typed [DataTable] receiver, the same reasoning
     *  [de.littletrotter.ui.view.invoice.InvoiceItemsTab.itemsGrid] is `internal` for. */
    internal val grid = DataTable(InvoiceAuditActionLine::class.java, false)
    private val bandTitle = H3().apply { addClassName("journal-band__title") }

    /** The count inside the band: a number span plus a word span (or one word span for "no
     *  entries"), split out of the single `journal.count` string in [showCount]. */
    internal val resultCount = Span().apply { addClassName("journal-band__count") }
    internal val emptyState = Paragraph().apply { addClassName("empty-state"); isVisible = false }

    private val detailDialog = JournalEntryDialog(::trArgs) { key -> UiTranslations.trOrNull(this, key) }
    private var currentInvoiceId: Long? = null
    private var currentSortDirection: SortDirection = initialSortDirection
    private val viewportFit = JournalCardViewportFit(this)

    /** How often the viewport-fit script was registered for this instance; stays 1 however
     *  often [render] rebuilds the card (Karibu tests assert exactly that). */
    internal val viewportFitRegistrations: Int get() = viewportFit.registrations

    /** Rebuilt once per page fetch in [refresh] (never per row/line) — see
     *  [de.littletrotter.ui.view.system.JournalPanel]'s own field for the same idiom. */
    private var composerContext = JournalComposerContext(tr = ::trArgs, trOrNull = ::trOrNull)

    override fun getComponent(): Component = this

    init {
        isPadding = false; isSpacing = true; setWidthFull()
        alignItems = FlexComponent.Alignment.STRETCH
        configureGrid()
    }

    fun render(invoiceId: Long) {
        removeAll()
        currentInvoiceId = invoiceId

        // Drop the double frame: the card carries the border, its padding now sits only on
        // the empty-state band (journal-invoice-card__empty, styles.css) so the grid's own ruled
        // lines run edge to edge and meet the card border instead of floating inside it.
        val panel = Div().apply {
            addClassNames("ino-card", "journal-invoice-card", "journal-invoice-card--fill", "lt-w-full", "lt-border-box")
        }
        bandTitle.text = tr("journal.view.title")
        // Not GridWrapper: it calls grid.setSizeFull(), forcing height:100% — the card's flex column
        // (journal-invoice-card--fill) gives the grid its height via flex-grow instead.
        grid.setWidthFull()
        panel.add(grid)
        emptyState.text = tr("journal.empty")
        emptyState.addClassName("journal-invoice-card__empty")
        panel.add(emptyState)
        add(panel)

        refresh(invoiceId)
        // The card is a fresh element on every render, the registered script only re-measures.
        viewportFit.refit()
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        viewportFit.register()
    }

    override fun onDetach(detachEvent: DetachEvent) {
        super.onDetach(detachEvent)
        viewportFit.unregister(detachEvent.ui)
    }

    private fun refresh(invoiceId: Long) {
        grid.dataProvider = DataProvider.fromCallbacks(
            { query ->
                val limit = if (query.limit <= 0) PAGE_SIZE else query.limit
                val pageIndex = query.offset / limit
                val lines = auditJournalQueryService.findActionsByInvoice(invoiceId, pageIndex, limit, currentSortDirection)
                composerContext = buildComposerContext(lines)
                lines.stream()
            },
            { _ -> auditJournalQueryService.countActionsByInvoice(invoiceId).toInt() },
        )
        // Same reasoning as the global panel: the count drives the header band and the empty
        // state, so it is asked for once here rather than read back out of the data provider.
        val total = auditJournalQueryService.countActionsByInvoice(invoiceId)
        showCount(tr("journal.count", total))
        emptyState.isVisible = total == 0L
    }

    private fun showCount(text: String) = resultCount.showJournalCount(text)

    /** Archive symbol and title on the left (the title ellipsises first), count on the right. */
    private fun buildBand(): Div = journalBand(bandTitle, resultCount)

    /** See [de.littletrotter.ui.view.system.JournalPanel.buildComposerContext] for the
     *  same "batch through the query service, never a repository in the view" reasoning. */
    private fun buildComposerContext(lines: List<InvoiceAuditActionLine>): JournalComposerContext = JournalComposerContext(
        tr = ::trArgs,
        trOrNull = ::trOrNull,
        recipientNames = auditJournalQueryService.recipientNames(lines),
        receiptFileNames = auditJournalQueryService.receiptFileNames(lines),
        itemPositions = auditJournalQueryService.itemPositions(lines),
    )

    private fun configureGrid() {
        grid.pageSize = PAGE_SIZE
        JournalPanelParts.configureGrid(
            grid,
            JournalTranslations(::trArgs, ::trOrNull),
            invoiceColumn = false,
            JournalGridCallbacks(composerContext = { composerContext }, onOpenDialog = detailDialog::open),
        )
        // "Zeitpunkt" is the one sortable column (JournalPanelParts.configureGrid); this
        // listener maps the grid's own sort event onto the enum the query service takes and
        // re-fetches. Clearing the sort (Vaadin's third click) toggles instead: see below.
        JournalPanelParts.installSortListener(grid, { currentSortDirection }) {
            currentSortDirection = it
            onSortDirectionChanged(it)
        }
        // The grid's own top header row, one cell joined across every column. Prepended after
        // the columns exist; the column labels stay the row beneath it.
        grid.prependHeaderRow().join(*grid.columns.toTypedArray()).apply {
            component = buildBand()
            partName = "journal-band-cell"
        }
        // The header shows the active arrow from the first render — the order is never implicit.
        JournalPanelParts.showSortArrow(grid, currentSortDirection)
    }

    /** Wires this panel's component-aware `tr(key, vararg params)` into the `(key, args) ->
     *  String` shape [JournalPanelParts]' shared builders take — same idiom
     *  [InvoiceExportPanel.trArgs] uses. */
    private fun trArgs(key: String, args: List<Any>): String = tr(key, *args.toTypedArray())

    /** The nullable counterpart [de.littletrotter.ui.view.journal.JournalFieldLabels] needs to
     *  tell "no translation defined for this column" from a real label. */
    private fun trOrNull(key: String): String? = UiTranslations.trOrNull(this, key)

    private companion object {
        const val PAGE_SIZE = 20
    }
}
