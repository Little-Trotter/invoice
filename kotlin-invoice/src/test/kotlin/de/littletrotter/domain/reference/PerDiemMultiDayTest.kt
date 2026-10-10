package de.littletrotter.domain.reference

import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PerDiemMultiDayTest {

    private fun rate(full: String, arrivalDeparture: String = full, inlandRules: Boolean = true): PerDiemRate = PerDiemRate().apply {
        fullDayRate = Money.of(full)
        arrivalDepartureRate = Money.of(arrivalDeparture)
        this.inlandRules = inlandRules
    }

    @Test
    fun `thirty day accumulation with mixed meal patterns`() {
        val r = rate("100.00", "100.00", inlandRules = true)
        val days = 30
        var expected = BigDecimal.ZERO
        var actual = BigDecimal.ZERO
        for (d in 0 until days) {
            val (b, l, di) = when (d % 3) {
                0 -> Triple(true, true, true)   // all meals => 100% deduction => claim 0
                1 -> Triple(true, false, false) // breakfast only => 20% deduction => claim 80
                else -> Triple(false, false, false) // no meals => claim 100
            }
            val claim = r.calculateClaimableAmount(isArrivalDeparture = false, deductions = PerDiemMealDeductions(breakfast = b, lunch = l, dinner = di))
            actual = actual.add(claim)
            val expectedForDay = when (d % 3) {
                0 -> BigDecimal("0.00")
                1 -> BigDecimal("80.00")
                else -> BigDecimal("100.00")
            }
            expected = expected.add(expectedForDay)
        }
        // compare totals
        assertEquals(0, actual.compareTo(expected.setScale(2)))
    }
}
