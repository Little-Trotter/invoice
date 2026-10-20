package de.littletrotter.ui.view.journal.composer

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `setReceiptsFor*` journals one flat `RECEIPT_ASSIGNED`/`RECEIPT_DETACHED` action; what
 *  really happened per link is only visible in the technical rows' own EVENTS, which is exactly
 *  what [ReceiptFamilyComposer] reads (never the action alone) — see the spec's own "facts to
 *  design around". */
class ReceiptFamilyComposerTest {

    @Test
    fun `an invoice-level assignment names the file and the invoice scope`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED, detail = """{"invoice_id": "1", "receipt_id": "9"}""")
        val line = testLine(action = InvoiceAuditAction.RECEIPT_ASSIGNED, rows = listOf(row))

        val result = ReceiptFamilyComposer.compose(line, testCtx(receiptFileNames = mapOf(9L to "tankquittung-0912.pdf")))

        assertEquals("journal.what.receiptAssigned(tankquittung-0912.pdf,journal.what.receiptScopeInvoice)", result)
    }

    @Test
    fun `an item-level detach names the file and the resolved position scope, never the raw item id`() {
        val row = testRow(
            event = InvoiceAuditEvent.ITEM_RECEIPT_UNASSIGNED,
            detail = """{"invoice_item_id": "5", "receipt_id": "9"}""",
        )
        val line = testLine(action = InvoiceAuditAction.RECEIPT_DETACHED, rows = listOf(row))

        val result = ReceiptFamilyComposer.compose(
            line,
            testCtx(receiptFileNames = mapOf(9L to "tankquittung-0912.pdf"), itemPositions = mapOf(5L to "3")),
        )

        assertEquals("journal.what.receiptDetached(tankquittung-0912.pdf,journal.what.receiptScopeItem(3))", result)
    }

    @Test
    fun `an item-level scope for an item that no longer exists falls back to a neutral placeholder, never the bare id`() {
        val row = testRow(
            event = InvoiceAuditEvent.ITEM_RECEIPT_UNASSIGNED,
            detail = """{"invoice_item_id": "5", "receipt_id": "9"}""",
        )
        val line = testLine(action = InvoiceAuditAction.RECEIPT_DETACHED, rows = listOf(row))

        val result = ReceiptFamilyComposer.compose(line, testCtx(receiptFileNames = mapOf(9L to "tankquittung-0912.pdf")))

        assertEquals(
            "journal.what.receiptDetached(tankquittung-0912.pdf,journal.what.receiptScopeItem(journal.what.itemPositionUnknown))",
            result,
        )
    }

    @Test
    fun `a receipt id with no resolved file name falls back to the unknown placeholder, never a crash`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED, detail = """{"invoice_id": "1", "receipt_id": "999"}""")
        val line = testLine(action = InvoiceAuditAction.RECEIPT_ASSIGNED, rows = listOf(row))

        val result = ReceiptFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.receiptAssigned(journal.what.receiptUnknown,journal.what.receiptScopeInvoice)", result)
    }

    @Test
    fun `several links in one action are listed, duplicates collapsed`() {
        val rows = listOf(
            testRow(seq = 1L, event = InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED, detail = """{"invoice_id": "1", "receipt_id": "9"}"""),
            testRow(seq = 2L, event = InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED, detail = """{"invoice_id": "1", "receipt_id": "10"}"""),
        )
        val line = testLine(action = InvoiceAuditAction.RECEIPT_ASSIGNED, rows = rows)

        val result = ReceiptFamilyComposer.compose(line, testCtx(receiptFileNames = mapOf(9L to "a.pdf", 10L to "b.pdf")))

        assertEquals(
            "journal.what.receiptAssigned(a.pdf,journal.what.receiptScopeInvoice); journal.what.receiptAssigned(b.pdf,journal.what.receiptScopeInvoice)",
            result,
        )
    }

    @Test
    fun `an item-level assignment names the file and the resolved position scope`() {
        val row = testRow(
            event = InvoiceAuditEvent.ITEM_RECEIPT_ASSIGNED,
            detail = """{"invoice_item_id": "5", "receipt_id": "9"}""",
        )
        val line = testLine(action = InvoiceAuditAction.RECEIPT_ASSIGNED, rows = listOf(row))

        val result = ReceiptFamilyComposer.compose(
            line,
            testCtx(receiptFileNames = mapOf(9L to "beleg.pdf"), itemPositions = mapOf(5L to "2")),
        )

        assertEquals("journal.what.receiptAssigned(beleg.pdf,journal.what.receiptScopeItem(2))", result)
    }

    /** A row with no parseable `receipt_id` at all (never expected from the trigger, but the
     *  defensive reader must not throw) is silently dropped rather than producing a phantom link. */
    @Test
    fun `a row with no parseable receipt id is dropped, not rendered as a phantom link`() {
        val malformed = testRow(event = InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED, detail = """{"invoice_id": "1"}""")
        val line = testLine(action = InvoiceAuditAction.RECEIPT_ASSIGNED, rows = listOf(malformed))

        assertNull(ReceiptFamilyComposer.compose(line, testCtx()))
    }

    @Test
    fun `rows that carry no receipt link at all yield null`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"free_text": {"old": "a", "new": "b"}}""")
        val line = testLine(action = InvoiceAuditAction.RECEIPT_ASSIGNED, rows = listOf(row))

        assertNull(ReceiptFamilyComposer.compose(line, testCtx()))
    }
}
