package de.littletrotter.application.dto

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.PerDiemRateFigures
import de.littletrotter.domain.reference.ReferenceRateValidity
import de.littletrotter.domain.reference.perDiemBaseRateFor
import de.littletrotter.domain.reference.perDiemClaimableAmount
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import java.time.LocalDate

/**
 * One row of the per-diem-rate catalogue: what the catalogue grid shows, what its editor saves,
 * and what the trip dialog's per-diem editor picks a rate from. [id] `null` marks a new row;
 * [version] is the row's optimistic-lock revision, checked on save.
 *
 * Implements [ReferenceRateValidity] so the trip dialog can resolve "which rate applies to this
 * day" ([de.littletrotter.ui.view.invoice.resolvePerDiemRateForEditing]) against the read row
 * directly, the same applicability rule the catalogue's own entity uses — never a second copy of
 * that rule.
 */
data class PerDiemRateDto(
    val id: Long? = null,
    val version: Long? = null,
    override val country: Country? = null,
    override val region: String? = null,
    val fullDayRate: Money = Money.ZERO,
    val arrivalDepartureRate: Money = Money.ZERO,
    val inlandRules: Boolean = false,
    val isDefault: Boolean = false,
    override val validFrom: LocalDate? = null,
    override val validTo: LocalDate? = null,
    override val isActive: Boolean = true,
    val source: String? = null,
) : ReferenceRateValidity {

    private val figures: PerDiemRateFigures
        get() = PerDiemRateFigures(fullDayRate, arrivalDepartureRate, inlandRules)

    /** The base rate this day is claimed at, before meal deductions. */
    fun baseRateFor(isArrivalDeparture: Boolean): Money =
        perDiemBaseRateFor(figures, isArrivalDeparture)

    /** The live claimable-amount preview a trip dialog's per-diem day shows before it is saved —
     *  the same formula [de.littletrotter.domain.reference.PerDiemRate.calculateClaimableAmount]
     *  persists a [de.littletrotter.domain.reference.TravelExpenseEntry] with. */
    fun calculateClaimableAmount(
        isArrivalDeparture: Boolean,
        deductions: PerDiemMealDeductions,
        employerContribution: Money = Money.ZERO,
    ): Money = perDiemClaimableAmount(figures, isArrivalDeparture, deductions, employerContribution)
}
