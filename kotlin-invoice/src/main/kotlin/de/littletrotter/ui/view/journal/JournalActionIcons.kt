package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.ui.component.ActionIcons

/**
 * One symbol per [InvoiceAuditAction], for the grouped grid's leading "tile" column (see
 * [JournalActionGridColumns.actionTileCell]). Same "symbol is never the only carrier of meaning"
 * rule [JournalEventIcons] follows: every place [actionTile] renders also carries the translated
 * `journal.action.<NAME>` label text as a `title`/`aria-label`, or as the adjoining "Vorgang" cell
 * text in the grid.
 *
 * Every icon here is one already used elsewhere in this app (no new icon font, no CDN) —
 * [VaadinIcon] only.
 */
object JournalActionIcons {

    private val iconMapping = mapOf(
        InvoiceAuditAction.UNSPECIFIED to VaadinIcon.WARNING,
        InvoiceAuditAction.INVOICE_CREATED to ActionIcons.ADD,
        InvoiceAuditAction.HEADER_CHANGED to VaadinIcon.EDIT,
        InvoiceAuditAction.RECIPIENT_CHANGED to VaadinIcon.USER,
        InvoiceAuditAction.ITEM_ADDED to ActionIcons.ADD,
        InvoiceAuditAction.ITEM_CHANGED to ActionIcons.EDIT,
        InvoiceAuditAction.ITEM_DELETED to ActionIcons.DELETE,
        InvoiceAuditAction.RECALCULATED to VaadinIcon.CALC,
        InvoiceAuditAction.STATUS_CHANGED to VaadinIcon.REFRESH,
        InvoiceAuditAction.SEND_REQUESTED to VaadinIcon.PAPERPLANE,
        InvoiceAuditAction.DOCUMENT_GENERATED to VaadinIcon.FILE_TEXT_O,
        InvoiceAuditAction.VALIDATION_RECORDED to VaadinIcon.CHECK_CIRCLE,
        InvoiceAuditAction.CANCELLATION to VaadinIcon.BAN,
        InvoiceAuditAction.RECEIPT_ASSIGNED to VaadinIcon.LINK,
        InvoiceAuditAction.RECEIPT_DETACHED to VaadinIcon.UNLINK,
        InvoiceAuditAction.TRIP_SAVED to VaadinIcon.SUITCASE,
        InvoiceAuditAction.REVERSE_CHARGE_DECIDED to VaadinIcon.MONEY,
        InvoiceAuditAction.INVOICE_DELETED to ActionIcons.DELETE,
        InvoiceAuditAction.DOCUMENT_REGENERATED to VaadinIcon.REFRESH,
        InvoiceAuditAction.DOCUMENT_RESTORED to VaadinIcon.RECYCLE,
        InvoiceAuditAction.DOCUMENT_REPRODUCED to VaadinIcon.COPY_O,
        InvoiceAuditAction.DOCUMENT_REPRODUCTION_SEND_BLOCKED to VaadinIcon.BAN,
        InvoiceAuditAction.CREATED_FROM_QUOTE to ActionIcons.CREATE_INVOICE,
    )

    fun icon(action: InvoiceAuditAction): VaadinIcon =
        iconMapping[action] ?: error("Unknown InvoiceAuditAction: $action")

    /** The filled square carrying [icon] — same `.journal-tile`/`.journal-tile--*` idiom
     *  [JournalEventIcons.entityTile] already established, tinted by
     *  [JournalActionLabels.cssModifier] instead of an event's verb colour. */
    fun actionTile(action: InvoiceAuditAction, label: String): Icon =
        tile(icon(action), JournalActionLabels.cssModifier(action), label)

    /** The shared tile: [icon] on a square tinted by one of the `journal-tile--*` tones, with
     *  [label] as tooltip only (the neighbouring cell spells the meaning out). */
    fun tile(icon: VaadinIcon, modifier: String, label: String): Icon =
        icon.create().apply {
            addClassNames("journal-tile", "journal-tile--$modifier")
            element.setAttribute("title", label)
            element.setAttribute("aria-hidden", "true")
        }
}
