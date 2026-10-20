package de.littletrotter.application.export.port.outbound

import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.export.InvoiceHeaderExportRow
import de.littletrotter.application.export.InvoiceJournalExportRow
import de.littletrotter.application.export.InvoicePositionExportRow

/**
 * The export's own read side (CQRS, mirrors [de.littletrotter.application.invoice
 * .InvoiceReadRepository]). Deliberately separate from every other invoice read port: a bulk
 * export over thousands of invoices has different correctness requirements than a single-invoice
 * detail read —
 *
 * 1. it must never dirty what it reads (no touching [de.littletrotter.domain.invoice.Invoice
 *    .references] on a managed entity — read [de.littletrotter.domain.invoice.InvoiceReferences]
 *    fields through a scalar projection instead),
 * 2. one malformed stored [de.littletrotter.domain.invoice.InvoiceNumber] must flag one row, not
 *    fail the whole sweep, and
 * 3. it must stream in bounded batches (id-keyset paging: `WHERE id > :afterId ORDER BY id`)
 *    instead of materialising the whole selection.
 *
 * **Two export modes (settled by the PO, see the excel-export README's own scope note), one read
 * side for both:** mode A exports exactly one invoice (the feature's actual purpose — one header
 * row, its positions, its journal); mode B exports the filtered invoice table (a nice-to-have, no
 * journal). [findHeaderRow]/[findPositionRows]/[findJournalRows] serve mode A; [findHeaderRows]
 * (plural, filtered and keyset-paged) stays for mode B — it is not dead, both modes are real
 * callers of this same port. Mode B never gets a journal method: there is no PO requirement for
 * one, and this interface offers no bulk, multi-invoice journal read for a mode-B caller to reach
 * for even by accident.
 */
interface InvoiceExportReadRepository {

    /**
     * Mode A — the `Rechnungen` sheet's single row for one invoice, or `null` when [invoiceId]
     * does not exist. Every field, every trap (§1 above) and the snapshot-vs-live party fallback
     * are identical to [findHeaderRows]; only the selection differs (one id instead of a filter
     * plus keyset page).
     */
    fun findHeaderRow(invoiceId: Long): InvoiceHeaderExportRow?

    /**
     * Mode A — every position of one invoice's `Positionen` sheet, in `sortOrder` ascending order
     * (the sheet's own §2 authoritative ordering key). Empty for an invoice with no items or an id that
     * does not exist — this method never throws for an unknown id, unlike [findHeaderRow] which
     * distinguishes "not found" via `null` (a positions-only caller has nothing else to report).
     */
    fun findPositionRows(invoiceId: Long): List<InvoicePositionExportRow>

    /**
     * Mode B — [findPositionRows] for a whole batch of invoices in one round trip instead of one
     * query set per invoice. Keyed by invoice id; ids without positions are absent from the map.
     * Each list is in the same `sortOrder` order as the single-invoice variant.
     */
    fun findPositionRows(invoiceIds: List<Long>): Map<Long, List<InvoicePositionExportRow>>

    /**
     * Mode A — every change-journal entry of one invoice's `Journal` sheet, ascending by `seq` (the
     * chain order, the only honest ordering for this sheet). Empty for an invoice with no journal
     * entries or an id that does not exist — same "never throws for an unknown id" contract as
     * [findPositionRows]. Internally batched by `seq` cursor ([de.littletrotter.domain.repository
     * .InvoiceAuditJournalRepository.streamByInvoiceId]) so one invoice's unbounded history is
     * never materialised in a single unbatched query, even though every batch is joined back into
     * one list here — the sheet itself has no further use for the page boundaries.
     */
    fun findJournalRows(invoiceId: Long): List<InvoiceJournalExportRow>

    /**
     * Mode B — one row per invoice matching [filter], ordered by id, starting strictly after
     * [afterId] (`null` for the first batch). Returns at most [batchSize] rows — the caller keeps
     * calling with the last returned row's [InvoiceHeaderExportRow.invoiceId] as the next
     * [afterId] until fewer than [batchSize] rows come back.
     *
     * A mandatory [batchSize] parameter, not a bare default value on this abstract
     * member: a default *value* on an abstract function still needs a JVM-level defaulting
     * bridge that only a non-Kotlin (Java) caller can reach, unlike every other default in this
     * port family, which is a narrower overload with a real body (see [findHeaderRows] below).
     * That bridge is then permanently uncovered from Kotlin tests, dead weight this interface
     * doesn't need.
     */
    fun findHeaderRows(
        filter: InvoiceExportFilter,
        afterId: Long?,
        batchSize: Int,
    ): List<InvoiceHeaderExportRow>

    /** [findHeaderRows] above, using [DEFAULT_BATCH_SIZE]. */
    fun findHeaderRows(filter: InvoiceExportFilter, afterId: Long?): List<InvoiceHeaderExportRow> =
        findHeaderRows(filter, afterId, DEFAULT_BATCH_SIZE)

    companion object {
        /** The id-keyset page size every batch call uses by default. */
        const val DEFAULT_BATCH_SIZE = 500
    }
}
