package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.converters.TripExpenseKindConverter
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.fixture.aTripExpense

class TripExpenseTest {

    // ── TripExpenseKind codes (explicit, never ordinal) ───────────────────────────────────────

    @Test
    fun `every kind has its documented explicit code`() {
        assertEquals(0, TripExpenseKind.HOTEL.code)
        assertEquals(1, TripExpenseKind.TRANSPORT.code)
        assertEquals(2, TripExpenseKind.MILEAGE.code)
        assertEquals(3, TripExpenseKind.OTHER.code)
        assertEquals(4, TripExpenseKind.TRAVEL_TIME.code)
    }

    @Test
    fun `fromCode is the inverse of code, fromCodeOrNull is null-safe for an unknown code`() {
        TripExpenseKind.entries.forEach { kind -> assertEquals(kind, TripExpenseKind.fromCode(kind.code)) }
        assertNull(TripExpenseKind.fromCodeOrNull(999))
        assertThrows(NoSuchElementException::class.java) { TripExpenseKind.fromCode(999) }
    }

    @Test
    fun `converter persists and reads back the explicit code, never the ordinal`() {
        val converter = TripExpenseKindConverter()
        TripExpenseKind.entries.forEach { kind ->
            val db = converter.convertToDatabaseColumn(kind)
            assertEquals(kind.code, db)
            assertEquals(kind, converter.convertToEntityAttribute(db))
        }
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
        assertNull(converter.convertToEntityAttribute(999), "an unknown code decodes to null, never crashes the read")
    }

    // ── netAmount derivation ───────────────────────────────────────────────────────────────────

    private fun expense(
        quantity: String,
        unitPriceNet: String,
        description: String = "Hotel Nacht",
        receipt: Receipt? = null,
    ) = aTripExpense(
        kind = TripExpenseKind.HOTEL,
        description = description,
        receipt = receipt,
        quantity = BigDecimal(quantity),
        unitPriceNet = Money.of(unitPriceNet),
    )

    @Test
    fun `calculateNetAmount multiplies quantity by unit price, rounded to the currency scale`() {
        val e = expense(quantity = "3", unitPriceNet = "33.3333")
        e.calculateNetAmount()
        assertEquals(Money.of("99.99"), e.netAmount)
    }

    @Test
    fun `calculateNetAmount handles a negative unit price (a credit line)`() {
        val e = expense(quantity = "2", unitPriceNet = "-10.00")
        e.calculateNetAmount()
        assertEquals(Money.of("-20.00"), e.netAmount)
    }

    @Test
    fun `negateForCancellation negates the unit price and re-derives netAmount, quantity untouched`() {
        val e = expense(quantity = "2", unitPriceNet = "50.00")
        e.calculateNetAmount()
        assertEquals(Money.of("100.00"), e.netAmount)

        e.negateForCancellation()

        assertEquals(Money.of("-50.00"), e.unitPriceNet)
        assertEquals(0, BigDecimal("2").compareTo(e.quantity))
        assertEquals(Money.of("-100.00"), e.netAmount)
    }

    // ── receipt link is optional ───────────────────────────────────────────────────────────────

    @Test
    fun `receipt link is optional`() {
        val e = expense(quantity = "1", unitPriceNet = "10.00")
        assertNull(e.receipt)

        assertNotNull(expense(quantity = "1", unitPriceNet = "10.00", receipt = Receipt()).receipt)
    }

    // ── validate() ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `validate rejects a blank description`() {
        val e = expense(quantity = "1", unitPriceNet = "10.00", description = "")
        val ex = assertThrows(InvoiceValidationException::class.java) { e.validate() }
        assertEquals(InvoiceValidationError.TRIP_EXPENSE_DESCRIPTION_BLANK, ex.error)
    }

    @Test
    fun `validate rejects a description longer than 512 characters`() {
        val e = expense(quantity = "1", unitPriceNet = "10.00", description = "x".repeat(513))
        val ex = assertThrows(InvoiceValidationException::class.java) { e.validate() }
        assertEquals(InvoiceValidationError.TRIP_EXPENSE_DESCRIPTION_TOO_LONG, ex.error)
    }

    @Test
    fun `validate accepts a description of exactly 512 characters`() {
        val e = expense(quantity = "1", unitPriceNet = "10.00", description = "x".repeat(512))
        e.validate() // must not throw
    }

    @Test
    fun `validate rejects a negative quantity`() {
        val e = expense(quantity = "-1", unitPriceNet = "10.00")
        val ex = assertThrows(InvoiceValidationException::class.java) { e.validate() }
        assertEquals(InvoiceValidationError.TRIP_EXPENSE_QUANTITY_NEGATIVE, ex.error)
    }

    @Test
    fun `validate accepts a well-formed expense line`() {
        val e = expense(quantity = "1", unitPriceNet = "10.00")
        assertEquals(InvoiceItemUnit.PIECE, e.unit)
        e.validate() // must not throw
    }

    @Test
    fun `travel time without a rate accepts blank description for the Kulanz fallback`() {
        aTripExpense(
            kind = TripExpenseKind.TRAVEL_TIME,
            unit = InvoiceItemUnit.HOUR,
            quantity = BigDecimal("1.5"),
            unitPriceNet = Money.ZERO,
            rateProvided = false,
        ).apply {
            validate()
        }
    }

    @Test
    fun `travel time with an explicit zero accepts an empty optional description`() {
        val e = aTripExpense(
            kind = TripExpenseKind.TRAVEL_TIME,
            unit = InvoiceItemUnit.HOUR,
            rateProvided = true,
            description = "",
        )
        e.validate()
    }
}
