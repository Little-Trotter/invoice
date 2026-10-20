package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.MultiSelectComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.DataProvider
import de.littletrotter.application.invoice.port.inbound.AuditJournalQueryPort
import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.application.dto.JournalGlobalFilter
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.journal.JournalActionLabels
import de.littletrotter.ui.view.journal.JournalEntryDialog
import de.littletrotter.ui.view.journal.JournalGridCallbacks
import de.littletrotter.ui.view.journal.JournalPanelParts
import de.littletrotter.ui.view.journal.JournalTranslations
import de.littletrotter.ui.view.journal.composer.JournalComposerContext
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

private const val ARIA_LABEL = "aria-label"

/**
 * The global "Journal" tab of [SystemView]: every business action across every
 * invoice, newest first, filtered (invoice number, actor, business action, date range) and paged.
 * One line per `business_tx` — see [InvoiceAuditActionLine] — with its technical rows one click
 * below (Grid item-details). Same `@Dependent`/`@Inject constructor`/build-everything-in-`init`
 * shape as [CachePanel] — the other panels [SystemTab] dispatches to.
 *
 * The table is explicitly designed to grow unbounded (J3 — PO decision: unlimited retention) —
 * [refresh] always goes through a lazy [DataProvider], never a full load, same idiom
 * [de.littletrotter.ui.view.invoice.ReceiptPoolView.refresh] uses. It is also the app's densest
 * read: it gets the whole remaining viewport (`system-journal-panel`, styles.css — a flex
 * chain, not a fixed `min-height`), sortable by "Zeitpunkt" (newest first by default).
 *
 * PO decision 2 (2026-09-17) — the row-event filter is REMOVED, not hidden: an
 * auditor filters by business action now, [actionFilter] is a multi-select over
 * [InvoiceAuditAction]. The small, batched name lookups a "Was genau" sentence needs but a journal
 * row never carries verbatim (a receipt's file name, a recipient's display name) are resolved by
 * [auditJournalQueryService] itself ([AuditJournalQueryPort.recipientNames]/[AuditJournalQueryPort
 * .receiptFileNames]) — this panel never injects a domain repository directly, same layering every
 * other read here already goes through an application service for.
 */
