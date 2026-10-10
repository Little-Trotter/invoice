package de.littletrotter.application.invoice

import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceItemNotFoundException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoicePositionNotFoundException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.requireExpectedVersion
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.requireReferenceTaxRate
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.catalogue.LeistungsverzeichnisRules
import de.littletrotter.domain.shared.stampForPersist
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Extracted from `InvoiceLifecycleService`: position-level mutations (add/update/delete a
 * single [InvoiceItem]) on an already-existing [Invoice], with their tax resolution and
 * Leistungsverzeichnis-rule gating.
 */
@ApplicationScoped
class InvoiceItemMutationService @Inject constructor(
    private val invoiceRepo: InvoiceRepository,
    private val support: InvoiceLifecycleSupport,
    private val auditActorProvider: AuditActorProvider,
    private val domainEventBus: Event<DomainEvent>,
    private val overviewCache: InvoiceCachePort,
    private val transactionSynchronizationRegistry: jakarta.transaction.TransactionSynchronizationRegistry,
) {

    /**
     * W4 — builds a fresh [InvoiceItem] from [command] and attaches it to the managed invoice this
     * method loads itself; the item dialogs (goods/service) build only the command, never the
     * entity (arc-improvements AR1, extended from headers to items). [InvoiceItemCommand
     * .taxRateId]/[InvoiceItemCommand.articleId] resolve against [InvoiceLifecycleSupport
     * .taxRateRepo]/[InvoiceLifecycleSupport.articleRepo] the same way [updateItem] resolves its
     * own reference ids — the command never carries a live reference, only ids.
     *
     * [command].category may only be [InvoiceItemCategory.SERVICE]/[InvoiceItemCategory
     * .PRODUCT] (see [InvoiceItemCommand]'s own KDoc); a business trip goes exclusively through
     * [TripService.addTrip].
     *
     * IMP-5 — positionId/sortOrder are deliberately left unset here: `Invoice.addItem`, called
     * below, is their single source of truth (auto-numbering a blank/`<= 0` value), so this command
     * has no field for either — a caller can no longer request a specific positionId, which no
     * production caller ever legitimately needed (only the UI dialogs call this, and none of them
     * offer that choice).
     */
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.ITEM_ADDED)
    fun addItem(invoiceId: Long, command: InvoiceItemCommand): Invoice {
        if (command.category == InvoiceItemCategory.TRIP) {
            throw InvalidParameterException("A business trip must be created via TripService.addTrip, not addItem.")
        }
        requireAllowedLeistungsverzeichnis(command.category, command.unit)
        val invoice = invoiceRepo.findByIdWithItems(invoiceId)
            ?: throw InvoiceNotFoundException(invoiceId)
        if (invoice.isReadOnly()) throw InvoiceImmutableException(invoice.invoiceNumber.value)
        invoice.requireExpectedVersion(command.expectedVersion)

        val item = InvoiceItem().also { applyCommandFields(it, command) }
        applyItemTaxAndEntries(invoice, item, command)
        item.calculateAmounts()
        invoice.addItem(item)
        invoice.recalculateTotals()

        return persistAndPublish(invoice)
    }

    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.ITEM_DELETED)
    fun deleteItemByPosition(invoiceId: Long, positionId: String, expectedVersion: Long? = null): Invoice {
        val invoice = invoiceRepo.findByIdWithItems(invoiceId)
            ?: throw InvoiceNotFoundException(invoiceId)
        if (invoice.isReadOnly()) throw InvoiceImmutableException(invoice.invoiceNumber.value)
        invoice.requireExpectedVersion(expectedVersion)
        val item = invoice.itemList.find { it.positionId == positionId }
            ?: throw InvoicePositionNotFoundException(positionId)
        invoice.removeItem(item)
        invoice.recalculateTotals()

        return persistAndPublish(invoice)
    }

    /**
     * Applies a goods/service item dialog's edits onto the managed item this method finds within
     * the managed invoice it loads itself — the item-level counterpart to
     * [InvoiceHeaderUpdateService.updateHeader]. W4 — [command] replaces the detached,
     * dialog-populated [InvoiceItem] this method used to take (same field set [addItem] applies at
     * creation); only the fields listed below are copied onto the managed target, so nothing about
     * the invoice header is touched. [InvoiceItemCommand.taxRateId] resolves the same way [addItem]
     * resolves it. Monkey finding (2026-08-30) — this used to only ever move the live [InvoiceItem
     * .taxRateRef] pointer, leaving [InvoiceItem.taxRateSnapshot] (and its R1 siblings) frozen to
     * whatever they were last snapshotted to; since [InvoiceItem.effectiveTaxRate] prefers that
     * frozen snapshot once one exists, passing a genuinely different [InvoiceItemCommand.taxRateId]
     * silently had no effect on the computed VAT at all — the reference changed, the number never
     * did. Fixed via [applyRequestedTaxRateIfChanged]: a resolved rate whose code differs from what
     * is already frozen ([InvoiceItem.taxRuleCode]) is re-applied and re-snapshotted through the
     * same atomic [InvoiceItem.applyTaxRule] [addItem] uses, so amounts recompute against it; the
     * unchanged rate id still only moves the live pointer. [InvoiceItemCommand.taxRateId] `null`
     * means "no change" and leaves [InvoiceItem.taxRateRef] exactly as it already is — see
     * [applyRequestedTaxRateIfChanged]'s own KDoc for why a missing id may no longer clear it.
     * [InvoiceItemCommand.articleId] and the exemption-text overrides remain creation-only (see
     * [InvoiceItemCommand]'s own KDoc).
     *
     * Rejects [command].category == TRIP: a business trip's edits go exclusively
     * through [TripService.updateTrip], including a category switch to/from TRIP (not supported —
     * see [TripCommand]'s own KDoc), and rejecting a TRIP target/command here means a stray
     * `category` write can never accidentally turn a goods/service position into (or a trip out
     * of) a shape this method no longer knows how to handle.
     */
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.ITEM_CHANGED)
    fun updateItem(invoiceId: Long, command: InvoiceItemCommand): Invoice {
        if (command.category == InvoiceItemCategory.TRIP) {
            throw InvalidParameterException("A business trip must be edited via TripService.updateTrip, not updateItem.")
        }
        val invoice = invoiceRepo.findByIdWithItems(invoiceId)
            ?: throw InvoiceNotFoundException(invoiceId)
        if (invoice.isReadOnly()) throw InvoiceImmutableException(invoice.invoiceNumber.value)
        invoice.requireExpectedVersion(command.expectedVersion)
        val itemId = requireNotNull(command.itemId) { "Item id required for update" }
        val target = invoice.itemList.find { it.id == itemId } ?: throw InvoiceItemNotFoundException(itemId)
        if (target.category == InvoiceItemCategory.TRIP) {
            throw InvoiceValidationException(InvoiceValidationError.TRIP_CATEGORY_SWITCH_NOT_SUPPORTED)
        }
        if (target.category != command.category) {
            throw InvalidParameterException("Position category is immutable; use the category-specific editor.")
        }
        requireAllowedLeistungsverzeichnis(command.category, command.unit)

        applyCommandFields(target, command)
        applyRequestedTaxRateIfChanged(target, command.taxRateId)

        target.calculateAmounts()
        invoice.recalculateTotals()
        return persistAndPublish(invoice)
    }

    /** Shared by [addItem]/[deleteItemByPosition]/[updateItem]: commits the mutated [invoice],
     *  forces the optimistic-lock version to move even when JPA dirty checking alone would not
     *  see a change, and publishes the domain events the mutation queued — the one place all
     *  three item mutations hand their result back to their caller. */
    private fun persistAndPublish(invoice: Invoice): Invoice {
        invoice.stampForPersist(auditActorProvider.currentActor())
        val saved = invoiceRepo.save(invoice)
        invoiceRepo.forceVersionIncrement(saved)
        invoice.pullDomainEvents().forEach { domainEventBus.fireAsync(it) }
        evictOverviewAfterCommit()
        return saved
    }

    /** L28 — list-visible totals/positions changed, but the aggregate queues item events only for
     *  numbered invoices and [updateItem] queues none, so the event-driven evictor never fired and
     *  the cached overview stayed stale. Evict explicitly, after commit (an earlier eviction would
     *  let a concurrent list read re-cache the pre-commit row). */
    private fun evictOverviewAfterCommit() {
        val tsr = transactionSynchronizationRegistry
        if (tsr.transactionStatus == jakarta.transaction.Status.STATUS_ACTIVE) {
            tsr.registerInterposedSynchronization(object : jakarta.transaction.Synchronization {
                override fun beforeCompletion() = Unit
                override fun afterCompletion(status: Int) {
                    if (status == jakarta.transaction.Status.STATUS_COMMITTED) overviewCache.evictAllOverview()
                }
            })
        } else {
            overviewCache.evictAllOverview()
        }
    }

    /**
     * W4 — resolves and applies [command]'s tax-relevant fields onto [item], mirroring what the
     * item dialogs used to do directly on the transient entity they built: an article-bound
     * position under a seller VAT registration derives (and is blocked without) its tax from
     * [InvoiceItem.applySellerCountryTaxVariant]; every other position commits [InvoiceItemCommand
     * .taxRateId] via [InvoiceItem.applyTaxRule]. The exemption-text override is applied
     * only here, at creation.
     *
     * The TRIP branch this method used to carry (a straight rename of the deleted
     * TRAVEL_EXPENSE/EXPENSE/PER_DIEM handling) moved to [TripService], the only place a TRIP
     * position's tax/quantity/unit/price are derived now; [command].category is never TRIP here
     * ([addItem] rejects it before this is called).
     */
    private fun applyItemTaxAndEntries(invoice: Invoice, item: InvoiceItem, command: InvoiceItemCommand) {
        item.articleRef = command.articleId?.let { articleId ->
            support.articleRepo.findById(articleId)
                ?: throw InvalidParameterException("Unknown catalogue entry id $articleId.")
        }
        if (item.articleRef != null && item.articleRef!!.category != command.category) {
            throw InvalidParameterException("Catalogue category cannot differ from the position category.")
        }
        val sellerCountry = invoice.sellerVatRegistrationCountry
        if (sellerCountry != null && item.articleRef != null) {
            val fallback = if (item.articleRef?.taxVariantFor(sellerCountry) == null) {
                support.taxRateRepo.requireReferenceTaxRate(sellerCountry, invoice.invoiceDate)
            } else {
                null
            }
            item.applySellerCountryTaxVariant(sellerCountry, fallback)
        } else {
            val rate = command.taxRateId?.let { support.taxRateRepo.findById(it) }
                ?: throw InvalidParameterException("Tax rate is required for this position.")
            item.applyTaxRule(rate)
        }
        command.exemptionReasonTextOverride?.let { item.exemptionReasonText = it }
        command.taxLegalBasisOverride?.let { item.taxLegalBasis = it }
    }

    /** Shared command → item field copy between [addItem] and [updateItem] — a new
     *  [InvoiceItemCommand] field is applied to creation and update in one commit,
     *  never only one of them. */
    private fun applyCommandFields(item: InvoiceItem, command: InvoiceItemCommand) {
        item.category = command.category
        item.applyPositionText(command.description, command.itemDescription, command.deliveryNoteReference)
        item.buyerItemDescription = command.buyerItemDescription
        item.quantity = command.quantity
        item.unit = command.unit
        item.unitPriceNet = command.unitPriceNet
    }

    /**
     * Monkey finding (2026-08-30) — the [updateItem] half of the fix: resolves [taxRateId] the
     * same way [addItem] does and, only when it names a rate genuinely different from what
     * [target] already has frozen ([InvoiceItem.taxRuleCode]), commits it via
     * [InvoiceItem.applyTaxRule] so the R1 snapshot (and therefore [InvoiceItem.effectiveTaxRate])
     * actually moves. `taxRateId == null` means "no change" and leaves [InvoiceItem.taxRateRef]
     * exactly as it already is (including a pre-existing `null`) — the inline grid editor
     * ([de.littletrotter.ui.view.invoice.toUpdateCommand], an extension on
     * [de.littletrotter.application.dto.ItemDto]) round-trips whatever [InvoiceItem.taxRateRef]
     * currently resolves to, so treating `null` as "clear it" would silently drop the live
     * reference on every plain field edit once it happened to read back `null`. An unchanged id
     * still only moves the live pointer, without re-snapshotting.
     * An id that fails to resolve ([de.littletrotter.application.invoice.InvoiceLifecycleSupport
     * .taxRateRepo]'s `findById` returns `null`) throws, mirroring [addItem]'s own PRODUCT/SERVICE
     * branch, which requires the same lookup to succeed.
     */
    private fun applyRequestedTaxRateIfChanged(target: InvoiceItem, taxRateId: Long?) {
        if (taxRateId == null) return
        val requested = support.taxRateRepo.findById(taxRateId)
            ?: throw InvalidParameterException("Unknown tax rate id $taxRateId.")
        if (requested.code != target.taxRuleCode) {
            target.applyTaxRule(requested)
        } else {
            target.taxRateRef = requested
        }
    }

    /** Shared by [addItem]/[updateItem]: translates a [LeistungsverzeichnisRules] rejection into
     *  the application-layer [InvalidParameterException], preserving the original cause. */
    private fun requireAllowedLeistungsverzeichnis(category: InvoiceItemCategory, unit: InvoiceItemUnit) {
        try {
            LeistungsverzeichnisRules.requireAllowed(category, unit)
        } catch (ex: IllegalArgumentException) {
            throw InvalidParameterException(ex.message ?: "Invalid unit for position category.", ex)
        }
    }
}
