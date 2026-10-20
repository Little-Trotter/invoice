package de.littletrotter.application.invoice

import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.repository.InvoiceAuditActionFilter
import de.littletrotter.domain.repository.InvoiceAuditActionGroup
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.GermanTime
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.OffsetDateTime
import de.littletrotter.application.invoice.port.inbound.AuditJournalQueryPort
import de.littletrotter.application.dto.JournalGlobalFilter
import de.littletrotter.application.dto.InvoiceAuditActionLine

/**
 * Read-only service over [InvoiceAuditJournalRepository] for the two journal views
 * (per-invoice tab, global system view). Injected directly into the Vaadin panels, the same
 * "small `@ApplicationScoped` service wrapping one repository" shape [GenerationJobService]
 * already uses for [de.littletrotter.ui.view.invoice.InvoiceExportPanel] — neither journal view
 * needs any of the invoice detail input ports. Its own reason to exist rather than injecting
 * [InvoiceAuditJournalRepository] directly: [occurredRange] below (day-granularity filter →
 * exact-match repository parameters), and assembling one
 * [InvoiceAuditActionLine] per business action out of the repository's two-query shape (grouped
 * page, then that page's technical rows in ONE further query — never a per-line query).
 *
 * Also the ONE place that resolves the small, batched name lookups a "Was genau" sentence
 * needs but a journal row never carries verbatim (a recipient's display name — `recipient_id` is
 * only a foreign key; a receipt's file name — `invoice_receipt`/`invoice_item_receipt` carry only
 * `receipt_id`). The Vaadin panels call [recipientNames]/[receiptFileNames] on the SAME page of
 * [InvoiceAuditActionLine]s [findActionsByInvoice]/[findGlobalActions] just returned — never
 * inject [ReceiptRepository]/[RecipientRepository] into a view themselves, which would cross the
 * UI → domain-repository layering this codebase's onion architecture forbids (views go through an
 * application service/facade, same as every other read here).
 */
