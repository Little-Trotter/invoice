package de.littletrotter.ui.view.journal.composer

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A direct-SQL write (action 0, actor a DB role) names WHICH columns it touched, field by
 *  field, matching the mockup's "1 Feld geändert: due_date". */
class ExternalWriteFamilyComposerTest {

    @Test
    fun `a single changed field is named outright`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, action = InvoiceAuditAction.UNSPECIFIED, detail = """{"due_date": {"old": "2026-01-01", "new": "2026-02-01"}}""")
        val line = testLine(action = InvoiceAuditAction.UNSPECIFIED, actor = "lt_owner", rows = listOf(row))

        val result = ExternalWriteFamilyComposer.compose(line, testCtx { key -> if (key == "journal.field.due_date") "Fälligkeitsdatum" else null })

        assertEquals("journal.what.externalWrite(1,Fälligkeitsdatum)", result)
    }

    @Test
    fun `more than three touched columns collapse into a plus-n-more count`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_UPDATED,
            action = InvoiceAuditAction.UNSPECIFIED,
            detail = """{"due_date":{"old":"a","new":"b"},"free_text":{"old":"c","new":"d"},
                |"buyer_reference":{"old":"e","new":"f"},"cost_center":{"old":"g","new":"h"}}""".trimMargin(),
        )
        val line = testLine(action = InvoiceAuditAction.UNSPECIFIED, actor = "lt_owner", rows = listOf(row))

        val result = ExternalWriteFamilyComposer.compose(line, testCtx())

        assertEquals(true, result?.startsWith("journal.what.externalWriteMore(4,"))
        assertEquals(true, result?.endsWith(",1)"))
    }

    /** An INSERT-shaped row (a direct SQL insert, not an update) renders as a
     *  [de.littletrotter.ui.view.journal.JournalDetailRendering.Snapshot] rather than a diff — only
     *  its actually populated columns are named, a `null` column is not counted as "touched". */
    @Test
    fun `an inserted row names only its populated columns, not a null one`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_CREATED,
            action = InvoiceAuditAction.UNSPECIFIED,
            detail = """{"invoice_number": "RE-2026-0099", "free_text": null}""",
        )
        val line = testLine(action = InvoiceAuditAction.UNSPECIFIED, actor = "lt_owner", rows = listOf(row))

        val result = ExternalWriteFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.externalWrite(1,Invoice number)", result)
    }

    @Test
    fun `no changed or populated fields at all yields null`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, action = InvoiceAuditAction.UNSPECIFIED, detail = "{}")
        val line = testLine(action = InvoiceAuditAction.UNSPECIFIED, actor = "lt_owner", rows = listOf(row))

        assertNull(ExternalWriteFamilyComposer.compose(line, testCtx()))
    }
}
