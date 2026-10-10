package de.littletrotter.application.invoice

import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.application.travel.PerDiemCalculator
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoicePositionNotFoundException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.TripTaxContext
import de.littletrotter.domain.invoice.selectTripTaxRate
import de.littletrotter.domain.compliance.reverseChargeEligibility
import de.littletrotter.domain.compliance.reverseChargeIneligibilityReason
import de.littletrotter.domain.invoice.requireExpectedVersion
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.repository.requireReferenceTaxRate
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.stampForPersist
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.math.BigDecimal

/**
 * The lifecycle operations for a whole business trip
 * ([de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP] position): [addTrip]/[updateTrip]
 * are the [TripCommand] counterpart to [InvoiceLifecycleService.addItem]/[InvoiceLifecycleService
 * .updateItem], kept as their own focused collaborator (rk-2-application.md item 2) rather than
 * grown onto the already-large [InvoiceLifecycleService] — [Invoice] stays the aggregate root
 * either way: both methods load the managed invoice themselves, mutate its `items` collection
 * in place and call [Invoice.recalculateTotals] once, in one transaction, exactly like every
 * other item-level write path.
 *
 * [de.littletrotter.domain.invoice.TripExpense] and the trip's
 * [de.littletrotter.domain.reference.TravelExpenseEntry] days are child collections of the
 * [InvoiceItem] they belong to (`cascade = ALL, orphanRemoval = true`) — this class never gives
 * either its own repository (DDD).
 *
 * [SetsAuditGuc] moved from class level onto [addTrip]/[updateTrip] individually, both
 * `TRIP_SAVED` (the same action either way — a trip create/edit is one auditor-facing action).
 */
