package de.littletrotter.application.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pure, DB-free validation for [TripCommand]/
 * [TripCommandValidator]. See [TripServiceTest] for the DB-backed cases (unknown receipt against
 * an item's actually-attached set, the PER_DIEM_UI gate against a real feature flag).
 */
class TripCommandValidatorTest {

    private fun minimalTrip(title: String = "Dienstreise Hamburg") = TripCommand(title = title)

    @Test
    fun `validateStructure rejects a blank title`() {
        val ex = assertThrows(InvoiceValidationException::class.java) {
            TripCommandValidator.validateStructure(minimalTrip(title = "   "))
        }
        assertEquals(InvoiceValidationError.ITEM_DESCRIPTION_BLANK, ex.error)
    }

    @Test
    fun `validateStructure rejects a negative fixed price`() {
        val ex = assertThrows(InvoiceValidationException::class.java) {
            TripCommandValidator.validateStructure(minimalTrip().copy(fixedNetPrice = Money.of("-1.00")))
        }
        assertEquals(InvoiceValidationError.ITEM_TRIP_FIXED_PRICE_NEGATIVE, ex.error)
    }

    @Test
    fun `validateStructure accepts a zero fixed price`() {
        assertDoesNotThrow {
            TripCommandValidator.validateStructure(minimalTrip().copy(fixedNetPrice = Money.ZERO))
        }
    }

    @Test
    fun `validateStructure rejects a blank expense description`() {
        val command = minimalTrip().copy(
            expenses = listOf(
                TripExpenseCommand(
                    kind = TripExpenseKind.HOTEL, description = "  ", quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("100.00"),
                ),
            ),
        )
        val ex = assertThrows(InvoiceValidationException::class.java) { TripCommandValidator.validateStructure(command) }
        assertEquals(InvoiceValidationError.TRIP_EXPENSE_DESCRIPTION_BLANK, ex.error)
    }

    @Test
    fun `validateStructure accepts optional blank travel time text with any rate decision`() {
        listOf(false, true).forEach { rateProvided ->
            val command = minimalTrip().copy(
                expenses = listOf(
                    TripExpenseCommand(
                        kind = TripExpenseKind.TRAVEL_TIME, description = "  ", quantity = BigDecimal("1.5"),
                        unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.ZERO, rateProvided = rateProvided,
                    ),
                ),
            )
            assertDoesNotThrow { TripCommandValidator.validateStructure(command) }
        }
    }

    @Test
    fun `validateStructure rejects a negative expense quantity`() {
        val command = minimalTrip().copy(
            expenses = listOf(
                TripExpenseCommand(
                    kind = TripExpenseKind.MILEAGE, description = "Km", quantity = BigDecimal("-1"),
                    unit = InvoiceItemUnit.KILOMETER, unitPriceNet = Money.of("0.30"),
                ),
            ),
        )
        val ex = assertThrows(InvoiceValidationException::class.java) { TripCommandValidator.validateStructure(command) }
        assertEquals(InvoiceValidationError.TRIP_EXPENSE_QUANTITY_NEGATIVE, ex.error)
    }

    @Test
    fun `validateStructure accepts detailedOnInvoice without any expenses (README border case 1 slash 2)`() {
        assertDoesNotThrow {
            TripCommandValidator.validateStructure(minimalTrip().copy(detailedOnInvoice = true, fixedNetPrice = Money.of("500.00")))
        }
    }

    @Test
    fun `validateReceipts rejects an expense referencing a receipt not attached to the trip`() {
        val command = minimalTrip().copy(
            expenses = listOf(
                TripExpenseCommand(
                    kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("100.00"), receiptId = 42L,
                ),
            ),
        )
        val ex = assertThrows(InvoiceValidationException::class.java) {
            TripCommandValidator.validateReceipts(command, attachedReceiptIds = setOf(1L, 2L))
        }
        assertEquals(InvoiceValidationError.TRIP_EXPENSE_RECEIPT_NOT_ATTACHED, ex.error)
    }

    @Test
    fun `validateReceipts accepts an expense referencing an attached receipt`() {
        val command = minimalTrip().copy(
            expenses = listOf(
                TripExpenseCommand(
                    kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("100.00"), receiptId = 42L,
                ),
            ),
        )
        assertDoesNotThrow { TripCommandValidator.validateReceipts(command, attachedReceiptIds = setOf(42L)) }
    }

    @Test
    fun `validateReceipts accepts an expense with no receipt at all`() {
        val command = minimalTrip().copy(
            expenses = listOf(
                TripExpenseCommand(
                    kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("100.00"),
                ),
            ),
        )
        assertDoesNotThrow { TripCommandValidator.validateReceipts(command, attachedReceiptIds = emptySet()) }
    }

    @Test
    fun `validatePerDiemGate rejects per-diem days on create while the flag is off`() {
        val command = minimalTrip().copy(perDiemDays = listOf(perDiemDay()))
        val ex = assertThrows(InvoiceValidationException::class.java) {
            TripCommandValidator.validatePerDiemGate(command, perDiemUiEnabled = false)
        }
        assertEquals(InvoiceValidationError.PER_DIEM_FEATURE_DISABLED, ex.error)
    }

    @Test
    fun `validatePerDiemGate lets an update through while the flag is off if the trip already had per-diem days`() {
        val command = minimalTrip().copy(perDiemDays = listOf(perDiemDay()))
        assertDoesNotThrow {
            TripCommandValidator.validatePerDiemGate(command, perDiemUiEnabled = false, alreadyHasPerDiemDays = true)
        }
    }

    @Test
    fun `validatePerDiemGate never fires for a trip without per-diem days, flag off or on`() {
        assertDoesNotThrow { TripCommandValidator.validatePerDiemGate(minimalTrip(), perDiemUiEnabled = false) }
    }

    @Test
    fun `validateStructure accepts missing and whitespace per-diem descriptions and participants`() {
        for (text in listOf("", "  \n\t")) {
            val command = minimalTrip().copy(perDiemDays = listOf(
                perDiemDay(description = text, participant = text),
                perDiemDay(description = text, participant = text),
            ))
            assertDoesNotThrow { TripCommandValidator.validateStructure(command) }
        }
    }

    @Test
    fun `validateStructure accepts a per-diem entry with both a description and a participant`() {
        val command = minimalTrip().copy(perDiemDays = listOf(perDiemDay()))
        assertDoesNotThrow { TripCommandValidator.validateStructure(command) }
    }

    private fun perDiemDay(description: String = "Team-Reise Hamburg", participant: String = "Kundentermin") = PerDiemEntryCommand(
        date = LocalDate.of(2026, 2, 8), isArrivalDeparture = false,
        breakfast = false, lunch = false, dinner = false,
        description = description, participant = participant, employerContribution = Money.ZERO, perDiemRateId = 1L,
    )
}
