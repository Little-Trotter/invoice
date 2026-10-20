package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.repository.HotelRateRepository
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate

@ApplicationScoped
class PanacheHotelRateRepository : BasePanacheRepository(), HotelRateRepository {

    companion object {
        /** Entity-specific allowlist; the tab offers exactly one sortable column
         *  (country), which already is the query's existing default order. */
        private val SORT_ALLOWLIST = mapOf("country" to "h.country")
    }

    override fun findByCountryAndDate(country: Country, date: LocalDate, region: String?): HotelRate? =
        em.createQuery(
            """SELECT h FROM HotelRate h
               WHERE h.country = :c
               AND h.isActive = TRUE
               AND (:r IS NULL OR h.region = :r OR h.region IS NULL)
               AND (h.validFrom IS NULL OR h.validFrom <= :d)
               AND (h.validTo IS NULL OR h.validTo >= :d)
               ORDER BY h.region DESC NULLS LAST, h.validFrom DESC NULLS LAST""",
            HotelRate::class.java
        ).setParameter("c", country)
            .setParameter("r", region)
            .setParameter("d", date)
            .resultList.firstOrNull()

    override fun findAll(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        sortField: String,
        sortDir: SortDirection,
    ): List<HotelRate> {
        val (sortExpr, dir) = resolveSortExpr(sortField, sortDir, SORT_ALLOWLIST, "h.country")
        val countries = Country.matching(filterText)
        val hasFilter = !filterText.isNullOrBlank()
        // QOD2/SqlSourceToSinkFlow — see COUNTRY_PARAM's KDoc: one of two hardcoded literals,
        // selected by a boolean, never string-built from the search text.
        val where = when {
            !hasFilter -> ""
            countries.isEmpty() -> " WHERE 1 = 0"
            else -> " WHERE h.country IN :$COUNTRY_PARAM"
        }
        val q = em.createQuery(
            "SELECT h FROM HotelRate h$where ORDER BY $sortExpr $dir, h.id $dir",
            HotelRate::class.java,
        )
        if (hasFilter && countries.isNotEmpty()) q.setParameter(COUNTRY_PARAM, countries)
        return q.setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
    }

    override fun count(filterText: String?): Long {
        val countries = Country.matching(filterText)
        val hasFilter = !filterText.isNullOrBlank()
        val where = when {
            !hasFilter -> ""
            countries.isEmpty() -> " WHERE 1 = 0"
            else -> " WHERE h.country IN :$COUNTRY_PARAM"
        }
        val q = em.createQuery("SELECT COUNT(h) FROM HotelRate h$where", Long::class.java)
        if (hasFilter && countries.isNotEmpty()) q.setParameter(COUNTRY_PARAM, countries)
        return q.singleResult
    }

    override fun countInactive(filterText: String?): Long {
        val countries = Country.matching(filterText)
        val hasFilter = !filterText.isNullOrBlank()
        val extra = when {
            !hasFilter -> ""
            countries.isEmpty() -> " AND 1 = 0"
            else -> " AND h.country IN :$COUNTRY_PARAM"
        }
        val q = em.createQuery("SELECT COUNT(h) FROM HotelRate h WHERE h.isActive = false$extra", Long::class.java)
        if (hasFilter && countries.isNotEmpty()) q.setParameter(COUNTRY_PARAM, countries)
        return q.singleResult
    }

    override fun findById(id: Long): HotelRate? = findByIdInternal(id, HotelRate::class.java)

    override fun save(rate: HotelRate): HotelRate = saveEntity(rate) { rate.id }

    override fun delete(id: Long) { deleteById(id, HotelRate::class.java) }
}
