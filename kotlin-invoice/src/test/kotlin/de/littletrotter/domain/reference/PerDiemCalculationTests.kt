package de.littletrotter.domain.reference

import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

class PerDiemCalculationTests {

    private fun bd(v: String) = BigDecimal(v)

    private fun expectedClaimable(
        fullDayRate: BigDecimal,
        arrivalDepartureRate: BigDecimal,
        inlandRules: Boolean,
        isArrivalDeparture: Boolean,
        breakfast: Boolean,
        lunch: Boolean,
        dinner: Boolean,
        employerContribution: BigDecimal = BigDecimal.ZERO,
    ): BigDecimal {
        val baseRate = if (isArrivalDeparture) arrivalDepartureRate else fullDayRate
        val breakfastPct = bd("0.20")
        val mealPct = bd("0.40")

        val deduction = if (inlandRules) {
            var d = BigDecimal.ZERO
            if (breakfast) d = d.add(fullDayRate.multiply(breakfastPct))
            if (lunch) d = d.add(fullDayRate.multiply(mealPct))
            if (dinner) d = d.add(fullDayRate.multiply(mealPct))
            d
        } else {
            var d = BigDecimal.ZERO
            if (breakfast) d = d.add(baseRate.multiply(breakfastPct))
            if (lunch) d = d.add(baseRate.multiply(mealPct))
            if (dinner) d = d.add(baseRate.multiply(mealPct))
            d
        }

        val totalDeduction = deduction.add(employerContribution)
        val result = baseRate.subtract(totalDeduction).setScale(2, RoundingMode.HALF_UP)
        return if (result < BigDecimal.ZERO) BigDecimal.ZERO.setScale(2) else result
    }

    @Test
    fun `one day various meal combinations inland and international`() {
        val full = bd("100.00")
        val arr = bd("50.00")

        val pdInland = PerDiemRate().edit {
            country = de.littletrotter.shared.country.Country.DE
            fullDayRate = Money.of(full)
            arrivalDepartureRate = Money.of(arr)
            inlandRules = true
        }
        val pdIntl = PerDiemRate().edit {
            country = de.littletrotter.shared.country.Country.DE
            fullDayRate = Money.of(full)
            arrivalDepartureRate = Money.of(arr)
            inlandRules = false
        }

        // iterate all combinations of breakfast/lunch/dinner
        for (b in listOf(true, false)) {
            for (l in listOf(true, false)) {
                for (d in listOf(true, false)) {
                    // full day
                    val claimInland = pdInland.calculateClaimableAmount(false, PerDiemMealDeductions(b, l, d), Money.ZERO)
                    val expInland = expectedClaimable(full, arr, true, false, b, l, d)
                    assertEquals(0, claimInland.compareTo(expInland), "inland mismatch for b=$b l=$l d=$d")

                    val claimIntl = pdIntl.calculateClaimableAmount(false, PerDiemMealDeductions(b, l, d), Money.ZERO)
                    val expIntl = expectedClaimable(full, arr, false, false, b, l, d)
                    assertEquals(0, claimIntl.compareTo(expIntl), "intl full-day mismatch for b=$b l=$l d=$d")

                    // arrival/departure
                    val claimInlandAD = pdInland.calculateClaimableAmount(true, PerDiemMealDeductions(b, l, d), Money.ZERO)
                    val expInlandAD = expectedClaimable(full, arr, true, true, b, l, d)
                    assertEquals(0, claimInlandAD.compareTo(expInlandAD), "inland AD mismatch for b=$b l=$l d=$d")

                    val claimIntlAD = pdIntl.calculateClaimableAmount(true, PerDiemMealDeductions(b, l, d), Money.ZERO)
                    val expIntlAD = expectedClaimable(full, arr, false, true, b, l, d)
                    assertEquals(0, claimIntlAD.compareTo(expIntlAD), "intl AD mismatch for b=$b l=$l d=$d")
                }
            }
        }
    }

