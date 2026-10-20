package de.littletrotter.domain.value

import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import java.math.BigDecimal

enum class MealDeductionRules {
    INLAND {
        override fun calculateDeduction(fullDayRate: Money, baseRate: Money, deductions: PerDiemMealDeductions): Money {
            return calculateMealDeduction(fullDayRate, deductions)
        }
    },
    INTERNATIONAL {
        override fun calculateDeduction(fullDayRate: Money, baseRate: Money, deductions: PerDiemMealDeductions): Money {
            return calculateMealDeduction(baseRate, deductions)
        }
    };

    abstract fun calculateDeduction(fullDayRate: Money, baseRate: Money, deductions: PerDiemMealDeductions): Money

    /**
     * Task A: each deduction component is a monetary amount and must be rounded to 2 decimals
     * immediately (via [Money.percentage]), so the printed per-meal deduction matches the
     * accumulated total exactly (step-by-step traceability for the end customer). Task Q3:
     * routed through [Money] instead of a hand-rolled multiply/setScale chain.
     */
    protected fun calculateMealDeduction(baseRate: Money, deductions: PerDiemMealDeductions): Money {
        var deduction = Money.ZERO
        if (deductions.breakfast) deduction += baseRate.percentage(BREAKFAST_PCT)
        if (deductions.lunch) deduction += baseRate.percentage(MEAL_PCT)
        if (deductions.dinner) deduction += baseRate.percentage(MEAL_PCT)
        return deduction
    }

    companion object {
        // percentage() takes percentage points (e.g. 19 means 19 %), so these are x100 the
        // fractions the old code multiplied by directly.
        private val BREAKFAST_PCT = BigDecimal("20")
        private val MEAL_PCT = BigDecimal("40")
    }
}
