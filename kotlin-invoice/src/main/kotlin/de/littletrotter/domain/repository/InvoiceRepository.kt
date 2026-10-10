package de.littletrotter.domain.repository

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.ValidationStatus

/**
 * Domain repository port for the invoice aggregate. Deliberately has no default method bodies
 * (arc-improvements AR7): a port describes *what* is fetched, not *how* it is searched. An
 * in-memory free-text scan used to live here as a "fallback" that `PanacheInvoiceRepository`
 * overrode with real JPQL and every test double silently inherited unnoticed — a Full-table
 * scan the moment a test forgot to override it. Search semantics now live once, in
 * `PanacheInvoiceRepository`; the equivalent scan for test doubles lives in
 * `InMemoryOverviewScan` under `src/test`, not here.
 */
interface InvoiceRepository {
    fun findById(id: Long): Invoice?
    fun findByIdWithItems(id: Long): Invoice?

    /**
     * Like [findByIdWithItems], but first takes a row write lock, so two concurrent status
     * transitions of the same invoice run one after the other and the second one sees the
     * first one's committed status instead of both reading the same stale state. In-memory test
     * doubles have no concurrent sessions; the plain read is equivalent there.
     */
    fun findByIdWithItemsForStatusChange(id: Long): Invoice? = findByIdWithItems(id)
    fun findByInvoiceNumber(number: String): Invoice?

