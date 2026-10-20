package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.applyChipTone
import de.littletrotter.ui.component.orPlaceholder
import de.littletrotter.ui.view.invoice.buildDateTimeCell
import java.time.OffsetDateTime

/**
 * Shared, read-only cell builders for both journal views
 * ([de.littletrotter.ui.view.invoice.InvoiceJournalPanel], per invoice;
 * [de.littletrotter.ui.view.system.JournalPanel], global). `tr` takes the same
 * `(String, List<Any>) -> String` shape `GenerationJobCells`'s own extracted cell builders use, so
 * each panel wires its own component-aware `tr(key, *args)` through unchanged.
 *
 * A row answers *when, who, what happened, how much changed* — one line per event, nothing
 * ellipsised. It deliberately does not try to show the payload: a single INSERT carries every
 * column of a ~50-column table, so the old "render every field into the cell" column showed a
 * clipped fragment of one entry instead of a legible list of many. [summaryCell] names what
 * changed and counts the rest; [detailButton] opens [JournalEntryDialog] for the actual values.
 *
 * Still read-only by construction — the only affordance here opens a popup; nothing edits or
 * deletes, because the journal is append-only and there is nothing to offer.
 */
object JournalGridColumns {

    /** Same two-line date/time rendering [de.littletrotter.ui.view.invoice.InvoiceExportPanel]'s
     *  own generation-job grid uses for `createdAt`/`finishedAt` — one look for "when" across the
     *  whole app, not a journal-specific format. `tabular-nums` (journal-occurred-at, styles.css)
     *  is scoped to this cell rather than added to [buildDateTimeCell] itself, which other grids
     *  also use — the "align like a ledger" column here is now sortable, they are not. */
    fun occurredAtCell(occurredAt: OffsetDateTime): Component =
        buildDateTimeCell(occurredAt).apply { addClassName("journal-occurred-at") }

    fun actorText(entry: InvoiceAuditJournalEntry): String = entry.actorName.orPlaceholder(entry.actor)

    /** The row's leading symbol: the affected object (invoice / line item / document) as a filled
     *  square, tinted by the same verb colour [eventBadge]'s chip carries. Icon-only by design —
     *  the chip right next to it spells the event out in words, so this adds a scannable second
     *  encoding without repeating the text. */
    fun entityCell(entry: InvoiceAuditJournalEntry, tr: (String, List<Any>) -> String): Component =
        JournalEventIcons.entityTile(entry.event, tr(JournalEventLabels.entityLabelKey(entry.event), emptyList()))

    /** A status chip toned by [JournalEventLabels.tone], plus the verb symbol from [JournalEventIcons]. */
    fun eventBadge(entry: InvoiceAuditJournalEntry, tr: (String, List<Any>) -> String): Span =
        Span().apply {
            applyChipTone(JournalEventLabels.tone(entry.event))
            addClassName("journal-chip")
            add(JournalEventIcons.chipIcon(entry.event))
            add(Span(tr(JournalEventLabels.labelKey(entry.event), emptyList())))
            element.setAttribute("role", "status")
        }

    /** Invoice number over the affected object's name — the global view's "which invoice, and
     *  which part of it" cell. An unnumbered draft says so in words rather than showing a blank. */
    fun invoiceCell(entry: InvoiceAuditJournalEntry, tr: (String, List<Any>) -> String): Component = Div().apply {
        addClassName("journal-invoice")
        val number = entry.invoiceNumber
        add(
            Span(number ?: tr("journal.invoice.unnumbered", emptyList())).apply {
                addClassNames("journal-invoice__number", if (number == null) "text-muted" else "journal-invoice__number--set")
            },
        )
        add(
            Span(tr(JournalEventLabels.entityLabelKey(entry.event), emptyList()))
                .apply { addClassName("journal-invoice__entity") },
        )
    }

    /** What changed, in words: a count chip plus the first few field *names*, then "+n weitere".
     *  For an UPDATE with exactly one changed field the actual old→new pair fits, so it is shown —
     *  that single case is the one where the grid can answer the question outright. */
    fun summaryCell(
        entry: InvoiceAuditJournalEntry,
        tr: (String, List<Any>) -> String,
        trOrNull: (String) -> String?,
    ): Component {
        val summary = JournalEntryContent.summarize(entry, trOrNull) { key -> tr(key, emptyList()) }
        if (summary.changeCount == 0) {
            return Span(tr("journal.detail.empty", emptyList())).apply { addClassName("text-muted") }
        }
        return Div().apply {
            addClassName("journal-summary")
            add(
                Span(
                    if (summary.isChange) {
                        tr("journal.summary.changed", listOf(summary.changeCount))
                    } else {
                        tr("journal.summary.captured", listOf(summary.changeCount))
                    },
                ).apply { addClassName("journal-summary__count") },
            )
            add(Span(fieldPhrase(summary, tr)).apply { addClassName("journal-summary__fields") })
        }
    }

    private fun fieldPhrase(summary: JournalEntrySummary, tr: (String, List<Any>) -> String): String {
        val single = summary.namedLines().singleOrNull()
        if (single != null && single.isChange) {
            return "${single.label}: ${single.oldValue.orPlaceholder()} → ${single.newValue.orPlaceholder()}"
        }
        val names = summary.previewLabels().joinToString(", ")
        val hidden = summary.hiddenCount
        return if (hidden > 0) "$names " + tr("journal.summary.more", listOf(hidden)) else names
    }

    /** The row's one affordance: open [JournalEntryDialog]. Icon-only with an `aria-label`, the
     *  same shape every other icon-only grid action in this app uses. */
    fun detailButton(
        entry: InvoiceAuditJournalEntry,
        tr: (String, List<Any>) -> String,
        onOpen: (InvoiceAuditJournalEntry) -> Unit,
    ): Button = AppButtons.icon(ActionIcons.DETAILS, tr("journal.action.details", emptyList())) { onOpen(entry) }.apply {
        addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
        addClassName("journal-detail-button")
    }
}
