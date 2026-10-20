package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.grid.GridVariant
import com.vaadin.flow.component.textfield.TextArea
import de.littletrotter.application.quote.port.inbound.AddQuoteNoteUseCase
import de.littletrotter.application.quote.port.inbound.QuoteLogEntry
import de.littletrotter.application.quote.port.inbound.QuoteLogQueryPort
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.journalBand
import de.littletrotter.ui.component.showJournalCount
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.view.journal.JournalCardViewportFit
import de.littletrotter.ui.view.journal.JournalGridColumns
import de.littletrotter.ui.view.journal.JournalPanelParts

private const val NOTE_MAX_LENGTH = 2000

/**
 * The "Protokoll" of an offer, built to look and behave exactly like the invoice's journal so both
 * histories read the same: one card that reaches down to the window edge (shared
 * [JournalCardViewportFit]), a band with title, entry count and the note action, and a striped grid
 * of symbol tile, time, user, action and details. The time column carries the same sort arrow and
 * toggle as the invoice, newest first by default because the latest change is what a reader looks
 * for; the list is small, so it is sorted in memory. Annotating is a single right-aligned "Notiz"
 * button for those who may edit; it opens a small dialog and the grid reloads after saving.
 */
internal class QuoteProtocolPanel(
    private val host: TranslationProvider,
    private val logQuery: QuoteLogQueryPort,
    private val addNote: AddQuoteNoteUseCase,
) : VerticalLayout() {

    internal val grid = DataTable(QuoteLogEntry::class.java)
    internal val resultCount = Span().apply { addClassName("journal-band__count") }
    internal val emptyState = Paragraph(host.tr("quote.log.empty")).apply {
        addClassNames("empty-state", "journal-invoice-card__empty")
        isVisible = false
    }
    internal val noteButton = AppButtons.secondary(host.tr("quote.log.note.add"), ActionIcons.NOTE) { openNoteDialog() }

    private val labels = QuoteLabels(host)
    private var quoteId: String? = null
    private var direction = SortDirection.DESC
    private var entries: List<QuoteLogEntry> = emptyList()
    internal val viewportFit = JournalCardViewportFit(this)

    init {
        isPadding = false
        isSpacing = true
        setWidthFull()
        // Stretch, so the card spans the tab instead of shrinking to the grid's intrinsic width.
        alignItems = FlexComponent.Alignment.STRETCH
        noteButton.addThemeVariants(ButtonVariant.LUMO_SMALL)
        noteButton.isVisible = false
        configureGrid()
        add(
            Div(grid, emptyState).apply {
                addClassNames("ino-card", "journal-invoice-card", "journal-invoice-card--fill", "lt-w-full", "lt-border-box")
            },
        )
    }

    /** Binds the panel to [id] and loads the history, returning it; the note button is shown only when [mayAddNote]. */
    fun show(id: String, mayAddNote: Boolean): List<QuoteLogEntry> {
        quoteId = id
        noteButton.isVisible = mayAddNote
        reload()
        return entries
    }

    fun reload() {
        val id = quoteId ?: return
        entries = guarded { logQuery.log(id, emptySet()) } ?: emptyList()
        showItems()
        showCount(host.tr("quote.log.count", entries.size))
        emptyState.isVisible = entries.isEmpty()
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        viewportFit.register()
    }

    override fun onDetach(detachEvent: DetachEvent) {
        super.onDetach(detachEvent)
        viewportFit.unregister(detachEvent.ui)
    }

    private fun showItems() {
        val byTime = entries.sortedBy { it.occurredAt }
        grid.setItems(if (direction == SortDirection.DESC) byTime.reversed() else byTime)
    }

    private fun configureGrid() {
        grid.setWidthFull()
        grid.addClassName("journal-grid")
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER)
        grid.isAllRowsVisible = false
        grid.addComponentColumn { QuoteLogIcons.tile(it.kind, labels.logKind(it.kind)) }.setHeader("").asCompactColumn().key = "tile"
        grid.addComponentColumn {
            JournalGridColumns.occurredAtCell(it.occurredAt.atZone(GermanTime.ZONE).toOffsetDateTime())
        }.setHeader(host.tr("journal.column.occurredAt"))
            .asCompactColumn()
            .setSortable(true)
            .setSortProperty(JournalPanelParts.OCCURRED_AT_KEY)
            .key = JournalPanelParts.OCCURRED_AT_KEY
        grid.addColumn { it.actor }.setHeader(host.tr("journal.column.actor")).asCompactColumn().key = "actor"
        grid.addComponentColumn { Span(labels.logKind(it.kind)).apply { addClassName("journal-action-label") } }
            .setHeader(host.tr("journal.column.action")).asCompactColumn().key = "action"
        grid.addComponentColumn { Span(it.text).apply { addClassName("journal-what") } }
            .setHeader(host.tr("journal.column.what")).asTextColumn(TextColumnWidth.LARGE).key = "what"
        JournalPanelParts.installSortListener(grid, { direction }) {
            direction = it
            showItems()
        }
        grid.prependHeaderRow().join(*grid.columns.toTypedArray()).apply {
            component = buildBand()
            partName = "journal-band-cell"
        }
        JournalPanelParts.showSortArrow(grid, direction)
    }

    /** Symbol and title on the left, entry count and the note action on the right. */
    private fun buildBand(): Div = journalBand(
        H3(host.tr("quote.log.title")).apply { addClassName("journal-band__title") },
        resultCount,
        ActionBar(noteButton).apply { width = "auto" },
    )

    private fun showCount(text: String) = resultCount.showJournalCount(text)

    private fun openNoteDialog() {
        val field = TextArea(host.tr("quote.log.note.label")).apply {
            setWidthFull()
            maxLength = NOTE_MAX_LENGTH
        }
        val dialog = AppDialog(host.tr("quote.log.note.add"), AppDialog.Size.M)
        val cancel = AppButtons.tertiary(host.tr("app.cancel")) { dialog.close() }
        val save = AppButtons.primary(host.tr("app.save")) { saveNote(dialog, field) }.apply { isEnabled = false }
        field.addValueChangeListener { save.isEnabled = it.value.isNotBlank() }
        dialog.add(field)
        dialog.footer(cancel, save)
        dialog.open()
    }

    private fun saveNote(dialog: Dialog, field: TextArea) {
        val id = quoteId ?: return
        if (guarded { addNote.addNote(id, field.value.trim()) } != null) {
            dialog.close()
            reload()
        }
    }
}
