package de.littletrotter.domain.value

import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MealDeductionRulesTest {

    @Test
    fun `inland uses fullDayRate percentages`() {
        val full = Money.of("100.00")
        val base = Money.of("80.00")
        val ded = MealDeductionRules.INLAND.calculateDeduction(full, base, PerDiemMealDeductions(breakfast = true, lunch = true, dinner = false))
        // breakfast 20% of full = 20.00, lunch 40% of full = 40.00 => total 60.00
        assertEquals(Money.of("60.00"), ded)
    }

    @Test
    fun `international uses baseRate percentages`() {
        val full = Money.of("100.00")
        val base = Money.of("80.00")
        val ded = MealDeductionRules.INTERNATIONAL.calculateDeduction(full, base, PerDiemMealDeductions(breakfast = true, lunch = false, dinner = true))
        // breakfast 20% of base = 16.00, dinner 40% of base = 32.00 => 48.00
        assertEquals(Money.of("48.00"), ded)
    }

    @Test
    fun `no meals yields zero`() {
        val full = Money.of("123.45")
        val base = Money.of("67.89")
        val d1 = MealDeductionRules.INLAND.calculateDeduction(full, base, PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false))
        val d2 = MealDeductionRules.INTERNATIONAL.calculateDeduction(full, base, PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false))
        assertEquals(Money.ZERO, d1)
        assertEquals(Money.ZERO, d2)
    }

    @Test
    fun `all meals true uses correct sums`() {
        val full = Money.of("200.00")
        val base = Money.of("150.00")
        val inland = MealDeductionRules.INLAND.calculateDeduction(full, base, PerDiemMealDeductions(breakfast = true, lunch = true, dinner = true))
        val international = MealDeductionRules.INTERNATIONAL.calculateDeduction(full, base, PerDiemMealDeductions(breakfast = true, lunch = true, dinner = true))
        // inland: 20% + 40% + 40% of full = 100% of full = 200.00
        assertEquals(Money.of("200.00"), inland)
        // international: 20% + 40% + 40% of base = 100% of base = 150.00
        assertEquals(Money.of("150.00"), international)
    }

    // ── Task A: rounding traceability ──────────────────────────────────────────

    @Test
    fun `each deduction component is rounded to 2 decimals before accumulation`() {
        // Task A: a rate of 33.33 produces sub-cent precision: 33.33 * 0.20 = 6.666.
        // This must be rounded to 6.67 immediately (HALF_UP), not accumulated at
        // full precision and rounded only at the end — otherwise the printed
        // per-meal deduction (6.67) would not reproduce the total deduction.
        val base = Money.of("33.33")
        val ded = MealDeductionRules.INTERNATIONAL.calculateDeduction(
            fullDayRate = base,
            baseRate = base,
            deductions = PerDiemMealDeductions(breakfast = true, lunch = true, dinner = true),
        )
        // breakfast: 33.33 * 0.20 = 6.666 → 6.67
        // lunch:     33.33 * 0.40 = 13.332 → 13.33
        // dinner:    33.33 * 0.40 = 13.332 → 13.33
        // total:     6.67 + 13.33 + 13.33 = 33.33
        assertEquals(Money.of("33.33"), ded)
    }

    @Test
    fun `deduction components reproduce total exactly at 2 decimals`() {
        // Task A: verify that the sum of the individually-rounded per-meal
        // deductions equals the returned deduction (traceability invariant).
        val base = Money.of("59.00")
        val breakfastDed = base.percentage(java.math.BigDecimal("20"))
        val lunchDed = base.percentage(java.math.BigDecimal("40"))
        val dinnerDed = base.percentage(java.math.BigDecimal("40"))
        val expectedTotal = breakfastDed + lunchDed + dinnerDed

        val actual = MealDeductionRules.INTERNATIONAL.calculateDeduction(
            fullDayRate = base,
            baseRate = base,
            deductions = PerDiemMealDeductions(breakfast = true, lunch = true, dinner = true),
        )
        assertEquals(expectedTotal, actual)
        // Also verify the scale is exactly 2
        assertEquals(2, actual.amount.scale())
    }
}
