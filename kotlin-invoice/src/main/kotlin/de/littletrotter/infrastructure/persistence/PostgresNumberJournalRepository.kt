package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.compliance.NumberJournalChain
import de.littletrotter.domain.compliance.NumberJournalEntry
import de.littletrotter.domain.compliance.NumberJournalEvent
import de.littletrotter.domain.repository.NumberJournalRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hibernate.Session
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * See `V063__number_journal.sql` for why `seq` is drawn via
 * `nextval('number_journal_seq')` **before** the actual `INSERT`: the `entry_hash` needs `seq`
 * as input, and updating the freshly inserted row afterward would itself already violate
 * "append only" (the `RULE` in the same migration makes such an `UPDATE` a no-op anyway).
 *
 * Deliberately has **no own** `@Transactional`: for `GAPLESS` allocation this call must run in
 * the same transaction as the counter increment
 * ([PostgresNumberCounterRepository.allocateGapless]) — a rollback of the caller's transaction
 * then reverts both together.
 */
@ApplicationScoped
class PostgresNumberJournalRepository @Inject constructor(private val em: EntityManager) : NumberJournalRepository {

    override fun append(entry: NumberJournalEntry, genesisHash: String): NumberJournalEntry {
        // Before acquiring the lock and before any write attempt: an entry whose fields would
        // break canonicalization must never enter the chain (IMP-4).
        NumberJournalChain.assertCanonicalizable(entry)
        lockChain(entry.numberRangeId)

        // A Hibernate-typed native query returns the sequence value already as a Long,
        // no unchecked cast needed.
        val seq = em.unwrap(Session::class.java)
            .createNativeQuery("SELECT nextval('number_journal_seq')", Long::class.javaObjectType)
            .singleResult
        val prevHash = findLatest(entry.numberRangeId)?.entryHash ?: genesisHash
        val entryHash = NumberJournalChain.computeEntryHash(prevHash, seq, entry)

        em.createNativeQuery(
            """INSERT INTO number_journal
                 (seq, number_range_id, event, period_key, number_prefix, number_sequence, number,
                  entity_type, entity_id, reason, actor, occurred_at, prev_hash, entry_hash,
                  sealed_start_value, sealed_count, sealed_first_number, sealed_last_number)
               VALUES
                 (:seq, :rangeId, :event, :periodKey, :prefix, :sequence, :number,
                  :entityType, :entityId, :reason, :actor, :occurredAt, :prevHash, :entryHash,
                  :sealedStartValue, :sealedCount, :sealedFirstNumber, :sealedLastNumber)""",
        )
            .setParameter("seq", seq)
            .setParameter(PARAM_RANGE_ID, entry.numberRangeId)
            .setParameter("event", entry.event.name)
            .setParameter("periodKey", entry.periodKey)
            .setParameter("prefix", entry.numberPrefix)
            .setParameter("sequence", entry.numberSequence)
            .setParameter("number", entry.number)
            .setParameter("entityType", entry.entityType)
            .setParameter("entityId", entry.entityId)
            .setParameter("reason", entry.reason)
            .setParameter("actor", entry.actor)
            .setParameter("occurredAt", entry.occurredAt)
            .setParameter("prevHash", prevHash)
            .setParameter("entryHash", entryHash)
            .setParameter("sealedStartValue", entry.sealedStartValue)
            .setParameter("sealedCount", entry.sealedCount)
            .setParameter("sealedFirstNumber", entry.sealedFirstNumber)
            .setParameter("sealedLastNumber", entry.sealedLastNumber)
            .executeUpdate()

        return entry.copy(seq = seq, prevHash = prevHash, entryHash = entryHash)
    }

