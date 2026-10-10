package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.shared.receipt.BudgetVerdict
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.detachRowButton
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import org.jboss.logging.Logger
import de.littletrotter.ui.component.ActionIcons

private const val TEXT_MUTED = "text-muted"

/**
 * The "Belege" tab, extracted out of `InvoiceDetailView` into a `@Dependent` CDI bean of its own,
 * the same shape as [InvoicePrintControls]/[InvoiceGenerationJobsPanel]: injected once into
 * [InvoiceDetailView] for its whole lifetime instead of a Div rebuilt per invoice. [render]/
 * [refresh] both rebuild this component's content wholesale — no persistent slot worth preserving
 * here (same reasoning as [InvoicePrintControls.render]'s own KDoc): `readOnly`, the two receipt
 * lists and the position grouping all depend on invoice state a background refresh can change.
 *
 * Both tables are multi-select with a Material "contextual app bar" replacing their normal header
 * while a selection exists, every row gets its own single-click UNLINK action, and the two tables'
 * selections combine into exactly one atomic
 * [de.littletrotter.application.invoice.ReceiptLinkService.detachReceipts] call — a bulk selection
 * spanning positions and the invoice level at once must still be one atomic command, never two
 * separate writes — see `InvoiceReceiptsTabDetach.kt`, extracted so this file does not grow
 * further (same "do not let large classes grow" rule as [ReceiptDialog]'s own sibling extension
 * files).
 *
 * A write no longer reaches back into `InvoiceDetailView` directly (this class has no reference to
 * it): it reports through [InvoiceDetailState.notifyReceiptsChanged] instead, registered once via
 * [bind] — the same "callbacks via `InvoiceDetailState`" shape the Versionen-tab panel's
 * `onValidationFailure`/`onPrintScheduled` already use.
 */
