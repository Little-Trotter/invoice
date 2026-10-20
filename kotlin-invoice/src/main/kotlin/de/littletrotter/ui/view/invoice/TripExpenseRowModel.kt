package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.invoice.TripExpenseCommand
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.travel.port.inbound.HotelRatePrefillQueryPort
import de.littletrotter.application.travel.port.inbound.MileageRatePrefillQueryPort
import de.littletrotter.domain.invoice.ItemAmounts
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.LocalDate

/** One editable row of the "Posten" section — the UI-only counterpart of [TripExpenseCommand],
 *  kept as a mutable holder so field bindings can write straight onto it (same idiom as
 *  [ItemDto]'s own mutable columns, see that class's KDoc). [vehicleType] is never persisted (no
 *  [TripExpenseCommand] field for it) — it only steers [MileageRatePrefillQueryPort] prefill while the row
 *  is being edited, exactly like it did in the deleted `TravelItemDialog`. */
internal class TripExpenseRow(
    var kind: TripExpenseKind,
    var description: String,
    var travelDate: LocalDate?,
    var quantity: BigDecimal,
    var unit: InvoiceItemUnit,
    var unitPriceNet: Money,
    var receiptId: Long?,
    var rateProvided: Boolean = true,
    var vehicleType: VehicleType = VehicleType.CAR,
) {
    fun netAmount(): Money = ItemAmounts.calculate(quantity, unitPriceNet, BigDecimal.ZERO).first

    fun toCommand(sortOrder: Int): TripExpenseCommand = TripExpenseCommand(
        kind = kind,
        description = description,
        travelDate = travelDate,
        quantity = quantity,
        unit = unit,
        unitPriceNet = unitPriceNet,
        receiptId = receiptId,
        sortOrder = sortOrder,
        rateProvided = rateProvided,
    )
}

/** The row-editable units a trip expense line reasonably takes: a hotel per night, hours for travel time,
 *  a mileage claim, or a lump sum/piece — the full [InvoiceItemUnit] catalogue is products/services scope,
 *  not a trip's. */
internal val TRIP_EXPENSE_UNITS = listOf(InvoiceItemUnit.DAY, InvoiceItemUnit.HOUR, InvoiceItemUnit.KILOMETER, InvoiceItemUnit.FLAT_RATE, InvoiceItemUnit.PIECE)

/** The display order of trip expense kind buttons in the add-button row and kind ComboBox.
 *  [TripExpenseKind] is persisted by explicit code and its declaration order is append-only, so it
 *  cannot double as the presentation order; this list is the one place that order is decided. */
internal val TRIP_EXPENSE_KIND_DISPLAY_ORDER = listOf(
    TripExpenseKind.HOTEL,
    TripExpenseKind.TRANSPORT,
    TripExpenseKind.MILEAGE,
    TripExpenseKind.TRAVEL_TIME,
    TripExpenseKind.OTHER,
)

/**
 * The one place a [TripExpenseRow]'s unit/quantity/price (and, for mileage, vehicle type)
 * are derived for a given kind: shared by the "+" buttons and the "Bearbeiten" tab's kind
 * ComboBox, so switching an existing row's kind re-derives the same numbers a freshly added row
 * of that kind would start with. Never touches [TripExpenseRow.description]/[TripExpenseRow.receiptId]/
 * [TripExpenseRow.travelDate] — those stay whatever the user already entered.
 */
