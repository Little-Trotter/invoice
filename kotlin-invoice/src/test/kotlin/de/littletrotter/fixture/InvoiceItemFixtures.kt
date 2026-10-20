package de.littletrotter.fixture

import de.littletrotter.domain.invoice.replaceReceipts
import de.littletrotter.domain.invoice.addTripExpense
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceItemLineChange
import de.littletrotter.domain.invoice.InvoiceItemTaxChange
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.TripLineSettings
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.reference.TaxRateDefinition
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import java.math.BigDecimal
import java.time.LocalDate

/** A transient 19 % standard-rate rule, the tax every fixture line carries unless told otherwise. */
fun aStandardTaxRate(): TaxRate =
    TaxRate.create(TaxRateDefinition(rate = TaxRatePercent(BigDecimal("19.00")), category = "S"))

/**
 * An invoice line built through the domain operations only. Every default is the entity default,
 * except the tax: a line always carries a tax rate, so the default is a 19 % standard rule and its
 * category snapshot; pass `taxRateRef = null` to build a line that has none.
 *
 * [tripExpenses] and [receipts] are attached after creation to the collections the line owns.
 * [sortOrder] is the position a caller preset before adding the line to an invoice (which keeps a
 * positive value); [owner] is the invoice the line points back to without being in its item list,
 * as a line loaded for a single-line edit does.
 * The amounts are derived by `calculateAmounts`, as in production; [netAmount], [vatAmount] and
 * [grossAmount] hydrate stored amounts that disagree with the line values (all three together),
 * and [productClassificationScheme]/[productClassificationCode] hydrate the transient BT-158 pair.
 */
fun anInvoiceItem(
    id: Long? = null,
    positionId: String = "",
    category: InvoiceItemCategory = InvoiceItemCategory.SERVICE,
    description: String = "",
    itemDescription: String? = null,
    buyerItemDescription: String? = null,
    deliveryNoteReference: String? = null,
    servicePeriodFrom: LocalDate? = null,
    servicePeriodTo: LocalDate? = null,
    costCenter: String? = null,
    quantity: BigDecimal = BigDecimal.ONE,
    unit: InvoiceItemUnit = InvoiceItemUnit.HOUR,
    unitPriceNet: Money = Money.ZERO,
    taxRateRef: TaxRate? = aStandardTaxRate(),
    articleRef: Article? = null,
    taxRuleCode: String? = null,
    taxCategory: String? = taxRateRef?.category,
    taxRateSnapshot: TaxRatePercent? = null,
    exemptionReasonCode: String? = null,
    exemptionReasonText: String? = null,
    taxLegalBasis: String? = null,
    tripDetailedOnInvoice: Boolean = false,
    tripFixedNetPrice: Money? = null,
    tripExpenses: List<TripExpense> = emptyList(),
    receipts: List<Receipt> = emptyList(),
    sortOrder: Int = 0,
    owner: Invoice? = null,
    netAmount: Money? = null,
    vatAmount: Money? = null,
    grossAmount: Money? = null,
    productClassificationScheme: String? = null,
    productClassificationCode: String? = null,
): InvoiceItem = InvoiceItem.draft(
    InvoiceItemLineChange(
        category = category,
        description = description,
        itemDescription = itemDescription,
        deliveryNoteReference = deliveryNoteReference,
        buyerItemDescription = buyerItemDescription,
        quantity = quantity,
        unit = unit,
        unitPriceNet = unitPriceNet,
    ),
    positionId,
).apply {
    changeTax(
        InvoiceItemTaxChange(
            taxRateRef = taxRateRef,
            articleRef = articleRef,
            taxRuleCode = taxRuleCode,
            taxCategory = taxCategory,
            taxRateSnapshot = taxRateSnapshot,
            exemptionReasonCode = exemptionReasonCode,
            exemptionReasonText = exemptionReasonText,
            taxLegalBasis = taxLegalBasis,
        ),
    )
    configureTrip(TripLineSettings(servicePeriodFrom, servicePeriodTo, costCenter, tripDetailedOnInvoice, tripFixedNetPrice))
    tripExpenses.forEach(::addTripExpense)
    replaceReceipts(receipts)
    if (id != null) PersistenceEmulation.assignId(this, id)
    if (netAmount != null || vatAmount != null || grossAmount != null) {
        PersistenceEmulation.assignAmounts(
            this,
            netAmount ?: this.netAmount,
            vatAmount ?: this.vatAmount,
            grossAmount ?: this.grossAmount,
        )
    }
    if (productClassificationScheme != null || productClassificationCode != null) {
        PersistenceEmulation.assignProductClassification(this, productClassificationScheme, productClassificationCode)
    }
    if (sortOrder != 0) PersistenceEmulation.hydrate(this, "sortOrder", sortOrder)
    if (owner != null) PersistenceEmulation.hydrate(this, "invoice", owner)
}

