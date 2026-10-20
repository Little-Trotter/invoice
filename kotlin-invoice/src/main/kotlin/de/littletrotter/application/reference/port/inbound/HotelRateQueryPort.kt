package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.HotelRateDto
import de.littletrotter.domain.shared.SortDirection

/** Input port for reading the hotel-rate catalogue: the catalogue tab's paged grid. */
interface HotelRateQueryPort {
    fun findHotelRateById(id: Long): HotelRateDto?

    fun findHotelRates(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "country",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<HotelRateDto>

    fun countHotelRates(filterText: String? = null): Long

    /** The header counter's "N inaktiv" half. */
    fun countInactiveHotelRates(filterText: String? = null): Long
}
