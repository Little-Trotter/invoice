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
import de.littletrotter.application.cache.port.outbound.ReferenceListCachePort
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.repository.requireReferenceTaxRate
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.LocalDate

/**
 * tax-rates slice of the former `ReferenceDataService` (split by Referenzdaten domain,
 *). Frequently-read [TaxRate] reference data is
 * served through a Valkey cache (DB 2, 24 h TTL) so that per-invoice-form DB round-trips are
 * avoided; cache is invalidated on any write.
 *
 * CACHE-FIX: the cache stores [TaxRateCacheEntry] (a plain DTO), not the [TaxRate] entity — see
 * that class's KDoc.
 */
@ApplicationScoped
class TaxRateService @Inject constructor(
    private val taxRateRepository: TaxRateRepository,
    private val cache: ReferenceListCachePort,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private const val KEY_TAX_RATES = "tax-rates"
        private const val REF_TTL_SECONDS = 86400L // 24 h
        private val log = Logger.getLogger(TaxRateService::class.java)
    }

    fun findTaxRateById(id: Long): TaxRate? = taxRateRepository.findById(id)

    /** Resolves the country's explicitly marked reference rate without cache or pagination. */
    fun findReferenceTaxRate(country: Country, date: LocalDate): TaxRate =
        taxRateRepository.requireReferenceTaxRate(country, date)

    fun findAllTaxRates(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String? = null,
        sortDir: SortDirection = SortDirection.ASC,
    ): List<TaxRate> {
        // Only cache the unfiltered, unsorted full lookup used for reference dropdowns —
        // fix-grid-sorting: an explicit sort (sortField != null) always bypasses it and reads
        // through, the same way a non-null filterText already does.
        if (pageIndex == 0 && filterText == null && sortField == null) {
            val cached = cache.getCachedReferenceList(KEY_TAX_RATES)
            if (cached != null) {
                return try {
                    // CACHE-FIX: cached as TaxRateCacheEntry (plain BigDecimal rate), not the
                    // TaxRate entity directly — see TaxRateCacheEntry's KDoc for why the entity
                    // itself never round-trips through Jackson (TaxRatePercent value-class getter
                    // mangling).
                    objectMapper.readValue<List<TaxRateCacheEntry>>(cached).map { it.toEntity() }
                } catch (e: JsonProcessingException) {
                    // readValue only throws JsonProcessingException. A stale/
                    // incompatible cached blob falls back to a fresh DB read instead of failing the
                    // whole lookup.
                    log.warnf("Failed to deserialize cached tax rates: %s", e.message)
                    null
                } ?: fetchAndCacheTaxRates(pageSize)
            }
            return fetchAndCacheTaxRates(pageSize)
        }
        return taxRateRepository.findAll(pageIndex, pageSize, filterText, sortField, sortDir)
    }

    private fun fetchAndCacheTaxRates(pageSize: Int): List<TaxRate> {
        val rates = taxRateRepository.findAll(0, pageSize, null)
        try {
            val entries = rates.map { TaxRateCacheEntry.from(it) }
            cache.cacheReferenceList(KEY_TAX_RATES, objectMapper.writeValueAsString(entries), REF_TTL_SECONDS)
        } catch (e: JsonProcessingException) {
            // cache.cacheOverview (InvoiceCachePort) never throws by contract
            // (both implementations degrade internally); the only realistic failure here is
            // writeValueAsString. Caching is best-effort, so this stays a caught fallback.
            log.warnf("Failed to cache tax rates: %s", e.message)
        }
        return rates
    }

    fun countTaxRates(filterText: String? = null): Long = taxRateRepository.count(filterText)
    fun countInactiveTaxRates(filterText: String? = null): Long = taxRateRepository.countInactive(filterText)
    fun isTaxRateInUse(id: Long): Boolean = taxRateRepository.isInUse(id)
    /** Backs the tab's "Verwendung" column and its deactivate/delete confirmation. */
    fun findTaxRateUsageCounts(): Map<Long, Long> = taxRateRepository.usageCounts()

    @Transactional
    fun saveTaxRate(rate: TaxRate): TaxRate {
        rate.validate()
        if (rate.isReference && taxRateRepository.findReferences(rate.country).any { it.id != rate.id }) {
            throw InvalidParameterException(
                "Für ${rate.country.alpha2} ist bereits ein Referenzsteuersatz hinterlegt.",
            )
        }
        if (rate.isDefault) {
            taxRateRepository.findDefaults()
                .filter { it.id != rate.id }
                .forEach { it.clearDefault(); taxRateRepository.save(it) }
        }
        val saved = taxRateRepository.save(rate)
        cache.evictReferenceList(KEY_TAX_RATES)
        return saved
    }

    @Transactional
    fun deleteTaxRate(id: Long) {
        taxRateRepository.delete(id)
        cache.evictReferenceList(KEY_TAX_RATES)
    }
}
