package de.littletrotter.domain.reference

import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PerDiemClaimablePermutationsTest {

    private fun rate(full: String, arrivalDeparture: String = full, inlandRules: Boolean = true): PerDiemRate = PerDiemRate().edit {
        fullDayRate = Money.of(full)
        arrivalDepartureRate = Money.of(arrivalDeparture)
        this.inlandRules = inlandRules
    }

    @Test
    fun `full day all meals inland`() {
        val r = rate("100.00", "100.00", inlandRules = true)
        val claim = r.calculateClaimableAmount(isArrivalDeparture = false, deductions = PerDiemMealDeductions(breakfast = true, lunch = true, dinner = true))
        // inland: deduction 100% of full => claimable = baseRate(fullDay) - 100% = 0
        assertEquals(0, claim.compareTo(BigDecimal("0.00")))
    }

    @Test
    fun `single meal breakfast international uses base rate fraction`() {
        val r = rate("120.00", "120.00", inlandRules = false)
        val claim = r.calculateClaimableAmount(isArrivalDeparture = false, deductions = PerDiemMealDeductions(breakfast = true, lunch = false, dinner = false))
        // breakfast deduction = 20% of base (fullDay) = 24.00 => claim = fullDayRate - deduction = 96.00
        assertEquals(0, claim.compareTo(BigDecimal("96.00")))
    }

    @Test
    fun `edge case one day mixed meals`() {
        val r1 = rate("50.00", "50.00", inlandRules = true)
        val c1 = r1.calculateClaimableAmount(isArrivalDeparture = false, deductions = PerDiemMealDeductions(breakfast = false, lunch = true, dinner = false))
        // lunch 40% of full = 20.00 => claim 30.00
        assertEquals(0, c1.compareTo(BigDecimal("30.00")))

        val r2 = rate("50.00", "50.00", inlandRules = false)
        val c2 = r2.calculateClaimableAmount(isArrivalDeparture = false, deductions = PerDiemMealDeductions(breakfast = false, lunch = false, dinner = true))
        // dinner 40% of base = 20.00 => claim = 30.00
        assertEquals(0, c2.compareTo(BigDecimal("30.00")))
    }
}
