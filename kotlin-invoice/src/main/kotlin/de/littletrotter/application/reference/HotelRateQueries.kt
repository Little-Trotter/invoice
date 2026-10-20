package de.littletrotter.application.reference

import de.littletrotter.application.reference.port.inbound.HotelRateQueryPort
import de.littletrotter.application.dto.HotelRateDto
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [HotelRateQueryPort] on top of [HotelRateService]. */
@ApplicationScoped
class HotelRateQueries @Inject constructor(
    private val hotelRateService: HotelRateService,
) : HotelRateQueryPort {

    override fun findHotelRateById(id: Long): HotelRateDto? = hotelRateService.findHotelRateById(id)?.toDto()

    override fun findHotelRates(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection): List<HotelRateDto> =
        hotelRateService.findAllHotelRates(pageIndex, pageSize, filterText, sortField, sortDir).map { it.toDto() }

    override fun countHotelRates(filterText: String?): Long = hotelRateService.countHotelRates(filterText)

    override fun countInactiveHotelRates(filterText: String?): Long = hotelRateService.countInactiveHotelRates(filterText)
}
