package de.littletrotter.application.travel.port.inbound

import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions

/** Input port for previewing the claimable per-diem amount in the trip dialog. */
interface PerDiemPreviewQueryPort {
    /** The claimable amount for [rate] after meal [deductions] and an optional [employerContribution]. */
    fun previewClaimableAmount(
        rate: PerDiemRateDto,
        isArrivalDeparture: Boolean,
        deductions: PerDiemMealDeductions,
        employerContribution: Money = Money.ZERO,
    ): Money
}
