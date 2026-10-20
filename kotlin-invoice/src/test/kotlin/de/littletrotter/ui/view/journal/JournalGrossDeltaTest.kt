package de.littletrotter.ui.view.journal

import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class JournalGrossDeltaTest {

    private fun row(detail: String, event: InvoiceAuditEvent = InvoiceAuditEvent.INVOICE_UPDATED) = InvoiceAuditJournalEntry(
        seq = 1L, businessTx = 1L, invoiceId = 1L, invoiceNumber = "RE-2026-0001",
        actor = "oidc-sub-1", actorName = "alice", occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        event = event, action = de.littletrotter.domain.compliance.InvoiceAuditAction.RECALCULATED, detail = detail,
        documentSha256 = null, prevHash = "prev", entryHash = "hash",
    )

    @Test
    fun `computes the signed difference of an UPDATED gross_amount diff`() {
        val delta = JournalGrossDelta.compute(listOf(row("""{"gross_amount": {"old": "100.00", "new": "142.80"}}""")))

        assertEquals(Money.of("42.80"), delta)
    }

    @Test
    fun `a decrease yields a negative Money`() {
        val delta = JournalGrossDelta.compute(listOf(row("""{"gross_amount": {"old": "100.00", "new": "80.00"}}""")))

        assertEquals(Money.of("-20.00"), delta)
    }

    @Test
    fun `no gross_amount diff among the rows yields null, not a zero delta`() {
        val delta = JournalGrossDelta.compute(listOf(row("""{"due_date": {"old": "2026-01-01", "new": "2026-02-01"}}""")))

        assertNull(delta)
    }

    @Test
    fun `an INSERT snapshot never yields a delta - there is no old value to diff against`() {
        val delta = JournalGrossDelta.compute(listOf(row("""{"gross_amount": "142.80"}""", event = InvoiceAuditEvent.INVOICE_CREATED)))

        assertNull(delta)
    }

    @Test
    fun `deltas across two rows of the same action sum together`() {
        val rows = listOf(
            row("""{"gross_amount": {"old": "100.00", "new": "80.00"}}"""),
            row("""{"gross_amount": {"old": "50.00", "new": "90.00"}}"""),
        )

        assertEquals(Money.of("20.00"), JournalGrossDelta.compute(rows))
    }

    @Test
    fun `a null old gross_amount value yields no delta, not a crash`() {
        val delta = JournalGrossDelta.compute(listOf(row("""{"gross_amount": {"old": null, "new": "80.00"}}""")))

        assertNull(delta)
    }

    @Test
    fun `a non-numeric new gross_amount value yields no delta, not a crash`() {
        val delta = JournalGrossDelta.compute(listOf(row("""{"gross_amount": {"old": "80.00", "new": "not-a-number"}}""")))

        assertNull(delta)
    }

    @Test
    fun `the InvoiceAuditActionLine overload delegates to the row-list computation`() {
        val entry = row("""{"gross_amount": {"old": "100.00", "new": "142.80"}}""")
        val line = InvoiceAuditActionLine(
            businessTx = 1L, action = InvoiceAuditAction.RECALCULATED, actor = "oidc-sub-1", actorName = "alice",
            occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
            invoiceIds = listOf(1L), invoiceNumbers = listOf("RE-2026-0001"), rows = listOf(entry),
        )

        assertEquals(Money.of("42.80"), JournalGrossDelta.compute(line))
    }
}