    @Test
    fun `invoice includes at least two travel expense types and sums claimable`() {
        val pd = PerDiemRate().edit {
            country = de.littletrotter.shared.country.Country.DE
            fullDayRate = Money.of(bd("80.00"))
            arrivalDepartureRate = Money.of(bd("30.00"))
            inlandRules = false
        }

        val t1 = TravelExpenseEntry().edit {
            travelDate = LocalDate.now()
            isArrivalDeparture = false
            breakfastDeduction = true
            lunchDeduction = false
            dinnerDeduction = true
        }
        val t2 = TravelExpenseEntry().edit {
            travelDate = LocalDate.now().plusDays(1)
            isArrivalDeparture = true
            breakfastDeduction = false
            lunchDeduction = true
            dinnerDeduction = false
        }

        val claim1 = pd.calculateClaimableAmount(t1.isArrivalDeparture, PerDiemMealDeductions(t1.breakfastDeduction, t1.lunchDeduction, t1.dinnerDeduction))
        val claim2 = pd.calculateClaimableAmount(t2.isArrivalDeparture, PerDiemMealDeductions(t2.breakfastDeduction, t2.lunchDeduction, t2.dinnerDeduction))

        val expected1 = expectedClaimable(pd.fullDayRate.amount, pd.arrivalDepartureRate.amount, pd.inlandRules, t1.isArrivalDeparture, t1.breakfastDeduction, t1.lunchDeduction, t1.dinnerDeduction)
        val expected2 = expectedClaimable(pd.fullDayRate.amount, pd.arrivalDepartureRate.amount, pd.inlandRules, t2.isArrivalDeparture, t2.breakfastDeduction, t2.lunchDeduction, t2.dinnerDeduction)

        assertEquals(0, claim1.compareTo(expected1))
        assertEquals(0, claim2.compareTo(expected2))
        // At least two types present
        assertTrue(t1.isArrivalDeparture != t2.isArrivalDeparture)
        assertEquals(0, claim1.add(claim2).compareTo(expected1.add(expected2)))
    }

    @Test
    fun `one month mixed meal patterns sum matches expected for inland and international rates`() {
        val days = 30
        val full = bd("85.00")
        val arr = bd("40.00")

        val pdInland = PerDiemRate().edit {
            country = de.littletrotter.shared.country.Country.DE
            fullDayRate = Money.of(full)
            arrivalDepartureRate = Money.of(arr)
            inlandRules = true
        }
        val pdIntl = PerDiemRate().edit {
            country = de.littletrotter.shared.country.Country.DE
            fullDayRate = Money.of(full)
            arrivalDepartureRate = Money.of(arr)
            inlandRules = false
        }

        var sumInland = BigDecimal.ZERO.setScale(2)
        var sumIntl = BigDecimal.ZERO.setScale(2)
        var expectedInland = BigDecimal.ZERO.setScale(2)
        var expectedIntl = BigDecimal.ZERO.setScale(2)

        var date = LocalDate.now()
        for (i in 0 until days) {
            val b = i % 2 == 0
            val l = i % 3 == 0
            val d = i % 5 == 0
            val isAD = i % 7 == 0 // some days are arrival/departure

            val cInland = pdInland.calculateClaimableAmount(isAD, PerDiemMealDeductions(b, l, d))
            val cIntl = pdIntl.calculateClaimableAmount(isAD, PerDiemMealDeductions(b, l, d))

            sumInland = sumInland.add(cInland)
            sumIntl = sumIntl.add(cIntl)

            val eInland = expectedClaimable(full, arr, true, isAD, b, l, d)
            val eIntl = expectedClaimable(full, arr, false, isAD, b, l, d)

            expectedInland = expectedInland.add(eInland)
            expectedIntl = expectedIntl.add(eIntl)

            date = date.plusDays(1)
        }

        assertEquals(0, sumInland.compareTo(expectedInland))
        assertEquals(0, sumIntl.compareTo(expectedIntl))
    }
}
