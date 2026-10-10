package de.littletrotter.application.invoice

import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Application service for attaching/detaching receipts, both at item level and at invoice
 * (whole-document) level. Kept together deliberately: [de.littletrotter.ui.view.invoice.ReceiptTargetGateway]
 * dispatches polymorphically over both targets and every write path shares the same read-only
 * ([requireWritable]) and document-budget ([ReceiptBudgetService]) guards.
 */
@ApplicationScoped
class ReceiptLinkService @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val invoiceRepository: InvoiceRepository,
    private val receiptPoolService: ReceiptPoolService,
    private val receiptBudgetService: ReceiptBudgetService,
) {
    companion object {
        /** Page size used where the *complete* set is required (the replace-everything write
         *  paths). Named so the intent is not mistaken for an arbitrarily large page. */
        private const val UNBOUNDED_PAGE_SIZE = Int.MAX_VALUE
    }

    // ── Item-level receipts ──────────────────────────────────────────────

    /**
     * belege-improve — one page of receipts that this item does **not** already carry, for the
     * assign picker. Excluding them in the query (not in the UI) keeps paging honest and makes
     * every offered row actionable; previously the picker listed already-attached receipts,
     * let the user select them, and then answered "all selected are already assigned".
     */
    @Transactional
    fun findAssignableForItem(
        invoiceItemId: Long?,
        pageIndex: Int,
        pageSize: Int,
        filterText: String? = null,
        /** See [de.littletrotter.domain.repository.ReceiptRepository.findAssignable]'s
         *  own parameter of the same name: lets the position picker hide receipts already shown
         *  in its separate "this invoice" group. */
        excludeReceiptIds: Set<Long> = emptySet(),
    ): List<Receipt> = receiptRepository.findAssignable(
        pageIndex, pageSize, filterText, invoiceItemId, excludeReceiptIds = excludeReceiptIds,
    )

    /** Row count matching [findAssignableForItem]. */
    @Transactional
    fun countAssignableForItem(
        invoiceItemId: Long?,
        filterText: String? = null,
        excludeReceiptIds: Set<Long> = emptySet(),
    ): Long = receiptRepository.countAssignable(filterText, invoiceItemId, excludeReceiptIds = excludeReceiptIds)

    /** V4 — add more receipts to an item without the caller rebuilding the whole list (n:m,
     *  additive). Deduplicates against what is already attached and enforces the read-only guard.
     *  Refuses (never truncates) when the invoice-wide resulting receipt set would exceed the
     *  budget — see [setReceiptsForItem]. */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.RECEIPT_ASSIGNED)
    fun addReceiptsToItem(invoiceItemId: Long, receiptIds: List<Long>, expectedVersion: Long? = null) {
        val current = findReceiptsForItem(invoiceItemId).mapNotNull { it.id }.toSet()
        setReceiptsForItem(invoiceItemId, (current + receiptIds).toList(), expectedVersion)
    }

    /** Removes specific receipts from an item, keeping the rest; enforces the read-only
     *  guard. Re-expressed through [detachReceipts] so removal has exactly one implementation. */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.RECEIPT_DETACHED)
    fun removeReceiptsFromItem(invoiceItemId: Long, receiptIds: List<Long>, expectedVersion: Long? = null) {
        val invoice = invoiceRepository.findInvoiceByItemId(invoiceItemId)
            ?: throw ApplicationValidationException("Invoice item not found: $invoiceItemId")
        detachReceipts(
            invoice.id!!,
            ReceiptDetachSelection(itemReceiptIds = mapOf(invoiceItemId to receiptIds.toSet())),
            expectedVersion,
        )
    }

    /**
     * Replaces the full list of receipts attached to a specific [InvoiceItem].
     *
     * Rejected when the owning invoice is finalized or cancelled — attaching or removing
     * receipts changes a locked invoice, so it is only allowed while the invoice is open.
     *
     * The guard budgets the **invoice-wide** resulting receipt set, not just this write's
     * own [receiptIds]: the invoice's own invoice-level receipts, plus every *other* item's own
     * receipts, plus these new ids, deduplicated by object key. The read-only check runs first,
     * before [receiptIds] is even resolved. (The assignment-time completeness guard is now
     * redundant here — receipt-mandatory-fields guarantees every persisted [Receipt] already
     * carries both fields, so nothing reaching this point can ever be incomplete.)
     */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.RECEIPT_ASSIGNED)
    fun setReceiptsForItem(invoiceItemId: Long, receiptIds: List<Long>, expectedVersion: Long? = null) {
        val (item, invoice) = findItemWithInvoice(invoiceItemId)
        requireWritable(invoice, expectedVersion)
        val newReceipts = receiptIds.map { receiptPoolService.findById(it) }
        receiptBudgetService.requireWithinBudget(
            receiptBudgetService.resultingReceiptsForItem(invoice.id!!, invoiceItemId, newReceipts),
        )
        item.receipts.clear()
        item.receipts.addAll(newReceipts)
        // Persist through the aggregate boundary so the join-table mutation and its version are
        // committed before the caller refreshes the read model. Dirty checking alone is not a
        // sufficient acknowledgement for a command endpoint.
        invoiceRepository.save(invoice)
    }

    /**
     * Returns the count of receipts attached to an invoice item.
     */
    @Transactional
    fun countReceiptsForItem(invoiceItemId: Long): Long {
        findItemWithInvoice(invoiceItemId)
        return receiptRepository.countForItem(invoiceItemId)
    }

    /**
     * Returns the receipts currently attached to an invoice item.
     *
     * Reads the **complete** set, not a page: the write paths above derive the new set from this
     * result and replace everything, so a paged read would silently drop links beyond the page.
     */
    @Transactional
    fun findReceiptsForItem(invoiceItemId: Long): List<Receipt> {
        findItemWithInvoice(invoiceItemId)
        return receiptRepository.findForItem(invoiceItemId, 0, UNBOUNDED_PAGE_SIZE)
    }

    // ── belege-improve A4 — Receipts at invoice level (not per position) ─────────

    /**
     * belege-improve A4 — one page of receipts that this invoice does **not** already carry (as an
     * invoice-level link), for the assign picker. Excludes what the **invoice** already carries,
     * NOT what a position carries — a receipt may be both (A4.3). Shared WHERE-building with the
     * count keeps page and count truthful.
     */
    @Transactional
    fun findAssignableForInvoice(
        invoiceId: Long?,
        pageIndex: Int,
        pageSize: Int,
        filterText: String? = null,
    ): List<Receipt> = receiptRepository.findAssignable(pageIndex, pageSize, filterText, excludeInvoiceId = invoiceId)

    /** belege-improve A4 — row count matching [findAssignableForInvoice]. */
    @Transactional
    fun countAssignableForInvoice(invoiceId: Long?, filterText: String? = null): Long =
        receiptRepository.countAssignable(filterText, excludeInvoiceId = invoiceId)

    /** belege-improve A4 — receipts attached to the invoice as a whole (invoice_receipt, n:m).
     *  Reads the **complete** set for the same reason as [findReceiptsForItem]. */
    @Transactional
    fun findReceiptsForInvoice(invoiceId: Long): List<Receipt> =
        receiptRepository.findForInvoice(invoiceId, 0, UNBOUNDED_PAGE_SIZE)

    /** belege-improve A4 — row count matching [findReceiptsForInvoice]. */
    @Transactional
    fun countReceiptsForInvoice(invoiceId: Long): Long = receiptRepository.countForInvoice(invoiceId)

    /**
     * belege-improve UX-fix — all item-level receipts of one invoice in ONE query, keyed by item
     * id. Used by the Belege tab's "Belege an Positionen" card, which would otherwise call
     * [findReceiptsForItem] once per position (N+1). The underlying repository query already
     * existed for the export path ([de.littletrotter.application.invoice.InvoiceDtoMapper]) —
     * this just exposes it to the UI layer instead of adding a second implementation.
     */
    @Transactional
    fun findItemReceiptsForInvoice(invoiceId: Long): Map<Long, List<Receipt>> =
        receiptRepository.findItemReceiptsForInvoice(invoiceId)

    /** belege-improve A4 — add more receipts to the invoice as a whole (n:m, additive). Deduplicates
     *  against what is already attached and enforces the read-only guard. Refuses (never truncates)
     *  when the invoice-wide resulting receipt set would exceed the budget — see
     *  [setReceiptsForInvoice]. */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.RECEIPT_ASSIGNED)
    fun addReceiptsToInvoice(invoiceId: Long, receiptIds: List<Long>, expectedVersion: Long? = null) {
        val current = findReceiptsForInvoice(invoiceId).mapNotNull { it.id }.toSet()
        setReceiptsForInvoice(invoiceId, (current + receiptIds).toList(), expectedVersion)
    }

    /** Removes specific receipts from the invoice, keeping the rest;
     *  enforces the read-only guard. Re-expressed through [detachReceipts] so removal has exactly
     *  one implementation. */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.RECEIPT_DETACHED)
    fun removeReceiptsFromInvoice(invoiceId: Long, receiptIds: List<Long>, expectedVersion: Long? = null) {
        detachReceipts(invoiceId, ReceiptDetachSelection(invoiceLevelReceiptIds = receiptIds.toSet()), expectedVersion)
    }

    /**
     * One atomic bulk detach spanning invoice-level receipts and/or several positions'
     * receipts in a single command. A bulk selection from the receipts tab can cover multiple
     * positions; calling [removeReceiptsFromItem] once per position would bump the invoice
     * version between calls (each call itself does one invoiceRepository.save), so a second
     * call would either fail its own `expectedVersion` check or, without one, lose the
     * concurrency guard entirely. This command loads the invoice once, validates the whole
     * selection, mutates every targeted collection, and saves once — all-or-nothing.
     *
     * Every item id in [ReceiptDetachSelection.itemReceiptIds] must belong to [invoiceId], checked
     * before any collection is mutated, so a foreign item id refuses the whole call and nothing
     * partial is ever saved ([ApplicationValidationException]).
     *
     * Deliberately skips the budget guard: a subset of the current receipt set can
     * only shrink the invoice-wide document, so the budget guard (meant for add/replace) can never
     * be violated by a pure removal; running it here would trap an already over-budget invoice
     * (legacy data, a lowered limit) into being unable to reduce its own receipt set.
     *
     * Idempotent: an id that is no longer attached is a silent no-op for that id; if
     * nothing in the whole selection was attached, no save and no audit-journal noise.
     */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.RECEIPT_DETACHED)
    fun detachReceipts(invoiceId: Long, selection: ReceiptDetachSelection, expectedVersion: Long? = null) {
        val invoice = invoiceRepository.findById(invoiceId)
            ?: throw ApplicationValidationException("Invoice not found: $invoiceId")
        requireWritable(invoice, expectedVersion)

        val itemsById = invoice.itemList.associateBy { it.id }
        val unknownItemIds = selection.itemReceiptIds.keys.filterNot { it in itemsById.keys }
        if (unknownItemIds.isNotEmpty()) {
            throw ApplicationValidationException(
                "Invoice item(s) $unknownItemIds do not belong to invoice $invoiceId",
            )
        }

        var changed = false
        if (selection.invoiceLevelReceiptIds.isNotEmpty()) {
            changed = invoice.receipts.removeIf { it.id in selection.invoiceLevelReceiptIds } || changed
        }
        selection.itemReceiptIds.forEach { (itemId, receiptIds) ->
            if (receiptIds.isEmpty()) return@forEach
            val item = itemsById.getValue(itemId)
            changed = item.receipts.removeIf { it.id in receiptIds } || changed
        }

        if (changed) {
            invoiceRepository.save(invoice)
        }
    }

    /**
     * belege-improve A4 — replaces the full set of receipts attached to the invoice as a whole.
     *
     * Rejected when the invoice is finalized or cancelled — attaching or removing receipts changes
     * a locked invoice, so it is only allowed while the invoice is open. The read-only guard lives
     * here (service layer), not in the UI.
     *
     * The guard budgets the **invoice-wide** resulting receipt set: these new [receiptIds]
     * plus every item's own receipts (untouched by this write), deduplicated by object key. The
     * invoice's *current* invoice-level receipts are irrelevant here — this write replaces them
     * outright. The read-only check runs first, before [receiptIds] is even resolved. (The
     * assignment-time completeness guard is now redundant here, same reasoning as
     * [setReceiptsForItem].)
     */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.RECEIPT_ASSIGNED)
    fun setReceiptsForInvoice(invoiceId: Long, receiptIds: List<Long>, expectedVersion: Long? = null) {
        val invoice = invoiceRepository.findById(invoiceId)
            ?: throw ApplicationValidationException("Invoice not found: $invoiceId")
        requireWritable(invoice, expectedVersion)
        val newReceipts = receiptIds.map { receiptPoolService.findById(it) }
        receiptBudgetService.requireWithinBudget(
            receiptBudgetService.resultingReceiptsForInvoice(invoiceId, newReceipts),
        )
        invoice.receipts.clear()
        invoice.receipts.addAll(newReceipts)
        invoiceRepository.save(invoice)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Read-only guard shared by [setReceiptsForItem] and [setReceiptsForInvoice]: an invoice
     *  locked by [Invoice.isReadOnly] must reject any receipt-set replacement, checked before any
     *  budget arithmetic — or even resolving the requested receipt ids — runs. */
    // Every internal call site forwards its own `expectedVersion` parameter explicitly — the
    // default below was dead code (never omitted) since this helper is private.
    private fun requireWritable(invoice: Invoice, expectedVersion: Long?) {
        if (expectedVersion != null && invoice.version != expectedVersion) {
            throw jakarta.persistence.OptimisticLockException(
                "Invoice ${invoice.id} was modified concurrently (expected version $expectedVersion, found ${invoice.version})",
            )
        }
        if (invoice.isReadOnly()) {
            throw InvoiceImmutableException(invoice.invoiceNumber.value)
        }
    }

    /** A3 — a position is reached only through its owning [Invoice] aggregate, never fetched by
     *  item id alone. */
    private fun findItemWithInvoice(itemId: Long): Pair<InvoiceItem, Invoice> {
        val invoice = invoiceRepository.findInvoiceByItemId(itemId)
            ?: throw ApplicationValidationException("Invoice item not found: $itemId")
        val item = invoice.itemList.find { it.id == itemId }
            ?: throw ApplicationValidationException("Invoice item not found: $itemId")
        return item to invoice
    }
}
