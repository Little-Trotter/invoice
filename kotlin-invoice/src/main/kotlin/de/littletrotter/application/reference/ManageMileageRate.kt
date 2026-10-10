package de.littletrotter.application.reference

import de.littletrotter.application.dto.MileageRateDto
import de.littletrotter.application.reference.port.inbound.ManageMileageRateUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.reference.MileageRate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [ManageMileageRateUseCase] on top of [MileageRateService]; every write needs reference-data edit rights. */
@ApplicationScoped
class ManageMileageRate @Inject constructor(
    private val mileageRateService: MileageRateService,
) : ManageMileageRateUseCase {

    private val rows = CatalogueWrites(mileageRateService::findMileageRateById, MileageRate::version) { MileageRate() }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun saveMileageRate(rate: MileageRateDto): MileageRateDto {
        val target = rows.rowFor(rate.id, rate.version)
        rate.applyTo(target)
        return mileageRateService.saveMileageRate(target).toDto()
    }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun deleteMileageRate(id: Long) = mileageRateService.deleteMileageRate(id)
}
