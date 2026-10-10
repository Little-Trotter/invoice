package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.domain.shared.SortDirection
import java.time.LocalDate

interface MileageRateRepository {
    fun findAll(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "country",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<MileageRate>
    fun count(filterText: String? = null): Long
    /** Count of rates with `isActive = false`, same [filterText] semantics as
     *  [count]; the header counter's "N inaktiv" half. */
    fun countInactive(filterText: String? = null): Long
    fun findById(id: Long): MileageRate?
    fun save(rate: MileageRate): MileageRate
    fun delete(id: Long)

    /** R2 — the actual resolution for new positions: vehicle type + travel date, never country.
     *  Only ever returns an active row. */
    fun findByVehicleTypeAndDate(vehicleType: VehicleType, date: LocalDate): MileageRate?
}
