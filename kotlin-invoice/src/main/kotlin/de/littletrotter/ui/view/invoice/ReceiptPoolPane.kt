package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.CallbackDataProvider
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.value.ValueChangeMode
import de.littletrotter.application.invoice.ReceiptCountLimitExceededException
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import java.util.stream.Stream

private const val FS_08 = "fs-08"
private const val ERROR_KEY = "invoiceDetail.items.receipts.error"

/**
 * [ReceiptDialog]'s "Belegpool" tab (Tab 2: search the pool, assign to this target),
 * extracted out of that already-900+-line file's `OpenSession` into its own composer, the same
 * shape [ReceiptUploadForm] already is for the "Hochladen" tab: built once by
 * `ReceiptDialog.OpenSession.build()`, its [getComponent] added to the dialog's [TabSheet]
 * directly by [ReceiptDialog] — no forwarding methods kept on [ReceiptDialog] itself.
 *
 * Holds [dialog] (not individual lambdas for every collaborator) because it genuinely needs many
 * of that class's facilities — [ReceiptDialog.gateway] for every target-bound read/write,
 * [ReceiptDialog.tr]/[ReceiptDialog.log] for i18n/logging, [ReceiptDialog.downloadAnchor]/
 * [ReceiptDialog.viewAnchor] for the shared row actions — the same relationship
 * `ReceiptDialogAttachedTab.kt`'s extension functions already have to [ReceiptDialog], just held
 * as a field instead of as an implicit extension receiver. [onChanged]/[reloadAttached]/
 * [selectAttachedTab] are the three genuinely dialog-open-local callbacks (same three
 * `ReceiptDialogAttachedTab.buildAttachedTab` already takes as parameters) this pane cannot reach
 * via [dialog] itself.
 *
 * For a [ReceiptTarget.Item] with write access, a second grid ([invoiceReceiptsGrid])
 * lists this invoice's own receipts (invoice-level or on another position) ahead of, and excluded
 * from, the remaining paged pool below it.
 */
