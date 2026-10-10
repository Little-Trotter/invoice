package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.ui.component.ActionIcons

/**
 * The journal's two-symbol vocabulary: a **verb** symbol (what happened — added, changed, removed)
 * and an **entity** symbol (to what — invoice header, line item, document, trip expense line).
 * Split deliberately along the same two axes [JournalEventLabels.verbOf]/[JournalEventLabels
 * .entityOf] already establish, so twelve event constants (the `trip_expense` triple was added)
 * need twelve labels but only seven symbols — a reader learns the grid by scanning two short
 * alphabets, not one long one.
 *
 * Symbols are never the only cue: every place these render carries the translated label text (or,
 * for the icon-only entity tile, a `title` attribute) alongside — colour and glyph stay redundant
 * with the text, per the same accessibility rule the `.status-chip` set follows.
 *
 * The `Icon`-building helpers live here rather than in [JournalGridColumns] because the detail
 * dialog needs the identical symbols at a different size.
 */
object JournalEventIcons {

    fun verbIcon(event: InvoiceAuditEvent): VaadinIcon = when (JournalEventLabels.verbOf(event)) {
        JournalEventLabels.Verb.CREATED -> ActionIcons.ADD
        JournalEventLabels.Verb.UPDATED -> ActionIcons.EDIT
        JournalEventLabels.Verb.DELETED -> ActionIcons.DELETE
        JournalEventLabels.Verb.ASSIGNED -> VaadinIcon.LINK
        JournalEventLabels.Verb.UNASSIGNED -> VaadinIcon.UNLINK
    }

    fun entityIcon(event: InvoiceAuditEvent): VaadinIcon = when (JournalEventLabels.entityOf(event)) {
        JournalEventLabels.Entity.INVOICE -> VaadinIcon.FILE_TEXT_O
        JournalEventLabels.Entity.ITEM -> VaadinIcon.LIST
        JournalEventLabels.Entity.DOCUMENT -> ActionIcons.ATTACHMENTS
        JournalEventLabels.Entity.TRIP_EXPENSE -> VaadinIcon.SUITCASE
        JournalEventLabels.Entity.RECEIPT -> ActionIcons.ATTACHMENTS
    }

    /** The filled square that carries [entityIcon] in the grid's leading column and in the
     *  dialog's header — a Bauhaus primitive, tinted by the same verb colour the event chip uses,
     *  so a row's colour reads identically from its far left and its middle. */
    fun entityTile(event: InvoiceAuditEvent, label: String): Icon =
        entityIcon(event).create().apply {
            addClassNames("journal-tile", "journal-tile--${JournalEventLabels.cssModifier(event)}")
            element.setAttribute("title", label)
            element.setAttribute("aria-hidden", "true")
        }

    /** [verbIcon] sized to sit inside a `.status-chip` next to its label text. */
    fun chipIcon(event: InvoiceAuditEvent): Icon =
        verbIcon(event).create().apply {
            addClassName("journal-chip-icon")
            element.setAttribute("aria-hidden", "true")
        }
}