@Dependent
class JournalPanel @Inject constructor(
    private val auditJournalQueryService: AuditJournalQueryPort,
) : VerticalLayout(), TranslationProvider {

    /** `internal` (not `private`) so tests can read `grid.dataProvider.fetch(Query())` and the
     *  current filter fields directly — same reasoning as
     *  [de.littletrotter.ui.view.invoice.InvoiceJournalPanel.grid]. */
    internal val grid = DataTable(InvoiceAuditActionLine::class.java, false)
    internal val invoiceNumberFilter = TextField()
    internal val actorFilter = TextField()
    internal val actionFilter = MultiSelectComboBox<InvoiceAuditAction>()
    internal val dateFromFilter = DatePicker()
    internal val dateToFilter = DatePicker()
    internal val resultCount = Span()
    internal val emptyState = Paragraph().apply { addClassName("empty-state"); isVisible = false }

    /** Guards [refresh] while [clearFilters] resets five fields in a row — see its own comment. */
    private var suspendRefresh = false

    /** The "Zeitpunkt" column's own sort state; defaults to newest first, same default as
     *  before sorting was added. This panel is a `@Dependent` bean [SystemView] keeps alive for
     *  the session (shown/hidden via `isVisible`, never rebuilt), so — unlike
     *  [de.littletrotter.ui.view.invoice.InvoiceJournalPanel] — its own field survives a tab
     *  switch without extra plumbing. */
    private var currentSortDirection: SortDirection = SortDirection.DESC

    /** Rebuilt once per page fetch in [refresh] (never per row/line): the batched
     *  receipt-file-name/recipient-name lookups the current page's "Was genau" cells need. */
    private var composerContext = JournalComposerContext(tr = ::trArgs, trOrNull = ::trOrNull)

    private val detailDialog = JournalEntryDialog(::trArgs) { key -> UiTranslations.trOrNull(this, key) }

    override fun getComponent(): Component = this

    init {
        addClassName("system-journal-panel")
        setWidthFull(); isPadding = false; isSpacing = true

        configureFilters()
        configureGrid()

        emptyState.text = tr("journal.empty")
        add(JournalPanelParts.header(tr("journal.view.title"), resultCount), buildToolbar(), GridWrapper(grid).apply { setWidthFull() }, emptyState)
        refresh()
    }

    private fun configureFilters() {
        invoiceNumberFilter.placeholder = tr("journal.filter.invoiceNumber")
        invoiceNumberFilter.element.setAttribute(ARIA_LABEL, tr("journal.filter.invoiceNumber"))
        invoiceNumberFilter.isClearButtonVisible = true
        invoiceNumberFilter.prefixComponent = VaadinIcon.FILE_TEXT_O.create()
        invoiceNumberFilter.addValueChangeListener { refresh() }

        actorFilter.placeholder = tr("journal.filter.actor")
        actorFilter.element.setAttribute(ARIA_LABEL, tr("journal.filter.actor"))
        actorFilter.isClearButtonVisible = true
        actorFilter.prefixComponent = VaadinIcon.USER.create()
        actorFilter.addValueChangeListener { refresh() }

        actionFilter.placeholder = tr("journal.filter.action.all")
        actionFilter.element.setAttribute(ARIA_LABEL, tr("journal.filter.action"))
        actionFilter.setItems(InvoiceAuditAction.entries)
        actionFilter.setItemLabelGenerator { tr(JournalActionLabels.labelKey(it)) }
        actionFilter.addValueChangeListener { refresh() }
        actionFilter.width = "16rem"

        dateFromFilter.placeholder = tr("journal.filter.dateFrom")
        dateFromFilter.element.setAttribute(ARIA_LABEL, tr("journal.filter.dateFrom"))
        dateFromFilter.isClearButtonVisible = true
        dateFromFilter.addValueChangeListener { refresh() }

        dateToFilter.placeholder = tr("journal.filter.dateTo")
        dateToFilter.element.setAttribute(ARIA_LABEL, tr("journal.filter.dateTo"))
        dateToFilter.isClearButtonVisible = true
        dateToFilter.addValueChangeListener { refresh() }
    }

    private fun buildToolbar(): HorizontalLayout =
        HorizontalLayout(invoiceNumberFilter, actorFilter, actionFilter, dateFromFilter, dateToFilter, buildResetButton()).apply {
            addClassNames("ino-toolbar", "journal-toolbar")
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isSpacing = true
            setWidthFull()
        }

    /** Five filter fields are five things to clear one by one; this clears them in one click.
     *  Deliberately the only non-filter control in the toolbar — it changes the *query*, never
     *  the journal. */
    private fun buildResetButton(): Button =
        AppButtons.tertiary(tr("journal.filter.reset"), ActionIcons.CLEAR_FILTERS) { clearFilters() }.apply {
            addClassName("journal-reset-button")
        }

    /** Each `value = …` fires its own listener, so [refresh] would run five times for one click;
     *  [suspendRefresh] holds it back and runs it once at the end. */
    private fun clearFilters() {
        suspendRefresh = true
        invoiceNumberFilter.clear()
        actorFilter.clear()
        actionFilter.clear()
        dateFromFilter.clear()
        dateToFilter.clear()
        suspendRefresh = false
        refresh()
    }

    private fun configureGrid() {
        grid.pageSize = PAGE_SIZE
        JournalPanelParts.configureGrid(
            grid,
            JournalTranslations(::trArgs, ::trOrNull),
            invoiceColumn = true,
            JournalGridCallbacks(composerContext = { composerContext }, onOpenDialog = detailDialog::open),
        )
        // "Zeitpunkt" is the one sortable column (JournalPanelParts.configureGrid); this
        // listener maps the grid's own sort event onto the direction findGlobalActions takes.
        // Clearing the sort (Vaadin's third click) toggles the direction instead: see below.
        JournalPanelParts.installSortListener(grid, { currentSortDirection }) { currentSortDirection = it }
        // The header shows the active arrow from the first render — the order is never implicit.
        JournalPanelParts.showSortArrow(grid, currentSortDirection)
    }

    /** `TextField.value` defaults to `""`, not `null`, when untouched — `.blankToNull()` keeps
     *  an unfilled/cleared filter field represented as `null` here too, matching what
     *  [de.littletrotter.application.invoice.port.inbound.AuditJournalQueryPort] normalizes it to anyway. */
    private fun currentFilter(): JournalGlobalFilter = JournalGlobalFilter(
        invoiceNumber = invoiceNumberFilter.value.blankToNull(),
        actor = actorFilter.value.blankToNull(),
        actions = actionFilter.selectedItems,
        occurredFrom = dateFromFilter.value,
        occurredTo = dateToFilter.value,
    )

    private fun refresh() {
        if (suspendRefresh) return
        val filter = currentFilter()
        grid.dataProvider = DataProvider.fromCallbacks(
            { query ->
                val limit = if (query.limit <= 0) PAGE_SIZE else query.limit
                val pageIndex = query.offset / limit
                val lines = auditJournalQueryService.findGlobalActions(currentFilter(), pageIndex, limit, currentSortDirection)
                composerContext = buildComposerContext(lines)
                lines.stream()
            },
            { _ -> auditJournalQueryService.countGlobalActions(currentFilter()).toInt() },
        )
        // Counted once per filter change rather than read back off the data provider: the count
        // drives both the header chip and the empty state, and a filter that matches nothing must
        // say so in words — `journal.empty` existed but was never wired to anything before.
        val total = auditJournalQueryService.countGlobalActions(filter)
        resultCount.text = tr("journal.count", total)
        emptyState.isVisible = total == 0L
    }

    /** Two batched calls into [auditJournalQueryService] per fetched page, never one per
     *  line, and never a repository touched directly from this view. */
    private fun buildComposerContext(lines: List<InvoiceAuditActionLine>): JournalComposerContext = JournalComposerContext(
        tr = ::trArgs,
        trOrNull = ::trOrNull,
        recipientNames = auditJournalQueryService.recipientNames(lines),
        receiptFileNames = auditJournalQueryService.receiptFileNames(lines),
        itemPositions = auditJournalQueryService.itemPositions(lines),
    )

    /** Wires this panel's component-aware `tr(key, vararg params)` into the `(key, args) ->
     *  String` shape [JournalPanelParts]' shared builders take. */
    private fun trArgs(key: String, args: List<Any>): String = tr(key, *args.toTypedArray())

    /** The nullable counterpart [de.littletrotter.ui.view.journal.JournalFieldLabels] needs to
     *  tell "no translation defined for this column" from a real label. */
    private fun trOrNull(key: String): String? = UiTranslations.trOrNull(this, key)

    private companion object {
        const val PAGE_SIZE = 25
    }
}
