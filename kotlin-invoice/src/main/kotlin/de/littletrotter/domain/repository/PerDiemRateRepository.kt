package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.SortDirection
import java.time.LocalDate

interface PerDiemRateRepository {
    fun findByCountryAndRegionAndDate(country: Country, region: String?, date: LocalDate): PerDiemRate?
    fun findAll(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "country",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<PerDiemRate>
    fun count(filterText: String? = null): Long
    /** Count of rates with `isActive = false`, same [filterText] semantics as
     *  [count]; the header counter's "N inaktiv" half. */
    fun countInactive(filterText: String? = null): Long
    fun findById(id: Long): PerDiemRate?
    fun findDefault(): PerDiemRate?
    fun clearAllDefaults()
    fun save(rate: PerDiemRate): PerDiemRate
    fun delete(id: Long)
}
