package de.littletrotter.domain.invoice

import de.littletrotter.fixture.PersistenceEmulation
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.fixture.aTripExpense
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit

/**
 * The amount rule, forced quantity/unit, and validate() invariants for a business trip
 * ([InvoiceItemCategory.TRIP]): `net = tripFixedNetPrice ?: (Σ tripExpenses.netAmount + Σ
 * perDiemEntries.calculatedAmount)`, quantity/unit are never user-set.
 */
class InvoiceItemTripTest {

    private fun rate(percent: String) = TaxRate().edit { rate = TaxRatePercent(BigDecimal(percent)) }

    private fun expense(price: String, quantity: String = "1", description: String = "Hotel Nacht") = aTripExpense(
        kind = TripExpenseKind.HOTEL,
        description = description,
        quantity = BigDecimal(quantity),
        unitPriceNet = Money.of(price),
    ).apply {
        calculateNetAmount()
    }

    private fun perDiemDay(amount: String) = TravelExpenseEntry().edit {
        travelDate = LocalDate.of(2026, 9, 12)
        calculatedAmount = Money.of(amount)
    }

    private fun tripItem(rate: TaxRate = rate("19.00"), owner: Invoice? = null): InvoiceItem = anInvoiceItem(
        positionId = "1",
        owner = owner,
        category = InvoiceItemCategory.TRIP,
        description = "Dienstreise",
        taxRateRef = rate,
    )

    // ── Amount rule ────────────────────────────────────────────────────────────────────────

    @Test
    fun `net is the sum of the expense lines when there is no fixed price`() {
        val item = tripItem()
        item.addTripExpense(expense("100.00"))
        item.addTripExpense(expense("50.00"))
        item.calculateAmounts()

        assertEquals(Money.of("150.00"), item.netAmount)
        assertEquals(Money.of("28.50"), item.vatAmount)
        assertEquals(Money.of("178.50"), item.grossAmount)
    }

    @Test
    fun `a fixed price replaces the sum of the expense lines`() {
        val item = tripItem()
        item.addTripExpense(expense("100.00"))
        item.edit { tripFixedNetPrice = Money.of("500.00") }
        item.calculateAmounts()

        assertEquals(Money.of("500.00"), item.netAmount)
        assertEquals(Money.of("95.00"), item.vatAmount)
        assertEquals(Money.of("595.00"), item.grossAmount)
    }

    @Test
    fun `expense lines and per-diem days both contribute to the net amount`() {
        val item = tripItem()
        item.addTripExpense(expense("100.00"))
        item.addPerDiemEntry(perDiemDay("28.00"))
        item.addPerDiemEntry(perDiemDay("14.00"))
        item.calculateAmounts()

        assertEquals(Money.of("142.00"), item.netAmount)
    }

    @Test
    fun `a fixed price of exactly zero is honoured, not treated as unset`() {
        val item = tripItem()
        item.addTripExpense(expense("100.00"))
        item.edit { tripFixedNetPrice = Money.ZERO }
        item.calculateAmounts()

        assertEquals(Money.ZERO, item.netAmount)
        assertEquals(Money.ZERO, item.vatAmount)
        assertEquals(Money.ZERO, item.grossAmount)
    }

    @Test
    fun `a negative expense line (credit) reduces the trip's net amount`() {
        val item = tripItem()
        item.addTripExpense(expense("100.00"))
        item.addTripExpense(expense("-20.00"))
        item.calculateAmounts()

        assertEquals(Money.of("80.00"), item.netAmount)
    }

    @Test
    fun `the unit price rounds to the currency scale before multiplying, per expense line`() {
        val item = tripItem()
        // 3 x 33.3333 rounded to scale first is 3 x 33.33 = 99.99, not 100.00 — same rule as
        // InvoiceItem.calculateAmounts's own companion formula.
        item.addTripExpense(
            aTripExpense(
                kind = TripExpenseKind.MILEAGE,
                description = "Kilometer",
                quantity = BigDecimal("3"),
                unitPriceNet = Money(BigDecimal("33.3333")),
            ).apply {
                calculateNetAmount()
            },
        )
        item.calculateAmounts()

        assertEquals(Money.of("99.99"), item.netAmount)
    }

    @Test
    fun `calculatedTripNet is independent of a set fixed price`() {
        val item = tripItem()
        item.addTripExpense(expense("100.00"))
        item.addTripExpense(expense("50.00"))
        item.edit { tripFixedNetPrice = Money.of("999.00") }

        assertEquals(Money.of("150.00"), item.calculatedTripNet())
        item.calculateAmounts()
        assertEquals(Money.of("999.00"), item.netAmount, "the item's own net still follows the fixed price")
    }

    @Test
    fun `quantity and unit are forced to 1 and FLAT_RATE regardless of what was set before`() {
        val item = tripItem()
        item.edit {
            quantity = BigDecimal("7")
            unit = InvoiceItemUnit.KILOMETER
        }
        item.addTripExpense(expense("10.00"))

        item.calculateAmounts()

        assertEquals(0, BigDecimal.ONE.compareTo(item.quantity))
        assertEquals(InvoiceItemUnit.FLAT_RATE, item.unit)
    }

    // ── negateForCancellation (README border case 4) ────────────────────────────────────────────────

