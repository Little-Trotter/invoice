package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.tabs.TabSheet
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.AuditJournalQueryService
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.ui.i18n.UiTranslations
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * The read-mode tab sheet of the invoice detail page: Übersicht, Positionen, Belege, Versionen and,
 * when an audit journal source exists, Journal. Owns the tab order, the persistent tab hosts and the
 * "open the Versionen tab" deep link; the view only decides when to show it (never in entry mode).
 *
 * The tab components are injected once for the view's whole lifetime and only re-rendered on every
 * data change, so a user's place in a tab (filters, sort order, the job grid) survives a refresh.
 */
@Dependent
class InvoiceDetailTabs
@Inject
constructor(
    internal val overviewPanel: InvoiceOverviewPanel,
    internal val invoiceItemsTab: InvoiceItemsTab,
    internal val invoiceReceiptsTab: InvoiceReceiptsTab,
    internal val exportPanel: InvoiceExportPanel,
    // Read-only source of the "Journal" tab. Defaulted to null so component tests that don't
    // exercise the journal need not supply it; production CDI always resolves the real bean, and
    // null simply means the tab is not built.
    private val auditJournalQueryService: AuditJournalQueryService? = null,
) {

    /** The tab sheet while the read-mode page is shown; `null` in entry mode or before the first
     *  render. Never rebuilt on a data change, only its content. */
    internal var tabSheet: TabSheet? = null

    /**
     * Persistent content hosts for "Journal" and "Versionen". Re-adding tabs would re-select index 0
     * and attach the job `Grid` (`isAllRowsVisible`) into a hidden container, where it sizes itself
     * to zero rows — a completed print job's row then vanished. Swapping only the content of a host
     * that stays selected keeps the grid's first layout pass visible.
     */
    private var journalTabHost: Div? = null
    internal var versionsTabHost: Div? = null

    /** The [versionsTabHost] the shared [exportPanel] is rendered into; `null` forces
     *  [refreshExportPanel] to render and re-attach it instead of the cheaper refresh. */
    private var exportPanelHost: Div? = null

    /** Held here because [buildJournalPanel] rebuilds [InvoiceJournalPanel] on every refresh,
     *  which would otherwise reset the "Zeitpunkt" sort on every edit. */
    private var journalSortDirection: SortDirection = SortDirection.DESC

    /**
     * Wires the tab components to the open invoice's [state] once, for the view's whole lifetime.
     * [onEdit] switches the page into entry mode; [onReload] re-renders after a print job was
     * created or refreshed; the Positionen tab opens an item's receipts through the Belege tab's
     * dialog launcher, since it has none of its own.
     */
    internal fun bind(state: InvoiceDetailState, onEdit: (InvoiceDetailDto) -> Unit, onReload: (InvoiceDetailDto) -> Unit) {
        exportPanel.bind(state)
        invoiceReceiptsTab.receiptDialogLauncher.bind(state)
        invoiceItemsTab.invoiceItemDialogs.bind(state)
        overviewPanel.onEdit = { state.dto?.let(onEdit) }
        exportPanel.onReload = { state.dto?.let(onReload) }
        invoiceItemsTab.onReceiptsClick = { item ->
            state.dto?.let { invoiceReceiptsTab.receiptDialogLauncher.openItemReceiptDialog(it, item) }
        }
    }

    /** Builds a fresh tab sheet for [current]; the caller adds it to the page. */
    internal fun build(current: InvoiceDetailDto): TabSheet {
        val versionsHost = Div().apply { setWidthFull() }
        // The Journal tab exists exactly when the audit journal source does.
        val journalHost = if (auditJournalQueryService != null) Div().apply { setWidthFull() } else null
        journalTabHost = journalHost
        versionsTabHost = versionsHost
        // Fresh hosts: force a fresh attach of [exportPanel].
        exportPanelHost = null
        fillTabHosts(current)

        val tabs = TabSheet().apply {
            setWidthFull()
            add(tr("invoiceDetail.tab.overview"), overviewPanel)
            add(tr("invoiceDetail.tab.items"), invoiceItemsTab.apply { render(current) })
            add(tr("invoiceDetail.tab.receipts"), invoiceReceiptsTab.apply { render(current) })
            add(tr("invoiceDetail.tab.versions"), versionsHost)
            journalHost?.let { add(tr("invoiceDetail.tab.journal"), it) }
        }
        tabSheet = tabs
        return tabs
    }

    /** Forgets the tab sheet when the page switches to entry mode, so no background refresh
     *  renders into tabs that are no longer shown. */
    internal fun clear() {
        tabSheet = null
        journalTabHost = null
        versionsTabHost = null
        exportPanelHost = null
    }

    /** Incremental refresh after a data change: the Positionen tab always, the other tabs only
     *  while the tab sheet is shown. */
    internal fun refresh(current: InvoiceDetailDto) {
        invoiceItemsTab.refresh(current)
        if (tabSheet != null) {
            invoiceReceiptsTab.refresh(current)
            fillTabHosts(current)
        }
    }

    /** Refresh after receipts were attached or detached: only the two tabs that show them. */
    internal fun refreshReceipts(current: InvoiceDetailDto) {
        invoiceItemsTab.refresh(current)
        invoiceReceiptsTab.refresh(current)
    }

    /** Selects the Versionen tab, where the validation results live; the list's validation chip
     *  links here. No-op while no tab sheet is shown. */
    internal fun selectVersionsTab() {
        val tabs = tabSheet ?: return
        val versionsTab = (0 until tabs.tabCount)
            .firstOrNull { tabs.getTabAt(it).label == tr("invoiceDetail.tab.versions") }
        if (versionsTab != null) tabs.selectedIndex = versionsTab
    }

    /** Builds the "Journal" tab content, or `null` when no audit journal source is wired — the
     *  tab is then simply not added. */
    internal fun buildJournalPanel(current: InvoiceDetailDto): InvoiceJournalPanel? {
        val service = auditJournalQueryService ?: return null
        return InvoiceJournalPanel(service, journalSortDirection, onSortDirectionChanged = { journalSortDirection = it })
            .apply { render(current.id) }
    }

    /** Re-renders the tab contents that are rebuilt on every change. */
    private fun fillTabHosts(current: InvoiceDetailDto) {
        overviewPanel.render(current)
        journalTabHost?.apply { removeAll(); buildJournalPanel(current)?.let { add(it) } }
        refreshExportPanel(current)
    }

    /** Refreshes the shared [exportPanel] in place instead of rebuilding its job grid (which
     *  flickered); only the first call after a tab-host rebuild renders and attaches it. */
    private fun refreshExportPanel(current: InvoiceDetailDto) {
        val host = versionsTabHost ?: return
        if (exportPanelHost === host) {
            exportPanel.refresh(current)
            return
        }
        exportPanel.render(current)
        host.removeAll()
        host.add(exportPanel)
        exportPanelHost = host
    }

    private fun tr(key: String): String = UiTranslations.tr(overviewPanel, key)
}