internal class ReceiptPoolPane(
    private val dialog: ReceiptDialog,
    private val onChanged: () -> Unit,
    private val reloadAttached: (justAddedIds: Set<Long>) -> Unit,
    private val selectAttachedTab: () -> Unit,
) : TranslationProvider {
    private val target get() = dialog.target
    private val canWrite get() = dialog.canWrite
    private val gateway get() = dialog.gateway

    private var poolFilter = ""

    /** Usage strings for the page currently rendered — filled by the fetch callback in one
     *  query so the usage column never triggers a lookup per row. */
    private var usageByReceiptId: Map<Long, String> = emptyMap()

    // This-invoice grouping state (ReceiptTarget.Item only; stay at their defaults for
    // ReceiptTarget.Invoice, which has no such grouping). thisInvoiceReceiptIds is read by the
    // pool DataProvider's fetch/count closures to keep the remaining pool from repeating a
    // receipt already shown in the invoice's own group.
    private var thisInvoiceReceiptIds: Set<Long> = emptySet()
    private var thisInvoiceLocationByReceiptId: Map<Long, String> = emptyMap()

    // belege-improve — the receipt cap is shown and enforced before the user acts, not only as
    // an error afterwards: the hint names how many slots (and how much size) are left, and the
    // assign button refuses a selection that would exceed either.
    // The invoice-wide budget (count AND size, both positions and the invoice level), not
    // a per-target count. [budget] is only ever read after [refresh]'s own first call, which
    // `ReceiptDialog.OpenSession.build()` runs once every tab has been built; `lateinit` avoids a
    // redundant fetch at construction time.
    private lateinit var budget: InvoiceDocumentBudget
    private var assignedObjectKeys: Set<String> = emptySet()

    private val limitHint = Span().apply { addClassName(FS_08) }
    private val poolProvider: DataProvider<ReceiptRowDto, Void> = buildPoolProvider()
    private val assignBtn: Button = buildAssignButton()
    private val poolGrid: DataTable<ReceiptRowDto> = buildPoolGrid()

    // Built only for ReceiptTarget.Item with write access — genuinely absent otherwise (no
    // invoice-level grouping for a whole-invoice target), so these two stay nullable.
    private val invoiceReceiptsGrid: DataTable<ReceiptRowDto>? = buildInvoiceReceiptsGrid()
    private val invoiceReceiptsGroup: VerticalLayout? = buildInvoiceReceiptsGroup()

    private val root: VerticalLayout

    init {
        reloadInvoiceReceiptsGroup()
        val poolSearch = buildPoolSearch()
        poolSearch.addValueChangeListener { e ->
            // TextField's ValueChangeEvent.value is never actually null (Vaadin's String-typed
            // AbstractSinglePropertyField reads back the type's registered default, "", once the
            // element property is absent) — same proof established elsewhere in this codebase for other
            // TextField/TextArea `.value` reads (B2bFieldWidgetFactory).
            poolFilter = e.value
            poolGrid.deselectAll()
            invoiceReceiptsGrid?.deselectAll()
            reloadInvoiceReceiptsGroup()
            poolProvider.refreshAll()
        }
        assignBtn.addClickListener { onAssignClicked() }
        root = buildPoolPane(poolSearch)
    }

    override fun getComponent(): Component = root

    // ── budget-driven state, refreshed by ReceiptDialog.OpenSession.refreshLimitState ─────────

    internal fun refresh(currentBudget: InvoiceDocumentBudget, currentAssignedObjectKeys: Set<String>) {
        budget = currentBudget
        assignedObjectKeys = currentAssignedObjectKeys
        limitHint.applyBudgetHint(budgetHint(dialog.dlg, budget))
        updateAssignButtonState()
    }

    // The pool tab's own live label: how many more receipts still fit, read at a glance
    // without opening the tab. Only ever called once [refresh] has run at least once (same guard
    // ReceiptDialog.OpenSession.updatePoolTabLabel's own caller already has).
    internal fun tabLabelText(): String {
        val remaining = budget.remainingSlots
        return if (remaining > 0) {
            tr("invoiceDetail.items.receipts.tab.pool.remaining", remaining.toString())
        } else {
            tr("invoiceDetail.items.receipts.tab.pool.full")
        }
    }

    /** Called after an external write (the upload tab) that may have changed either this
     *  invoice's own receipts group or the remaining pool, without itself having gone through
     *  [onAssignClicked]. */
    internal fun reloadAfterExternalChange() {
        reloadInvoiceReceiptsGroup()
        reloadPool()
    }

    // ── search ─────────────────────────────────────────────────────────────────

    private fun buildPoolSearch(): TextField = TextField().apply {
        placeholder = tr("receipt.filter.placeholder")
        isClearButtonVisible = true
        setWidthFull()
        // Filter as you type instead of only on blur/Enter — the picker is a search-first surface.
        valueChangeMode = ValueChangeMode.LAZY
        prefixComponent = VaadinIcon.SEARCH.create()
    }

    // Client-side match for the (unpaged, bounded) "this invoice's own receipts" grid; the
    // remaining pool grid keeps matching [poolFilter] server-side, as before.
    private fun matchesPoolFilter(r: ReceiptRowDto): Boolean {
        val needle = poolFilter.trim()
        return needle.isEmpty() ||
            r.fileName.contains(needle, ignoreCase = true) ||
            r.description.contains(needle, ignoreCase = true)
    }

    // ── pool grid + DataProvider ──────────────────────────────────────────────

    // Identity via ReceiptRowDto.id, so the grid tracks a row across re-fetches by its receipt id
    // rather than by the fetched `data class` instance — a re-fetch in between two clicks of the
    // same checkbox (e.g. after the filter box's value-change fetch) must still resolve to the same
    // selectable row.
    private fun buildPoolProvider(): DataProvider<ReceiptRowDto, Void> = CallbackDataProvider(
        { query -> fetchPoolPage(query) },
        { _ -> countPoolPage() },
        { receipt: ReceiptRowDto -> receipt.id },
    )

    // UI event boundary — DataProvider fetch callback must not let ANY exception through, sole
    // catch-all of this flow.
    private fun fetchPoolPage(query: Query<ReceiptRowDto, Void>): Stream<ReceiptRowDto> {
        val filter = poolFilter.trim().takeIf { it.isNotEmpty() }
        return try {
            val page = gateway.fetchAssignable(
                query.offset / query.limit.coerceAtLeast(1), query.limit, filter, thisInvoiceReceiptIds,
            )
            usageByReceiptId = gateway.findUsageSummaries(
                page.map { it.id },
                tr("invoiceDetail.items.receipts.usage.unused"),
                tr("receipt.usage.invoiceLevel"),
            )
            page.stream()
        } catch (ex: Exception) {
            // DataProvider fetch callback: Vaadin invokes this directly while rendering the
            // grid, so an escaping exception breaks the grid the same way an escaping
            // listener exception would. Stays broad, but now logged and surfaced instead of
            // just falling back to an unexplained empty page.
            dialog.log.error("Failed to fetch assignable receipts (${gateway.targetContext()}, filter=$filter)", ex)
            UiNotifications.error(tr(ERROR_KEY, ex.message ?: ""))
            emptyList<ReceiptRowDto>().stream()
        }
    }

    // UI event boundary — DataProvider count callback must not let ANY exception through, sole
    // catch-all of this flow.
    private fun countPoolPage(): Int {
        val filter = poolFilter.trim().takeIf { it.isNotEmpty() }
        return try {
            gateway.countAssignable(filter, thisInvoiceReceiptIds).toInt()
        } catch (ex: Exception) {
            // Same rationale as the fetch callback above. Falling back to 0 without a trace
            // used to be indistinguishable from "no assignable receipts" — the exact failure
            // this package exists to remove — so it is logged now.
            dialog.log.error("Failed to count assignable receipts (${gateway.targetContext()}, filter=$filter)", ex)
            0
        }
    }

    private fun buildPoolGrid(): DataTable<ReceiptRowDto> = DataTable(ReceiptRowDto::class.java, true).apply {
        setWidthFull()
        height = "300px"
        dialog.addFileIdentityColumns(this)
        addColumn { r -> usageByReceiptId[r.id] ?: "" }
            .setHeader(tr("receipt.column.usage")).asTextColumn(TextColumnWidth.MEDIUM)
        addComponentColumn { receipt ->
            HorizontalLayout(dialog.downloadAnchor(receipt), dialog.viewAnchor(receipt)).apply { isSpacing = true }
        }.asActionColumn()
        dataProvider = poolProvider
        // The primary action follows the selection instead of sitting inert below the grid.
        addSelectionListener { updateAssignButtonState() }
    }

    // ── this invoice's own receipts (ReceiptTarget.Item only) ─────────────────

    // Second grid, ReceiptTarget.Item only: this invoice's own receipts (invoice-level or
    // on another position), listed ahead of the remaining pool and excluded from it (via
    // gateway.fetchAssignable/countAssignable's excludeReceiptIds above). Two grids, not one grid
    // with a client-side sort of the current page: this one is read complete and unpaged (bounded
    // — the same reads InvoiceDetailView's Belege tab already does for the same data), so it can
    // never be pushed onto page 2 of the paged pool below it — which a client-side sort of a
    // single grid's *current page* could not guarantee once the pool has more than one page.
    private fun buildInvoiceReceiptsGrid(): DataTable<ReceiptRowDto>? = if (target is ReceiptTarget.Item && canWrite) {
        DataTable(ReceiptRowDto::class.java, true).apply {
            setWidthFull()
            height = "160px"
            dialog.addFileIdentityColumns(this)
            // Every row here is a value of `rows` from gateway.findInvoiceOwnReceipts, and
            // thisInvoiceLocationByReceiptId is `locations` from that same call, filtered by the
            // same exclusion set — the two always share the same key domain, so the lookup here
            // can never miss.
            addColumn { r -> thisInvoiceLocationByReceiptId.getValue(r.id) }
                .setHeader(tr("receipt.column.usage")).asTextColumn(TextColumnWidth.MEDIUM)
            addComponentColumn { receipt ->
                HorizontalLayout(dialog.downloadAnchor(receipt), dialog.viewAnchor(receipt)).apply { isSpacing = true }
            }.asActionColumn()
            addSelectionListener { updateAssignButtonState() }
        }
    } else {
        null
    }

    private fun buildInvoiceReceiptsGroup(): VerticalLayout? = invoiceReceiptsGrid?.let { grid ->
        VerticalLayout().apply {
            isPadding = false; isSpacing = true
            add(
                Span(tr("invoiceDetail.items.receipts.pool.thisInvoiceHeading")).apply { addClassName("lt-semibold") },
                grid,
            )
        }
    }

    // No-op unless [target] is an [ReceiptTarget.Item] with write access whose own-receipts
    // grid already exists — called from the pool filter and from [reloadAfterExternalChange],
    // both of which run regardless of target/canWrite.
    private fun reloadInvoiceReceiptsGroup() {
        val itemTarget = target as? ReceiptTarget.Item ?: return
        val grid = invoiceReceiptsGrid ?: return
        val group = invoiceReceiptsGroup ?: return
        val (rows, locations) = gateway.findInvoiceOwnReceipts(itemTarget)
        thisInvoiceReceiptIds = rows.map { it.id }.toSet()
        thisInvoiceLocationByReceiptId = locations
        val filtered = rows.filter(::matchesPoolFilter)
        grid.setItems(filtered)
        // Empty group hidden outright, not rendered as an empty heading over an empty grid.
        group.isVisible = filtered.isNotEmpty()
    }

    private fun reloadPool() {
        poolGrid.dataProvider.refreshAll()
    }

    // ── assign ─────────────────────────────────────────────────────────────────

    // The click handler is attached once the pane's grids exist.
    private fun buildAssignButton(): Button =
        AppButtons.primary(tr("receipt.action.assignSelected"), ActionIcons.ASSIGN) {}.apply { isEnabled = false }

    // Selection can now come from either grid (this invoice's own receipts, or the
    // remaining pool); the button reflects the combined count so assigning still works in one
    // click across both, exactly as it did against the single grid before the split.
    private fun selectedForAssign(): List<ReceiptRowDto> =
        poolGrid.selectedItems.toList() + (invoiceReceiptsGrid?.selectedItems?.toList() ?: emptyList())

    // A selection refused here for the count limit is refused before the write, not accepted
    // and rejected by the service afterwards. The estimated size is a warning (the budget banner
    // above the grid), not a link-time gate — the hard stop sits before document generation, not
    // here (see ReceiptDialog.OpenSession.refreshLimitState's identical count-only rule).
    private fun updateAssignButtonState() {
        val selected = selectedForAssign()
        val n = selected.size
        val remainingSlots = budget.remainingSlots
        // Reusing an invoice's receipt on another position adds no document to its budget. Match
        // the service's object-key deduplication, including duplicate pool records.
        val additional = selected.filterNot { it.objectKey in assignedObjectKeys }.distinctBy { it.objectKey }
        val fitsCount = additional.size <= remainingSlots
        assignBtn.isEnabled = canWrite && n > 0 && fitsCount
        assignBtn.text = when {
            n == 0 -> tr("receipt.action.assignSelected")
            !fitsCount -> tr("receipt.error.limitSelection", remainingSlots.toString())
            else -> tr("receipt.action.assignSelectedCount", n.toString())
        }
    }

    // UI event boundary — click listener must not let ANY exception through, sole catch-all of this
    // flow.
    private fun onAssignClicked() {
        // Gathered from both grids so assigning works across both in one click.
        val selected = selectedForAssign().map { it.id }
        if (selected.isEmpty()) return
        try {
            gateway.addSelected(selected)
            onChanged()
            poolGrid.deselectAll()
            invoiceReceiptsGrid?.deselectAll()
            reloadAttached(selected.toSet())
            reloadInvoiceReceiptsGroup()
            reloadPool()
            UiNotifications.success(tr("invoiceDetail.items.receipts.assignedMany", selected.size.toString()))
            // Land the user where the result is visible; assigning is a means, not the goal.
            selectAttachedTab()
        } catch (ex: ReceiptCountLimitExceededException) {
            // Named case: the generic "error: <message>" wrapper would bury a rule the user can act on.
            UiNotifications.error(tr("receipt.error.limitExceeded", ex.limit.toString()))
            reloadAttached(emptySet())
            reloadInvoiceReceiptsGroup()
        } catch (ex: Exception) {
            // Click listener: must not let an exception escape. Logged with the receipt ids
            // that were being assigned so a failed assignment is discoverable.
            dialog.log.error("Failed to assign receiptIds=$selected (${gateway.targetContext()})", ex)
            UiNotifications.error(tr(ERROR_KEY, ex.message ?: ""))
        }
    }

    // ── layout ─────────────────────────────────────────────────────────────────

    private fun buildPoolPane(poolSearch: TextField): VerticalLayout = VerticalLayout().apply {
        isPadding = false; isSpacing = true
        add(
            Span(tr("invoiceDetail.items.receipts.assignPool.hint")).apply { addClassNames(FS_08, "text-muted") },
            limitHint,
            poolSearch,
        )
        invoiceReceiptsGroup?.let { group ->
            // This target shape: this invoice's own receipts first (own heading, hidden when
            // empty), then the labelled remainder — never one flat list with the former mixed
            // in unmarked, which was the defect this fixes.
            add(group)
            add(Span(tr("invoiceDetail.items.receipts.pool.otherHeading")).apply { addClassName("lt-semibold") })
        }
        add(
            poolGrid,
            HorizontalLayout(assignBtn).apply {
                setWidthFull(); justifyContentMode = FlexComponent.JustifyContentMode.END
            },
        )
    }
}
