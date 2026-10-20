package de.littletrotter.fixture

import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.TripExpenseDraft
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A trip expense with the entity defaults. [invoiceItem] is the owning line; a standalone expense
 * gets a throwaway owner because the creation factory requires one, and the net amount stays at
 * its entity default until `calculateNetAmount` runs, exactly like a freshly built entity.
 */
fun aTripExpense(
    id: Long? = null,
    invoiceItem: InvoiceItem = InvoiceItem(),
    kind: TripExpenseKind = TripExpenseKind.OTHER,
    description: String = "",
    travelDate: LocalDate? = null,
    quantity: BigDecimal = BigDecimal.ONE,
    unit: InvoiceItemUnit = InvoiceItemUnit.PIECE,
    unitPriceNet: Money = Money.ZERO,
    rateProvided: Boolean = true,
    receipt: Receipt? = null,
    sortOrder: Int = 0,
): TripExpense = TripExpense.create(
    TripExpenseDraft(
        invoiceItem = invoiceItem,
        kind = kind,
        description = description,
        travelDate = travelDate,
        quantity = quantity,
        unit = unit,
        unitPriceNet = unitPriceNet,
        rateProvided = rateProvided,
        receipt = receipt,
        sortOrder = sortOrder,
    ),
).let { expense -> if (id == null) expense else PersistenceEmulation.assignId(expense, id) }
