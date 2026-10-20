package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * These tests pin down the *correct* [InvoiceValidationError] code per field on
 * [TravelExpenseEntry.validate], not just that validation fails: a wrong code surfaces as the
 * wrong user-facing message, regardless of which field is actually invalid.
 */
class TravelExpenseEntryValidationTest {

    private fun validEntry(): TravelExpenseEntry = TravelExpenseEntry().edit {
        description = "Team-Reise Berlin"
        participant = "Kundentermin Berlin"
        travelDate = LocalDate.of(2026, 8, 21)
        fullDayRateSnapshot = Money.of("28.00")
        arrivalDepartureRateSnapshot = Money.of("14.00")
        calculatedAmount = Money.of("28.00")
        employerContribution = Money.ZERO
    }

    @Test
    fun `a fully specified entry passes validation`() {
        assertDoesNotThrow { validEntry().validate() }
    }

    @Test
    fun `a blank description is reported as TRAVEL_DESCRIPTION_BLANK`() {
        val entry = validEntry().edit { description = "  " }
        val ex = assertThrows(InvoiceValidationException::class.java) { entry.validate() }
        assertEquals(InvoiceValidationError.TRAVEL_DESCRIPTION_BLANK, ex.error)
    }

    @Test
    fun `a blank participant is reported as TRAVEL_PARTICIPANT_BLANK`() {
        val entry = validEntry().edit { participant = "  " }
        val ex = assertThrows(InvoiceValidationException::class.java) { entry.validate() }
        assertEquals(InvoiceValidationError.TRAVEL_PARTICIPANT_BLANK, ex.error)
    }

    @Test
    fun `a missing travel date is reported as TRAVEL_DATE_MISSING`() {
        val entry = validEntry().edit { travelDate = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { entry.validate() }
        assertEquals(InvoiceValidationError.TRAVEL_DATE_MISSING, ex.error)
    }

    @Test
    fun `a negative full-day rate is reported as TRAVEL_FULL_DAY_RATE_NEGATIVE`() {
        val entry = validEntry().edit { fullDayRateSnapshot = Money.of("-1.00") }
        val ex = assertThrows(InvoiceValidationException::class.java) { entry.validate() }
        assertEquals(InvoiceValidationError.TRAVEL_FULL_DAY_RATE_NEGATIVE, ex.error)
    }

    @Test
    fun `a negative arrival-departure rate is reported as TRAVEL_ARRIVAL_DEPARTURE_RATE_NEGATIVE`() {
        val entry = validEntry().edit { arrivalDepartureRateSnapshot = Money.of("-1.00") }
        val ex = assertThrows(InvoiceValidationException::class.java) { entry.validate() }
        assertEquals(InvoiceValidationError.TRAVEL_ARRIVAL_DEPARTURE_RATE_NEGATIVE, ex.error)
    }

    @Test
    fun `a negative calculated amount is reported as TRAVEL_CALCULATED_AMOUNT_NEGATIVE`() {
        val entry = validEntry().edit { calculatedAmount = Money.of("-1.00") }
        val ex = assertThrows(InvoiceValidationException::class.java) { entry.validate() }
        assertEquals(InvoiceValidationError.TRAVEL_CALCULATED_AMOUNT_NEGATIVE, ex.error)
    }

    @Test
    fun `a negative employer contribution is reported as TRAVEL_EMPLOYER_CONTRIBUTION_NEGATIVE`() {
        val entry = validEntry().edit { employerContribution = Money.of("-1.00") }
        val ex = assertThrows(InvoiceValidationException::class.java) { entry.validate() }
        assertEquals(InvoiceValidationError.TRAVEL_EMPLOYER_CONTRIBUTION_NEGATIVE, ex.error)
    }
}
