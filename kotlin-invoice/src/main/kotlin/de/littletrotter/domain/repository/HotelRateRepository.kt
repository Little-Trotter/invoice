package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.SortDirection
import java.time.LocalDate

interface HotelRateRepository {
    /** Count of rates with `isActive = false`, same [filterText] semantics as
     *  [count]; the header counter's "N inaktiv" half. */
    fun countInactive(filterText: String? = null): Long
    /** R2 — [region] defaults to `null` so existing call sites keep compiling; region-aware and
     *  only ever returns an active row now (parity with [PerDiemRateRepository]). */
    fun findByCountryAndDate(country: Country, date: LocalDate, region: String? = null): HotelRate?
    fun findAll(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "country",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<HotelRate>
    fun count(filterText: String? = null): Long
    fun findById(id: Long): HotelRate?
    fun save(rate: HotelRate): HotelRate
    fun delete(id: Long)
}
