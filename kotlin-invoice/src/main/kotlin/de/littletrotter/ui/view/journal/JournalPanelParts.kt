package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.grid.GridVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.data.renderer.ComponentRenderer
import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.applyChipTone
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.view.journal.composer.JournalComposerContext

/**
 * The parts both journal views are built from. The per-invoice tab
 * ([de.littletrotter.ui.view.invoice.InvoiceJournalPanel]) and the global System tab
 * ([de.littletrotter.ui.view.system.JournalPanel]) show the same rows with the same reading model
 * and differ in exactly two things: the global one adds an invoice column and a filter toolbar.
 * Everything else — the header band, the column set, the row click, the grid chrome — lives here
 * once rather than in two files that have to be kept identical by hand.
 *
 * The grid itself is action-grouped ([InvoiceAuditActionLine], one line per
 * `business_tx`); the earlier row-level look moved into the Grid item-details expansion
 * ([JournalActionGridColumns.technicalRowsDetails], built from the unchanged [JournalGridColumns]).
 */
/** LongParameterList — the two lookups [JournalPanelParts.configureGrid] renders every
 *  column's text through: `tr` for labels/headers, `trOrNull` for the technical-row detail
 *  strings that may legitimately have no translation. Both panels source them from the same
 *  [de.littletrotter.ui.i18n.TranslationProvider] (`::trArgs`, `::trOrNull`). */
class JournalTranslations(val tr: (String, List<Any>) -> String, val trOrNull: (String) -> String?)

/** LongParameterList — the two row-level callbacks [JournalPanelParts.configureGrid]
 *  wires: [composerContext] for the flex-grow "what" cell, [onOpenDialog] for the technical-row
 *  click inside a row's expanded details. Both panels supply the same pair together (their own
 *  composer state plus `detailDialog::open`). */
class JournalGridCallbacks(
    val composerContext: () -> JournalComposerContext,
    val onOpenDialog: (InvoiceAuditJournalEntry) -> Unit,
)

object JournalPanelParts {

    /** Title band: archive symbol, heading, and the result-count chip on the right. [count] is
     *  handed in (not created here) because each panel updates it from its own refresh. */
    fun header(title: String, count: Span): Div = Div().apply {
        addClassName("journal-header")
        count.applyChipTone(ChipTone.NEUTRAL)
        count.addClassName("journal-count")
        add(
            Div().apply {
                addClassName("journal-header__title")
                add(
                    VaadinIcon.ARCHIVE.create().apply {
                        addClassName("journal-header__icon")
                        element.setAttribute("aria-hidden", "true")
                    },
                )
                add(H3(title))
            },
            count,
        )
    }

    /**
     * The shared action-level column set, in reading order: tile (the action's own symbol), when,
     * [invoiceColumn] (global view only — every row of the per-invoice tab belongs to the invoice
     * on screen), who, "Vorgang" (the action label), "Was genau" (flex-grow 1), "Δ Brutto"
     * (right-aligned) and the expand toggle. A row click toggles the SAME item-details expansion
     * the trailing toggle button does — [onOpenDialog] only ever fires from a technical row inside
     * that expansion (via [JournalActionGridColumns.technicalRowsDetails]), never from the action
     * row itself.
     */
    fun configureGrid(
        grid: DataTable<InvoiceAuditActionLine>,
        translations: JournalTranslations,
        invoiceColumn: Boolean,
        callbacks: JournalGridCallbacks,
    ) {
        val tr = translations.tr
        val trOrNull = translations.trOrNull
        val composerContext = callbacks.composerContext
        val onOpenDialog = callbacks.onOpenDialog
        grid.setWidthFull()
        grid.addClassName("journal-grid")
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER)
        grid.isAllRowsVisible = false
        // PO decision 4 — the ONE row-level warning treatment this view uses: action 0
        // (UNSPECIFIED) together with a DB-role actor, see JournalActionGridColumns.isExternalWrite.
        // setPartNameGenerator, not setClassNameGenerator (this Vaadin version has no whole-grid
        // class name generator — same idiom as MessageCenterView's own "unread-row" part).
        grid.setPartNameGenerator { line: InvoiceAuditActionLine -> if (JournalActionGridColumns.isExternalWrite(line)) "journal-row--warning" else null }