internal class TripExpenseRowPrefill(
    private val recipientQueryPort: RecipientQueryPort,
    private val hotelRateResolver: HotelRatePrefillQueryPort?,
    private val mileageRateResolver: MileageRatePrefillQueryPort?,
    private val invoice: InvoiceDetailDto,
    // A plain UI helper may not read the clock itself (TimeSourceArchitectureTest); the real
    // caller (TripDialogLauncher) passes TimeSource.today() explicitly.
    private val today: LocalDate,
) {
    fun apply(row: TripExpenseRow, kind: TripExpenseKind) {
        row.kind = kind
        when (kind) {
            TripExpenseKind.HOTEL -> {
                row.unit = InvoiceItemUnit.DAY
                row.quantity = BigDecimal.ONE
                val recipientCountry = invoice.recipient?.id
                    ?.let { recipientQueryPort.findBillingAddress(it)?.countryAlpha2 }
                    ?.let { Country.fromAlpha2(it) }
                val rate = recipientCountry?.let { hotelRateResolver?.ratePerNight(it, today) }
                row.unitPriceNet = rate ?: Money.ZERO
            }
            TripExpenseKind.MILEAGE -> {
                row.unit = InvoiceItemUnit.KILOMETER
                row.quantity = BigDecimal.ZERO
                row.vehicleType = VehicleType.CAR
                val rate = mileageRatePerKm(VehicleType.CAR, row.travelDate)
                row.unitPriceNet = rate?.let { Money.of(it) } ?: Money.ZERO
            }
            TripExpenseKind.TRAVEL_TIME -> {
                row.unit = InvoiceItemUnit.HOUR
                row.quantity = BigDecimal.ZERO
                row.unitPriceNet = Money.ZERO
                row.rateProvided = false
            }
            else -> {
                row.unit = InvoiceItemUnit.FLAT_RATE
                row.quantity = BigDecimal.ONE
                row.unitPriceNet = Money.ZERO
                row.rateProvided = true
            }
        }
    }

    /** The statutory per-km rate for [vehicleType] on [date] (today when unset); `null` without a resolver. */
    fun mileageRatePerKm(vehicleType: VehicleType, date: LocalDate?): BigDecimal? =
        mileageRateResolver?.ratePerKm(vehicleType, date ?: today)
}

/**
 * The ordered expense rows of one trip item, the single source of truth both "Posten"
 * views render from, plus the save-relevant derivations (net total, missing-description check,
 * commands) and the receipt exclusivity rule (a receipt belongs to at most one row).
 */
internal class TripExpenseRowList(
    private val prefill: TripExpenseRowPrefill,
    existingItem: ItemDto?,
    // See TripExpenseRowPrefill.today's own KDoc.
    private val today: LocalDate,
) {
    private val rows = mutableListOf<TripExpenseRow>()

    init {
        existingItem?.trip?.expenses.orEmpty().sortedBy { it.sortOrder }.forEach { expense ->
            rows += TripExpenseRow(
                kind = TripExpenseKind.valueOf(expense.kind),
                description = expense.description,
                travelDate = expense.travelDate,
                quantity = expense.quantity,
                unit = expense.unit?.let { InvoiceItemUnit.valueOf(it) } ?: InvoiceItemUnit.PIECE,
                unitPriceNet = expense.unitPriceNet,
                receiptId = expense.receiptId,
                rateProvided = expense.rateProvided,
            )
        }
    }

    val size: Int get() = rows.size

    fun isEmpty(): Boolean = rows.isEmpty()

    fun snapshot(): List<TripExpenseRow> = rows.toList()

    fun add(kind: TripExpenseKind): TripExpenseRow {
        val row = TripExpenseRow(kind, "", today, BigDecimal.ONE, InvoiceItemUnit.FLAT_RATE, Money.ZERO, null)
        prefill.apply(row, kind)
        rows += row
        return row
    }

    fun changeKind(row: TripExpenseRow, kind: TripExpenseKind) = prefill.apply(row, kind)

    /** Moves the row at [from] to [to]; `false` (and no change) when [to] is out of range. */
    fun move(from: Int, to: Int): Boolean {
        if (to !in rows.indices) return false
        rows.add(to, rows.removeAt(from))
        return true
    }

    fun removeAt(index: Int) {
        rows.removeAt(index)
    }

    /** Net sum of all rows, the same [ItemAmounts.calculate] formula [TripExpenseRow.netAmount] uses. */
    fun total(): Money = rows.fold(Money.ZERO) { acc, row -> acc + row.netAmount() }

    /** A row other than travel time without a description blocks save. */
    fun hasMissingDescription(): Boolean = rows.any { it.description.isBlank() && it.kind != TripExpenseKind.TRAVEL_TIME }

    fun commands(): List<TripExpenseCommand> = rows.mapIndexed { index, row -> row.toCommand(index + 1) }

    /** Receipt ids already assigned to a row other than the one at [index]. */
    fun receiptIdsAssignedElsewhere(index: Int): Set<Long> =
        rows.filterIndexed { i, _ -> i != index }.mapNotNull { it.receiptId }.toSet()
}
