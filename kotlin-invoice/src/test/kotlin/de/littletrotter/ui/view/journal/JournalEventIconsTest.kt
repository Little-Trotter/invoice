package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [JournalEventIcons] — the journal's verb/entity symbol vocabulary, pinned against every
 *  [InvoiceAuditEvent] so a future thirteenth event (a new verb suffix or entity prefix) fails
 *  loudly here instead of silently falling through [JournalEventLabels]'s `error(...)` branch. */
class JournalEventIconsTest {

    @Test
    fun `happy path - every event's verb icon matches its JournalEventLabels verb`() {
        InvoiceAuditEvent.entries.forEach { event ->
            val expected = when (JournalEventLabels.verbOf(event)) {
                JournalEventLabels.Verb.CREATED -> VaadinIcon.PLUS
                JournalEventLabels.Verb.UPDATED -> VaadinIcon.PENCIL
                JournalEventLabels.Verb.DELETED -> VaadinIcon.TRASH
                JournalEventLabels.Verb.ASSIGNED -> VaadinIcon.LINK
                JournalEventLabels.Verb.UNASSIGNED -> VaadinIcon.UNLINK
            }
            assertEquals(expected, JournalEventIcons.verbIcon(event), "verb icon for $event")
        }
    }

    @Test
    fun `boundary - every event's entity icon matches its JournalEventLabels entity`() {
        InvoiceAuditEvent.entries.forEach { event ->
            val expected = when (JournalEventLabels.entityOf(event)) {
                JournalEventLabels.Entity.INVOICE -> VaadinIcon.FILE_TEXT_O
                JournalEventLabels.Entity.ITEM -> VaadinIcon.LIST
                JournalEventLabels.Entity.DOCUMENT -> VaadinIcon.PAPERCLIP
                JournalEventLabels.Entity.TRIP_EXPENSE -> VaadinIcon.SUITCASE
                JournalEventLabels.Entity.RECEIPT -> VaadinIcon.PAPERCLIP
            }
            assertEquals(expected, JournalEventIcons.entityIcon(event), "entity icon for $event")
        }
    }

    @Test
    fun `boundary - entityTile carries the journal-tile class, the verb colour modifier, the label as title and is aria-hidden`() {
        val event = InvoiceAuditEvent.INVOICE_ITEM_DELETED

        val tile = JournalEventIcons.entityTile(event, "Position gelöscht")

        assertTrue(tile.hasClassName("journal-tile"))
        assertTrue(tile.hasClassName("journal-tile--${JournalEventLabels.cssModifier(event)}"))
        assertEquals("Position gelöscht", tile.element.getAttribute("title"))
        assertEquals("true", tile.element.getAttribute("aria-hidden"))
    }

    @Test
    fun `boundary - chipIcon carries the chip-icon class and is aria-hidden, matching verbIcon's glyph`() {
        val event = InvoiceAuditEvent.INVOICE_UPDATED

        val chip = JournalEventIcons.chipIcon(event)

        assertTrue(chip.hasClassName("journal-chip-icon"))
        assertEquals("true", chip.element.getAttribute("aria-hidden"))
    }
}
