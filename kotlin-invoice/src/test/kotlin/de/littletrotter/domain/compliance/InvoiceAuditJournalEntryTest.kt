package de.littletrotter.domain.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * [InvoiceAuditJournalEntry] carries no behavior of its own, but the two consumers named
 * in its KDoc ('s chain recompute, 's rendering) both depend on plain value equality
 * (comparing two entries / copying one with a changed field), so that contract is worth pinning
 * directly rather than trusting the compiler-generated `data class` members untested.
 */
class InvoiceAuditJournalEntryTest {

    private val occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z")

    private fun sample(seq: Long = 1L, entryHash: String = "hash-1") = InvoiceAuditJournalEntry(
        seq = seq,
        businessTx = seq,
        invoiceId = 42L,
        invoiceNumber = "RE-2026-0001",
        actor = "oidc-sub-123",
        actorName = "alice",
        occurredAt = occurredAt,
        event = InvoiceAuditEvent.INVOICE_CREATED,
        action = InvoiceAuditAction.UNSPECIFIED,
        detail = """{"invoice_number": "RE-2026-0001"}""",
        documentSha256 = null,
        prevHash = "genesis",
        entryHash = entryHash,
    )

    @Test
    fun `RS-8 - two entries built from the same values are equal`() {
        assertEquals(sample(), sample())
    }

    @Test
    fun `RS-8 - entries with a different seq are not equal`() {
        assertNotEquals(sample(seq = 1L), sample(seq = 2L))
    }

    @Test
    fun `RS-8 - copy changes only the targeted field, RS-12's chain walk relies on this for prevHash re-linking`() {
        val original = sample(entryHash = "hash-1")
        val relinked = original.copy(prevHash = original.entryHash)

        assertEquals("hash-1", relinked.prevHash)
        assertEquals(original.seq, relinked.seq)
        assertEquals(original.invoiceId, relinked.invoiceId)
        assertEquals(original.event, relinked.event)
    }

    @Test
    fun `RS-8 - every constructor field is readable back unchanged`() {
        val entry = sample()

        assertEquals(1L, entry.seq)
        assertEquals(42L, entry.invoiceId)
        assertEquals("RE-2026-0001", entry.invoiceNumber)
        assertEquals("oidc-sub-123", entry.actor)
        assertEquals("alice", entry.actorName)
        assertEquals(occurredAt, entry.occurredAt)
        assertEquals(InvoiceAuditEvent.INVOICE_CREATED, entry.event)
        assertEquals("""{"invoice_number": "RE-2026-0001"}""", entry.detail)
        assertEquals(null, entry.documentSha256)
        assertEquals("genesis", entry.prevHash)
        assertEquals("hash-1", entry.entryHash)
    }
}
