package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.repository.MasterdataChangeListener
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.domain.shared.likePattern
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.TypedQuery
import jakarta.persistence.Tuple
import jakarta.transaction.Transactional
import jakarta.transaction.Transactional.TxType
import de.littletrotter.domain.shared.FILTER_PARAM
import de.littletrotter.domain.shared.likeFilter
import org.hibernate.Session

@ApplicationScoped
class PanacheNumberRangeRepository @Inject constructor(
    private val em: EntityManager,
    private val masterdataChanges: MasterdataChangeListener,
) : NumberRangeRepository {

    override fun findActive(subject: NumberRangeSubject): NumberRange? =
        subjectQuery(subject, onlyActive = true).setMaxResults(1).resultList.firstOrNull()

    override fun findBySubject(subject: NumberRangeSubject): List<NumberRange> =
        subjectQuery(subject, onlyActive = false).resultList

    private fun subjectQuery(subject: NumberRangeSubject, onlyActive: Boolean) =
        em.createQuery(
            "SELECT r FROM NumberRange r WHERE r.subject = :subject" +
                (if (onlyActive) " AND r.isActive = true" else "") + " ORDER BY r.id",
            NumberRange::class.java,
        ).setParameter("subject", subject)

    override fun findById(id: Long): NumberRange? = em.find(NumberRange::class.java, id)

    override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<NumberRange> =
        em.createQuery("SELECT r FROM NumberRange r${where(filterText)} ORDER BY r.subject, r.id", NumberRange::class.java)
            .bindFilter(filterText)
            .setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList

    override fun count(filterText: String?): Long =
        em.createQuery("SELECT COUNT(r) FROM NumberRange r${where(filterText)}", Long::class.java)
            .bindFilter(filterText)
            .singleResult

    override fun countInactive(filterText: String?): Long {
        val condition = if (filterText.isNullOrBlank()) "" else " AND (${likeFilter("r.displayName")} OR ${likeFilter("r.code")})"
        return em.createQuery("SELECT COUNT(r) FROM NumberRange r WHERE r.isActive = false$condition", Long::class.java)
            .bindFilter(filterText)
            .singleResult
    }

    /** IMP-3 — filter condition in one place (not separate in findAll and count): both must hit
     *  the same set, else UI counts differ from display. Parentheses around OR branches are critical:
     *  adding an AND condition without them reverses precedence and changes query semantics. */
    private fun where(filterText: String?): String =
        if (filterText.isNullOrBlank()) {
            ""
        } else {
            " WHERE (${likeFilter("r.displayName")} OR ${likeFilter("r.code")})"
        }

    private fun <T> TypedQuery<T>.bindFilter(filterText: String?): TypedQuery<T> =
        also { if (!filterText.isNullOrBlank()) it.setParameter(FILTER_PARAM, likePattern(filterText)) }

    @Transactional
    override fun save(range: NumberRange): NumberRange {
        val saved = if (range.id == null) { em.persist(range); range } else em.merge(range)
        masterdataChanges.saved(saved)
        return saved
    }

    @Transactional
    override fun delete(id: Long) {
        em.find(NumberRange::class.java, id)?.let {
            masterdataChanges.deleting(it)
            em.remove(it)
        }
    }
}

/** Counter column (Postgres `INSERT … ON CONFLICT … RETURNING`). Stores **last_value** —
 *  **last issued**, not next: first allocation creates row with [NumberRange.startValue] and
 *  returns it (first number *is* start value); each subsequent increments by 1 and returns
 *  incremented. Column always holds just-issued number; next is `last_value + 1`.
 *  Column was named `next_value` until V072, contradicting its contents — [peekNext] returned
 *  unchanged and showed already-consumed numbers in UI (IMP-1). Column name is not cosmetic;
 *  every reader here depends on its truth. */
@ApplicationScoped
class PostgresNumberCounterRepository @Inject constructor(private val em: EntityManager) : NumberCounterRepository {

    override fun peekNext(range: NumberRange, scopeKey: String, periodKey: String): Long {
        // Session.createNativeQuery(sql, Tuple::class.java) uses Hibernate's dedicated
        // NativeQueryTupleTransformer, unlike passing a plain scalar Class<R> (e.g. Number, which
        // is abstract and has no matching constructor — Hibernate's implicit constructor-result
        // mapping for a non-entity Class<R> then fails at runtime instead of just reading the
        // column). Tuple.get(index, Class<X>) is a checked type read, not a cast.
        val found = em.unwrap(Session::class.java).createNativeQuery(
            "SELECT last_value FROM number_counter WHERE number_range_id = :rid AND scope_key = :sk AND period_key = :pk",
            Tuple::class.java,
        ).setParameter("rid", range.id)
            .setParameter("sk", scopeKey)
            .setParameter("pk", periodKey)
            .resultList
        // No row yet = start value is next. With row = last issued, next is that + 1.
        return if (found.isEmpty()) range.startValue else found[0].get(0, Number::class.java).toLong() + 1
    }

