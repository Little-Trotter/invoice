package de.littletrotter.application.reference

import de.littletrotter.application.dto.HotelRateDto
import de.littletrotter.application.reference.port.inbound.ManageHotelRateUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.reference.HotelRate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [ManageHotelRateUseCase] on top of [HotelRateService]; every write needs reference-data edit rights. */
@ApplicationScoped
class ManageHotelRate @Inject constructor(
    private val hotelRateService: HotelRateService,
) : ManageHotelRateUseCase {

    private val rows = CatalogueWrites(hotelRateService::findHotelRateById, HotelRate::version) { HotelRate() }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun saveHotelRate(rate: HotelRateDto): HotelRateDto {
        val target = rows.rowFor(rate.id, rate.version)
        rate.applyTo(target)
        return hotelRateService.saveHotelRate(target).toDto()
    }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun deleteHotelRate(id: Long) = hotelRateService.deleteHotelRate(id)
}
