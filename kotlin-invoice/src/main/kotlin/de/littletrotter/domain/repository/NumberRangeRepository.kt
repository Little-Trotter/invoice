package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject

/** Reference data port for [NumberRange] data. */
interface NumberRangeRepository {
    fun findActive(subject: NumberRangeSubject): NumberRange?
    /** Every range of [subject], active or not, ordered by id. */
    fun findBySubject(subject: NumberRangeSubject): List<NumberRange>
    fun findById(id: Long): NumberRange?
    fun findAll(pageIndex: Int = 0, pageSize: Int = 60, filterText: String? = null): List<NumberRange>
    fun count(filterText: String? = null): Long
    /** Count of ranges with `isActive = false`, same [filterText] semantics as
     *  [count]; the header counter's "N inaktiv" half. */
    fun countInactive(filterText: String? = null): Long
    fun save(range: NumberRange): NumberRange
    fun delete(id: Long)
}

/**
 * Counter storage for [NumberRange] — key: `(number_range_id, scope_key, period_key)`.
 * Two allocation paths differ in concurrency: UNIQUE_ONLY in own tx, GAPLESS in caller's tx (with row lock).
 */
interface NumberCounterRepository {
    /**
     * Next number this range would allocate (without consuming; for preview).
     * Contract: before first allocation = [NumberRange.startValue]; after each = last+1.
     */
    fun peekNext(range: NumberRange, scopeKey: String, periodKey: String): Long

    /** UNIQUE_ONLY allocation in own transaction. */
    fun allocateUniqueOnly(range: NumberRange, scopeKey: String, periodKey: String): Long

    /** GAPLESS allocation in caller's transaction. */
    fun allocateGapless(range: NumberRange, scopeKey: String, periodKey: String): Long

    /** Current counter value (last allocated) for range. */
    fun currentValue(subject: NumberRangeSubject): Long?

    /**
     * Raises `last_value` for `(range, scopeKey, periodKey)` up to [target] — never lowers it; if
     * the current value is already at or past [target], it is returned unchanged. Backs the
     * "synchronize number counters to existing data" admin action (see
     * [de.littletrotter.application.reference.NumberRangeService.syncCounterTo]), which heals
     * drift between bulk-loaded rows and the counter in one call instead of relying on
     * [de.littletrotter.application.masterdata.MasterDataNumberService]'s per-save skip-to-free
     * retry to walk past every stale number one at a time.
     *
     * Default: repeatedly calls [allocateUniqueOnly] — correct but O(n) allocations; adequate for
     * in-memory test doubles. [de.littletrotter.infrastructure.persistence
     * .PostgresNumberCounterRepository] overrides this with a single atomic upsert.
     */
    fun raiseToAtLeast(range: NumberRange, scopeKey: String, periodKey: String, target: Long): Long {
        var current = peekNext(range, scopeKey, periodKey) - 1
        while (current < target) {
            current = allocateUniqueOnly(range, scopeKey, periodKey)
        }
        return current
    }

    /**
     * Whether range was ever used (authoritative: row exists only after allocate call, not peek).
     * Used to reject prefix changes on active ranges.
     */
    fun hasBeenUsed(rangeId: Long): Boolean = false
}
