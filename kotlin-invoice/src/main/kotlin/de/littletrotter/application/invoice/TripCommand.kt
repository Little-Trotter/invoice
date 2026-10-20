package de.littletrotter.application.invoice

import de.littletrotter.application.command.Command
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The write payload for a whole business trip
 * ([de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP] position), the trip-only counterpart
 * to [InvoiceItemCommand]: [TripService.addTrip]/[TripService.updateTrip] build/apply a
 * [de.littletrotter.domain.invoice.InvoiceItem] plus its [de.littletrotter.domain.invoice
 * .TripExpense] children and [de.littletrotter.domain.reference.TravelExpenseEntry] per-diem days
 * from this, never a JPA entity.
 *
 * [title] is BT-153 ([de.littletrotter.domain.invoice.InvoiceItem.description]); [purpose] is
 * BT-154 ([de.littletrotter.domain.invoice.InvoiceItem.itemDescription]); [periodFrom]/[periodTo]
 * are BG-26 ([de.littletrotter.domain.invoice.InvoiceItem.servicePeriodFrom]/`servicePeriodTo`).
 * Quantity/unit are never part of this command — a TRIP position's quantity/unit are always
 * forced to `1`/[InvoiceItemUnit.FLAT_RATE] by [de.littletrotter.domain.invoice.InvoiceItem
 * .calculateAmounts]. Tax is never part of this command either (unlike [InvoiceItemCommand
 * .taxRateId]) — tax is resolved server-side by
 * [de.littletrotter.domain.invoice.selectTripTaxRate]: every normal trip uses the selected
 * seller-country reference, while an explicit Reverse-Charge decision uses the RC rule.
 *
 * A category switch of an existing position to/from TRIP is not supported: delete and re-add
 * (README border case) — [TripService.updateTrip] rejects a target whose
 * [de.littletrotter.domain.invoice.InvoiceItem.category] is not already TRIP.
 */
data class TripCommand(
    val title: String,
    val purpose: String? = null,
    val periodFrom: LocalDate? = null,
    val periodTo: LocalDate? = null,
    val detailedOnInvoice: Boolean = false,
    val fixedNetPrice: Money? = null,
    val expenses: List<TripExpenseCommand> = emptyList(),
    val perDiemDays: List<PerDiemEntryCommand> = emptyList(),
    val costCenter: String? = null,
    val deliveryNoteReference: String? = null,
    /** WP1 (0.9.10) — see [InvoiceItemCommand.expectedVersion]'s own KDoc; the same
     *  owning-invoice-version check applies here via [TripService.addTrip]/[TripService
     *  .updateTrip]. */
    val expectedVersion: Long? = null,
) : Command

/**
 * One [de.littletrotter.domain.invoice.TripExpense] line to be built/applied by [TripService].
 * [receiptId], if set, must already be attached to the trip item — [TripService]
 * validates this against the item's own [de.littletrotter.domain.invoice.InvoiceItem.receipts],
 * never against the whole receipt pool.
 */
data class TripExpenseCommand(
    val kind: TripExpenseKind,
    val description: String,
    val travelDate: LocalDate? = null,
    val quantity: BigDecimal,
    val unit: InvoiceItemUnit,
    val unitPriceNet: Money,
    val receiptId: Long? = null,
    val sortOrder: Int = 0,
    /** Travel-time only: distinguishes a missing rate (null in the UI) from an explicit zero. */
    val rateProvided: Boolean = true,
) : Command

/**
 * One [de.littletrotter.domain.reference.TravelExpenseEntry] day to be built server-side via
 * [de.littletrotter.application.travel.PerDiemCalculator] (which itself wraps
 * [de.littletrotter.application.reference.PerDiemService.createEntry]) against the resolved
 * [perDiemRateId] — the entity itself is never constructed by a caller.
 *
 * Moved out of [InvoiceItemCommand]: per-diem days only ever occur inside a
 * [TripCommand] now, never as a standalone category.
 */
data class PerDiemEntryCommand(
    val date: LocalDate,
    val isArrivalDeparture: Boolean,
    val breakfast: Boolean,
    val lunch: Boolean,
    val dinner: Boolean,
    /** Optional description shared by the per-diem block. */
    val description: String,
    /** Optional traveler name; unnamed entries remain independent, including on the same date. */
    val participant: String,
    val employerContribution: Money,
    val perDiemRateId: Long,
) : Command
