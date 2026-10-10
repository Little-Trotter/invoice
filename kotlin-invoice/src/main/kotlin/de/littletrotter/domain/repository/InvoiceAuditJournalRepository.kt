package de.littletrotter.domain.repository

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.shared.SortDirection
import java.time.OffsetDateTime

/**
 * One business action, grouped by `invoice_audit_journal.business_tx`: the row-level
 * repository (this file, findActionGroupsByInvoiceId/findActionGroupsPage) never loads the
 * technical rows themselves, only the group's own aggregate columns. Which invoice(s) the
 * business_tx touched (almost always one; a `STORNO` transaction touches two — see
 * `InvoiceAuditAction.STORNO`'s own KDoc) is deliberately NOT carried here: the caller
 * ([de.littletrotter.application.invoice.AuditJournalQueryService]) loads the matching technical
 * rows for a whole page of groups in ONE second query (findByBusinessTx), and those rows already
 * carry `invoiceId`/`invoiceNumber` correctly paired per row — a second, independently `DISTINCT`-
 * aggregated array in this SQL would risk the two arrays losing their pairing (Postgres gives no
 * ordering guarantee between two separate `ARRAY_AGG(DISTINCT ...)` calls on different columns).
 */
data class InvoiceAuditActionGroup(
    val businessTx: Long,
    val action: InvoiceAuditAction,
    val actor: String,
    val actorName: String?,
    /** Representative timestamp for the whole action — `MIN(occurred_at)`, i.e. the first row
     *  this business transaction wrote (matches [minSeq]). */
    val occurredAt: OffsetDateTime,
    val minSeq: Long,
    val maxSeq: Long,
    val rowCount: Long,
)

/**
 * Read-only access to `invoice_audit_journal` (append-only, hash-chained; written
 * exclusively by the `capture_invoice_audit_event()` DB trigger — see
 * `V1__baseline_schema.sql`, section 13). No write method exists here on purpose: unlike
 * [NumberJournalRepository], nothing in the application ever appends to this journal directly.
 *
 * Shaped for its known consumers: the nightly chain-verification job ([streamBatch],
 * cursor-based — the table has unbounded retention, J3), the per-invoice journal export
 * ([streamByInvoiceId]), and the two journal views below, which read one line per BUSINESS ACTION
 * rather than one line per row — [findActionGroupsByInvoiceId]/[findActionGroupsPage] group by
 * `business_tx` (`MIN(seq)`/`MAX(seq)`/`COUNT(*)`, paged and ordered by `MIN(seq)` so an action
 * with many technical rows never splits across a page boundary), with [findByBusinessTx] as the
 * one second query that loads those groups' technical rows.
 */
interface InvoiceAuditJournalRepository {

    /** Every row, ascending by seq, in one call — small-scale/dev use only. The nightly chain
     *  check uses [streamBatch] instead, so it never holds the whole (unbounded, J3) table in
     *  memory at once; nothing in production calls this method today. */
    fun streamAll(): List<InvoiceAuditJournalEntry>

    /** Cursor-based batch read for the nightly chain-verification job: rows with
     *  `seq > afterSeq`, ascending by seq, capped at [batchSize]. Called repeatedly with the
     *  previous batch's last `seq` until it returns fewer than [batchSize] rows (or none) — the
     *  whole-table sweep this exists for must never load `invoice_audit_journal` (unbounded
     *  retention, J3) into memory in one shot. */
    fun streamBatch(afterSeq: Long, batchSize: Int): List<InvoiceAuditJournalEntry>

    /** Cursor-based batch read for the `Journal` export sheet: one invoice's rows with
     *  `seq > afterSeq`, ascending by seq, capped at [batchSize]. Same seq-cursor shape as
     *  [streamBatch] (a single invoice's own history is unbounded too, J3), scoped to [invoiceId]
     *  instead of the whole table. Called repeatedly with the previous batch's last `seq` until it
     *  returns fewer than [batchSize] rows (or none), the same convention every other batch method
     *  on this interface already uses. */
    fun streamByInvoiceId(invoiceId: Long, afterSeq: Long, batchSize: Int): List<InvoiceAuditJournalEntry>

