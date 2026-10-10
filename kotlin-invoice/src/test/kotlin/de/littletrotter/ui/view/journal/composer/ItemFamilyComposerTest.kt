package de.littletrotter.ui.view.journal.composer

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ItemFamilyComposerTest {

    @Test
    fun `an added item names position, description, quantity and price`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_CREATED,
            detail = """{"position_id": "3", "description": "Kabelkanal 40x60", "quantity": "12", "unit_price_net": "10.00"}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_ADDED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemAdded(3,Kabelkanal 40x60,12,10,00 EUR)", result)
    }

    @Test
    fun `a deleted item names position and description from the pre-delete snapshot`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_DELETED,
            detail = """{"position_id": "2", "description": "Altposition"}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_DELETED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemDeleted(2,Altposition)", result)
    }

    @Test
    fun `a changed quantity names the field label and both values`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"_row": {"id": "10", "position_id": "1"}, "quantity": {"old": "5", "new": "8"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx { key -> if (key == "journal.field.quantity") "Menge" else null })

        assertEquals("journal.what.itemChanged(1,Menge: 5 → 8)", result)
    }

    /** An unknown/uncurated JSON field falls back to the raw column name (no crash), via
     *  [de.littletrotter.ui.view.journal.JournalFieldLabels.humanize]. */
    @Test
    fun `an unknown changed column falls back to its own humanized raw name`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"_row": {"id": "10", "position_id": "1"}, "some_new_column": {"old": "a", "new": "b"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemChanged(1,Some new column: a → b)", result)
    }

    /** The trigger writes `_row` on every UPDATE unconditionally, so a real ITEM_CHANGED
     *  row always carries it; this exercises the position number coming from `_row.position_id`
     *  specifically, not from a coincidentally-present flat `position_id` diff key. */
    @Test
    fun `the position number comes from _row, not from a flat position_id key`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"_row": {"id": "99", "position_id": "7"}, "description": {"old": "a", "new": "b"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemChanged(7,Description: a → b)", result)
    }

    @Test
    fun `recalculation without a position uses the invoice's own gross delta`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"gross_amount": {"old": "100.00", "new": "142.80"}}""")
        val line = testLine(action = InvoiceAuditAction.RECALCULATED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.recalculated(42,80 EUR)", result)
    }

    @Test
    fun `recalculation with no item row and no gross delta yields null`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"due_date": {"old": "2026-01-01", "new": "2026-02-01"}}""")
        val line = testLine(action = InvoiceAuditAction.RECALCULATED, rows = listOf(row))

        assertNull(ItemFamilyComposer.compose(line, testCtx()))
    }

    @Test
    fun `an item change that touches only bookkeeping fields (position id itself) yields null`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"position_id": {"old": "1", "new": "1"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        assertNull(ItemFamilyComposer.compose(line, testCtx()))
    }

    /** A changed unit resolves through [de.littletrotter.ui.view.journal.JournalValueFormatter]'s
     *  enum-label branch rather than showing the raw SMALLINT ordinal on either side. */
    @Test
    fun `a changed unit names both sides via the unit label, not the raw ordinal`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"_row": {"id": "10", "position_id": "1"}, "unit": {"old": "0", "new": "1"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemChanged(1,Unit: invoiceDetail.unit.hour → invoiceDetail.unit.day)", result)
    }

    /** [de.littletrotter.shared.Money] formatting, never a raw decimal string, for a changed
     *  `unit_price_net` — the one item column this composer treats as money. */
    @Test
    fun `a changed unit price formats both sides as money`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"_row": {"id": "10", "position_id": "1"}, "unit_price_net": {"old": "10.00", "new": "12.50"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemChanged(1,Unit price net: 10,00 EUR → 12,50 EUR)", result)
    }

    /** A malformed `unit_price_net` value (never expected from the trigger, but the formatter must
     *  not throw) falls back to the raw text on the side that fails to parse. */
    @Test
    fun `a malformed unit price value falls back to its own raw text`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"_row": {"id": "10", "position_id": "1"}, "unit_price_net": {"old": "n-a", "new": "also-bad"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemChanged(1,Unit price net: n-a → also-bad)", result)
    }

    @Test
    fun `an added item with no quantity or price snapshot falls back to blank fields, not a crash`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_CREATED,
            detail = """{"position_id": "5", "description": "Nur Text"}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_ADDED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemAdded(5,Nur Text,,)", result)
    }

    @Test
    fun `an item change with no _row and no position_id diff at all falls back to a blank position`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"description": {"old": "a", "new": "b"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemChanged(,Description: a → b)", result)
    }

    @Test
    fun `an added item with no description key at all still names position and blank fields`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_ITEM_CREATED, detail = """{"position_id": "5"}""")
        val line = testLine(action = InvoiceAuditAction.ITEM_ADDED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemAdded(5,,,)", result)
    }

    @Test
    fun `an ITEM_CHANGED row with an empty payload and no _row yields null, not a crash`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED, detail = "{}")
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        assertNull(ItemFamilyComposer.compose(line, testCtx()))
    }

    @Test
    fun `a changed unit price with a null old value falls back to blank on that side only`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"_row": {"id": "10", "position_id": "1"}, "unit_price_net": {"old": null, "new": "12.50"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemChanged(1,Unit price net:  → 12,50 EUR)", result)
    }

    @Test
    fun `a changed description with a null old value falls back to blank on that side only`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"_row": {"id": "10", "position_id": "1"}, "description": {"old": null, "new": "Neu"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemChanged(1,Description:  → Neu)", result)
    }

    @Test
    fun `a deleted item with no snapshot at all falls back to blank position and description`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_ITEM_DELETED, detail = "{}")
        val line = testLine(action = InvoiceAuditAction.ITEM_DELETED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemDeleted(,)", result)
    }

    @Test
    fun `an added item with no position_id key at all falls back to a blank position`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_CREATED,
            detail = """{"description": "Nur Text", "quantity": "3", "unit_price_net": "5.00"}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_ADDED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemAdded(,Nur Text,3,5,00 EUR)", result)
    }

    /** `_row` present (so [de.littletrotter.ui.view.journal.JournalRowIdentity] is non-null) but
     *  without its own `position_id` key — the elvis on `.positionId` (a field access on a non-null
     *  object, distinct from the `rowIdentity(...)` call itself being null) still falls back to the
     *  flat diff, never a blank position while a real diff value exists. */
    @Test
    fun `an item change with a _row that itself lacks position_id falls back to the flat diff`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            detail = """{"_row": {"id": "10"}, "position_id": {"old": "3", "new": "4"}, "description": {"old": "a", "new": "b"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.ITEM_CHANGED, rows = listOf(row))

        val result = ItemFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.itemChanged(4,Description: a → b)", result)
    }
}
