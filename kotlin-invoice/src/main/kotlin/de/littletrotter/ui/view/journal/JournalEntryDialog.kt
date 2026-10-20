package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.Scroller
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.orPlaceholder
import java.time.format.DateTimeFormatter

private const val ARIA_HIDDEN = "aria-hidden"

/**
 * The read-only detail popup for one journal entry — the answer to "the grid shows changes, not
 * entries": the grid now carries one legible line per *event*, and the payload behind it lives
 * here, laid out as a real diff instead of being squeezed into a cell.
 *
 * Three zones, in the order a reader asks for them: **what** (event symbol, chip and the invoice
 * it happened to), **who/when** (a meta strip), **what exactly** (the field lines). The append-only
 * hash that seals the entry sits in the footer — present for anyone who wants it, out of the way
 * of everyone who doesn't.
 *
 * A fresh invoice's INSERT snapshot carries every column of a ~50-column table, most of them
 * empty. Those are hidden by default behind a count and a checkbox, and a field search appears
 * once a payload is long enough for scrolling to be the slower way to find one line.
 *
 * Read-only by construction, like the grids that open it: nothing here writes, and the only
 * buttons are "close" and the browser's own text selection.
 */
class JournalEntryDialog(
    private val tr: (String, List<Any>) -> String,
    private val trOrNull: (String) -> String?,
) {

    /** `internal` so Karibu tests can drive the toggle/search without going through the DOM —
     *  same reasoning the panels' own grids are `internal` for. */
    internal val showEmptyFields = Checkbox()
    internal val fieldSearch = TextField()
    internal val dialog = AppDialog("", AppDialog.Size.L)

    /** Header and footer content live in containers this class owns, refilled per [open], rather
     *  than being added to `dialog.header`/`dialog.footer` afresh each time: Vaadin attaches those
     *  as virtual children, which neither `children` nor a test can walk back out. */
    internal val headerContent = Div()
    internal val footerContent = Div()

    private val lineHost = Div()
    private var summary: JournalEntrySummary = JournalEntrySummary(emptyList(), 0, 0, false)

    init {
        dialog.addClassName("journal-dialog")
        dialog.isResizable = true
        dialog.isDraggable = true

        showEmptyFields.addValueChangeListener { renderLines() }
        fieldSearch.placeholder = tr("journal.dialog.search", emptyList())
        fieldSearch.element.setAttribute("aria-label", tr("journal.dialog.search", emptyList()))
        fieldSearch.isClearButtonVisible = true
        fieldSearch.valueChangeMode = ValueChangeMode.LAZY
        fieldSearch.addValueChangeListener { renderLines() }
        fieldSearch.addClassName("journal-dialog__search")

        lineHost.addClassName("journal-lines")
        headerContent.setWidthFull()
        footerContent.setWidthFull()
        dialog.header.add(headerContent)
        dialog.footer.add(footerContent)
    }

    fun open(entry: InvoiceAuditJournalEntry) {
        summary = JournalEntryContent.summarize(entry, trOrNull) { key -> tr(key, emptyList()) }
        showEmptyFields.value = false
        fieldSearch.value = ""
        fieldSearch.isVisible = summary.totalCount > SEARCH_THRESHOLD
        showEmptyFields.isVisible = !summary.isChange && summary.totalCount > summary.changeCount
        showEmptyFields.label = tr("journal.dialog.showEmpty", listOf(summary.totalCount - summary.changeCount))

        dialog.removeAll()
        headerContent.removeAll()
        headerContent.add(buildHeader(entry))
        dialog.add(buildBody(entry))
        footerContent.removeAll()
        footerContent.add(buildFooter(entry))
        renderLines()
        dialog.open()
    }

    private fun buildHeader(entry: InvoiceAuditJournalEntry): Div = Div().apply {
        addClassNames("journal-dialog__head", "journal-dialog__head--${JournalEventLabels.cssModifier(entry.event)}")
        val eventLabel = tr(JournalEventLabels.labelKey(entry.event), emptyList())
        add(JournalEventIcons.entityTile(entry.event, tr(JournalEventLabels.entityLabelKey(entry.event), emptyList())))
        add(
            Div().apply {
                addClassName("journal-dialog__titles")
                add(Span(eventLabel).apply { addClassName("journal-dialog__title") })
                add(
                    Span(
                        entry.invoiceNumber.orPlaceholder(tr("journal.invoice.unnumbered", emptyList())),
                    ).apply { addClassName("journal-dialog__subtitle") },
                )
            },
        )
    }

    private fun buildBody(entry: InvoiceAuditJournalEntry): Div = Div().apply {
        addClassName("journal-dialog__body")
        add(buildMetaStrip(entry))
        add(
            Div().apply {
                addClassName("journal-dialog__toolbar")
                add(
                    Span(
                        if (summary.isChange) {
                            tr("journal.dialog.changeCount", listOf(summary.changeCount))
                        } else {
                            tr("journal.dialog.fieldCount", listOf(summary.changeCount, summary.totalCount))
                        },
                    ).apply { addClassName("journal-dialog__count") },
                )
                add(fieldSearch, showEmptyFields)
            },
        )
        add(Scroller(lineHost).apply { addClassName("journal-dialog__scroller") })
    }

    private fun buildMetaStrip(entry: InvoiceAuditJournalEntry): Div = Div().apply {
        addClassName("journal-dialog__meta")
        add(metaCell(VaadinIcon.CLOCK, tr("journal.column.occurredAt", emptyList()), TIMESTAMP.format(entry.occurredAt)))
        add(metaCell(VaadinIcon.USER, tr("journal.column.actor", emptyList()), JournalGridColumns.actorText(entry)))
        add(metaCell(VaadinIcon.FILE_TEXT_O, tr("journal.column.invoiceNumber", emptyList()), entry.invoiceNumber.orPlaceholder()))
        add(metaCell(VaadinIcon.HASH, tr("journal.dialog.seq", emptyList()), entry.seq.toString()))
    }

    private fun metaCell(icon: VaadinIcon, label: String, value: String): Div = Div().apply {
        addClassName("journal-meta")
        add(icon.create().apply { addClassName("journal-meta__icon"); element.setAttribute(ARIA_HIDDEN, "true") })
        add(
            Div().apply {
                addClassName("journal-meta__text")
                add(Span(label).apply { addClassName("journal-meta__label") })
                add(Span(value).apply { addClassName("journal-meta__value") })
            },
        )
    }

    /** The entry's own hash — the reason this journal is evidence and not just a log. Shown
     *  truncated (a 64-char hex string is unreadable inline) with the full value on hover. */
    private fun buildFooter(entry: InvoiceAuditJournalEntry): Div = Div().apply {
        addClassName("journal-dialog__foot")
        add(
            Div().apply {
                addClassName("journal-hash")
                add(VaadinIcon.LOCK.create().apply { addClassName("journal-hash__icon"); element.setAttribute(ARIA_HIDDEN, "true") })
                add(Span(tr("journal.dialog.hash", emptyList())).apply { addClassName("journal-hash__label") })
                add(
                    Span(entry.entryHash.take(HASH_PREVIEW) + "…").apply {
                        addClassName("journal-hash__value")
                        element.setAttribute("title", entry.entryHash)
                    },
                )
            },
        )
        add(
            AppButtons.tertiary(tr("app.close", emptyList())) { dialog.close() },
        )
    }

    private fun renderLines() {
        lineHost.removeAll()
        val visible = visibleLines()
        if (visible.isEmpty()) {
            lineHost.add(
                Span(tr("journal.detail.empty", emptyList())).apply { addClassName("journal-lines__empty") },
            )
            return
        }
        visible.forEach { lineHost.add(if (it.isChange) changeRow(it) else snapshotRow(it)) }
    }

    /** `internal` so a test can assert the toggle/search actually narrow the payload without
     *  reaching into the rendered DOM. */
    internal fun visibleLines(): List<JournalEntryLine> {
        val needle = fieldSearch.value.orEmpty().trim().lowercase()
        return summary.lines
            .filter { showEmptyFields.value == true || !it.isEmpty }
            .filter { needle.isEmpty() || it.label.lowercase().contains(needle) || it.field.lowercase().contains(needle) }
    }

    private fun changeRow(line: JournalEntryLine): Div = Div().apply {
        addClassNames("journal-line", "journal-line--change")
        add(Span(line.label).apply { addClassName("journal-line__label") })
        add(Span(line.oldValue.orPlaceholder()).apply { addClassName("journal-line__old") })
        add(VaadinIcon.ARROW_RIGHT.create().apply { addClassName("journal-line__arrow"); element.setAttribute(ARIA_HIDDEN, "true") })
        add(Span(line.newValue.orPlaceholder()).apply { addClassName("journal-line__new") })
    }

    private fun snapshotRow(line: JournalEntryLine): Div = Div().apply {
        addClassNames("journal-line", "journal-line--snapshot")
        add(Span(line.label).apply { addClassName("journal-line__label") })
        add(Span(line.newValue.orPlaceholder()).apply { addClassName("journal-line__value") })
    }
}

private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(GermanTime.ZONE)

/** Payloads shorter than this fit on one screen — a search box above them would be one
 *  more control to skip past, not a shortcut. */
private const val SEARCH_THRESHOLD = 12
private const val HASH_PREVIEW = 12