    @Test
    fun `negateForCancellation negates the fixed price and every expense line's unit price, quantities untouched`() {
        val item = tripItem()
        item.edit { tripFixedNetPrice = Money.of("500.00") }
        val hotel = expense("100.00", quantity = "2")
        item.addTripExpense(hotel)
        item.calculateAmounts()

        item.negateForCancellation()
        item.calculateAmounts()

        assertEquals(Money.of("-500.00"), item.tripFixedNetPrice)
        assertEquals(Money.of("-100.00"), hotel.unitPriceNet)
        assertEquals(0, BigDecimal("2").compareTo(hotel.quantity), "quantity stays untouched")
        assertEquals(Money.of("-200.00"), hotel.netAmount)
        assertEquals(Money.of("-500.00"), item.netAmount, "still fixed-price-driven, now negated")
    }

    @Test
    fun `negateForCancellation on an item with no fixed price negates only the expense lines`() {
        val item = tripItem()
        val hotel = expense("100.00")
        item.addTripExpense(hotel)
        item.calculateAmounts()

        item.negateForCancellation()
        item.calculateAmounts()

        assertEquals(null, item.tripFixedNetPrice)
        assertEquals(Money.of("-100.00"), item.netAmount)
    }

    // ── validate() ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `on a cancellation document the per-diem days are subtracted so the trip net is the exact negative`() {
        val original = tripItem(owner = Invoice()).apply {
            addTripExpense(expense("36.00"))
            addPerDiemEntry(perDiemDay("28.00"))
            calculateAmounts()
        }
        assertEquals(Money.of("64.00"), original.netAmount)

        val cancellationOwner = anInvoice(typeCode = InvoiceTypeCode.CORRECTED_INVOICE)
        val cancellation = tripItem(owner = cancellationOwner).apply {
            PersistenceEmulation.whileEditable(cancellationOwner) {
                addTripExpense(expense("36.00"))
                addPerDiemEntry(perDiemDay("28.00"))
            }
            negateForCancellation()
            calculateAmounts()
        }
        assertEquals(Money.of("-64.00"), cancellation.netAmount)
        assertEquals(Money.of("-64.00"), cancellation.calculatedTripNet())
        assertEquals(Money.of("28.00"), cancellation.perDiemEntryList.single().calculatedAmount)
    }

    @Test
    fun `a cancellation document with a fixed price still uses the negated fixed price`() {
        val cancellationOwner = anInvoice(typeCode = InvoiceTypeCode.CORRECTED_INVOICE)
        val cancellation = tripItem(owner = cancellationOwner).edit {
            tripFixedNetPrice = Money.of("100.00")
        }.apply {
            PersistenceEmulation.whileEditable(cancellationOwner) { addPerDiemEntry(perDiemDay("28.00")) }
            negateForCancellation()
            calculateAmounts()
        }
        assertEquals(Money.of("-100.00"), cancellation.netAmount)
    }

    @Test
    fun `validate rejects per-diem days on a SERVICE position`() {
        val item = anInvoiceItem(
            positionId = "1",
            description = "Beratung",
            category = InvoiceItemCategory.SERVICE,
            taxRateRef = rate("19.00"),
        ).apply {
            addPerDiemEntry(perDiemDay("28.00"))
        }
        val ex = assertThrows(InvoiceValidationException::class.java) { item.validate() }
        assertEquals(InvoiceValidationError.ITEM_PER_DIEM_ENTRIES_WITHOUT_TRIP_CATEGORY, ex.error)
    }

    @Test
    fun `validate rejects a fixed price on a PRODUCT position`() {
        val item = anInvoiceItem(
            positionId = "1",
            description = "Ware",
            category = InvoiceItemCategory.PRODUCT,
            taxRateRef = rate("19.00"),
            tripFixedNetPrice = Money.of("50.00"),
        )
        val ex = assertThrows(InvoiceValidationException::class.java) { item.validate() }
        assertEquals(InvoiceValidationError.ITEM_TRIP_FIXED_PRICE_WITHOUT_TRIP_CATEGORY, ex.error)
    }

    @Test
    fun `validate accepts a negative fixed price on a TRIP position - required for a cancellation copy`() {
        // negateForCancellation() negates tripFixedNetPrice for a credit-note copy
        // exactly like it negates unitPriceNet, which validate() has never rejected either (B1);
        // a CHECK/validate() rejection here made a fixed-price trip's cancellation copy unsavable. The
        // "a user-entered fixed price is >= 0" rule lives at creation/update time in
        // TripCommandValidator (application layer), never in this domain method.
        val item = tripItem().edit { tripFixedNetPrice = Money.of("-1.00") }
        item.validate()
    }

    @Test
    fun `validate rejects a blank expense description`() {
        val item = tripItem()
        item.addTripExpense(expense("10.00", description = "  "))
        val ex = assertThrows(InvoiceValidationException::class.java) { item.validate() }
        assertEquals(InvoiceValidationError.TRIP_EXPENSE_DESCRIPTION_BLANK, ex.error)
    }

    @Test
    fun `README border case 2 - a TRIP with only per-diem days and no expense lines is valid`() {
        val item = tripItem()
        item.addPerDiemEntry(perDiemDay("28.00"))
        item.calculateAmounts()
        item.validate() // must not throw
        assertFalse(item.tripExpenseList.isNotEmpty())
        assertEquals(Money.of("28.00"), item.netAmount)
    }

    @Test
    fun `README border case 1 - a TRIP with only a fixed price and no expense lines is valid`() {
        val item = tripItem()
        item.edit { tripFixedNetPrice = Money.of("500.00") }
        item.calculateAmounts()
        item.validate() // must not throw
        assertEquals(Money.ZERO, item.calculatedTripNet(), "the Excel delta would be the full fixed price")
        assertEquals(Money.of("500.00"), item.netAmount)
    }
}
