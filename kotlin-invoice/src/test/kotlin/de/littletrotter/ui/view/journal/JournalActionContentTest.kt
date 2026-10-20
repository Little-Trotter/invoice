package de.littletrotter.ui.view.journal

import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import de.littletrotter.ui.view.journal.composer.testCtx
import java.time.OffsetDateTime

/** A composer returning nothing (a bookkeeping-only update, every changed column
 *  already stripped by the trigger) still renders label + "keine fachliche Änderung", never a
 *  blank "Was genau" cell. Also pins that every [InvoiceAuditAction] dispatches to SOME composer
 *  (the `when` in `composerFor` is exhaustive over the enum, so a missing case is a compile error,
 *  not a runtime gap — this test is the behavioural companion to that compile-time guarantee). */
class JournalActionContentTest {

    private fun row(action: InvoiceAuditAction, event: InvoiceAuditEvent = InvoiceAuditEvent.INVOICE_UPDATED, detail: String = "{}") =
        InvoiceAuditJournalEntry(
            seq = 1L, businessTx = 1L, invoiceId = 1L, invoiceNumber = "RE-2026-0001",
            actor = "oidc-sub-1", actorName = "alice", occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
            event = event, action = action, detail = detail,
            documentSha256 = null, prevHash = "prev", entryHash = "hash",
        )

    private fun line(action: InvoiceAuditAction, rows: List<InvoiceAuditJournalEntry>) = InvoiceAuditActionLine(
        businessTx = 1L, action = action, actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        invoiceIds = listOf(1L), invoiceNumbers = listOf("RE-2026-0001"), rows = rows,
    )

    @Test
    fun `an empty diff falls back to the fixed no-change sentence`() {
        val theLine = line(InvoiceAuditAction.HEADER_CHANGED, listOf(row(InvoiceAuditAction.HEADER_CHANGED, detail = "{}")))

        val result = JournalActionContent.whatHappened(theLine, testCtx())

        assertEquals("journal.what.noChange", result)
    }

    @Test
    fun `every InvoiceAuditAction produces a non-null sentence or the fixed fallback, never throws`() {
        InvoiceAuditAction.entries.forEach { action ->
            val theLine = line(action, listOf(row(action, detail = "{}")))
            val result = JournalActionContent.whatHappened(theLine, testCtx())
            assertEquals(true, result.isNotBlank(), "action $action produced a blank sentence")
        }
    }
}
