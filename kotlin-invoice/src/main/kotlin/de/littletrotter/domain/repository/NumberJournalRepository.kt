package de.littletrotter.domain.repository

import de.littletrotter.domain.compliance.NumberJournalEntry

/**
 * Number journal access (append-only). No update/delete by design; DB enforces via constraints.
 */
interface NumberJournalRepository {

    /**
     * Append [entry]. Caller-provided seq/hashes ignored; computed here so caller cannot provide
     * wrong hash. PrevHash from latest entry (or genesisHash if none). In caller's transaction,
     * so rollback takes both counter and entry together.
     */
    fun append(entry: NumberJournalEntry, genesisHash: String): NumberJournalEntry

    /** Latest appended entry for range (or null if empty). */
    fun findLatest(numberRangeId: Long): NumberJournalEntry?

    /** All entries for range, ascending by seq (for chain verification and report). */
    fun findAll(numberRangeId: Long): List<NumberJournalEntry>

    /** Cursor-based batch read across *every* range, for the nightly chain-verification
     *  job: rows with `seq > afterSeq` (the table's single, cross-range `BIGSERIAL`, not a
     *  per-range counter), ascending by seq, capped at [batchSize]. Unlike [findAll] (already
     *  scoped to one range, but still an unbounded `List` return), this is what the whole-table
     *  sweep uses — `number_journal` has unbounded retention (J3), so it must never be loaded in
     *  one shot. Ranges are interleaved in the result (seq order is global, not per range); the
     *  caller keeps its own running chain state per range across calls — see
     *  [de.littletrotter.domain.compliance.verifyIncremental]. */
    fun streamAllBatched(afterSeq: Long, batchSize: Int): List<NumberJournalEntry>
}
