package de.littletrotter.domain.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * pure chain logic for `invoice_audit_journal`, no database. Mirrors the same trigger
 * computation `capture_invoice_audit_event()` runs in `V1__baseline_schema.sql` (section 13); see
 * [InvoiceAuditJournalChain]'s own KDoc for why the two must stay byte-for-byte identical.
 */
class InvoiceAuditJournalChainTest {

    private val now = OffsetDateTime.parse("2026-09-04T10:00:00Z")

    private fun entry(
        seq: Long,
        prevHash: String,
        invoiceId: Long = 42L,
        invoiceNumber: String? = "RE-2026-0001",
        actor: String = "system",
        actorName: String? = null,
        event: InvoiceAuditEvent = InvoiceAuditEvent.INVOICE_CREATED,
        action: InvoiceAuditAction = InvoiceAuditAction.UNSPECIFIED,
        businessTx: Long = seq,
        detail: String = """{"id": 42}""",
        documentSha256: String? = null,
    ): InvoiceAuditJournalEntry {
        val base = InvoiceAuditJournalEntry(
            seq = seq, businessTx = businessTx, invoiceId = invoiceId, invoiceNumber = invoiceNumber, actor = actor,
            actorName = actorName, occurredAt = now, event = event, action = action, detail = detail,
            documentSha256 = documentSha256, prevHash = prevHash, entryHash = "",
        )
        return base.copy(entryHash = InvoiceAuditJournalChain.computeEntryHash(prevHash, base))
    }

    // ─── genesis / entryHash basics ──────────────────────────────────────────────────

    @Test
    fun `RS-12 - genesisHash is deterministic`() {
        assertEquals(InvoiceAuditJournalChain.genesisHash(), InvoiceAuditJournalChain.genesisHash())
    }

