package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import java.math.BigDecimal
import de.littletrotter.domain.shared.SortDirection
import java.time.LocalDate

interface TaxRateRepository {
    /** @param sortField `"name"` sorts by name (the tab's one sortable column); `null` (default)
     *   keeps the unsorted grid's existing `country, rate` order. */
    fun findAll(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String? = null,
        sortDir: SortDirection = SortDirection.ASC,
    ): List<TaxRate>
    fun count(filterText: String? = null): Long
    /** Count of rates with `isActive = false`, same [filterText] semantics as
     *  [count]; the header counter's "N inaktiv" half. */
    fun countInactive(filterText: String? = null): Long
    fun findById(id: Long): TaxRate?

    /** Looks up the one rule with this stable [TaxRate.code] (e.g. a Reverse-Charge rule code) —
     *  cheaper than scanning [findAll] for a single row. Default (full scan) for test doubles;
     *  [de.littletrotter.infrastructure.persistence.PanacheTaxRateRepository] overrides it with a
     *  real query. */
    fun findByCode(code: String): TaxRate? =
        findAll(pageIndex = 0, pageSize = Int.MAX_VALUE).firstOrNull { it.code == code }

    fun findValidAt(date: LocalDate): List<TaxRate>
    fun findDefault(): TaxRate?
    fun findDefaults(): List<TaxRate>
    /** All explicitly marked reference rates for a country. Deliberately unpaged and uncached. */
    fun findReferences(country: Country): List<TaxRate> =
        findAll(pageIndex = 0, pageSize = Int.MAX_VALUE)
            .filter { it.country == country && it.isReference }

    fun save(rate: TaxRate): TaxRate
    fun delete(id: Long)

    /** R7 — whether any [de.littletrotter.domain.invoice.InvoiceItem], [de.littletrotter
     *  .domain.masterdata.ArticleTaxVariant] or [de.littletrotter.domain.masterdata.Article]
     *  default still references this rate via its live FK. Used to protect a rule's
     *  [TaxRate.code] from being changed, and to block a hard delete, once it has actually been
     *  used anywhere. */
    fun isInUse(id: Long): Boolean

    /** Usage counts across every entity that can
     *  reference a tax rate (invoice items, article tax variants, an article's own default rate),
     *  grouped in one query and keyed by tax_rate id; a rate absent from the map is unused. Backs
     *  the tab's "Verwendung" column and the deactivate/delete confirmation — never a per-row
     *  query. */
    fun usageCounts(): Map<Long, Long>
}

/** Resolve exactly one currently eligible country reference, with inclusive validity bounds. */
fun TaxRateRepository.requireReferenceTaxRate(country: Country, date: LocalDate): TaxRate {
    val references = findReferences(country)
        .filter {
            it.isActive && it.isSelectable && it.category == "S" && !it.isHotelRate &&
                it.rate.value > BigDecimal.ZERO && it.isApplicableFor(country, date, requireHotelRate = false)
        }
    return when (references.size) {
        1 -> references.single()
        0 -> throw InvalidParameterException(
            "Für ${country.alpha2} ist am $date kein gültiger Referenzsteuersatz hinterlegt.",
        )
        else -> throw InvalidParameterException(
            "Für ${country.alpha2} sind am $date mehrere Referenzsteuersätze hinterlegt.",
        )
    }
}
