package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.SortDirection
import java.time.LocalDate

/**
 * Input port for reading the tax-rate catalogue: the catalogue tab's paged grid and usage column,
 * and the tax-rate selectors of the article form and the invoice item grid.
 */
interface TaxRateQueryPort {
    /** Every tax rate, capped at the reference-data lookup page size. */
    fun findAllTaxRates(): List<TaxRateDto>

    fun findTaxRateById(id: Long): TaxRateDto?

    /** The standard rate of [country] valid on [date]. */
    fun findReferenceTaxRate(country: Country, date: LocalDate): TaxRateDto

    /** @param sortField `"name"` sorts by name (the tab's one sortable column); `null` (default)
     *   keeps the unsorted grid's `country, rate` order. */
    fun findTaxRates(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String? = null,
        sortDir: SortDirection = SortDirection.ASC,
    ): List<TaxRateDto>

    fun countTaxRates(filterText: String? = null): Long

    /** The header counter's "N inaktiv" half. */
    fun countInactiveTaxRates(filterText: String? = null): Long

    /** Whether any invoice item, article tax variant or article default already references
     *  this rate; guards code edits and hard delete. */
    fun isTaxRateInUse(id: Long): Boolean

    /** Backs the tab's "Verwendung" column and its deactivate/delete confirmation; one grouped
     *  query, no per-row calls. */
    fun findTaxRateUsageCounts(): Map<Long, Long>
}