    @Test
    fun `RS-12 - entryHash is deterministic for identical input`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val a = entry(1, genesis)
        val b = entry(1, genesis)
        assertEquals(a.entryHash, b.entryHash)
    }

    @Test
    fun `RS-12 - entryHash differs when actorName changes from null to a real value`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val withNull = entry(1, genesis, actorName = null)
        val withValue = entry(1, genesis, actorName = "alice")
        assertNotEquals(withNull.entryHash, withValue.entryHash)
    }

    /**
     * Documents a known, accepted collision boundary (same reasoning as
     * [NumberJournalChainTest]'s own IMP-4 case): if a free-text field itself contains the
     * separator byte, two different (invoiceNumber, actor) pairs can serialize to the exact
     * same payload and therefore hash identically. Unlike `number_journal` (guarded at write
     * time by [NumberJournalChain.assertCanonicalizable]), nothing rejects a control character
     * before `capture_invoice_audit_event()` hashes it -- the trigger reads whatever the
     * underlying columns already contain. Not a regression to fix here; recorded so it stays a
     * deliberate, known property instead of a surprise.
     */
    @Test
    fun `RS-12 - a literal separator byte inside a field can make two different entries hash identically`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val a = entry(1, genesis, invoiceNumber = "a\u001Fb", actor = "system")
        val b = entry(1, genesis, invoiceNumber = "a", actor = "b\u001Fsystem")
        assertEquals(
            a.entryHash, b.entryHash,
            "this collision is exactly why callers must never let invoiceNumber/actor contain U+001F",
        )
    }

    // ─── verifyInvoiceAuditChainBatch — happy path ───────────────────────────────────

    @Test
    fun `RS-12 - a clean chain verifies as Valid and advances nextPrevHash to the last entry_hash`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val first = entry(1, genesis)
        val second = entry(2, first.entryHash)
        val third = entry(3, second.entryHash)

        val result = verifyInvoiceAuditChainBatch(genesis, listOf(first, second, third))

        assertEquals(InvoiceAuditChainVerification.Valid, result.verification)
        assertEquals(third.entryHash, result.nextPrevHash)
        assertEquals(3L, result.checked)
    }

    @Test
    fun `RS-12 - an empty batch is Valid and leaves prevHash unchanged`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val result = verifyInvoiceAuditChainBatch(genesis, emptyList())
        assertEquals(InvoiceAuditChainVerification.Valid, result.verification)
        assertEquals(genesis, result.nextPrevHash)
        assertEquals(0L, result.checked)
    }

    @Test
    fun `RS-12 - verification threads across two batches via nextPrevHash`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val first = entry(1, genesis)
        val second = entry(2, first.entryHash)
        val third = entry(3, second.entryHash)

        val firstBatch = verifyInvoiceAuditChainBatch(genesis, listOf(first))
        val secondBatch = verifyInvoiceAuditChainBatch(firstBatch.nextPrevHash, listOf(second, third))

        assertEquals(InvoiceAuditChainVerification.Valid, secondBatch.verification)
        assertEquals(third.entryHash, secondBatch.nextPrevHash)
    }

    // ─── verifyInvoiceAuditChainBatch — corrupted-row case (must detect the break) ───

    @Test
    fun `RS-12 - a tampered detail field is detected and names the offending seq`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val first = entry(1, genesis)
        val second = entry(2, first.entryHash)
        // entry_hash stays the old one — exactly what a rewritten row on the host would look like.
        val tampered = second.copy(detail = """{"id": 99999}""")
        val third = entry(3, second.entryHash)

        val result = verifyInvoiceAuditChainBatch(genesis, listOf(first, tampered, third))

        assertEquals(
            InvoiceAuditChainVerification.Broken(2, "entry_hash does not match the entry's own content"),
            result.verification,
        )
        // Stops at the break — the third (unreachable) entry is never counted as checked.
        assertEquals(1L, result.checked)
    }

    @Test
    fun `RS-12 - a removed middle entry breaks the chain at its successor`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val first = entry(1, genesis)
        val second = entry(2, first.entryHash)
        val third = entry(3, second.entryHash)

        // "second" is simply missing — third.prevHash points into nothing the batch has seen.
        val result = verifyInvoiceAuditChainBatch(genesis, listOf(first, third))

        assertEquals(
            InvoiceAuditChainVerification.Broken(3, "prevHash does not match the previous entry_hash"),
            result.verification,
        )
        assertEquals(1L, result.checked)
    }

    @Test
    fun `RS-12 - once broken, nextPrevHash stops advancing`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val first = entry(1, genesis)
        val bogus = entry(2, "not-the-real-prev-hash")

        val result = verifyInvoiceAuditChainBatch(genesis, listOf(first, bogus))

        assertEquals(first.entryHash, result.nextPrevHash)
    }

    // ─── action/business_tx are part of the hash input ───────────────────────

    @Test
    fun `II4-A - entryHash differs when action changes, all other fields equal`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val itemAdded = entry(1, genesis, action = InvoiceAuditAction.ITEM_ADDED)
        val recalculated = entry(1, genesis, action = InvoiceAuditAction.RECALCULATED)
        assertNotEquals(itemAdded.entryHash, recalculated.entryHash)
    }

    @Test
    fun `II4-A - entryHash differs when business_tx changes, all other fields equal`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val a = entry(1, genesis, businessTx = 100L)
        val b = entry(1, genesis, businessTx = 200L)
        assertNotEquals(a.entryHash, b.entryHash)
    }

    /**
     * The failure mode the whole action/business_tx addition exists to catch: a row
     * copied and replayed with its `action` silently flipped (the DB write itself always stays
     * consistent — this simulates a rewritten row on the host, same threat model as the "tampered
     * detail" case above) must break chain verification, exactly like tampering with any other
     * hashed field.
     */
    @Test
    fun `II4-A-B3 - flipping action in a copied row makes verification fail`() {
        val genesis = InvoiceAuditJournalChain.genesisHash()
        val first = entry(1, genesis, action = InvoiceAuditAction.ITEM_ADDED)
        val second = entry(2, first.entryHash, action = InvoiceAuditAction.ITEM_ADDED)
        // entry_hash stays the one computed for ITEM_ADDED — exactly what a rewritten row would
        // look like: the stored hash no longer matches the (now tampered) action it should cover.
        val flipped = second.copy(action = InvoiceAuditAction.RECALCULATED)

        val result = verifyInvoiceAuditChainBatch(genesis, listOf(first, flipped))

        assertEquals(
            InvoiceAuditChainVerification.Broken(2, "entry_hash does not match the entry's own content"),
            result.verification,
        )
    }
}
