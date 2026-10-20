package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.orPlaceholder
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.view.journal.composer.JournalComposerContext

/**
 * Cell builders for the grouped, action-level grid both journal views now share (per-
 * invoice tab, global system view) — one column set per [InvoiceAuditActionLine], the technical
 * [InvoiceAuditActionLine.rows] rendered separately, in the Grid item-details expansion, via the
 * pre-existing [JournalGridColumns] (unchanged — the row-level look "moves one level down", it is
 * not rebuilt).
 *
 * Same split [JournalGridColumns] already established at the row level, one level up: nothing here
 * writes, the only affordance is the expand toggle.
 */
object JournalActionGridColumns {

    /** The leading tile column — [InvoiceAuditActionLine.action]'s own symbol, tinted by
     *  [JournalActionLabels.cssModifier]. Icon-only by design, same reasoning
     *  [JournalGridColumns.entityCell] gives: the "Vorgang" column right next to it spells the
     *  action out in words. */
    fun actionTileCell(line: InvoiceAuditActionLine, tr: (String, List<Any>) -> String): Component =
        JournalActionIcons.actionTile(line.action, tr(actionLabelKey(line), emptyList()))

    fun occurredAtCell(line: InvoiceAuditActionLine): Component = JournalGridColumns.occurredAtCell(line.occurredAt)

    fun actorText(line: InvoiceAuditActionLine): String = line.actorName.orPlaceholder(line.actor)

    /** "Vorgang" — the action's own label, in the warning tone (PO decision — the ONLY row-level
     *  emphasis this view uses) exactly when [isExternalWrite] is true for this line. */
    fun actionLabelCell(line: InvoiceAuditActionLine, tr: (String, List<Any>) -> String): Component =
        Span(tr(actionLabelKey(line), emptyList())).apply {
            addClassName("journal-action-label")
            if (isExternalWrite(line)) addClassName("journal-action-label--warning")
        }

    /** B3 — [JournalActionLabels.EXTERNAL_WRITE_LABEL_KEY] instead of the generic
     *  `journal.action.UNSPECIFIED` exactly when [isExternalWrite] holds for [line]. */
    private fun actionLabelKey(line: InvoiceAuditActionLine): String =
        if (isExternalWrite(line)) JournalActionLabels.EXTERNAL_WRITE_LABEL_KEY else JournalActionLabels.labelKey(line.action)

    /** "Was genau" — the composed sentence, built once per line via [JournalActionContent]. */
    fun whatCell(line: InvoiceAuditActionLine, ctx: JournalComposerContext): Component =
        Span(JournalActionContent.whatHappened(line, ctx)).apply { addClassName("journal-what") }

    /** "Δ Brutto" — right-aligned, `tabular-nums`, signed; an empty cell (not "0,00 EUR") when the
     *  action's rows never touched `invoice.gross_amount` (see [JournalGrossDelta]'s own KDoc on
     *  why that is the correct empty case, not a zero delta). */
    fun grossDeltaCell(line: InvoiceAuditActionLine): Component {
        val delta = JournalGrossDelta.compute(line)
        return Span(delta?.let(::formatSignedDelta).orEmpty()).apply { addClassName("journal-gross-delta") }
    }

    /** The global panel's own "which invoice(s)" cell — almost always one number; a `CANCELLATION` line
     *  spans two (see [InvoiceAuditActionLine.invoiceNumbers]), joined with an arrow so a reader
     *  sees "the original became the credit note" at a glance rather than an unordered pair. */
    fun invoiceNumbersCell(line: InvoiceAuditActionLine, tr: (String, List<Any>) -> String): Component {
        val unnumbered = tr("journal.invoice.unnumbered", emptyList())
        val numbers = line.invoiceNumbers.map { it ?: unnumbered }
        val text = if (numbers.size > 1) numbers.joinToString(" → ") else numbers.firstOrNull().orEmpty()
        return Span(text).apply { addClassName("journal-invoice__number") }
    }

    /** The row's one affordance beyond opening item-details by clicking it: an explicit chevron
     *  toggle, rotated via [expanded] so its direction always matches the panel's current state —
     *  same "symbol is never the only cue" rule as everywhere else in this view (`aria-label` +
     *  `title` alongside). */
    fun expandToggleButton(line: InvoiceAuditActionLine, expanded: Boolean, tr: (String, List<Any>) -> String, onToggle: (InvoiceAuditActionLine) -> Unit): Button {
        val icon = if (expanded) ActionIcons.COLLAPSE else ActionIcons.EXPAND
        val label = tr(if (expanded) "journal.action.collapse" else "journal.action.expand", emptyList())
        return AppButtons.icon(icon, label) { onToggle(line) }.apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
            addClassName("journal-expand-button")
        }
    }

    /** [JournalActionLabels.isExternalWrite] together with [JournalActorClassifier.isDatabaseRole]
     *  — the ONE row-level warning condition this view renders (PO decision 4: no other row is
     *  coloured for emphasis). Exposed so the grid's own row `classNameGenerator` can apply
     *  `.journal-row--warning` without duplicating the predicate. */
    fun isExternalWrite(line: InvoiceAuditActionLine): Boolean =
        JournalActionLabels.isExternalWrite(line.action) && JournalActorClassifier.isDatabaseRole(line.actor)

    private fun formatSignedDelta(delta: Money): String {
        val sign = if (delta.isNegative()) "" else "+"
        return sign + MoneyFormatter.format(delta)
    }

    /** The action's technical rows, exactly as the former flat grid rendered them (event badge,
     *  summary, seq, hash prefix) — the existing [JournalEntryDialog] still opens from here for the
     *  full old/new values and hashes. Reuses [JournalGridColumns] verbatim; nothing the row-level
     *  view offered is lost, it only moved one level down (Grid item-details). */
    fun technicalRowsDetails(
        line: InvoiceAuditActionLine,
        tr: (String, List<Any>) -> String,
        trOrNull: (String) -> String?,
        onOpenDialog: (InvoiceAuditJournalEntry) -> Unit,
    ): Component = Div().apply {
        addClassName("journal-technical-rows")
        line.rows.sortedBy { it.seq }.forEach { row ->
            add(
                Div().apply {
                    addClassName("journal-technical-row")
                    add(JournalGridColumns.entityCell(row, tr))
                    add(JournalGridColumns.eventBadge(row, tr))
                    add(JournalGridColumns.summaryCell(row, tr, trOrNull).apply { addClassName("journal-technical-row__summary") })
                    add(Span(row.seq.toString()).apply { addClassName("journal-technical-row__seq") })
                    add(
                        Span(row.entryHash.take(HASH_PREVIEW) + "…").apply {
                            addClassName("journal-technical-row__hash")
                            element.setAttribute("title", row.entryHash)
                        },
                    )
                    add(JournalGridColumns.detailButton(row, tr, onOpenDialog))
                },
            )
        }
    }

    private const val HASH_PREVIEW = 12
}