@Dependent
class InvoiceReceiptsTab
@Inject
constructor(
    internal val invoiceReceiptQueryPort: InvoiceReceiptQueryPort,
    internal val attachReceiptsUseCase: AttachReceiptsUseCase,
    internal val receiptDialogLauncher: ReceiptDialogLauncher,
) : Div(), TranslationProvider {

    internal val log = Logger.getLogger(javaClass)

    /** The invoice this tab last rendered — re-read at click time by every detach/undo action in
     *  `InvoiceReceiptsTabDetach.kt`, so a queued click always acts on the freshest data rather
     *  than a snapshot captured at render time. Always set before any click listener built below
     *  can fire (both are wired inside [render]). */
    internal lateinit var current: InvoiceDetailDto

    // A plain Div has no padding/spacing toggle of its own (unlike VerticalLayout); the tab's
    // spacing between its two cards comes from the cards' own margins, same as
    // InvoicePrintControls' own init block.
    init { setWidthFull() }

    override fun getComponent(): Component = this

    /** Read model for the position table; it keeps position identity next to each document. */
    internal data class PositionReceiptRow(val item: ItemDto, val receipt: ReceiptRowDto)

    /** The Belege tab: a summary of what hangs on the invoice as a whole plus the entry point to
     *  the parameterized receipt dialog. The threefold structure (zugeordnet / aus Pool zuordnen /
     *  hochladen) lives in the dialog itself — the same component as the position dialog,
     *  parameterized by [ReceiptTarget], not copied. */
    fun render(invoice: InvoiceDetailDto) {
        current = invoice
        removeAll()

        val invoiceId = invoice.id
        val readOnly = invoice.isReadOnly()
        val receipts = invoiceReceiptQueryPort.findReceiptsForInvoice(invoiceId)
        // Fetched once per render/refresh (not per grid row, same "no N+1" rule as
        // ReceiptBudgetService.invoiceDocumentBudget's own KDoc) and reused for both the budget
        // hint in the header and the too-large warning below.
        val budget = invoiceReceiptQueryPort.invoiceDocumentBudget(invoiceId)

        // One management action is shared by both tables and sits above them.
        add(buildReceiptsManageRow(invoice))
        val verdict = budget.verdict
        if (verdict is BudgetVerdict.DocumentTooLarge) {
            add(buildBudgetTooLargeBanner(verdict))
        }
        if (readOnly) {
            // No selection column, no row action, no selection bar on a read-only invoice; this
            // line explains why the controls the writable case shows below are simply absent.
            add(Span(tr("invoiceDetail.receipts.readOnlyHint")).apply { addClassName(TEXT_MUTED) })
        }

        val itemReceiptsByItemId = invoiceReceiptQueryPort.findItemReceiptsForInvoice(invoiceId)
        val rows = computePositionReceiptRows(invoice, itemReceiptsByItemId)

        // A trip-expense receipt is never offered here; it never reaches this tab in the first
        // place, since neither findReceiptsForInvoice/findItemReceiptsForInvoice (invoice_receipt
        // / invoice_item_receipt) nor this method reads TripExpense.receipt_id at all — that link
        // is owned by TripDialog exclusively.

        val invoiceGrid = buildInvoiceLevelReceiptGrid(receipts, readOnly) { receipt -> detachInvoiceLevelReceipt(receipt) }
        val positionGrid = buildPositionReceiptGrid(invoice, rows, readOnly) { row -> detachPositionReceipt(row) }

        val invoiceCard = Div().apply { addClassNames("ino-card", "ino-card--padded") }
        val positionCard = Div().apply { addClassNames("ino-card", "ino-card--padded") }

        val headers = buildReceiptsTabHeaders(budget)
        if (!readOnly) {
            wireReceiptsTabSelection(
                ReceiptTableSection(invoiceGrid, invoiceCard),
                ReceiptTableSection(positionGrid, positionCard),
                headers,
            )
        } else {
            invoiceCard.add(headers.invoiceNormalHeader, invoiceGrid)
            positionCard.add(headers.positionNormalHeader, positionGrid)
        }

        if (receipts.isEmpty()) invoiceCard.add(Span(tr("invoiceDetail.receipts.none")).apply { addClassName(TEXT_MUTED) })
        add(invoiceCard)

        if (rows.isEmpty()) positionCard.add(Span(tr("invoiceDetail.receipts.none")).apply { addClassName(TEXT_MUTED) })
        add(positionCard)
    }

    /** Same rebuild as [render] — no persistent slot to preserve here (see [render]'s own KDoc). */
    fun refresh(invoice: InvoiceDetailDto) = render(invoice)

    /** PO decision — linking a receipt no longer refuses an oversized estimate outright (only the
     *  receipt-count limit still does); instead this warning stays visible on every render/refresh
     *  ([render]/[refresh] both rebuild wholesale, so no separate show/hide wiring is needed) for
     *  as long as [BudgetVerdict.DocumentTooLarge] holds, with a way out to the receipt pool. The
     *  actual hard stop sits in front of document generation
     *  ([de.littletrotter.application.invoice.GenerationJobCreationService]), not here. */
    private fun buildBudgetTooLargeBanner(verdict: BudgetVerdict.DocumentTooLarge): Div {
        val message = Span(
            tr(
                "receipt.budget.tooLarge.banner",
                DocumentSizeFormatter.format(verdict.estimated),
                DocumentSizeFormatter.format(verdict.limit),
            ),
        ).apply { addClassName("lt-flex-grow") }
        val link = RouterLink(tr("receipt.budget.tooLarge.link"), ReceiptPoolView::class.java)
        val layout = HorizontalLayout(VaadinIcon.WARNING.create(), message, link).apply {
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        }
        return Div(layout).apply {
            addClassNames("receipt-budget-too-large-banner", "lt-warning-banner", "lt-mb-050")
        }
    }

    /** The single management-action row shared by both tables, sitting above them. */
    private fun buildReceiptsManageRow(invoice: InvoiceDetailDto): HorizontalLayout {
        val manageBtn = AppButtons.primary(tr("invoiceDetail.receipts.manage"), ActionIcons.ATTACHMENTS) {
            receiptDialogLauncher.openInvoiceReceiptDialog(invoice)
        }
        return ActionBar(manageBtn)
    }

    /**
     * A receipt assigned to a position was invisible in this tab, since invoice_receipt and
     * invoice_item_receipt are disjoint scopes; that gap is treated as a bug, not intended
     * behaviour. This makes the tab the complete view, grouped by position.
     */
    private fun computePositionReceiptRows(
        invoice: InvoiceDetailDto,
        itemReceiptsByItemId: Map<Long, List<ReceiptRowDto>>,
    ): List<PositionReceiptRow> {
        val positionsWithReceipts = invoice.items
            .filter { item -> !itemReceiptsByItemId[item.id].isNullOrEmpty() }
            .sortedWith(positionIdComparator())
        return positionsWithReceipts.flatMap { item ->
            itemReceiptsByItemId[item.id].orEmpty().map { receipt -> PositionReceiptRow(item, receipt) }
        }
    }

    private fun buildInvoiceLevelReceiptGrid(
        receipts: List<ReceiptRowDto>,
        readOnly: Boolean,
        onDetachOne: (ReceiptRowDto) -> Unit,
    ): DataTable<ReceiptRowDto> =
        DataTable(ReceiptRowDto::class.java, multiselect = false).apply {
            setWidthFull()
            // No selection column at all on a read-only invoice (Grid's own default without
            // MULTI is SINGLE, which still highlights a clicked row) — NONE removes that too.
            selectionMode = if (readOnly) Grid.SelectionMode.NONE else Grid.SelectionMode.MULTI
            addColumn { it.fileName }.setHeader(tr("receipt.column.fileName")).asTextColumn(TextColumnWidth.MEDIUM)
            addColumn { it.description.ifBlank { "-" } }.setHeader(tr("receipt.column.description")).asTextColumn(TextColumnWidth.MEDIUM)
            addColumn { formatReceiptDocumentDate(it.documentDate) }.setHeader(tr("receipt.column.documentDate")).asCompactColumn()
            addColumn { formatReceiptSize(it.sizeBytes) }.setHeader(tr("receipt.column.size")).asNumericColumn()
            if (!readOnly) {
                addComponentColumn { receipt -> detachRowButton(tr("receipt.action.detach")) { onDetachOne(receipt) } }
                    .asActionColumn()
            }
            setItems(receipts)
        }

    private fun buildPositionReceiptGrid(
        invoice: InvoiceDetailDto,
        rows: List<PositionReceiptRow>,
        readOnly: Boolean,
        onDetachOne: (PositionReceiptRow) -> Unit,
    ): DataTable<PositionReceiptRow> =
        DataTable(PositionReceiptRow::class.java, multiselect = false).apply {
            setWidthFull()
            selectionMode = if (readOnly) Grid.SelectionMode.NONE else Grid.SelectionMode.MULTI
            addColumn { it.item.positionId }.setHeader(tr("invoiceDetail.items.column.position")).asCompactColumn()
            addColumn { it.item.description }.setHeader(tr("invoiceDetail.items.column.description")).asTextColumn(TextColumnWidth.MEDIUM)
            addColumn { it.receipt.fileName }.setHeader(tr("receipt.column.fileName")).asTextColumn(TextColumnWidth.MEDIUM)
            addColumn { it.receipt.description.ifBlank { "-" } }.setHeader(tr("receipt.column.description")).asTextColumn(TextColumnWidth.MEDIUM)
            addColumn { formatReceiptDocumentDate(it.receipt.documentDate) }.setHeader(tr("receipt.column.documentDate")).asCompactColumn()
            addComponentColumn { row ->
                val goToPositionBtn = AppButtons.tertiary(tr("invoiceDetail.items.receipts.count", "1"), ActionIcons.ATTACHMENTS) {
                    receiptDialogLauncher.openItemReceiptDialog(invoice, row.item)
                }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) }
                if (readOnly) {
                    goToPositionBtn
                } else {
                    HorizontalLayout(goToPositionBtn, detachRowButton(tr("receipt.action.detach")) { onDetachOne(row) }).apply {
                        isSpacing = true
                    }
                }
            }.asActionColumn()
            setItems(rows)
        }
}