@ApplicationScoped
class AuditJournalQueryService @Inject constructor(
    private val repository: InvoiceAuditJournalRepository,
    private val receiptRepository: ReceiptRepository,
    private val recipientRepository: RecipientRepository,
    private val invoiceLookup: InvoiceLookupRepository,
) : AuditJournalQueryPort {

    /** One invoice's history, one line per business action, paged and ordered by [direction] on
     *  the group's own `MIN(seq)` — [direction] defaults to newest first (PO decision
     *  2026-09-17): the "Zeitpunkt" column's own sort listener passes the user's current toggle
     *  explicitly, this default only applies before the user has touched the column. */
    override fun findActionsByInvoice(
        invoiceId: Long,
        pageIndex: Int,
        pageSize: Int,
        direction: SortDirection,
    ): List<InvoiceAuditActionLine> {
        val groups = repository.findActionGroupsByInvoiceId(invoiceId, pageIndex, pageSize, direction)
        return attachRows(groups, invoiceId)
    }

    /** Action count for [findActionsByInvoice] — the per-invoice tab's pagination control. */
    override fun countActionsByInvoice(invoiceId: Long): Long = repository.countActionGroupsByInvoiceId(invoiceId)

    /** All actions matching [filter], paged and ordered by [direction] on the group's own
     *  `MIN(seq)` — the global view's grid data provider. [direction] defaults to newest first. */
    override fun findGlobalActions(
        filter: JournalGlobalFilter,
        pageIndex: Int,
        pageSize: Int,
        direction: SortDirection,
    ): List<InvoiceAuditActionLine> {
        val groups = repository.findActionGroupsPage(pageIndex, pageSize, repositoryFilter(filter), direction)
        return attachRows(groups, invoiceId = null)
    }

    /** Action count for [filter] — the global view's pagination control. */
    override fun countGlobalActions(filter: JournalGlobalFilter): Long =
        repository.countActionGroupsPage(repositoryFilter(filter))

    /** The UI's [JournalGlobalFilter] as the port's exact-match/`OffsetDateTime` filter: free text
     *  trimmed (blank = no filter), the picked dates widened to [occurredRange]'s bounds. */
    private fun repositoryFilter(filter: JournalGlobalFilter): InvoiceAuditActionFilter {
        val (from, to) = occurredRange(filter)
        return InvoiceAuditActionFilter(
            actions = filter.actions,
            invoiceNumber = filter.invoiceNumber?.trim()?.ifBlank { null },
            actor = filter.actor?.trim()?.ifBlank { null },
            occurredFrom = from,
            occurredTo = to,
        )
    }

    /** The one second query behind every `find*Actions*` method above: every technical
     *  row of [groups] (however many groups a page holds) in a SINGLE call to
     *  [InvoiceAuditJournalRepository.findByBusinessTx], then zipped back onto each group by its
     *  own `business_tx` — never a per-group query. [invoiceId] scopes the rows to one invoice's
     *  own side of a `CANCELLATION` group when called from the per-invoice tab; `null` (the global view)
     *  loads every row of the group, on whichever invoice(s) it touched. */
    private fun attachRows(groups: List<InvoiceAuditActionGroup>, invoiceId: Long?): List<InvoiceAuditActionLine> {
        if (groups.isEmpty()) return emptyList()
        val rowsByTx = repository.findByBusinessTx(groups.map { it.businessTx }, invoiceId).groupBy { it.businessTx }
        return groups.map { group -> InvoiceAuditActionLine.of(group, rowsByTx[group.businessTx].orEmpty()) }
    }

    /** Recipient display names for every `recipient_id` a [lines] page's `HEADER_CHANGED`/
     *  `RECIPIENT_CHANGED` rows changed, in ONE [RecipientRepository.findNamesByIds] call — never
     *  one lookup per line. `recipient_id` only ever appears as a diff on an `INVOICE_UPDATED` row
     *  (see [de.littletrotter.ui.view.journal.composer.HeaderRecipientFamilyComposer], the sole
     *  reader of this column). */
    override fun recipientNames(lines: List<InvoiceAuditActionLine>): Map<Long, String> {
        val ids = lines.asSequence()
            .filter { it.action == InvoiceAuditAction.HEADER_CHANGED || it.action == InvoiceAuditAction.RECIPIENT_CHANGED }
            .flatMap { it.rows }
            .filter { it.event.name.endsWith("_UPDATED") }
            .flatMap { diffValues(it.detail, RECIPIENT_ID_COLUMN) }
            .toSet()
        return recipientRepository.findNamesByIds(ids)
    }

    /** Receipt file names for every `receipt_id` a [lines] page's `RECEIPT_ASSIGNED`/
     *  `RECEIPT_DETACHED` rows touched, in ONE [ReceiptRepository.findFileNames] call. `receipt_id`
     *  always appears as a flat snapshot value on the four link-table events (INSERT for
     *  `*_ASSIGNED`, DELETE for `*_UNASSIGNED` — neither ends in `_UPDATED`, so the trigger always
     *  writes the full-row snapshot shape for them, never a diff). */
    override fun receiptFileNames(lines: List<InvoiceAuditActionLine>): Map<Long, String> {
        val ids = lines.asSequence()
            .filter { it.action == InvoiceAuditAction.RECEIPT_ASSIGNED || it.action == InvoiceAuditAction.RECEIPT_DETACHED }
            .flatMap { it.rows }
            .filter { !it.event.name.endsWith("_UPDATED") }
            .mapNotNull { snapshotValue(it.detail, RECEIPT_ID_COLUMN) }
            .toSet()
        return receiptRepository.findFileNames(ids)
    }

    /** Position numbers for every `invoice_item_id` a [lines] page's item-scope
     *  `RECEIPT_ASSIGNED`/`RECEIPT_DETACHED` rows carry, in ONE [InvoiceLookupRepository.findItemPositions]
     *  call — never one lookup per line. `invoice_item_id` only ever appears as a flat snapshot
     *  value on the two `ITEM_RECEIPT_*` events (see [receiptFileNames]'s own reasoning on shape). */
    override fun itemPositions(lines: List<InvoiceAuditActionLine>): Map<Long, String> {
        val ids = lines.asSequence()
            .filter { it.action == InvoiceAuditAction.RECEIPT_ASSIGNED || it.action == InvoiceAuditAction.RECEIPT_DETACHED }
            .flatMap { it.rows }
            .filter { !it.event.name.endsWith("_UPDATED") }
            .mapNotNull { snapshotValue(it.detail, INVOICE_ITEM_ID_COLUMN) }
            .toSet()
        return invoiceLookup.findItemPositions(ids)
    }

    /** `occurredFrom`/`occurredTo` are day-granularity [java.time.LocalDate]s
     *  (a `DatePicker` has no time component); the repository's `occurred_at` is a `TIMESTAMPTZ`.
     *  `occurredFrom` becomes that day's start (inclusive); `occurredTo` becomes the START OF THE
     *  DAY AFTER (exclusive upper bound), so a picked "to" date of 2026-09-04 still includes every
     *  moment ON 2026-09-04. Resolved against [GermanTime.ZONE] — the same day-boundary zone every
     *  other date-range filter in this app already uses. */
    private fun occurredRange(filter: JournalGlobalFilter): Pair<OffsetDateTime?, OffsetDateTime?> {
        val from = filter.occurredFrom?.atStartOfDay(GermanTime.ZONE)?.toOffsetDateTime()
        val to = filter.occurredTo?.plusDays(1)?.atStartOfDay(GermanTime.ZONE)?.toOffsetDateTime()
        return from to to
    }

    /** [column]'s old/new pair from a `{"column": {"old":..,"new":..}}` diff payload — the shape
     *  [recipientNames] needs `recipient_id` in. Defensive like every other reader of this raw
     *  JSONB text: a malformed payload or an absent column yields no ids, never a crash. */
    private fun diffValues(detailJson: String, column: String): List<Long> {
        val field = runCatching { MAPPER.readTree(detailJson) }.getOrNull()?.get(column) ?: return emptyList()
        return listOfNotNull(field.get("old"), field.get("new")).mapNotNull { it.takeIf { node -> !node.isNull }?.asText()?.toLongOrNull() }
    }

    /** [column]'s flat value from a `{"column": value, ...}` row-snapshot payload — the shape
     *  [receiptFileNames] needs `receipt_id` in. */
    private fun snapshotValue(detailJson: String, column: String): Long? {
        val field = runCatching { MAPPER.readTree(detailJson) }.getOrNull()?.get(column) ?: return null
        return field.takeIf { !it.isNull }?.asText()?.toLongOrNull()
    }
}

private val MAPPER = ObjectMapper()
private const val RECIPIENT_ID_COLUMN = "recipient_id"
private const val RECEIPT_ID_COLUMN = "receipt_id"
private const val INVOICE_ITEM_ID_COLUMN = "invoice_item_id"
