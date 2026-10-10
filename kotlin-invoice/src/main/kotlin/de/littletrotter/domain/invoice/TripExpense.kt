package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.MoneyConverter
import de.littletrotter.domain.shared.NamedEntity
import de.littletrotter.domain.shared.converters.InvoiceItemUnitConverter
import de.littletrotter.domain.invoice.converters.TripExpenseKindConverter
import de.littletrotter.shared.Money
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One expense line (hotel night, taxi ride, mileage, …) of a [TripExpenseKind]
 * within a [InvoiceItem] of category [de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP].
 * A child entity, never its own aggregate/repository (DDD, [InvoiceItem] stays the aggregate
 * root): only ever loaded/saved through the owning [InvoiceItem]'s
 * `@OneToMany(cascade = ALL, orphanRemoval = true)` collection.
 *
 * [unitPriceNet] may be negative — a credit/discount line within a trip, the same as
 * [InvoiceItem.unitPriceNet] allows today. [netAmount] is derived (see [calculateNetAmount]) and
 * persisted for the read side (PDF/XML/Excel writers read only the stored column, never
 * recompute).
 */
@Entity
@Table(name = "trip_expense")
class TripExpense : NamedEntity {
    companion object {
        const val TRAVEL_TIME_KULANZ_TEXT = "Reise erfolgt gemäß Auftrag oder Kulanz"
        const val DESCRIPTION_MAX_LENGTH = 512
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_item_id")
    var invoiceItem: InvoiceItem? = null

    @Convert(converter = TripExpenseKindConverter::class)
    @Column(nullable = false)
    var kind: TripExpenseKind = TripExpenseKind.OTHER

    @Column(columnDefinition = "TEXT")
    var description: String = ""

    @Column(name = "travel_date")
    var travelDate: LocalDate? = null

    @Column(precision = 14, scale = 4)
    var quantity: BigDecimal = BigDecimal.ONE

    @Convert(converter = InvoiceItemUnitConverter::class)
    @Column(nullable = false)
    var unit: InvoiceItemUnit = InvoiceItemUnit.PIECE

    @Column(name = "unit_price_net", precision = 14, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var unitPriceNet: Money = Money.ZERO

    /** Only meaningful for TRAVEL_TIME. False means no hourly rate was supplied (the amount is
     * zero and the invoice uses the Kulanz text); true preserves an explicitly entered zero. */
    @Column(name = "rate_provided", nullable = false)
    var rateProvided: Boolean = true

    @Column(name = "net_amount", precision = 14, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var netAmount: Money = Money.ZERO

    /** Optional, one receipt per expense line; must already be attached to the owning
     *  trip item (enforced in the application layer). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    var receipt: Receipt? = null

    @Column(name = "sort_order")
    var sortOrder: Int = 0

    @Version
    var version: Long? = 0

    /** Same "quantity x price, rounded before multiply" rule as [InvoiceItem.calculateAmounts] —
     *  one shared formula, no second implementation. Sets [netAmount]. */
    fun calculateNetAmount() {
        netAmount = InvoiceItem.calculateAmounts(quantity, unitPriceNet, BigDecimal.ZERO).first
    }

    /** ST-3/border case 4 — negates [unitPriceNet] (never [quantity]) for a storno copy, then
     *  re-derives [netAmount]. Mirrors [InvoiceItem.negateForStorno]'s own rule for the parent
     *  item's fixed price. */
    fun negateForStorno() {
        unitPriceNet = -unitPriceNet
        calculateNetAmount()
    }

    fun validate() {
        if (description.isBlank() && kind != TripExpenseKind.TRAVEL_TIME) {
            throw InvoiceValidationException(InvoiceValidationError.TRIP_EXPENSE_DESCRIPTION_BLANK)
        }
        if (description.length > DESCRIPTION_MAX_LENGTH) throw InvoiceValidationException(InvoiceValidationError.TRIP_EXPENSE_DESCRIPTION_TOO_LONG, listOf(DESCRIPTION_MAX_LENGTH))
        if (quantity < BigDecimal.ZERO) throw InvoiceValidationException(InvoiceValidationError.TRIP_EXPENSE_QUANTITY_NEGATIVE)
    }
}
