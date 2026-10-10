/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.application.reference

import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.repository.HotelRateRepository
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * hotel-rates slice of the former `ReferenceDataService` (split by Referenzdaten
 * domain).
 */
@ApplicationScoped
class HotelRateService @Inject constructor(
    private val hotelRateRepository: HotelRateRepository,
) {
    fun findHotelRateById(id: Long): HotelRate? = hotelRateRepository.findById(id)
    fun findAllHotelRates(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "country",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<HotelRate> =
        hotelRateRepository.findAll(pageIndex, pageSize, filterText, sortField, sortDir)
    fun countHotelRates(filterText: String? = null): Long = hotelRateRepository.count(filterText)
    fun countInactiveHotelRates(filterText: String? = null): Long = hotelRateRepository.countInactive(filterText)

    @Transactional
    fun saveHotelRate(rate: HotelRate): HotelRate = hotelRateRepository.save(rate)

    @Transactional
    fun deleteHotelRate(id: Long) = hotelRateRepository.delete(id)
}
