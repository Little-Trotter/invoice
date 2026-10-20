package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class PerDiemRateTest {

    @Test
    fun `matchesRegion handles blanks and case-insensitive comparisons`() {
        val global = PerDiemRate().edit { region = null }
        val regional = PerDiemRate().edit { region = " ber " }

        assertTrue(global.matchesRegion("MUC"))
        assertTrue(regional.matchesRegion("BER"))
        assertTrue(regional.matchesRegion(" ber "))
        assertFalse(regional.matchesRegion("HAM"))
    }

    @Test
    fun `isApplicableFor uses normalized region matching and validity window`() {
        val rate = PerDiemRate().edit {
            country = Country.DE
            region = "ber"
            validFrom = LocalDate.of(2025, 1, 1)
            validTo = LocalDate.of(2025, 12, 31)
        }

        assertTrue(rate.isApplicableFor(Country.DE, "BER", LocalDate.of(2025, 6, 1)))
        assertFalse(rate.isApplicableFor(Country.DE, "MUC", LocalDate.of(2025, 6, 1)))
        assertFalse(rate.isApplicableFor(Country.DE, "BER", LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `R2 - an inactive row is never applicable even when country, region and date match`() {
        val deactivated = PerDiemRate().edit {
            country = Country.NL; isActive = false
            validFrom = LocalDate.of(2024, 1, 1)
        }
        assertFalse(deactivated.isApplicableFor(Country.NL, null, LocalDate.of(2026, 6, 1)))
    }

    @Test
    fun `isApplicableFor is false for a different country even when region and date match`() {
        val rate = PerDiemRate().edit { country = Country.DE; validFrom = LocalDate.of(2024, 1, 1) }
        assertFalse(rate.isApplicableFor(Country.FR, null, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `isApplicableFor is applicable from the very beginning when validFrom is unset`() {
        val rate = PerDiemRate().edit { country = Country.DE; validFrom = null }
        assertTrue(rate.isApplicableFor(Country.DE, null, LocalDate.of(2000, 1, 1)))
    }

    @Test
    fun `isApplicableFor is not yet applicable before its validFrom date`() {
        val rate = PerDiemRate().edit { country = Country.DE; validFrom = LocalDate.of(2026, 6, 1) }
        assertFalse(rate.isApplicableFor(Country.DE, null, LocalDate.of(2026, 1, 1)))
    }

    // ── validate() — the two rate fields and the validity window ────────────────

    @Test
    fun `validate rejects a negative arrival-departure rate`() {
        val rate = PerDiemRate().edit {
            country = Country.DE
            fullDayRate = Money.of("28.00")
            arrivalDepartureRate = Money.of("-1.00")
        }
        assertThrows(InvalidParameterException::class.java) { rate.validate() }
    }

    @Test
    fun `validate rejects validFrom after validTo`() {
        val rate = PerDiemRate().edit {
            country = Country.DE
            validFrom = LocalDate.of(2026, 1, 1)
            validTo = LocalDate.of(2025, 1, 1)
        }
        assertThrows(InvalidParameterException::class.java) { rate.validate() }
    }

    // ── Task A: rounding traceability ──────────────────────────────────────────

    @Test
    fun `calculateClaimableAmount is step-by-step traceable at 2 decimals`() {
        // Task A: the claimed amount must reproduce exactly from the printed
        // base rate and the printed per-meal deductions. Using a rate that
        // produces sub-cent precision when multiplied by 20%/40%.
        val rate = PerDiemRate().edit {
            fullDayRate = Money.of("33.33")
            arrivalDepartureRate = Money.of("16.67")
            inlandRules = false // international: deductions from base rate
        }

        val claimable = rate.calculateClaimableAmount(
            isArrivalDeparture = false,
            deductions = PerDiemMealDeductions(breakfast = true, lunch = true, dinner = true),
        )

        // base = 33.33, all meals deducted:
        // breakfast: 33.33 * 0.20 = 6.666 → 6.67
        // lunch:     33.33 * 0.40 = 13.332 → 13.33
        // dinner:    33.33 * 0.40 = 13.332 → 13.33
        // deduction = 6.67 + 13.33 + 13.33 = 33.33
        // claimable = 33.33 - 33.33 = 0.00
        assertEquals(0, claimable.compareTo(BigDecimal("0.00")))
        assertEquals(2, claimable.scale())
    }

    @Test
    fun `calculateClaimableAmount with employer contribution rounds deduction to 2`() {
        // Task A: employerContribution with sub-cent precision must be rounded
        // to 2 decimals before subtracting from the base rate.
        val rate = PerDiemRate().edit {
            fullDayRate = Money.of("28.00")
            arrivalDepartureRate = Money.of("14.00")
            inlandRules = true
        }

        val claimable = rate.calculateClaimableAmount(
            isArrivalDeparture = false,
            deductions = PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false),
            // employerContribution with 3 decimals — must be rounded to 0.01
            employerContribution = Money.of("0.005"),
        )

        // base = 28.00, deduction = 0.00 (no meals) + 0.005 → 0.01 (HALF_UP)
        // claimable = 28.00 - 0.01 = 27.99
        assertEquals(0, claimable.compareTo(BigDecimal("27.99")))
    }

    @Test
    fun `calculateClaimableAmount reproduces from printed rate and deduction`() {
        // Task A: traceability invariant — claimable = baseRate - deduction,
        // where both baseRate and deduction are at exactly 2 decimals.
        val rate = PerDiemRate().edit {
            fullDayRate = Money.of("59.00")
            arrivalDepartureRate = Money.of("28.00")
            inlandRules = false
        }

        val claimable = rate.calculateClaimableAmount(
            isArrivalDeparture = false,
            deductions = PerDiemMealDeductions(breakfast = true, lunch = false, dinner = true),
        )

        // Reproduce step by step:
        val baseRate = BigDecimal("59.00").setScale(2, java.math.RoundingMode.HALF_UP)
        val breakfastDed = BigDecimal("59.00").multiply(BigDecimal("0.20")).setScale(2, java.math.RoundingMode.HALF_UP) // 11.80
        val dinnerDed = BigDecimal("59.00").multiply(BigDecimal("0.40")).setScale(2, java.math.RoundingMode.HALF_UP)   // 23.60
        val totalDeduction = breakfastDed.add(dinnerDed) // 35.40
        val expected = baseRate.subtract(totalDeduction).setScale(2, java.math.RoundingMode.HALF_UP) // 23.60

        assertEquals(0, claimable.compareTo(expected))
        assertEquals(2, claimable.scale())
    }
}
