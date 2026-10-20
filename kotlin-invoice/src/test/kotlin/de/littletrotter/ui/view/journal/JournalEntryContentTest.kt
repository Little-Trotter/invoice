package de.littletrotter.ui.view.journal

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** [JournalEntryContent]: the whole payload-to-text path (JSON → labels →
 *  readable values → counts) without a UI in the way. */
class JournalEntryContentTest {

    private val trEcho: (String) -> String = { it }
    private val trNone: (String) -> String? = { null }

    private fun entry(event: InvoiceAuditEvent, detail: String) = InvoiceAuditJournalEntry(
        seq = 1L, businessTx = 1L, invoiceId = 1L, invoiceNumber = "RE-2026-0001",
        actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        event = event, action = InvoiceAuditAction.UNSPECIFIED, detail = detail,
        documentSha256 = null, prevHash = "prev", entryHash = "hash",
    )

    private fun summarize(event: InvoiceAuditEvent, detail: String) =
        JournalEntryContent.summarize(entry(event, detail), trNone, trEcho)

    @Test
    fun `an UPDATE becomes one change line per changed column, with both sides formatted`() {
        val summary = summarize(
            InvoiceAuditEvent.INVOICE_UPDATED,
            """{"status": {"old": 0, "new": 1}, "free_text": {"old": null, "new": "Notiz"}}""",
        )

        assertTrue(summary.isChange)
        assertEquals(2, summary.changeCount)
        val status = summary.lines.first { it.field == "status" }
        assertEquals("invoice.status.draft", status.oldValue)
        assertEquals("invoice.status.review", status.newValue)
        assertEquals("Status", status.label, "no journal.field key resolves in this test, so the humanized fallback applies")
    }

    @Test
    fun `an INSERT snapshot counts only the columns that carry something`() {
        val summary = summarize(
            InvoiceAuditEvent.INVOICE_CREATED,
            """{"invoice_number": "RE-2026-0001", "free_text": null, "cost_center": null}""",
        )

        assertFalse(summary.isChange)
        assertEquals(1, summary.changeCount)
        assertEquals(3, summary.totalCount, "the empty columns are kept so the dialog can offer them behind its toggle")
    }

    @Test
    fun `namedLines drops a snapshot's empty columns but keeps every change`() {
        val snapshot = summarize(InvoiceAuditEvent.INVOICE_CREATED, """{"invoice_number": "RE-1", "free_text": null}""")
        assertEquals(listOf("invoice_number"), snapshot.namedLines().map { it.field })

        val update = summarize(InvoiceAuditEvent.INVOICE_UPDATED, """{"free_text": {"old": "a", "new": null}}""")
        assertEquals(listOf("free_text"), update.namedLines().map { it.field })
    }

    @Test
    fun `only the first three fields are named, the rest are counted`() {
        val summary = summarize(
            InvoiceAuditEvent.INVOICE_UPDATED,
            """
            {"free_text": {"old": null, "new": "a"},
             "cost_center": {"old": null, "new": "b"},
             "project_number": {"old": null, "new": "c"},
             "buyer_reference": {"old": null, "new": "d"},
             "due_date": {"old": null, "new": "2026-10-01"}}
            """.trimIndent(),
        )

        assertEquals(3, summary.previewLabels().size)
        assertEquals(2, summary.hiddenCount)
    }

    @Test
    fun `an empty payload produces no lines and nothing to count`() {
        val summary = summarize(InvoiceAuditEvent.INVOICE_UPDATED, "{}")

        assertTrue(summary.lines.isEmpty())
        assertEquals(0, summary.changeCount)
        assertEquals(0, summary.hiddenCount)
        assertTrue(summary.isChange, "an empty UPDATE is still an update — the event decides, not the payload")
    }

    @Test
    fun `a malformed payload degrades to an empty summary instead of throwing`() {
        val summary = summarize(InvoiceAuditEvent.INVOICE_CREATED, "not json at all")

        assertTrue(summary.lines.isEmpty())
        assertFalse(summary.isChange)
    }
}
