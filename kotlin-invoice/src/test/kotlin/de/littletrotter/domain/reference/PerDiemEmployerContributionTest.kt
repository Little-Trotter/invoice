package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PerDiemEmployerContributionTest {

    private fun rate(full: String, arrivalDeparture: String = full, inlandRules: Boolean = true): PerDiemRate = PerDiemRate().edit {
        fullDayRate = Money.of(full)
        arrivalDepartureRate = Money.of(arrivalDeparture)
        this.inlandRules = inlandRules
    }

    @Test
    fun `employer contribution can reduce claim to zero but not negative`() {
        val r = rate("100.00", "100.00", inlandRules = true)
        // all meals => deduction 100% of full = 100. employerContribution 10 => deductionWithContribution = 110 => claim clipped to 0
        val claim = r.calculateClaimableAmount(isArrivalDeparture = false, deductions = PerDiemMealDeductions(breakfast = true, lunch = true, dinner = true), employerContribution = Money.of("10.00"))
        assertEquals(0, claim.compareTo(BigDecimal("0.00")))
    }

    @Test
    fun `negative employer contribution throws`() {
        val r = rate("100.00", "100.00", inlandRules = true)
        assertThrows(InvalidParameterException::class.java) {
            r.calculateClaimableAmount(isArrivalDeparture = false, deductions = PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), employerContribution = Money.of("-1.00"))
        }
    }
}