/**
 * Edits an existing line in place through the same domain operations the application uses: the
 * line values, the tax values and the trip settings each move as one group, so the editor reads
 * the current group, changes the touched value and writes the group back.
 */
fun InvoiceItem.edit(block: InvoiceItemEditor.() -> Unit): InvoiceItem = also { InvoiceItemEditor(it).block() }

/** Property-style access to the editable groups of an [InvoiceItem]; each write applies at once. */
class InvoiceItemEditor internal constructor(internal val item: InvoiceItem) {
    private fun line(change: InvoiceItemLineChange.() -> InvoiceItemLineChange) =
        item.updateLine(item.asLineChange().change())

    private fun tax(change: InvoiceItemTaxChange.() -> InvoiceItemTaxChange) =
        item.changeTax(item.asTaxChange().change())

    private fun trip(change: TripLineSettings.() -> TripLineSettings) = item.configureTrip(
        TripLineSettings(
            item.servicePeriodFrom,
            item.servicePeriodTo,
            item.costCenter,
            item.tripDetailedOnInvoice,
            item.tripFixedNetPrice,
        ).change(),
    )
    var receipts: List<Receipt>
        get() = item.receiptList
        set(value) { PersistenceEmulation.hydrate(item, "receipts", value.toMutableList()) }

    /**
     * The position id of a line as a loaded row carries it; numbering a new line is
     * `Invoice.addItem`'s job.
     */
    var positionId: String
        get() = item.positionId
        set(value) { PersistenceEmulation.hydrate(item, "positionId", value) }

    var category: InvoiceItemCategory
        get() = item.category
        set(value) = line { copy(category = value) }
    var description: String
        get() = item.description
        set(value) = line { copy(description = value) }
    var itemDescription: String?
        get() = item.itemDescription
        set(value) = line { copy(itemDescription = value) }
    var quantity: BigDecimal
        get() = item.quantity
        set(value) = line { copy(quantity = value) }
    var unit: InvoiceItemUnit
        get() = item.unit
        set(value) = line { copy(unit = value) }
    var unitPriceNet: Money
        get() = item.unitPriceNet
        set(value) = line { copy(unitPriceNet = value) }

    var taxRateRef: TaxRate?
        get() = item.taxRateRef
        // Binding a rule also takes its category snapshot unless one is set, as applyTaxRule would.
        set(value) = tax { copy(taxRateRef = value, taxCategory = taxCategory ?: value?.category) }
    var articleRef: Article?
        get() = item.articleRef
        set(value) = tax { copy(articleRef = value) }
    var taxRateSnapshot: TaxRatePercent?
        get() = item.taxRateSnapshot
        set(value) = tax { copy(taxRateSnapshot = value) }
    var servicePeriodFrom: LocalDate?
        get() = item.servicePeriodFrom
        set(value) = trip { copy(periodFrom = value) }
    var servicePeriodTo: LocalDate?
        get() = item.servicePeriodTo
        set(value) = trip { copy(periodTo = value) }
    var tripFixedNetPrice: Money?
        get() = item.tripFixedNetPrice
        set(value) = trip { copy(fixedNetPrice = value) }

    fun calculateAmounts() = item.calculateAmounts()

    fun applyTaxRule(rule: TaxRate) = item.applyTaxRule(rule)

    // Stored state a persisted row can carry independently of the line values.
    /** The stored amounts of a line, independent of the quantity and price the line carries. */
    var netAmount: Money
        get() = item.netAmount
        set(value) { PersistenceEmulation.hydrate(item, "netAmount", value) }

    var vatAmount: Money
        get() = item.vatAmount
        set(value) { PersistenceEmulation.hydrate(item, "vatAmount", value) }

    var grossAmount: Money
        get() = item.grossAmount
        set(value) { PersistenceEmulation.hydrate(item, "grossAmount", value) }

    var id: Long?
        get() = item.id
        set(value) { PersistenceEmulation.assignId(item, value) }

    var sortOrder: Int
        get() = item.sortOrder
        set(value) { PersistenceEmulation.hydrate(item, "sortOrder", value) }

    var invoice: Invoice?
        get() = item.invoice
        set(value) { PersistenceEmulation.hydrate(item, "invoice", value) }
}