    /** The invoice drafted from the accepted offer [quoteId], if one exists; at most one can. */
    fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice?
    fun findAll(pageIndex: Int = 0, pageSize: Int = 60): List<Invoice>
    fun findOverviewPage(pageIndex: Int = 0, pageSize: Int = 60, filterText: String? = null, status: InvoiceStatus? = null): List<Invoice>
    fun countForOverview(filterText: String? = null, status: InvoiceStatus? = null): Long
    fun count(): Long
    fun countByStatus(status: InvoiceStatus): Long
    fun findByRecipientId(recipientId: Long, pageIndex: Int = 0, pageSize: Int = 60): List<Invoice>
    fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int = 0, pageSize: Int = 60): List<Invoice>

    /**
     * Count of NON-finalized (`DRAFT`/`IN_REVIEW`) invoices of
     * [senderProfileId] whose [Invoice.sellerVatRegistrationCountry] is [country].
     * [de.littletrotter.application.masterdata.SellerProfileService.save]'s removal guard uses this
     * to block removing an `additionalVatRegistration` whose country an open invoice still
     * references; a `SENT`/`CANCELLED` invoice carries its own frozen snapshot and is
     * unaffected by the removal, so those never count here.
     *
     * Default implementation (full scan) for test doubles, same pattern as
     * [findByOriginalInvoiceNumber]; [de.littletrotter.infrastructure.persistence
     * .PanacheInvoiceRepository] overrides it with a real `COUNT` query.
     */
    fun countOpenInvoicesForSellerVatCountry(senderProfileId: Long, country: Country): Long =
        findAll(0, Int.MAX_VALUE).count {
            it.senderProfile?.id == senderProfileId &&
                it.sellerVatRegistrationCountry == country &&
                it.status != InvoiceStatus.SENT &&
                it.status != InvoiceStatus.CANCELLED &&
                it.status != InvoiceStatus.VOIDED
        }.toLong()
    fun existsByBillingAddressId(addressId: Long): Boolean
    fun existsByDeliveryAddressId(addressId: Long): Boolean

    /**
     * Whether any invoice references [b2bProfileId] through its own manual override
     * ([de.littletrotter.domain.invoice.Invoice.b2bProfile], per-invoice pin), independent
     * of any recipient's standing default ([de.littletrotter.domain.repository.RecipientRepository
     * .findNamesByB2bProfileId] only ever covers that latter case). Used by
     * [de.littletrotter.application.reference.B2bProfileLifecycleService.deleteProfile]'s guard so a
     * still-referenced profile is rejected with a readable German error instead of the raw FK
     * violation the `b2b_profile_id` column's `NO ACTION` constraint would otherwise surface at
     * flush time.
     *
     * Default implementation is a full scan, same reasoning as [findByOriginalInvoiceNumber]: every
     * existing test double keeps compiling unchanged; [de.littletrotter.infrastructure.persistence
     * .PanacheInvoiceRepository] overrides it with a real `COUNT` query.
     */
    fun existsByB2bProfileId(b2bProfileId: Long): Boolean =
        findAll(0, Int.MAX_VALUE).any { it.b2bProfile?.id == b2bProfileId }
    /** Loads the [Invoice] aggregate that owns the item with [itemId], with [Invoice.items]
     *  populated. A position is reached only through its aggregate, never fetched standalone. */
    fun findInvoiceByItemId(itemId: Long): Invoice?

    /**
     * Position numbers ("Pos. 3") for many invoice items in ONE query, keyed by item id.
     * [de.littletrotter.ui.view.journal.composer.ReceiptFamilyComposer]'s item-scope sentence needs
     * this: `invoice_item_receipt`/`trip_expense` carry only `invoice_item_id` (a database primary
     * key), never the auditor-facing position number. An item id with no matching invoice (a
     * hard-deleted DRAFT position) is simply missing from the result, never an exception.
     *
     * Default implementation folds over [findInvoiceByItemId] per id (same "test doubles keep
     * compiling" reasoning as [countOpenInvoicesForSellerVatCountry]); [de.littletrotter.infrastructure.persistence
     * .PanacheInvoiceRepository] overrides it with a real single query, same idiom as
     * [de.littletrotter.domain.repository.ReceiptRepository.findFileNames].
     */
    fun findItemPositions(itemIds: Collection<Long>): Map<Long, String> =
        itemIds.mapNotNull { id -> findInvoiceByItemId(id)?.itemList?.firstOrNull { it.id == id }?.positionId?.let { id to it } }.toMap()

    fun save(invoice: Invoice): Invoice

    /**
     * Acquires a database-side optimistic force-increment lock for an existing invoice, so a
     * save that only touches a child row ([de.littletrotter.domain.invoice.InvoiceItem]/
     * [de.littletrotter.domain.invoice.TripExpense], each its own `@Version`-ed entity, not a
     * column of `invoice`) still bumps this aggregate's own [Invoice.version] and makes a
     * concurrent second item/trip writer carrying the same `expectedVersion` fail with
     * [jakarta.persistence.OptimisticLockException] via [de.littletrotter.domain.invoice
     * .requireExpectedVersion] instead of silently losing one of the two writes (ST-1 invariant
     * 6). Without this, an item edit that leaves [Invoice.totals]/`updatedBy` unchanged (e.g. a
     * description-only edit) dirties no column Hibernate tracks on `invoice` itself, so no
     * `UPDATE ... SET version = version + 1` is ever issued for it. Mirrors
     * [RecipientRepository.forceVersionIncrement]/[SenderProfileRepository
     * .forceVersionIncrement]/[ArticleRepository.forceVersionIncrement]; callers
     * ([de.littletrotter.application.invoice.InvoiceItemMutationService]/
     * [de.littletrotter.application.invoice.TripService]) call it after every [save] of an
     * item/trip mutation, unconditionally — not only when totals actually changed.
     */
    fun forceVersionIncrement(invoice: Invoice)

    /** Like [save] but immediate flush so unique-constraint violation surfaces to caller for rejection. */
    fun saveAndFlush(invoice: Invoice): Invoice = save(invoice)

    /** Invoices with [Invoice.needsRecalculation] set — see its KDoc for why the flag exists. */
    fun findAllForRecalculation(): List<Invoice>

    /**
     * The **ids** of the invoices [findAllForRecalculation] would return, for callers that sweep
     * the whole set and can tolerate individual rows being unreadable.
     *
     * Materialising an [Invoice] runs every `AttributeConverter` on the row, and
     * [de.littletrotter.domain.invoice.InvoiceNumberConverter] rejects a value that does not match
     * one of the three accepted formats. A single malformed `invoice_number` anywhere in the table
     * therefore makes [findAllForRecalculation] throw during its own `SELECT` — before the caller's
     * per-invoice error handling can see it — and takes down a sweep that had no business touching
     * that row. Selecting ids is a scalar projection: no entity is built, so no converter runs, and
     * the caller can load each invoice inside its own guard.
     *
     * The default delegates to [findAllForRecalculation] so in-memory fakes need no second
     * implementation; only the Panache repository issues the projection query.
     */
    fun findAllIdsForRecalculation(): List<Long> = findAllForRecalculation().mapNotNull { it.id }

    /**
     * All invoices whose [Invoice.originalInvoiceNumber] points to [number]
     * (existing cancellations for this invoice). Default implementation for test doubles.
     */
    fun findByOriginalInvoiceNumber(number: String): List<Invoice> =
        findAll(0, Int.MAX_VALUE).filter { it.originalInvoiceNumber == number }

    /**
     * `(invoiceId, invoiceNumberSequence)` for each finalized invoice with number.
     * Basis for reconciliation check: orphaned journal entries or invoice numbers without journal.
     * Default implementation (see [findByOriginalInvoiceNumber] for pattern rationale).
     */
    fun findFinalizedInvoiceNumberAssignments(): List<InvoiceNumberAssignment> =
        findAll(0, Int.MAX_VALUE)
            .filter { it.status == InvoiceStatus.SENT }
            .mapNotNull { invoice -> invoice.invoiceNumberSequence?.let { InvoiceNumberAssignment(invoice.id!!, it) } }

    /**
     * DocumentJobPublisher-concurrency-fix — flips [Invoice.validationStatus] for [id] to
     * [status], deliberately **bypassing** `@Version` optimistic locking (see
     * [PanacheInvoiceRepository][de.littletrotter.infrastructure.persistence
     * .PanacheInvoiceRepository]'s override for the actual bulk `UPDATE ... WHERE id = :id`, with
     * no `AND version = :version` predicate).
     *
     * Why bypassing optimistic locking is correct *here*, unlike everywhere else in this
     * codebase: [status] is a monotonic job-status flag — "an e-invoice generation job for this
     * invoice is genuinely in flight" ([de.littletrotter.application.invoice.DocumentJobPublisher
     * .publish]'s own KDoc) — not user-edited business data a concurrent UI save could
     * legitimately conflict over. Losing this one-column write to a lost-update race is a *far*
     * cheaper failure than the write throwing `StaleObjectStateException`/`OptimisticLockException`
     * and aborting the whole publish transaction (the production bug this exists to fix): the
     * flag is always reconciled again by the job's own eventual outcome
     * ([de.littletrotter.application.invoice.GenerationJobService.markCompleted]/
     * [de.littletrotter.application.invoice.GenerationJobService.markFailed]), and a concurrent
     * writer that *did* legitimately bump the invoice's version (a UI save, another
     * `GenerationJob` completing) never touches `validationStatus` itself, so there is no real
     * lost-update to worry about in practice — only a spurious, unnecessary version conflict.
     *
     * Returns whether a row was actually updated (`false` when [id] no longer exists) so a caller
     * can decide whether the in-memory mutation it also performs was meaningful.
     *
     * Default implementation (`find` + mutate + [save]) is what every in-memory test double
     * already gets for free — none of them have a real persistence context / dirty-checking
     * session to race against, so the plain, versioned write those tests already exercise via
     * [save] is equivalent in practice. See [findByOriginalInvoiceNumber] for the same
     * default-implementation-for-test-doubles pattern.
     */
    fun updateValidationStatus(id: Long, status: ValidationStatus): Boolean {
        val invoice = findById(id) ?: return false
        // Routes through the same transition-guard shape even in this default (test-double)
        // implementation; see Invoice.transitionValidationStatusTo's own KDoc for why every
        // status legally transitions to PENDING (the only value ever passed here in production),
        // including PENDING itself.
        invoice.transitionValidationStatusTo(status)
        save(invoice)
        return true
    }

    /**
     * DocumentJobPublisher-concurrency-fix — detaches [invoice] from the current persistence
     * context if (and only if) it is actually managed there. Necessary counterpart to
     * [updateValidationStatus]: that method writes the new status straight to the database via a
     * bulk statement, but [de.littletrotter.application.invoice.DocumentJobPublisher.publish]
     * *also* sets the field directly on the passed-in [Invoice] instance (for the plain-unit-test
     * / no-repository-wired case, and so callers see the updated value immediately). If [invoice]
     * happens to be a managed entity in the caller's own open transaction — true for both
     * [de.littletrotter.application.invoice.GenerationJobFastPublishTransaction.publishOne] and
     * [de.littletrotter.application.invoice.GenerationJobProcessor.pollPendingJobs], which each
     * load it inside their own `@Transactional` scope — that plain field assignment would mark
     * the entity dirty, and Hibernate's *automatic* dirty-checking flush at transaction commit
     * would reissue the very version-checked `UPDATE` [updateValidationStatus] was written to
     * avoid, independent of not calling [save] explicitly. Detaching removes the entity from that
     * dirty-checking altogether once every read this transaction still needs from it
     * ([de.littletrotter.application.invoice.InvoiceDtoMapper.mapToDto]) has already happened.
     *
     * A no-op default for every in-memory test double: none of them have a persistence context to
     * detach from, so there is nothing this method needs to do there.
     */
    fun detachIfManaged(invoice: Invoice) {
        // Intentional no-op default (S1186), see this method's own KDoc; PanacheInvoiceRepository
        // overrides it for the real persistence context.
    }
}

/** Siehe [InvoiceRepository.findFinalizedInvoiceNumberAssignments]. */
data class InvoiceNumberAssignment(val invoiceId: Long, val sequence: Long)
