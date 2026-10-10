package de.littletrotter.application.reference

import de.littletrotter.domain.compliance.NumberJournalChain
import de.littletrotter.domain.compliance.NumberJournalEntry
import de.littletrotter.domain.repository.NumberJournalRepository

/**
 * In-memory journal for unit tests that do **not** verify journal content.
 *
 * CDI ignores Kotlin default parameter values and always resolves every constructor parameter
 * in the container -- a nullable `NumberRangeService.journal` with a Kotlin-side `null` default
 * looks like an optional dependency but is not: production always gets a real journal, so a
 * `null` default would only ever apply in tests, silently diverging from production behavior.
 *
 * A test that does not care about journal content uses this class explicitly instead, making
 * the choice visible rather than silent. Entries stay available via [entries] for tests that do
 * want to look.
 *
 * Mirrors `PostgresNumberJournalRepository`'s chain logic (`prevHash` from the predecessor or
 * genesis, `entryHash` computed from it) so a test that does verify sees the same chain.
 */
class RecordingNumberJournalRepository : NumberJournalRepository {

    val entries = mutableListOf<NumberJournalEntry>()

    override fun append(entry: NumberJournalEntry, genesisHash: String): NumberJournalEntry {
        val seq = entries.size.toLong() + 1
        val prevHash = entries.lastOrNull { it.numberRangeId == entry.numberRangeId }?.entryHash ?: genesisHash
        val stored = entry.copy(
            seq = seq,
            prevHash = prevHash,
            entryHash = NumberJournalChain.computeEntryHash(prevHash, seq, entry),
        )
        entries += stored
        return stored
    }

    override fun findLatest(numberRangeId: Long): NumberJournalEntry? =
        entries.lastOrNull { it.numberRangeId == numberRangeId }

    override fun findAll(numberRangeId: Long): List<NumberJournalEntry> =
        entries.filter { it.numberRangeId == numberRangeId }

    /** Every range, ascending by (append order ==) seq; `batchSize` is not honored (test
     *  double, entry counts here are small enough that a single "page" is fine for tests that use
     *  it). */
    override fun streamAllBatched(afterSeq: Long, batchSize: Int): List<NumberJournalEntry> =
        entries.filter { (it.seq ?: 0L) > afterSeq }
}
