package de.littletrotter.ui.view.journal

import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [JournalDetailRenderer.render] against the exact `detail` JSONB shapes
 *  `capture_invoice_audit_event()` writes (`V1__baseline_schema.sql`, section 13): UPDATE stores
 *  `{"column": {"old":..,"new":..}}` pairs, INSERT/DELETE store a flat row snapshot. */
class JournalDetailRendererTest {

    @Test
    fun `UPDATE detail renders as old-new pairs, one per changed field`() {
        val detail = """{"free_text": {"old": "vorher", "new": "nachher"}, "quantity": {"old": 1, "new": 2}}"""

        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_UPDATED, detail)

        assertTrue(rendering is JournalDetailRendering.Diff)
        val changes = (rendering as JournalDetailRendering.Diff).changes.associateBy { it.field }
        assertEquals(2, changes.size)
        assertEquals(JournalFieldChange("free_text", "vorher", "nachher"), changes.getValue("free_text"))
        assertEquals(JournalFieldChange("quantity", "1", "2"), changes.getValue("quantity"))
    }

    @Test
    fun `UPDATE detail with a field that changed FROM null renders old as null, not the string 'null'`() {
        val detail = """{"delivery_date": {"old": null, "new": "2026-09-04"}}"""

        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_UPDATED, detail) as JournalDetailRendering.Diff

        val change = rendering.changes.single()
        assertEquals("delivery_date", change.field)
        assertEquals(null, change.oldValue)
        assertEquals("2026-09-04", change.newValue)
    }

    @Test
    fun `UPDATE detail with a field that changed TO null renders new as null`() {
        val detail = """{"free_text": {"old": "Notiz", "new": null}}"""

        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_UPDATED, detail) as JournalDetailRendering.Diff

        val change = rendering.changes.single()
        assertEquals("Notiz", change.oldValue)
        assertEquals(null, change.newValue)
    }

    @Test
    fun `INSERT detail renders as a flat field list, not old-new pairs`() {
        val detail = """{"id": 42, "invoice_number": "RE-2026-0001", "free_text": null}"""

        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_CREATED, detail)

        assertTrue(rendering is JournalDetailRendering.Snapshot)
        val fields = (rendering as JournalDetailRendering.Snapshot).fields.associateBy { it.field }
        assertEquals(3, fields.size)
        assertEquals("42", fields.getValue("id").value)
        assertEquals("RE-2026-0001", fields.getValue("invoice_number").value)
        assertEquals(null, fields.getValue("free_text").value)
    }

    @Test
    fun `DELETE detail renders as a flat field list, same shape as INSERT`() {
        val detail = """{"id": 7, "description": "Beratung"}"""

        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_ITEM_DELETED, detail)

        assertTrue(rendering is JournalDetailRendering.Snapshot)
        assertEquals(
            setOf(JournalFieldValue("id", "7"), JournalFieldValue("description", "Beratung")),
            (rendering as JournalDetailRendering.Snapshot).fields.toSet(),
        )
    }

    @Test
    fun `an empty UPDATE payload renders as an empty Diff, not a crash`() {
        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_UPDATED, "{}")

        assertEquals(JournalDetailRendering.Diff(emptyList()), rendering)
    }

    @Test
    fun `an empty INSERT payload renders as an empty Snapshot, not a crash`() {
        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_CREATED, "{}")

        assertEquals(JournalDetailRendering.Snapshot(emptyList()), rendering)
    }

    @Test
    fun `malformed JSON renders as an empty rendering instead of throwing`() {
        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_UPDATED, "not-json-at-all{{{")

        assertEquals(JournalDetailRendering.Diff(emptyList()), rendering)
    }

    @Test
    fun `boolean and numeric values render as their JSON text, not quoted`() {
        val detail = """{"is_active": {"old": true, "new": false}}"""

        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_UPDATED, detail) as JournalDetailRendering.Diff

        val change = rendering.changes.single()
        assertEquals("true", change.oldValue)
        assertEquals("false", change.newValue)
    }

    @Test
    fun `a partial UPDATE payload with only one changed column renders exactly that one field`() {
        val detail = """{"quantity": {"old": 1, "new": 3}}"""

        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_ITEM_UPDATED, detail) as JournalDetailRendering.Diff

        assertEquals(1, rendering.changes.size)
        assertEquals("quantity", rendering.changes.single().field)
    }

    /** The reserved `_row` key: never a changed field, always the row identity. */
    @Test
    fun `an UPDATE detail's reserved _row key is never a changed field but becomes the row identity`() {
        val detail = """{"_row": {"id": 42, "position_id": "3"}, "quantity": {"old": 1, "new": 3}}"""

        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_ITEM_UPDATED, detail) as JournalDetailRendering.Diff

        assertEquals(1, rendering.changes.size, "the _row key must never surface as a changed field")
        assertEquals("quantity", rendering.changes.single().field)
        assertEquals(JournalRowIdentity(id = "42", positionId = "3"), rendering.rowIdentity)
    }

    @Test
    fun `an UPDATE detail with no _row key renders a null row identity, not a crash`() {
        val detail = """{"quantity": {"old": 1, "new": 3}}"""

        val rendering = JournalDetailRenderer.render(InvoiceAuditEvent.INVOICE_ITEM_UPDATED, detail) as JournalDetailRendering.Diff

        assertEquals(null, rendering.rowIdentity)
    }
}