    /** One invoice's rows, paged, ordered by [direction] on `seq` — used directly by
     *  de.littletrotter.application.invoice.InvoiceAuditActionCaptureIT and similar row-level
     *  capture tests; the two journal VIEWS read the grouped [findActionGroupsByInvoiceId] instead
     *  (see this interface's own KDoc). `seq` (not `occurred_at`) is the sort key: it is assigned
     *  under the advisory lock in commit order, it is the hash-chain order, it is unique (no
     *  tie-breaker needed) and it is index-backed (`(invoice_id, seq)`). */
    fun findByInvoiceId(
        invoiceId: Long,
        pageIndex: Int,
        pageSize: Int,
        direction: SortDirection = SortDirection.DESC,
    ): List<InvoiceAuditJournalEntry>

    /** Row count for [findByInvoiceId]. */
    fun countByInvoiceId(invoiceId: Long): Long

    /** Every row, paged, optionally narrowed by [filter], ordered by [direction] on `seq`.
     *  Row-level filtering (including by [InvoiceAuditRowFilter.event]) stays on this port even
     *  though the global VIEW no longer offers an event filter of its own (PO decision 2: the
     *  UI control and its dedicated wiring are what got removed, not this general-purpose
     *  repository capability). */
    fun findPage(
        pageIndex: Int,
        pageSize: Int,
        filter: InvoiceAuditRowFilter = InvoiceAuditRowFilter.EMPTY,
        direction: SortDirection = SortDirection.DESC,
    ): List<InvoiceAuditJournalEntry>

    /** Row count for the same [filter] [findPage] accepts. */
    fun countPage(filter: InvoiceAuditRowFilter = InvoiceAuditRowFilter.EMPTY): Long

    /** One invoice's history, one line per business action, paged and ordered by
     *  [direction] on `MIN(seq)` (the same seq-not-occurred_at reasoning, extended to the group:
     *  stable, index-backed via `idx_invoice_audit_journal_business_tx (business_tx, seq)`).
     *  A `STORNO` transaction's rows exist on two invoices; scoped to [invoiceId] here, so the
     *  returned group's [InvoiceAuditActionGroup.rowCount]/InvoiceAuditActionGroup.invoiceIds
     *  reflect only THIS invoice's own side of it. */
    fun findActionGroupsByInvoiceId(
        invoiceId: Long,
        pageIndex: Int,
        pageSize: Int,
        direction: SortDirection = SortDirection.DESC,
    ): List<InvoiceAuditActionGroup>

    /** Row (action) count for [findActionGroupsByInvoiceId] — the per-invoice view's pagination
     *  control; counts DISTINCT `business_tx`, never technical rows. */
    fun countActionGroupsByInvoiceId(invoiceId: Long): Long

    /** Every business action across every invoice, one line per `business_tx`, optionally
     *  narrowed by [filter] (see [InvoiceAuditActionFilter] for the allow-list and date-bound
     *  conventions), paged and ordered by [direction] on `MIN(seq)`. */
    fun findActionGroupsPage(
        pageIndex: Int,
        pageSize: Int,
        filter: InvoiceAuditActionFilter = InvoiceAuditActionFilter.EMPTY,
        direction: SortDirection = SortDirection.DESC,
    ): List<InvoiceAuditActionGroup>

    /** Row (action) count for the same [filter] [findActionGroupsPage] accepts — counts DISTINCT
     *  `business_tx`, never technical rows. */
    fun countActionGroupsPage(filter: InvoiceAuditActionFilter = InvoiceAuditActionFilter.EMPTY): Long

    /** The technical rows of every group in [businessTxs], ascending by seq, in ONE query
     *  regardless of how many groups the caller's page holds (no N+1: [findActionGroupsByInvoiceId]/
     *  [findActionGroupsPage] never load these themselves). [invoiceId], when given, scopes the
     *  result to that invoice's own rows only — the per-invoice panel's own half of a `STORNO`
     *  group's two-invoice business_tx. */
    fun findByBusinessTx(businessTxs: Collection<Long>, invoiceId: Long? = null): List<InvoiceAuditJournalEntry>
}
