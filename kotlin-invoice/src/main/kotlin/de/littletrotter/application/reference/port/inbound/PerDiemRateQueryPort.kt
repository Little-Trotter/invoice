package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.reference.PerDiemRateService
import de.littletrotter.domain.shared.SortDirection

/**
 * Input port for reading the per-diem-rate catalogue: the catalogue tab's paged grid and the
 * trip dialog's per-diem selection.
 */
interface PerDiemRateQueryPort {
    /** Every per-diem rate, capped at the reference-data lookup page size. */
    fun findAllPerDiemRates(): List<PerDiemRateDto>

    fun findPerDiemRateById(id: Long): PerDiemRateDto?

    fun findDefaultPerDiemRate(): PerDiemRateDto?

    fun findPerDiemRates(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = PerDiemRateService.SORT_FIELD_COUNTRY,
        sortDir: SortDirection = SortDirection.ASC,
    ): List<PerDiemRateDto>

    fun countPerDiemRates(filterText: String? = null): Long

    /** The header counter's "N inaktiv" half. */
    fun countInactivePerDiemRates(filterText: String? = null): Long
}
