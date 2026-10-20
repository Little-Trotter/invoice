/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.application.reference

import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.repository.MileageRateRepository
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * mileage-rates slice of the former `ReferenceDataService` (split by Referenzdaten
 * domain).
 */
@ApplicationScoped
class MileageRateService @Inject constructor(
    private val mileageRateRepository: MileageRateRepository,
) {
    fun findMileageRateById(id: Long): MileageRate? = mileageRateRepository.findById(id)
    fun findAllMileageRates(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "country",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<MileageRate> =
        mileageRateRepository.findAll(pageIndex, pageSize, filterText, sortField, sortDir)
    fun countMileageRates(filterText: String? = null): Long = mileageRateRepository.count(filterText)
    fun countInactiveMileageRates(filterText: String? = null): Long = mileageRateRepository.countInactive(filterText)

    @Transactional
    fun saveMileageRate(rate: MileageRate): MileageRate {
        rate.validate()
        return mileageRateRepository.save(rate)
    }

    @Transactional
    fun deleteMileageRate(id: Long) = mileageRateRepository.delete(id)
}