        grid.addComponentColumn { JournalActionGridColumns.actionTileCell(it, tr) }
            .setHeader("").asCompactColumn()
        // The only sortable column: seq-backed (min(seq) for the group), server-side across
        // pages. setSortable(true) alone renders an arrow that sorts nothing on this lazy
        // provider; the key/property pair plus each panel's own addSortListener (mapping the sort
        // event to a query parameter) is what wires it up.
        grid.addComponentColumn { JournalActionGridColumns.occurredAtCell(it) }
            .setHeader(tr("journal.column.occurredAt", emptyList()))
            .asCompactColumn()
            .setSortable(true)
            .setSortProperty(OCCURRED_AT_KEY)
            .key = OCCURRED_AT_KEY
        if (invoiceColumn) {
            grid.addComponentColumn { JournalActionGridColumns.invoiceNumbersCell(it, tr) }
                .setHeader(tr("journal.column.invoiceNumber", emptyList())).asCompactColumn()
        }
        grid.addColumn { JournalActionGridColumns.actorText(it) }
            .setHeader(tr("journal.column.actor", emptyList())).asCompactColumn()
        grid.addComponentColumn { JournalActionGridColumns.actionLabelCell(it, tr) }
            .setHeader(tr("journal.column.action", emptyList())).asCompactColumn()
        // The only growing column — the grid reads as one ruled sheet across the full window
        // instead of a block with a ragged right edge.
        grid.addComponentColumn { JournalActionGridColumns.whatCell(it, composerContext()) }
            .setHeader(tr("journal.column.what", emptyList())).asTextColumn(TextColumnWidth.LARGE)
        grid.addComponentColumn { JournalActionGridColumns.grossDeltaCell(it) }
            .setHeader(tr("journal.column.grossDelta", emptyList()))
            .asNumericColumn()
        grid.addComponentColumn { line ->
            JournalActionGridColumns.expandToggleButton(line, grid.isDetailsVisible(line), tr) { toggled -> toggleDetails(grid, toggled) }
        }.asActionColumn()

        grid.setItemDetailsRenderer(
            ComponentRenderer { line: InvoiceAuditActionLine ->
                JournalActionGridColumns.technicalRowsDetails(line, tr, trOrNull, onOpenDialog)
            },
        )
        // The whole row is the affordance, not just the trailing toggle button — the same
        // "click a row to see it" expectation every other list in this app sets. Unlike the
        // earlier row-level grid, a click here toggles item-details rather than opening the dialog:
        // the dialog now only ever opens from a technical row (see [onOpenDialog] above).
        grid.addItemClickListener { event -> event.item?.let { toggleDetails(grid, it) } }
    }

    /**
     * Maps the grid's own sort event onto this project's [SortDirection] and hands it to
     * [onDirectionChanged]; the caller re-fetches there. Clearing the sort (Vaadin's third click)
     * toggles instead: the sorter cycles asc -> desc -> cleared, the arrow is never allowed to
     * vanish, so a cleared state re-applies the opposite of [currentDirection]. The re-applied
     * sort fires this listener again with a non-null order (normal path).
     */
    fun <T : Any> installSortListener(
        grid: DataTable<T>,
        currentDirection: () -> SortDirection,
        onDirectionChanged: (SortDirection) -> Unit,
    ) {
        grid.addSortListener { event ->
            val order = event.sortOrder.firstOrNull()
            if (order == null) {
                val opposite = if (currentDirection() == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                showSortArrow(grid, opposite)
                return@addSortListener
            }
            onDirectionChanged(
                when (order.direction) {
                    com.vaadin.flow.data.provider.SortDirection.ASCENDING -> SortDirection.ASC
                    else -> SortDirection.DESC
                },
            )
            grid.dataProvider.refreshAll()
            grid.scrollToStart()
        }
    }

    /**
     * Shows [direction] as the active arrow on the "occurredAt" column. The column is never
     * absent: [configureGrid] unconditionally registers this exact key.
     */
    fun <T : Any> showSortArrow(grid: DataTable<T>, direction: SortDirection) {
        grid.sort(listOf(GridSortOrder(grid.getColumnByKey(OCCURRED_AT_KEY)!!, direction.toVaadinSortDirection())))
    }

    private fun toggleDetails(grid: DataTable<InvoiceAuditActionLine>, line: InvoiceAuditActionLine) {
        grid.setDetailsVisible(line, !grid.isDetailsVisible(line))
        // Forces the expand-toggle button's own icon (chevron direction) to re-render — Vaadin
        // does not know a component column's rendered Icon depends on isDetailsVisible unless told.
        grid.dataProvider.refreshItem(line)
    }

    internal const val OCCURRED_AT_KEY = "occurredAt"
}

/** The one place [SortDirection] (this project's own enum, threaded through the repository
 *  ports) is translated into Vaadin's own `SortDirection`, for the initial `grid.sort(...)` call
 *  that shows the active arrow on first render. Both journal panels share it rather than each
 *  writing the same two-way `when`. */
internal fun SortDirection.toVaadinSortDirection(): com.vaadin.flow.data.provider.SortDirection = when (this) {
    SortDirection.ASC -> com.vaadin.flow.data.provider.SortDirection.ASCENDING
    SortDirection.DESC -> com.vaadin.flow.data.provider.SortDirection.DESCENDING
}
