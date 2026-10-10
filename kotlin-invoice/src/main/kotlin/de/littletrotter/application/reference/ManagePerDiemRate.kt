package de.littletrotter.application.reference

import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.reference.port.inbound.ManagePerDiemRateUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.reference.PerDiemRate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [ManagePerDiemRateUseCase] on top of [PerDiemRateService]; every write needs reference-data edit rights. */
@ApplicationScoped
class ManagePerDiemRate @Inject constructor(
    private val perDiemRateService: PerDiemRateService,
) : ManagePerDiemRateUseCase {

    private val rows = CatalogueWrites(perDiemRateService::findPerDiemRateById, PerDiemRate::version) { PerDiemRate() }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun savePerDiemRate(rate: PerDiemRateDto): PerDiemRateDto {
        val target = rows.rowFor(rate.id, rate.version)
        rate.applyTo(target)
        return perDiemRateService.savePerDiemRate(target).toDto()
    }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun deletePerDiemRate(id: Long) = perDiemRateService.deletePerDiemRate(id)
}
