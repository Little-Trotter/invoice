package de.littletrotter.domain.invoice

import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.aTripExpense
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.tax.VatCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The write methods of [InvoiceItem] and [TripExpense]. */
class InvoiceItemMethodsTest {

    private fun rate(percent: String) = TaxRate().edit {
        code = "DE_$percent"
        category = "S"
        rate = TaxRatePercent(BigDecimal(percent))
        legalBasis = "basis"
    }

    // ── updateLine ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateLine replaces the line values as one group`() {
        val item = InvoiceItem()

        item.updateLine(
            InvoiceItemLineChange(
                category = InvoiceItemCategory.TRIP,
                description = "Beratung",
                itemDescription = "Langtext",
                deliveryNoteReference = "LS-1",
                buyerItemDescription = "Kundentext",
                quantity = BigDecimal("2.5"),
                unit = InvoiceItemUnit.DAY,
                unitPriceNet = Money.of("80.00"),
            ),
        )

        assertEquals(InvoiceItemCategory.TRIP, item.category)
        assertEquals("Beratung", item.description)
        assertEquals("Langtext", item.itemDescription)
        assertEquals("LS-1", item.deliveryNoteReference)
        assertEquals("Kundentext", item.buyerItemDescription)
        assertEquals(BigDecimal("2.5"), item.quantity)
        assertEquals(InvoiceItemUnit.DAY, item.unit)
        assertEquals(Money.of("80.00"), item.unitPriceNet)
    }

    @Test
    fun `updateLine with a default change resets optional texts and the price`() {
        val item = anInvoiceItem(
            description = "x",
            itemDescription = "y",
            deliveryNoteReference = "LS",
            unitPriceNet = Money.of("5.00"),
        )

        item.updateLine(InvoiceItemLineChange())

        assertEquals("", item.description)
        assertNull(item.itemDescription)
        assertNull(item.deliveryNoteReference)
        assertEquals(Money.ZERO, item.unitPriceNet)
    }

    @Test
    fun `asLineChange read back from the item is the identity for updateLine`() {
        val item = anInvoiceItem(description = "x", quantity = BigDecimal("3"), unitPriceNet = Money.of("5.00"))
        val before = item.asLineChange()

        item.updateLine(before)

        assertEquals(before, item.asLineChange())
    }

    // ── changeTax ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `changeTax replaces the tax values as one group`() {
        val item = InvoiceItem()
        val ref = rate("19.00")

        item.changeTax(
            InvoiceItemTaxChange(
                taxRateRef = ref,
                taxRuleCode = "DE_STANDARD_19",
                taxCategory = "S",
                taxRateSnapshot = TaxRatePercent(BigDecimal("19.00")),
                exemptionReasonCode = "VATEX",
                exemptionReasonText = "text",
                taxLegalBasis = "basis",
            ),
        )

        assertSame(ref, item.taxRateRef)
        assertEquals("DE_STANDARD_19", item.taxRuleCode)
        assertEquals(VatCategory.STANDARD, item.taxCategory)
        assertEquals(0, BigDecimal("19.00").compareTo(item.taxRateSnapshot?.value))
        assertEquals("VATEX", item.exemptionReasonCode)
        assertEquals("text", item.exemptionReasonText)
        assertEquals("basis", item.taxLegalBasis)
    }

    @Test
    fun `changeTax with an empty change clears a snapshot`() {
        val item = anInvoiceItem(taxRateRef = rate("19.00"), taxRuleCode = "X", taxCategory = "S")

        item.changeTax(InvoiceItemTaxChange())

        assertNull(item.taxRateRef)
        assertNull(item.taxRuleCode)
        assertNull(item.taxCategory)
    }

    @Test
    fun `changeTax does not check the owning invoice, unlike applyTaxRule`() {
        val finalized = anInvoice(id = 1L).apply {
            transitionTo(InvoiceStatus.IN_REVIEW)
            transitionTo(InvoiceStatus.ISSUED)
        }
        val item = anInvoiceItem(owner = finalized)

        item.changeTax(InvoiceItemTaxChange(taxRuleCode = "X"))

        assertEquals("X", item.taxRuleCode)
    }

    // ── configureTrip ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `configureTrip sets period, cost center, detail flag and fixed price`() {
        val item = InvoiceItem()

        item.configureTrip(
            TripLineSettings(
                periodFrom = LocalDate.of(2026, 4, 1),
                periodTo = LocalDate.of(2026, 4, 3),
                costCenter = "CC-7",
                detailedOnInvoice = true,
                fixedNetPrice = Money.of("500.00"),
            ),
        )

        assertEquals(LocalDate.of(2026, 4, 1), item.servicePeriodFrom)
        assertEquals(LocalDate.of(2026, 4, 3), item.servicePeriodTo)
        assertEquals("CC-7", item.costCenter)
        assertTrue(item.tripDetailedOnInvoice)
        assertEquals(Money.of("500.00"), item.tripFixedNetPrice)
    }

    @Test
    fun `configureTrip with no fixed price removes a previous fixed price`() {
        val item = anInvoiceItem(tripFixedNetPrice = Money.of("500.00"), tripDetailedOnInvoice = true)

        item.configureTrip(TripLineSettings(null, null, null, false, null))

        assertNull(item.tripFixedNetPrice)
        assertEquals(false, item.tripDetailedOnInvoice)
        assertNull(item.servicePeriodFrom)
    }

    // ── draft / copyForCorrection ──────────────────────────────────────────────────────────────

    @Test
    fun `draft creates a line with the given position id and line values`() {
        val item = InvoiceItem.draft(InvoiceItemLineChange(description = "Beratung"), positionId = "4")

        assertEquals("4", item.positionId)
        assertEquals("Beratung", item.description)
    }

    @Test
    fun `copyForCorrection copies the line, negates the prices and leaves the source untouched`() {
        val source = anInvoiceItem(
            positionId = "2",
            description = "Reise",
            quantity = BigDecimal("1"),
            unitPriceNet = Money.of("40.00"),
            taxRateRef = rate("19.00"),
            tripFixedNetPrice = Money.of("300.00"),
            costCenter = "CC-7",
            tripExpenses = listOf(aTripExpense(description = "Hotel", unitPriceNet = Money.of("100.00"))),
        )

        val copy = InvoiceItem.copyForCorrection(source)

        assertNotSame(source, copy)
        assertEquals("2", copy.positionId)
        assertEquals("Reise", copy.description)
        assertEquals("CC-7", copy.costCenter)
        assertSame(source.taxRateRef, copy.taxRateRef)
        assertEquals(Money.of("-40.00"), copy.unitPriceNet)
        assertEquals(Money.of("-300.00"), copy.tripFixedNetPrice)
        assertEquals(Money.of("-100.00"), copy.tripExpenseList.single().unitPriceNet)
        assertNull(copy.invoice, "the copy belongs to no invoice until it is added to the storno")
        assertEquals(Money.of("40.00"), source.unitPriceNet)
        assertEquals(Money.of("300.00"), source.tripFixedNetPrice)
        assertEquals(Money.of("100.00"), source.tripExpenseList.single().unitPriceNet)
    }

    @Test
    fun `copyForCorrection of a line without a fixed price keeps the fixed price absent`() {
        val copy = InvoiceItem.copyForCorrection(anInvoiceItem(unitPriceNet = Money.of("10.00")))

        assertNull(copy.tripFixedNetPrice)
        assertTrue(copy.tripExpenseList.isEmpty())
    }

    // ── TripExpense ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `TripExpense create takes every value from the draft`() {
        val owner = InvoiceItem()

        val expense = TripExpense.create(
            TripExpenseDraft(
                invoiceItem = owner,
                kind = TripExpenseKind.HOTEL,
                description = "Hotel",
                travelDate = LocalDate.of(2026, 4, 2),
                quantity = BigDecimal("2"),
                unit = InvoiceItemUnit.PIECE,
                unitPriceNet = Money.of("80.00"),
                rateProvided = false,
                receipt = null,
                sortOrder = 3,
            ),
        )

        assertSame(owner, expense.invoiceItem)
        assertEquals(TripExpenseKind.HOTEL, expense.kind)
        assertEquals("Hotel", expense.description)
        assertEquals(LocalDate.of(2026, 4, 2), expense.travelDate)
        assertEquals(BigDecimal("2"), expense.quantity)
        assertEquals(Money.of("80.00"), expense.unitPriceNet)
        assertEquals(false, expense.rateProvided)
        assertEquals(3, expense.sortOrder)
        assertEquals(Money.ZERO, expense.netAmount, "the net amount is derived by calculateNetAmount, not by creation")
    }

    @Test
    fun `TripExpense copyFor attaches the copy to the new line and carries the computed net amount`() {
        val source = aTripExpense(
            kind = TripExpenseKind.TRANSPORT,
            description = "Essen",
            quantity = BigDecimal("2"),
            unitPriceNet = Money.of("15.00"),
            sortOrder = 4,
        ).apply { calculateNetAmount() }
        val newOwner = InvoiceItem()

        val copy = TripExpense.copyFor(newOwner, source)

        assertSame(newOwner, copy.invoiceItem)
        assertEquals(TripExpenseKind.TRANSPORT, copy.kind)
        assertEquals("Essen", copy.description)
        assertEquals(source.netAmount, copy.netAmount)
        assertEquals(4, copy.sortOrder)
    }

    @Test
    fun `TripExpense copyFor does not carry the rate-provided flag`() {
        val source = aTripExpense(kind = TripExpenseKind.TRAVEL_TIME, rateProvided = false)

        val copy = TripExpense.copyFor(InvoiceItem(), source)

        assertTrue(copy.rateProvided, "a corrected copy always starts with the default flag")
    }
}
