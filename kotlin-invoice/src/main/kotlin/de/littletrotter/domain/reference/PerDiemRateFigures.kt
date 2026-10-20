package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.value.MealDeductionRules
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import java.math.BigDecimal

/**
 * The three rate figures [PerDiemRate]/[de.littletrotter.application.dto.PerDiemRateDto] both
 * carry, bundled so the formula functions below take one parameter for them instead of three
 * (LongParameterList).
 */
data class PerDiemRateFigures(
    val fullDayRate: Money,
    val arrivalDepartureRate: Money,
    val inlandRules: Boolean,
)

/**
 * The per-diem claimable-amount formula, factored out of [PerDiemRate] so both the entity and
 * [de.littletrotter.application.dto.PerDiemRateDto] (the same row, read by the trip dialog before
 * a rate is ever persisted into a [de.littletrotter.domain.reference.TravelExpenseEntry]) compute
 * it identically — one formula, never a second copy drifting out of sync.
 */
fun perDiemBaseRateFor(figures: PerDiemRateFigures, isArrivalDeparture: Boolean): Money =
    if (isArrivalDeparture) figures.arrivalDepartureRate else figures.fullDayRate

fun perDiemMealDeduction(
    figures: PerDiemRateFigures,
    isArrivalDeparture: Boolean,
    deductions: PerDiemMealDeductions,
): Money {
    val baseRate = perDiemBaseRateFor(figures, isArrivalDeparture)
    val rules = if (figures.inlandRules) MealDeductionRules.INLAND else MealDeductionRules.INTERNATIONAL
    return rules.calculateDeduction(figures.fullDayRate, baseRate, deductions)
}

fun perDiemDeductionWithContribution(
    figures: PerDiemRateFigures,
    isArrivalDeparture: Boolean,
    deductions: PerDiemMealDeductions,
    employerContribution: Money,
): BigDecimal {
    if (employerContribution.isNegative()) {
        throw InvalidParameterException("Arbeitgeberleistung darf nicht negativ sein.")
    }
    // Rounds the total deduction to 2 decimals before it is subtracted from the base rate, so the
    // printed deduction matches the claimed amount exactly (step-by-step traceability).
    return (perDiemMealDeduction(figures, isArrivalDeparture, deductions) + employerContribution).amount
}

fun perDiemClaimableAmount(
    figures: PerDiemRateFigures,
    isArrivalDeparture: Boolean,
    deductions: PerDiemMealDeductions,
    employerContribution: Money,
): Money {
    if (employerContribution.isNegative()) {
        throw InvalidParameterException("Arbeitgeberleistung darf nicht negativ sein.")
    }
    // Base rate and deduction are each rounded to 2 decimals before the subtraction, so the
    // claimed amount reproduces exactly from the printed rate and printed deduction.
    val baseRate = perDiemBaseRateFor(figures, isArrivalDeparture)
    val deduction = Money.of(perDiemDeductionWithContribution(figures, isArrivalDeparture, deductions, employerContribution))
    val result = baseRate - deduction
    return if (result.isNegative()) Money.ZERO else result
}