    @Transactional(TxType.REQUIRES_NEW)
    override fun allocateUniqueOnly(range: NumberRange, scopeKey: String, periodKey: String): Long {
        return upsert(range, scopeKey, periodKey)
    }

    /**
     * No `SELECT … FOR UPDATE` before the upsert: `upsert()` alone is already the atomic
     * operation that Postgres serializes via the unique counter column. A preceding
     * `INSERT … ON CONFLICT DO NOTHING` (an earlier version of this method) would, on the very
     * first call for a key, already have created the row before `upsert()` itself ran its own
     * `INSERT … ON CONFLICT DO UPDATE … RETURNING` — so that second insert would always hit the
     * conflict branch and wrongly emit startValue+1 instead of startValue. Only difference from
     * [allocateUniqueOnly] is the transaction: here the caller's (no `@Transactional`), there its
     * own new one.
     */
    override fun allocateGapless(range: NumberRange, scopeKey: String, periodKey: String): Long =
        upsert(range, scopeKey, periodKey)

    /**
     * Own new transaction, like [allocateUniqueOnly]: this is an admin maintenance action, not
     * part of any caller's document-save transaction. `GREATEST(...)` in the same statement as
     * the upsert is what makes "never lowers" atomic against concurrent allocation — a plain
     * read-compare-write here could race with an in-flight [allocateUniqueOnly] and undo it.
     */
    @Transactional(TxType.REQUIRES_NEW)
    override fun raiseToAtLeast(range: NumberRange, scopeKey: String, periodKey: String, target: Long): Long {
        // See peekNext's KDoc for why Tuple, not a plain Number::class.java result class.
        val res = em.unwrap(Session::class.java).createNativeQuery(
            """INSERT INTO number_counter (number_range_id, scope_key, period_key, last_value)
               VALUES (:rid, :sk, :pk, :target)
               ON CONFLICT (number_range_id, scope_key, period_key)
               DO UPDATE SET last_value = GREATEST(number_counter.last_value, :target)
               RETURNING last_value""",
            Tuple::class.java,
        ).setParameter("rid", range.id)
            .setParameter("sk", scopeKey)
            .setParameter("pk", periodKey)
            .setParameter("target", target)
            .singleResult
        return res.get(0, Number::class.java).toLong()
    }

    private fun upsert(range: NumberRange, scopeKey: String, periodKey: String): Long {
        val res = em.unwrap(Session::class.java).createNativeQuery(
            """INSERT INTO number_counter (number_range_id, scope_key, period_key, last_value)
               VALUES (:rid, :sk, :pk, :start)
               ON CONFLICT (number_range_id, scope_key, period_key)
               DO UPDATE SET last_value = number_counter.last_value + 1
               RETURNING last_value""",
            Tuple::class.java,
        ).setParameter("rid", range.id)
            .setParameter("sk", scopeKey)
            .setParameter("pk", periodKey)
            .setParameter("start", range.startValue)
            .singleResult
        return res.get(0, Number::class.java).toLong()
    }

    /** Last issued value is in column as-is; hence `MAX(last_value)` without correction
     *  (before IMP-1 fix: `MAX(next_value) - 1`, yielding second-to-last due to bad naming).
     *  MAX aggregates independent counters (per vendor/period) into one value; for overview
     *  column that's intentional coarse-grained summary. Exact per-counter requires key on
     *  `number_range.id` not subject; different signature (see IMP-3). */
    override fun currentValue(subject: NumberRangeSubject): Long? {
        // See peekNext's KDoc for why Tuple; MAX() over zero rows still yields one row
        // whose column is null, so the get(...) read stays nullable without any cast.
        val res = em.unwrap(Session::class.java).createNativeQuery(
            "SELECT MAX(c.last_value) FROM number_counter c " +
                "JOIN number_range r ON r.id = c.number_range_id WHERE r.subject = :subject",
            Tuple::class.java,
        ).setParameter("subject", subject.ordinal).singleResult
        return res.get(0, Number::class.java)?.toLong()
    }

    // RD3-F1 — a row for this range in number_counter exists only once allocate…() has run at
    // least once (upsert() above); peekNext() never inserts one. That makes existence itself the
    // authoritative "has this range ever been used" signal, independent of scope/period.
    override fun hasBeenUsed(rangeId: Long): Boolean {
        val res = em.createNativeQuery(
            "SELECT EXISTS(SELECT 1 FROM number_counter WHERE number_range_id = :rid)",
        ).setParameter("rid", rangeId).singleResult
        return res as Boolean
    }
}
