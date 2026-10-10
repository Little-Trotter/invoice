package de.littletrotter.application.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import java.math.BigDecimal

/**
 * Pure, DB-free validation for a [TripCommand], shared by [TripService] (the actual write
 * path) and its own unit tests: nothing here loads an [de.littletrotter.domain.invoice.Invoice] or
 * an [de.littletrotter.domain.invoice.InvoiceItem], so a bad command is rejected before any
 * repository call.
 */
object TripCommandValidator {

    /** Structural checks that need only [command] itself: a blank title, a negative fixed price,
     *  a blank/negative expense line. A `detailedOnInvoice` trip with no expenses at all is
     *  explicitly accepted (README border case 1/2 — a fixed-price-only or per-diem-only trip is
     *  valid). */
    fun validateStructure(command: TripCommand) {
        if (command.title.isBlank()) {
            throw InvoiceValidationException(InvoiceValidationError.ITEM_DESCRIPTION_BLANK, message = "Trip title must not be blank.")
        }
        command.fixedNetPrice?.let {
            if (it.isNegative()) {
                throw InvoiceValidationException(InvoiceValidationError.ITEM_TRIP_FIXED_PRICE_NEGATIVE, message = "Trip fixed price must not be negative.")
            }
        }
        command.expenses.forEach { expense ->
            if (expense.description.isBlank() && expense.kind != de.littletrotter.domain.invoice.TripExpenseKind.TRAVEL_TIME) {
                throw InvoiceValidationException(InvoiceValidationError.TRIP_EXPENSE_DESCRIPTION_BLANK, message = "Trip expense description must not be blank.")
            }
            if (expense.quantity < BigDecimal.ZERO) {
                throw InvoiceValidationException(InvoiceValidationError.TRIP_EXPENSE_QUANTITY_NEGATIVE, message = "Trip expense quantity must not be negative.")
            }
        }
    }

    /** Every [de.littletrotter.application.invoice.TripExpenseCommand.receiptId] must
     *  already be one of [attachedReceiptIds] (the trip item's own currently-attached receipts) —
     *  a receipt not yet attached to the trip cannot be picked for one of its expense lines. */
    fun validateReceipts(command: TripCommand, attachedReceiptIds: Set<Long>) {
        command.expenses.forEach { expense ->
            val receiptId = expense.receiptId ?: return@forEach
            if (receiptId !in attachedReceiptIds) {
                throw InvoiceValidationException(
                    InvoiceValidationError.TRIP_EXPENSE_RECEIPT_NOT_ATTACHED,
                    message = "Receipt $receiptId is not attached to this trip item.",
                )
            }
        }
    }

    /** The same "creating per-diem days while the feature switch is off is
     *  rejected" gate [de.littletrotter.application.invoice.InvoiceLifecycleService] used to run
     *  for a PER_DIEM item, now keyed off "this command carries per-diem days" instead of a
     *  category. [alreadyHasPerDiemDays] narrows an update the same way the old `updateItem` gate
     *  did: editing a trip that already has per-diem days stays allowed even while the switch is
     *  off (existing data is never locked away by disabling a feature). */
    fun validatePerDiemGate(command: TripCommand, perDiemUiEnabled: Boolean, alreadyHasPerDiemDays: Boolean = false) {
        if (command.perDiemDays.isNotEmpty() && !alreadyHasPerDiemDays && !perDiemUiEnabled) {
            throw InvoiceValidationException(
                InvoiceValidationError.PER_DIEM_FEATURE_DISABLED,
                message = "per-diem creation is disabled via feature_setting",
            )
        }
    }
}
