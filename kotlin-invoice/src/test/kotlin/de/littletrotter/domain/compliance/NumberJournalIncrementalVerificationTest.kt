package de.littletrotter.domain.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * pure incremental chain logic for [verifyIncremental], the whole-table (all ranges at
 * once, seq-ordered batches) sibling of [verifyChain] (single already-loaded range). No database:
 * [de.littletrotter.application.compliance.ChainVerificationService] is the only production
 * caller, and it is covered separately by its own test against fakes.
 */
class NumberJournalIncrementalVerificationTest {

    private val now = OffsetDateTime.parse("2026-09-04T10:00:00Z")

    private fun entry(
        seq: Long,
        rangeId: Long,
        prevHash: String,
        number: String = "DEVEDGE-2026-00001",
    ): NumberJournalEntry {
        val unhashed = NumberJournalEntry(
            seq = seq, numberRangeId = rangeId, event = NumberJournalEvent.ALLOCATED, periodKey = "2026",
            numberPrefix = "DEVEDGE", numberSequence = seq, number = number, entityType = "Invoice",
            entityId = 1L, reason = null, actor = "system", occurredAt = now,
            prevHash = prevHash, entryHash = "",
        )
        return unhashed.copy(entryHash = NumberJournalChain.computeEntryHash(prevHash, seq, unhashed))
    }

    private val genesisRange1 = NumberJournalChain.genesisHash("RANGE_1", 1L)
    private val genesisRange2 = NumberJournalChain.genesisHash("RANGE_2", 1L)
    private val genesisFor: (Long) -> String? = { rangeId ->
        when (rangeId) {
            1L -> genesisRange1
            2L -> genesisRange2
            else -> null
        }
    }

    // ─── happy path ───────────────────────────────────────────────────────────────────

    @Test
    fun `RS-12 - a clean single-range chain verifies with no breaks`() {
        val first = entry(1, rangeId = 1L, prevHash = genesisRange1)
        val second = entry(2, rangeId = 1L, prevHash = first.entryHash)

        val step = verifyIncremental(emptyMap(), listOf(first, second), genesisFor)

        assertEquals(2L, step.checked)
        assertEquals(NumberJournalRangeState.Ongoing(second.entryHash), step.nextState[1L])
    }

    @Test
    fun `RS-12 - two ranges interleaved in one seq-ordered batch are verified independently`() {
        val r1first = entry(1, rangeId = 1L, prevHash = genesisRange1)
        val r2first = entry(2, rangeId = 2L, prevHash = genesisRange2)
        val r1second = entry(3, rangeId = 1L, prevHash = r1first.entryHash)
        val r2second = entry(4, rangeId = 2L, prevHash = r2first.entryHash)

        val step = verifyIncremental(emptyMap(), listOf(r1first, r2first, r1second, r2second), genesisFor)

        assertEquals(4L, step.checked)
        assertEquals(NumberJournalRangeState.Ongoing(r1second.entryHash), step.nextState[1L])
        assertEquals(NumberJournalRangeState.Ongoing(r2second.entryHash), step.nextState[2L])
    }

    @Test
    fun `RS-12 - state threads across two batches for the same range`() {
        val first = entry(1, rangeId = 1L, prevHash = genesisRange1)
        val second = entry(2, rangeId = 1L, prevHash = first.entryHash)

        val firstBatch = verifyIncremental(emptyMap(), listOf(first), genesisFor)
        val secondBatch = verifyIncremental(firstBatch.nextState, listOf(second), genesisFor)

        assertEquals(NumberJournalRangeState.Ongoing(second.entryHash), secondBatch.nextState[1L])
    }

    @Test
    fun `RS-12 - genesisFor is consulted only once per range across the whole run`() {
        var callCount = 0
        val countingGenesisFor: (Long) -> String? = { rangeId -> callCount++; genesisFor(rangeId) }
        val first = entry(1, rangeId = 1L, prevHash = genesisRange1)
        val second = entry(2, rangeId = 1L, prevHash = first.entryHash)

        var state = emptyMap<Long, NumberJournalRangeState>()
        state = verifyIncremental(state, listOf(first), countingGenesisFor).nextState
        verifyIncremental(state, listOf(second), countingGenesisFor)

        assertEquals(1, callCount)
    }

    // ─── corrupted-row case (must detect the break, not silently pass) ───────────────

    @Test
    fun `RS-12 - a tampered entry is detected and names the range and seq`() {
        val first = entry(1, rangeId = 1L, prevHash = genesisRange1)
        val second = entry(2, rangeId = 1L, prevHash = first.entryHash)
        val tampered = second.copy(number = "DEVEDGE-2026-99999") // entry_hash stays the old one

        val step = verifyIncremental(emptyMap(), listOf(first, tampered), genesisFor)

        val state = step.nextState[1L]
        assertTrue(state is NumberJournalRangeState.Broken, "expected the range to be flagged Broken, was $state")
        assertEquals(2L, (state as NumberJournalRangeState.Broken).atSeq)
    }

    @Test
    fun `RS-12 - once a range is broken, later entries of that range are skipped, not re-flagged`() {
        val first = entry(1, rangeId = 1L, prevHash = genesisRange1)
        val tampered = entry(2, rangeId = 1L, prevHash = "not-the-real-prev-hash")
        val third = entry(3, rangeId = 1L, prevHash = tampered.entryHash) // chains from the tampered row

        val step = verifyIncremental(emptyMap(), listOf(first, tampered, third), genesisFor)

        // Only seq 1 and 2 were actually checked — seq 3 was skipped once its range was flagged.
        assertEquals(2L, step.checked)
        val state = step.nextState[1L] as NumberJournalRangeState.Broken
        assertEquals(2L, state.atSeq)
    }

    @Test
    fun `RS-12 - one range breaking does not affect verification of another range in the same batch`() {
        val r1first = entry(1, rangeId = 1L, prevHash = genesisRange1)
        val r1tampered = entry(2, rangeId = 1L, prevHash = "wrong")
        val r2first = entry(3, rangeId = 2L, prevHash = genesisRange2)
        val r2second = entry(4, rangeId = 2L, prevHash = r2first.entryHash)

        val step = verifyIncremental(emptyMap(), listOf(r1first, r1tampered, r2first, r2second), genesisFor)

        assertTrue(step.nextState[1L] is NumberJournalRangeState.Broken)
        assertEquals(NumberJournalRangeState.Ongoing(r2second.entryHash), step.nextState[2L])
    }

    @Test
    fun `RS-12 - an entry for a range that no longer exists is flagged as broken`() {
        val orphan = entry(1, rangeId = 99L, prevHash = "irrelevant")

        val step = verifyIncremental(emptyMap(), listOf(orphan), genesisFor) // genesisFor(99L) == null

        val state = step.nextState[99L]
        assertTrue(state is NumberJournalRangeState.Broken, "expected Broken, was $state")
    }

    @Test
    fun `RS-12 - an entry without a seq is flagged as broken and cannot be checked`() {
        val entryWithoutSeq = entry(1, rangeId = 1L, prevHash = genesisRange1).copy(seq = null)

        val step = verifyIncremental(emptyMap(), listOf(entryWithoutSeq), genesisFor)

        val state = step.nextState[1L] as NumberJournalRangeState.Broken
        assertEquals(-1L, state.atSeq)
    }
}
