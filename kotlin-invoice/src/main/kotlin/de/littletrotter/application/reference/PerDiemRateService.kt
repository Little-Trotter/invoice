/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.application.reference

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * per-diem-rates slice of the former `ReferenceDataService` (split by Referenzdaten
 * domain). [PerDiemRate] reference data is served
 * through a Valkey cache (DB 2, 24 h TTL) for the default country-sorted lookup, invalidated on
 * any write.
 */
@ApplicationScoped
class PerDiemRateService @Inject constructor(
    private val perDiemRateRepository: PerDiemRateRepository,
    private val cache: InvoiceCachePort,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        /** Default `sortField` for the country-keyed rate lookup. */
        const val SORT_FIELD_COUNTRY = "country"
        private const val KEY_PER_DIEM = "ref:per-diem-rates"
        private const val REF_TTL_SECONDS = 86400L // 24 h
        private val log = Logger.getLogger(PerDiemRateService::class.java)
    }

    fun findPerDiemRateById(id: Long): PerDiemRate? = perDiemRateRepository.findById(id)
    fun findDefaultPerDiemRate(): PerDiemRate? = perDiemRateRepository.findDefault()

    private fun isDefaultCountrySort(sortField: String, sortDir: SortDirection): Boolean =
        sortField == SORT_FIELD_COUNTRY && sortDir == SortDirection.ASC

    fun findAllPerDiemRates(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = SORT_FIELD_COUNTRY,
        sortDir: SortDirection = SortDirection.ASC,
    ): List<PerDiemRate> {
        // fix-grid-sorting — the cached blob is the ASC default order; any other sort bypasses
        // the cache and reads through, the same way a non-null filterText already does.
        if (pageIndex == 0 && filterText == null && isDefaultCountrySort(sortField, sortDir)) {
            val cached = cache.getCachedOverview(KEY_PER_DIEM)
            if (cached != null) {
                return try {
                    objectMapper.readValue<List<PerDiemRate>>(cached)
                } catch (e: JsonProcessingException) {
                    // Narrowed, same reasoning as findAllTaxRates in TaxRateService.
                    log.warnf("Failed to deserialize cached per diem rates: %s", e.message)
                    null
                } ?: fetchAndCachePerDiem(pageSize)
            }
            return fetchAndCachePerDiem(pageSize)
        }
        return perDiemRateRepository.findAll(pageIndex, pageSize, filterText, sortField, sortDir)
    }

    private fun fetchAndCachePerDiem(pageSize: Int): List<PerDiemRate> {
        val rates = perDiemRateRepository.findAll(0, pageSize, null)
        try {
            cache.cacheOverview(KEY_PER_DIEM, objectMapper.writeValueAsString(rates), REF_TTL_SECONDS)
        } catch (e: JsonProcessingException) {
            // Narrowed, same reasoning as fetchAndCacheTaxRates in TaxRateService.
            log.warnf("Failed to cache per diem rates: %s", e.message)
        }
        return rates
    }

    fun countPerDiemRates(filterText: String? = null): Long = perDiemRateRepository.count(filterText)
    fun countInactivePerDiemRates(filterText: String? = null): Long = perDiemRateRepository.countInactive(filterText)

    @Transactional
    fun savePerDiemRate(rate: PerDiemRate): PerDiemRate {
        rate.validate()
        if (rate.isDefault) {
            perDiemRateRepository.clearAllDefaults()
        }
        val saved = perDiemRateRepository.save(rate)
        cache.evictOverview(KEY_PER_DIEM)
        return saved
    }

    @Transactional
    fun deletePerDiemRate(id: Long) {
        perDiemRateRepository.delete(id)
        cache.evictOverview(KEY_PER_DIEM)
    }
}
