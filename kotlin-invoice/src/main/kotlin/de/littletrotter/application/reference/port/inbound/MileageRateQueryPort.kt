package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.MileageRateDto
import de.littletrotter.domain.shared.SortDirection

/** Input port for reading the mileage-rate catalogue: the catalogue tab's paged grid. */
interface MileageRateQueryPort {
    fun findMileageRateById(id: Long): MileageRateDto?

    fun findMileageRates(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "country",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<MileageRateDto>

    fun countMileageRates(filterText: String? = null): Long

    /** The header counter's "N inaktiv" half. */
    fun countInactiveMileageRates(filterText: String? = null): Long
}
