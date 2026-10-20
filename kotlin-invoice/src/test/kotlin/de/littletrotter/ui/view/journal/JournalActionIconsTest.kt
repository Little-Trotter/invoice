package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.domain.compliance.InvoiceAuditAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** [JournalActionIcons]' action -> symbol mapping, plus the tile it builds around it. A new
 *  [InvoiceAuditAction] with no branch in [JournalActionIcons.icon] fails to compile (an exhaustive
 *  `when`, no `else`), so this test locks the ACTUAL icon chosen per action rather than merely
 *  asserting one exists — a symbol swap for the wrong action is a silent regression otherwise. */
class JournalActionIconsTest {

    private val expectedIcons = mapOf(
        InvoiceAuditAction.UNSPECIFIED to VaadinIcon.WARNING,
        InvoiceAuditAction.INVOICE_CREATED to VaadinIcon.PLUS,
        InvoiceAuditAction.HEADER_CHANGED to VaadinIcon.EDIT,
        InvoiceAuditAction.RECIPIENT_CHANGED to VaadinIcon.USER,
        InvoiceAuditAction.ITEM_ADDED to VaadinIcon.PLUS,
        InvoiceAuditAction.ITEM_CHANGED to VaadinIcon.PENCIL,
        InvoiceAuditAction.ITEM_DELETED to VaadinIcon.TRASH,
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
        InvoiceAuditAction.INVOICE_DELETED to VaadinIcon.TRASH,
        InvoiceAuditAction.DOCUMENT_REGENERATED to VaadinIcon.REFRESH,
        InvoiceAuditAction.DOCUMENT_RESTORED to VaadinIcon.RECYCLE,
        InvoiceAuditAction.DOCUMENT_REPRODUCED to VaadinIcon.COPY_O,
        InvoiceAuditAction.DOCUMENT_REPRODUCTION_SEND_BLOCKED to VaadinIcon.BAN,
        InvoiceAuditAction.CREATED_FROM_QUOTE to VaadinIcon.FILE_ADD,
    )

    @Test
    fun `every InvoiceAuditAction has an entry in the expectation map - a new action fails this test, not silently`() {
        assertEquals(InvoiceAuditAction.entries.toSet(), expectedIcons.keys)
    }

    @ParameterizedTest
    @EnumSource(InvoiceAuditAction::class)
    fun `icon resolves to the exact symbol this action is documented to carry`(action: InvoiceAuditAction) {
        assertEquals(expectedIcons.getValue(action), JournalActionIcons.icon(action))
    }

    @Test
    fun `actionTile carries the icon, the success-cancelled-warning tile modifier and the given label as title`() {
        val tile = JournalActionIcons.actionTile(InvoiceAuditAction.ITEM_DELETED, "Position gelöscht")

        assertTrue(tile.classNames.containsAll(listOf("journal-tile", "journal-tile--cancelled")))
        assertEquals("Position gelöscht", tile.element.getAttribute("title"))
        assertEquals("true", tile.element.getAttribute("aria-hidden"))
    }

    @Test
    fun `actionTile picks the success modifier for a creation-shaped action`() {
        val tile = JournalActionIcons.actionTile(InvoiceAuditAction.INVOICE_CREATED, "Rechnung erstellt")

        assertTrue(tile.classNames.contains("journal-tile--success"))
    }

    @Test
    fun `actionTile picks the warning modifier for a change to something that already existed`() {
        val tile = JournalActionIcons.actionTile(InvoiceAuditAction.STATUS_CHANGED, "Status geändert")

        assertTrue(tile.classNames.contains("journal-tile--warning"))
    }
}
