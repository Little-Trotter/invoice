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

        /** Builds a new expense line for [TripExpenseDraft.invoiceItem]; [netAmount] is not set
         *  here, the caller derives it with [calculateNetAmount] once the line is complete. */
        fun create(draft: TripExpenseDraft): TripExpense = TripExpense().apply {
            invoiceItem = draft.invoiceItem
            kind = draft.kind
            description = draft.description
            travelDate = draft.travelDate
            quantity = draft.quantity
            unit = draft.unit
            unitPriceNet = draft.unitPriceNet
            rateProvided = draft.rateProvided
            receipt = draft.receipt
            sortOrder = draft.sortOrder
        }

        /** Deep copy of [source] for [item], the credit note's new trip line item. The field list
         *  is explicit on purpose: a field added to [TripExpense] must not start being copied
         *  silently into a legal document. [unitPriceNet] is copied as-is (not yet negated); the
         *  caller negates the whole tree via [InvoiceItem.negateForCancellation] afterward. */
        fun copyFor(item: InvoiceItem, source: TripExpense): TripExpense = TripExpense().apply {
            invoiceItem = item
            kind = source.kind
            description = source.description
            travelDate = source.travelDate
            quantity = source.quantity
            unit = source.unit
            unitPriceNet = source.unitPriceNet
            netAmount = source.netAmount
            receipt = source.receipt
            sortOrder = source.sortOrder
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_item_id")
    var invoiceItem: InvoiceItem? = null
        protected set

    @Convert(converter = TripExpenseKindConverter::class)
    @Column(nullable = false)
    var kind: TripExpenseKind = TripExpenseKind.OTHER
        protected set

    @Column(columnDefinition = "TEXT")
    var description: String = ""
        protected set

    @Column(name = "travel_date")
    var travelDate: LocalDate? = null
        protected set

    @Column(precision = 14, scale = 4)
    var quantity: BigDecimal = BigDecimal.ONE
        protected set

    @Convert(converter = InvoiceItemUnitConverter::class)
    @Column(nullable = false)
    var unit: InvoiceItemUnit = InvoiceItemUnit.PIECE
        protected set

    @Column(name = "unit_price_net", precision = 14, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var unitPriceNet: Money = Money.ZERO
        protected set

    /** Only meaningful for TRAVEL_TIME. False means no hourly rate was supplied (the amount is
     * zero and the invoice uses the Kulanz text); true preserves an explicitly entered zero. */
    @Column(name = "rate_provided", nullable = false)
    var rateProvided: Boolean = true
        protected set

    @Column(name = "net_amount", precision = 14, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var netAmount: Money = Money.ZERO
        protected set

    /** Optional, one receipt per expense line; must already be attached to the owning
     *  trip item (enforced in the application layer). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    var receipt: Receipt? = null
        protected set

    @Column(name = "sort_order")
    var sortOrder: Int = 0
        protected set

    @Version
    var version: Long? = 0
        protected set

    /** Same "quantity x price, rounded before multiply" rule as [de.littletrotter.domain.invoice.ItemAmounts.calculate] —
     *  one shared formula, no second implementation. Sets [netAmount]. */
    fun calculateNetAmount() {
        netAmount = ItemAmounts.calculate(quantity, unitPriceNet, BigDecimal.ZERO).first
    }

    /** ST-3/border case 4 — negates [unitPriceNet] (never [quantity]) for a cancellation copy, then
     *  re-derives [netAmount]. Mirrors [InvoiceItem.negateForCancellation]'s own rule for the parent
     *  item's fixed price. */
    fun negateForCancellation() {
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

/** The input of [TripExpense.create]: everything a new expense line is built from. */
data class TripExpenseDraft(
    val invoiceItem: InvoiceItem,
    val kind: TripExpenseKind,
    val description: String,
    val travelDate: LocalDate?,
    val quantity: BigDecimal,
    val unit: InvoiceItemUnit,
    val unitPriceNet: Money,
    val rateProvided: Boolean,
    val receipt: Receipt?,
    val sortOrder: Int,
)
