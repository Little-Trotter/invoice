package de.littletrotter.ui.view.journal.composer

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TripFamilyComposerTest {

    @Test
    fun `a created trip expense names its description and net amount`() {
        val row = testRow(event = InvoiceAuditEvent.TRIP_EXPENSE_CREATED, detail = """{"description": "Hotel Muenchen", "net_amount": "120.00"}""")
        val line = testLine(action = InvoiceAuditAction.TRIP_SAVED, rows = listOf(row))

        val result = TripFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.tripLineWithAmount(Hotel Muenchen,120,00 EUR)", result)
    }

    @Test
    fun `several trip lines saved in one call are all named`() {
        val rows = listOf(
            testRow(seq = 1L, event = InvoiceAuditEvent.TRIP_EXPENSE_CREATED, detail = """{"description": "Hotel", "net_amount": "120.00"}"""),
            testRow(seq = 2L, event = InvoiceAuditEvent.TRIP_EXPENSE_UPDATED, detail = """{"net_amount": {"old": "40.00", "new": "55.00"}}"""),
        )
        val line = testLine(action = InvoiceAuditAction.TRIP_SAVED, rows = rows)

        val result = TripFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.tripLineWithAmount(Hotel,120,00 EUR); journal.what.tripLineWithAmount(journal.what.tripLineUnknown,55,00 EUR)", result)
    }

    /** A deleted trip line is named from its pre-delete snapshot too; a malformed `net_amount` (never
     *  expected in practice, but the parser must not throw) means no amount can be shown at all —
     *  the plain description, not `journal.what.tripLineWithAmount`, carries the phrase. */
    @Test
    fun `a deleted trip line with a malformed amount is named by its description alone`() {
        val row = testRow(event = InvoiceAuditEvent.TRIP_EXPENSE_DELETED, detail = """{"description": "Altposition", "net_amount": "n-a"}""")
        val line = testLine(action = InvoiceAuditAction.TRIP_SAVED, rows = listOf(row))

        val result = TripFamilyComposer.compose(line, testCtx())

        assertEquals("Altposition", result)
    }

    /** The description can also come from an UPDATE's own diff (not only a snapshot) — and with no
     *  `net_amount` anywhere on that row, no amount phrase is built either. */
    @Test
    fun `a changed description with no amount at all is named from the diff, without an amount phrase`() {
        val row = testRow(event = InvoiceAuditEvent.TRIP_EXPENSE_UPDATED, detail = """{"description": {"old": "Vorher", "new": "Nachher"}}""")
        val line = testLine(action = InvoiceAuditAction.TRIP_SAVED, rows = listOf(row))

        val result = TripFamilyComposer.compose(line, testCtx())

        assertEquals("Nachher", result)
    }

    @Test
    fun `no trip_expense rows at all yields null`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"free_text": {"old": "a", "new": "b"}}""")
        val line = testLine(action = InvoiceAuditAction.TRIP_SAVED, rows = listOf(row))

        assertNull(TripFamilyComposer.compose(line, testCtx()))
    }
}