@ApplicationScoped
class TripService @Inject constructor(
    private val invoiceRepo: InvoiceRepository,
    private val domainEventBus: Event<DomainEvent>,
    private val auditActorProvider: AuditActorProvider,
    private val perDiemCalculator: PerDiemCalculator,
    // SFS — same nullable-without-a-Kotlin-default convention as InvoiceLifecycleService's own
    // field (see that class's KDoc): the test builder hands `null` explicitly, production always
    // resolves the real bean.
    private val featureSettingsGate: FeatureSettingsGate?,
    private val taxRateRepo: TaxRateRepository,
    private val time: TimeSource,
) {

    /** Creates a new TRIP position on [invoiceId] from [command]. */
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.TRIP_SAVED)
    fun addTrip(invoiceId: Long, command: TripCommand): Invoice {
        TripCommandValidator.validateStructure(command)
        TripCommandValidator.validatePerDiemGate(command, perDiemUiEnabled = isPerDiemUiEnabled())
        // A brand-new position carries no receipts yet (they can only be attached to an id that
        // already exists) — any receiptId on a create command is therefore always unattached.
        TripCommandValidator.validateReceipts(command, attachedReceiptIds = emptySet())

        val invoice = invoiceRepo.findByIdWithItems(invoiceId) ?: throw InvoiceNotFoundException(invoiceId)
        if (invoice.isReadOnly()) throw InvoiceImmutableException(invoice.invoiceNumber.value)
        invoice.requireExpectedVersion(command.expectedVersion)

        val item = InvoiceItem().apply { category = InvoiceItemCategory.TRIP }
        applyTripFields(invoice, item, command)
        invoice.addItem(item)
        invoice.recalculateTotals()

        invoice.stampForPersist(auditActorProvider.currentActor())
        val saved = invoiceRepo.save(invoice)
        invoiceRepo.forceVersionIncrement(saved)
        invoice.pullDomainEvents().forEach { domainEventBus.fireAsync(it) }
        return saved
    }

    /** Applies [command] onto the existing TRIP position [positionId] of [invoiceId]. Rejects a
     *  target whose category is not already TRIP — a category switch to/from TRIP is not
     *  supported (delete and re-add, see [TripCommand]'s own KDoc). */
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.TRIP_SAVED)
    fun updateTrip(invoiceId: Long, positionId: String, command: TripCommand): Invoice {
        TripCommandValidator.validateStructure(command)

        val invoice = requireEditableInvoice(invoiceId)
        invoice.requireExpectedVersion(command.expectedVersion)
        val target = requireTripPosition(invoice, positionId)

        TripCommandValidator.validatePerDiemGate(
            command,
            perDiemUiEnabled = isPerDiemUiEnabled(),
            alreadyHasPerDiemDays = target.perDiemEntries.isNotEmpty(),
        )
        TripCommandValidator.validateReceipts(command, attachedReceiptIds = target.receipts.mapNotNull { it.id }.toSet())

        // README border case 2 — with the per-diem UI switched off the dialog cannot resubmit the
        // days it no longer shows; an update that carries none keeps the ones already stored.
        val keepStoredPerDiemDays = !isPerDiemUiEnabled() && command.perDiemDays.isEmpty() && target.perDiemEntries.isNotEmpty()
        applyTripFields(invoice, target, command, keepStoredPerDiemDays)
        invoice.recalculateTotals()

        invoice.stampForPersist(auditActorProvider.currentActor())
        val saved = invoiceRepo.save(invoice)
        invoiceRepo.forceVersionIncrement(saved)
        invoice.pullDomainEvents().forEach { domainEventBus.fireAsync(it) }
        return saved
    }

    // updateTrip's four lookup/guard throws (ThrowsCount) split across two helpers so
    // each stays under ThrowsCount on its own; behaviour is unchanged.
    private fun requireEditableInvoice(invoiceId: Long): Invoice {
        val invoice = invoiceRepo.findByIdWithItems(invoiceId) ?: throw InvoiceNotFoundException(invoiceId)
        if (invoice.isReadOnly()) throw InvoiceImmutableException(invoice.invoiceNumber.value)
        return invoice
    }

    private fun requireTripPosition(invoice: Invoice, positionId: String): InvoiceItem {
        val target = invoice.itemList.find { it.positionId == positionId }
            ?: throw InvoicePositionNotFoundException(positionId)
        if (target.category != InvoiceItemCategory.TRIP) {
            throw InvoiceValidationException(InvoiceValidationError.TRIP_CATEGORY_SWITCH_NOT_SUPPORTED)
        }
        return target
    }

    private fun isPerDiemUiEnabled(): Boolean = featureSettingsGate?.isEnabled(FeatureFlag.PER_DIEM_UI) != false

    /** Applies every [TripCommand] field onto [item] (new or managed-existing), rebuilds its
     *  [InvoiceItem.tripExpenses] and [InvoiceItem.perDiemEntries] wholesale from [command]
     *  (orphan removal handles the difference, same idiom [InvoiceLifecycleService] used for
     *  per-diem entries before the TRIP-category merge), resolves the country-reference or RC tax rate and
     *  re-derives amounts. Does not touch `invoice.items`/`recalculateTotals` — callers do that. */
    private fun applyTripFields(invoice: Invoice, item: InvoiceItem, command: TripCommand, keepStoredPerDiemDays: Boolean = false) {
        item.applyPositionText(command.title, command.purpose, command.deliveryNoteReference)
        item.servicePeriodFrom = command.periodFrom
        item.servicePeriodTo = command.periodTo
        item.costCenter = command.costCenter
        item.tripDetailedOnInvoice = command.detailedOnInvoice
        item.tripFixedNetPrice = command.fixedNetPrice

        item.tripExpenses.clear()
        command.expenses.forEachIndexed { index, expenseCommand ->
            val expense = TripExpense().apply {
                invoiceItem = item
                kind = expenseCommand.kind
                description = if (expenseCommand.kind == de.littletrotter.domain.invoice.TripExpenseKind.TRAVEL_TIME &&
                    !expenseCommand.rateProvided && expenseCommand.description.isBlank()
                ) TripExpense.TRAVEL_TIME_KULANZ_TEXT else expenseCommand.description
                travelDate = expenseCommand.travelDate
                quantity = expenseCommand.quantity
                unit = expenseCommand.unit
                unitPriceNet = expenseCommand.unitPriceNet
                rateProvided = expenseCommand.rateProvided
                receipt = expenseCommand.receiptId?.let { id -> item.receipts.find { it.id == id } }
                sortOrder = if (expenseCommand.sortOrder > 0) expenseCommand.sortOrder else index + 1
            }
            expense.calculateNetAmount()
            expense.validate()
            item.tripExpenses.add(expense)
        }

        if (!keepStoredPerDiemDays) {
            item.perDiemEntries.clear()
            perDiemCalculator.buildEntries(command.perDiemDays).forEach { entry ->
                entry.invoiceItem = item
                item.perDiemEntries.add(entry)
            }
        }

        val reverseChargeRate = if (invoice.reverseCharge) {
            val proposal = invoice.reverseChargeEligibility(time.today())
                ?: throw InvoiceValidationException(
                    InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE,
                    listOf(invoice.reverseChargeIneligibilityReason(time.today())),
                )
            taxRateRepo.findByCode(proposal.ruleCode)
                ?: throw InvalidParameterException("Reverse-Charge-Steuerregel '${proposal.ruleCode}' fehlt in den Stammdaten.")
        } else {
            null
        }
        // INV-F — every trip resolves its own tax rule. Normal trips use the selected seller
        // country's reference; RC trips resolve the RC rule directly and therefore also work on
        // invoices that contain no main-service position.
        val rate = selectTripTaxRate(
            TripTaxContext(
                invoice.reverseCharge,
                invoice.sellerVatRegistrationCountry, invoice.invoiceDate,
                reverseChargeRate,
            ),
            taxRateRepo::requireReferenceTaxRate,
        )
        item.applyTaxRule(rate)

        // Forced anyway by InvoiceItem.calculateAmounts() for TRIP, kept here only so a caller
        // inspecting `item` before calculateAmounts() sees the same forced values.
        item.quantity = BigDecimal.ONE
        item.unit = InvoiceItemUnit.FLAT_RATE
        item.calculateAmounts()
        // Not item.validate() here: positionId is still blank for a brand-new item at this point
        // (Invoice.addItem, called by our caller right after this, is what assigns it) — the same
        // reason InvoiceLifecycleService.addItem never calls it either. Invoice.validate() (run at
        // the usual checkpoints, e.g. before SENT) covers every item including this one.
    }
}
