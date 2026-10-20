package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.repository.InvoiceAuditActionFilter
import de.littletrotter.domain.repository.InvoiceAuditActionGroup
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import de.littletrotter.domain.repository.InvoiceAuditRowFilter
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Read-only, native-query based, same shape as [PostgresNumberJournalRepository]:
 * `invoice_audit_journal` has no JPA `@Entity` and is written exclusively by the
 * `capture_invoice_audit_event()` DB trigger (`V1__baseline_schema.sql`, section 13), never
 * through this class or any other application code.
 */
@ApplicationScoped
class PostgresInvoiceAuditJournalRepository @Inject constructor(
    private val em: EntityManager,
) : InvoiceAuditJournalRepository {

    private companion object {
        private const val PARAM_INVOICE_ID = "invoiceId"
    }

    /** Loads the whole table ascending by seq in one go — small-scale/dev use only. The
     *  nightly chain-verification job uses [streamBatch] instead (keyset pagination): the table
     *  has unbounded retention (J3), so a batch job must never hold it all in memory at once.
     *  Nothing in production calls this method today. */
    override fun streamAll(): List<InvoiceAuditJournalEntry> = query("ORDER BY seq")

    /** `firstResult = 0` (via `pageIndex = 0`) plus `maxResults = batchSize` on a
     *  `seq > :afterSeq` clause is exactly `LIMIT batchSize` with no `OFFSET`: the caller's own
     *  `afterSeq` cursor already does the work an `OFFSET` would, and unlike `OFFSET` it doesn't
     *  re-scan/re-skip the rows already verified in earlier batches. */
    override fun streamBatch(afterSeq: Long, batchSize: Int): List<InvoiceAuditJournalEntry> =
        query("WHERE seq > :afterSeq ORDER BY seq", mapOf("afterSeq" to afterSeq), pageIndex = 0, pageSize = batchSize)

    /** Same `firstResult = 0` / `maxResults = batchSize` shape as [streamBatch], scoped to
     *  one invoice: the caller's own `afterSeq` cursor already excludes every row the previous
     *  batch returned, so no `OFFSET` is needed here either. */
    override fun streamByInvoiceId(invoiceId: Long, afterSeq: Long, batchSize: Int): List<InvoiceAuditJournalEntry> =
        query(
            "WHERE invoice_id = :invoiceId AND seq > :afterSeq ORDER BY seq",
            mapOf(PARAM_INVOICE_ID to invoiceId, "afterSeq" to afterSeq),
            pageIndex = 0,
            pageSize = batchSize,
        )

    override fun findByInvoiceId(
        invoiceId: Long,
        pageIndex: Int,
        pageSize: Int,
        direction: SortDirection,
    ): List<InvoiceAuditJournalEntry> =
        query(
            // Direction comes from the SortDirection enum, never a caller-supplied string:
            // `.name` only ever yields the literal "ASC"/"DESC".
            "WHERE invoice_id = :invoiceId ORDER BY seq ${direction.name}",
            mapOf(PARAM_INVOICE_ID to invoiceId),
            pageIndex,
            pageSize,
        )

    override fun countByInvoiceId(invoiceId: Long): Long {
        val countQuery = em.createNativeQuery("SELECT COUNT(*) FROM invoice_audit_journal WHERE invoice_id = :invoiceId")
        countQuery.setParameter(PARAM_INVOICE_ID, invoiceId)
        return (countQuery.singleResult as Number).toLong()
    }

    override fun findPage(
        pageIndex: Int,
        pageSize: Int,
        filter: InvoiceAuditRowFilter,
        direction: SortDirection,
    ): List<InvoiceAuditJournalEntry> {
        val (clause, params) = rowFilterClause(filter)
        // Direction comes from the SortDirection enum, never a caller-supplied string.
        return query("$clause ORDER BY seq ${direction.name}", params, pageIndex, pageSize)
    }

    override fun countPage(filter: InvoiceAuditRowFilter): Long {
        val (clause, params) = rowFilterClause(filter)
        val countQuery = em.createNativeQuery("SELECT COUNT(*) FROM invoice_audit_journal $clause")
        params.forEach { (name, value) -> countQuery.setParameter(name, value) }
        return (countQuery.singleResult as Number).toLong()
    }

    /** Builds a `WHERE ...` clause (or the empty string) plus its bind parameters for
     *  [findPage]/[countPage] — every parameter is always bound, `clause` itself is never
     *  assembled from caller-supplied text. */
    private fun rowFilterClause(filter: InvoiceAuditRowFilter): Pair<String, Map<String, Any>> {
        val w = WhereBuilder()
        w.add("invoice_number = :invoiceNumber", "invoiceNumber", filter.invoiceNumber)
        w.add("actor = :actor", "actor", filter.actor)
        w.add("event = :event", "event", filter.event?.ordinal)
        w.add("occurred_at >= :occurredFrom", "occurredFrom", filter.occurredFrom)
        w.add("occurred_at < :occurredTo", "occurredTo", filter.occurredTo)
        return w.clause() to w.params
    }

    /** Accumulates SQL `WHERE` conditions with always-bound named parameters — every
     *  [add] call ties the condition text to one parameter name and one nullable value,
     *  skipping the condition when the value is null (the same discipline [rowFilterClause]
     *  and [filterClause] both enforce). Used by both clause builders so the four common
     *  dimension bindings (invoiceNumber, actor, occurredFrom, occurredTo) are written once. */
    private class WhereBuilder {
        private val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        fun add(condition: String, name: String, value: Any?) {
            if (value != null) {
                conditions += condition
                params[name] = value
            }
        }

        /** Multi-value counterpart of [add]: one condition bound to several named parameters at
         *  once (an IN-list's `:name0, :name1, …`). */
        fun addAll(condition: String, values: Map<String, Any>) {
            conditions += condition
            params.putAll(values)
        }

        fun clause(): String =
            if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
    }

    override fun findActionGroupsByInvoiceId(
        invoiceId: Long,
        pageIndex: Int,
        pageSize: Int,
        direction: SortDirection,
    ): List<InvoiceAuditActionGroup> =
        groupQuery(
            "WHERE invoice_id = :invoiceId",
            mapOf(PARAM_INVOICE_ID to invoiceId),
            direction,
            PageRequest(pageIndex, pageSize),
        )

    override fun countActionGroupsByInvoiceId(invoiceId: Long): Long {
        val countQuery = em.createNativeQuery(
            "SELECT COUNT(DISTINCT business_tx) FROM invoice_audit_journal WHERE invoice_id = :invoiceId",
        )
        countQuery.setParameter(PARAM_INVOICE_ID, invoiceId)
        return (countQuery.singleResult as Number).toLong()
    }

    override fun findActionGroupsPage(
        pageIndex: Int,
        pageSize: Int,
        filter: InvoiceAuditActionFilter,
        direction: SortDirection,
    ): List<InvoiceAuditActionGroup> {
        val (clause, params) = filterClause(filter)
        return groupQuery(clause, params, direction, PageRequest(pageIndex, pageSize))
    }

    override fun countActionGroupsPage(filter: InvoiceAuditActionFilter): Long {
        val (clause, params) = filterClause(filter)
        val countQuery = em.createNativeQuery("SELECT COUNT(DISTINCT business_tx) FROM invoice_audit_journal $clause")
        params.forEach { (name, value) -> countQuery.setParameter(name, value) }
        return (countQuery.singleResult as Number).toLong()
    }

    override fun findByBusinessTx(businessTxs: Collection<Long>, invoiceId: Long?): List<InvoiceAuditJournalEntry> {
        if (businessTxs.isEmpty()) return emptyList()
        // Named per-value parameters (:tx0, :tx1, ...), same discipline [filterClause]
        // uses for the multi-select action filter below: an IN-list built from caller-supplied
        // Long values never becomes literal SQL text, only bind parameter names do.
        val txParamNames = List(businessTxs.size) { "tx$it" }
        val conditions = mutableListOf("business_tx IN (${txParamNames.joinToString(", ") { ":$it" }})")
        val params = mutableMapOf<String, Any>()
        businessTxs.forEachIndexed { index, tx -> params["tx$index"] = tx }
        if (invoiceId != null) {
            conditions += "invoice_id = :invoiceId"
            params[PARAM_INVOICE_ID] = invoiceId
        }
        val clause = "WHERE " + conditions.joinToString(" AND ")
        return query("$clause ORDER BY seq", params)
    }

    /** Shared GROUP BY query behind every `findActionGroups*` method — [clause] is any `WHERE ...`
     *  (or the empty string) built by [filterClause]/the per-invoice callers above; [direction]
     *  orders and pages by `MIN(seq)` (the same seq-not-occurred_at reasoning, extended to the
     *  group), so an action's technical rows never split across a page boundary. */
    /** [pageIndex]/[pageSize] bundled so [groupQuery] takes one parameter for the page instead of
     *  two (LongParameterList). */
    private data class PageRequest(val pageIndex: Int, val pageSize: Int)

    private fun groupQuery(
        clause: String,
        params: Map<String, Any>,
        direction: SortDirection,
        page: PageRequest,
    ): List<InvoiceAuditActionGroup> {
        val nativeQuery = em.createNativeQuery(
            """SELECT business_tx,
                      MIN(action)       AS action,
                      MIN(actor)        AS actor,
                      MIN(actor_name)   AS actor_name,
                      MIN(occurred_at)  AS occurred_at,
                      MIN(seq)          AS min_seq,
                      MAX(seq)          AS max_seq,
                      COUNT(*)          AS row_count
                 FROM invoice_audit_journal $clause
                GROUP BY business_tx
                ORDER BY MIN(seq) ${direction.name}""",
        )
        params.forEach { (name, value) -> nativeQuery.setParameter(name, value) }
        nativeQuery.firstResult = page.pageIndex * page.pageSize
        nativeQuery.maxResults = page.pageSize
        // Casting each row to the reified `Array<*>` (its real runtime shape) is a
        // checked cast; only casting the whole `resultList` to `List<Array<Any?>>` was unchecked.
        return nativeQuery.resultList.map { toGroup(it as Array<*>) }
    }

    /** Builds a `WHERE ...` clause (or the empty string) plus its bind parameters for
     *  `findActionGroupsPage`/`countActionGroupsPage` — every parameter is always bound, `clause`
     *  itself is never assembled from caller-supplied text. [InvoiceAuditActionFilter.actions] (a
     *  multi-select allow-list) becomes named per-value parameters, the same discipline [findByBusinessTx] uses for its own
     *  IN-list, rather than a single collection parameter bound to a native `IN (:actions)` — JPA's
     *  native-query parameter binding gives no portable guarantee that a collection value expands
     *  into an IN-list the way JPQL's does. */
    private fun filterClause(filter: InvoiceAuditActionFilter): Pair<String, Map<String, Any>> {
        val w = WhereBuilder()
        if (filter.actions.isNotEmpty()) {
            val actionParams = filter.actions.withIndex().associate { (index, action) -> "action$index" to action.ordinal }
            w.addAll("action IN (${actionParams.keys.joinToString(", ") { ":$it" }})", actionParams)
        }
        w.add("invoice_number = :invoiceNumber", "invoiceNumber", filter.invoiceNumber)
        w.add("actor = :actor", "actor", filter.actor)
        w.add("occurred_at >= :occurredFrom", "occurredFrom", filter.occurredFrom)
        w.add("occurred_at < :occurredTo", "occurredTo", filter.occurredTo)
        return w.clause() to w.params
    }

    private fun query(
        clause: String,
        params: Map<String, Any> = emptyMap(),
        pageIndex: Int? = null,
        pageSize: Int? = null,
    ): List<InvoiceAuditJournalEntry> {
        val nativeQuery = em.createNativeQuery(
            """SELECT seq, invoice_id, invoice_number, actor, actor_name, occurred_at, event, action,
                      business_tx, detail, document_sha256, prev_hash, entry_hash
                 FROM invoice_audit_journal $clause""",
        )
        params.forEach { (name, value) -> nativeQuery.setParameter(name, value) }
        if (pageIndex != null && pageSize != null) {
            nativeQuery.firstResult = pageIndex * pageSize
            nativeQuery.maxResults = pageSize
        }
        return nativeQuery.resultList.map { toEntry(it as Array<*>) }
    }

    /** Column positions of the `SELECT` in [query] — named rather than positional literals so
     *  the mapping in [toEntry] stays readable and self-documenting as the column count grows. */
    private object Col {
        const val SEQ = 0
        const val INVOICE_ID = 1
        const val INVOICE_NUMBER = 2
        const val ACTOR = 3
        const val ACTOR_NAME = 4
        const val OCCURRED_AT = 5
        const val EVENT = 6
        const val ACTION = 7
        const val BUSINESS_TX = 8
        const val DETAIL = 9
        const val DOCUMENT_SHA256 = 10
        const val PREV_HASH = 11
        const val ENTRY_HASH = 12
    }

    /** Column positions of the `SELECT` in [groupQuery] — see [Col]'s own KDoc for why named. */
    private object GroupCol {
        const val BUSINESS_TX = 0
        const val ACTION = 1
        const val ACTOR = 2
        const val ACTOR_NAME = 3
        const val OCCURRED_AT = 4
        const val MIN_SEQ = 5
        const val MAX_SEQ = 6
        const val ROW_COUNT = 7
    }

    private fun toEntry(row: Array<*>): InvoiceAuditJournalEntry =
        InvoiceAuditJournalEntry(
            seq = (row[Col.SEQ] as Number).toLong(),
            businessTx = (row[Col.BUSINESS_TX] as Number).toLong(),
            invoiceId = (row[Col.INVOICE_ID] as Number).toLong(),
            invoiceNumber = row[Col.INVOICE_NUMBER] as String?,
            actor = row[Col.ACTOR] as String,
            actorName = row[Col.ACTOR_NAME] as String?,
            occurredAt = toOffsetDateTime(row[Col.OCCURRED_AT]),
            event = InvoiceAuditEvent.fromOrdinal((row[Col.EVENT] as Number).toInt()),
            action = InvoiceAuditAction.fromOrdinal((row[Col.ACTION] as Number).toInt()),
            detail = row[Col.DETAIL].toString(),
            documentSha256 = row[Col.DOCUMENT_SHA256] as String?,
            prevHash = row[Col.PREV_HASH] as String,
            entryHash = row[Col.ENTRY_HASH] as String,
        )

    private fun toGroup(row: Array<*>): InvoiceAuditActionGroup =
        InvoiceAuditActionGroup(
            businessTx = (row[GroupCol.BUSINESS_TX] as Number).toLong(),
            action = InvoiceAuditAction.fromOrdinal((row[GroupCol.ACTION] as Number).toInt()),
            actor = row[GroupCol.ACTOR] as String,
            actorName = row[GroupCol.ACTOR_NAME] as String?,
            occurredAt = toOffsetDateTime(row[GroupCol.OCCURRED_AT]),
            minSeq = (row[GroupCol.MIN_SEQ] as Number).toLong(),
            maxSeq = (row[GroupCol.MAX_SEQ] as Number).toLong(),
            rowCount = (row[GroupCol.ROW_COUNT] as Number).toLong(),
        )

    /** Same driver-dependent TIMESTAMPTZ handling as [PostgresNumberJournalRepository]; `internal`
     *  (not `private`) for the same reason as that sibling's own `toOffsetDateTime` — which
     *  driver type actually comes back is fixed for a given Testcontainers/driver setup, so a
     *  real IT read only ever exercises one branch; the others are driven directly. */
    internal fun toOffsetDateTime(raw: Any?): OffsetDateTime = when (raw) {
        is OffsetDateTime -> raw
        is java.time.Instant -> raw.atOffset(ZoneOffset.UTC)
        is java.sql.Timestamp -> raw.toInstant().atOffset(ZoneOffset.UTC)
        else -> error("Unexpected time type from invoice_audit_journal.occurred_at: ${raw?.javaClass}")
    }
}