    /**
     * Serializes all appends to **one** chain for the rest of the caller's transaction.
     *
     * Reading `prevHash` and writing the entry built from it is logically one step: if two
     * callers read the same chain head, two entries with an identical `prevHash` result — the
     * chain forks, and every later verification reports a CHAIN BREAK even though nobody
     * tampered with it. `GAPLESS` allocation doesn't hit this because the row lock on
     * `number_counter` already serializes it there; period closing
     * ([de.littletrotter.application.reference.NumberRangeService.sealPeriod]) does **not** hold
     * that lock — it doesn't increment a counter. Serialization must not depend on which
     * unrelated lock a caller happens to be holding.
     *
     * Uses `pg_advisory_xact_lock` instead of `SELECT … FOR UPDATE` on `number_range`: the lock
     * belongs to the chain, not to the master-data row, and it ends automatically with the
     * transaction — even on rollback, with no cleanup code needed.
     */
    private fun lockChain(numberRangeId: Long) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:ns, :rangeId)")
            .setParameter("ns", ADVISORY_LOCK_NAMESPACE)
            .setParameter(PARAM_RANGE_ID, numberRangeId.toInt())
            .singleResult
    }

    /** The most recently appended entry — via its own query instead of `findAll(…).last()`:
     *  the latter loaded and converted the chain's entire journal on **every** allocation just
     *  to read a single hash. After a few thousand invoices that makes each commit noticeably
     *  slower than the one before it (IMP-2). */
    override fun findLatest(numberRangeId: Long): NumberJournalEntry? =
        query("WHERE number_range_id = :rangeId ORDER BY seq DESC", numberRangeId, maxResults = 1)
            .firstOrNull()

    override fun findAll(numberRangeId: Long): List<NumberJournalEntry> =
        query("WHERE number_range_id = :rangeId ORDER BY seq", numberRangeId)

    /** No `number_range_id` filter on purpose: the nightly chain-verification job walks
     *  every range's chain in one seq-ordered sweep (see [de.littletrotter.domain.compliance
     *  .verifyIncremental]'s per-range state), so it cannot reuse [query]'s single-range clause,
     *  which unconditionally binds `:rangeId`. */
    override fun streamAllBatched(afterSeq: Long, batchSize: Int): List<NumberJournalEntry> =
        em.createNativeQuery(SELECT_COLUMNS + "FROM number_journal WHERE seq > :afterSeq ORDER BY seq")
            .setParameter("afterSeq", afterSeq)
            .setMaxResults(batchSize)
            .resultList
            .map { mapRow(it as Array<*>) }

    private fun query(clause: String, numberRangeId: Long, maxResults: Int? = null): List<NumberJournalEntry> =
        em.createNativeQuery(SELECT_COLUMNS + "FROM number_journal $clause")
            .setParameter(PARAM_RANGE_ID, numberRangeId)
            .let { if (maxResults != null) it.setMaxResults(maxResults) else it }
            .resultList
            // Casting each row to the reified `Array<*>` (its actual runtime shape,
            // JDBC/Hibernate always hands back `Object[]` rows for a multi-column native query)
            // is a checked cast; only casting the whole `List<Array<Any?>>` was unchecked.
            .map { mapRow(it as Array<*>) }

    private fun mapRow(row: Array<*>): NumberJournalEntry =
        NumberJournalEntry(
            seq = (row[0] as Number).toLong(),
            numberRangeId = (row[1] as Number).toLong(),
            event = NumberJournalEvent.valueOf(row[2] as String),
            periodKey = row[3] as String,
            numberPrefix = row[4] as String?,
            numberSequence = (row[5] as Number?)?.toLong(),
            number = row[6] as String?,
            entityType = row[7] as String?,
            entityId = (row[8] as Number?)?.toLong(),
            reason = row[9] as String?,
            actor = row[10] as String,
            occurredAt = toOffsetDateTime(row[OCCURRED_AT_COLUMN_INDEX]),
            prevHash = row[12] as String,
            entryHash = row[13] as String,
            sealedStartValue = (row[14] as Number?)?.toLong(),
            sealedCount = (row[15] as Number?)?.toLong(),
            sealedFirstNumber = row[16] as String?,
            sealedLastNumber = row[17] as String?,
        )

    /** Depending on the Hibernate configuration, the JDBC driver returns a `TIMESTAMPTZ` column
     *  either as `java.sql.Timestamp` or already as `java.time.Instant` — both are expected
     *  here; a rigid `as java.sql.Timestamp` would break on the other variant. `internal` (not
     *  `private`) so a unit test can drive the `OffsetDateTime`/`Timestamp`/error branches
     *  directly: which type the real JDBC driver hands back for `TIMESTAMPTZ` is fixed for a
     *  given Testcontainers setup, so an IT alone can never reach all of them. */
    internal fun toOffsetDateTime(raw: Any?): OffsetDateTime = when (raw) {
        is OffsetDateTime -> raw
        is java.time.Instant -> raw.atOffset(ZoneOffset.UTC)
        is java.sql.Timestamp -> raw.toInstant().atOffset(ZoneOffset.UTC)
        else -> error("Unerwarteter Zeittyp aus number_journal.occurred_at: ${raw?.javaClass}")
    }

    companion object {
        /** Dedicated namespace for the chain lock so it doesn't share PostgreSQL's global
         *  advisory-lock space with anyone. */
        private const val ADVISORY_LOCK_NAMESPACE = 1304

        /** Index of `occurred_at` in [SELECT_COLUMNS]'s column order. */
        private const val OCCURRED_AT_COLUMN_INDEX = 11

        private const val PARAM_RANGE_ID = "rangeId"

        /** Shared `SELECT ... FROM` prefix — [query] and [streamAllBatched] both append their own
         *  clause after it, and [mapRow] assumes this exact column order. */
        private const val SELECT_COLUMNS =
            """SELECT seq, number_range_id, event, period_key, number_prefix, number_sequence, number,
                      entity_type, entity_id, reason, actor, occurred_at, prev_hash, entry_hash,
                      sealed_start_value, sealed_count, sealed_first_number, sealed_last_number
                 """
    }
}
