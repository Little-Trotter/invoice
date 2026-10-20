package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.shared.likePattern
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate
import de.littletrotter.domain.shared.FILTER_PARAM
import de.littletrotter.domain.shared.likeFilter

@ApplicationScoped
class PanachePerDiemRateRepository : BasePanacheRepository(), PerDiemRateRepository {

    override fun findByCountryAndRegionAndDate(country: Country, region: String?, date: LocalDate): PerDiemRate? =
        em.createQuery(
            """SELECT p FROM PerDiemRate p
               WHERE p.country = :c
               AND p.isActive = TRUE
               AND (:r IS NULL OR p.region = :r OR p.region IS NULL)
               AND (p.validFrom IS NULL OR p.validFrom <= :d)
               AND (p.validTo IS NULL OR p.validTo >= :d)
               ORDER BY p.region DESC NULLS LAST""",
            PerDiemRate::class.java
        ).setParameter("c", country)
            .setParameter("r", region)
            .setParameter("d", date)
            .resultList.firstOrNull()

    /**
     * The country-or-region filter all three query methods below share, as a JPQL fragment.
     *
     * [leadingKeyword] is `WHERE` for a query that has no other condition and `AND` for one that
     * already carries one — the only difference between the three call sites, and the reason the
     * `AND` variant wraps its OR in parentheses: without them the OR would swallow the caller's
     * own condition.
     *
     * QOD2/SqlSourceToSinkFlow — see COUNTRY_PARAM's KDoc: the country half of the OR is one of
     * two hardcoded literals, selected by a boolean, never string-built from the search text; the
     * region half stays behind the [likeFilter] helper ([likePattern]'s KDoc/LikeEscapeGuardTest),
     * whose `path` argument is a call-site constant here too.
     */
    private fun filterFragment(filterText: String?, leadingKeyword: String): String {
        if (filterText.isNullOrBlank()) return ""
        val countryHalf =
            if (Country.matching(filterText).isEmpty()) "1 = 0" else "p.country IN :$COUNTRY_PARAM"
        val condition = "$countryHalf OR ${likeFilter("p.region")}"
        return if (leadingKeyword == "WHERE") " WHERE $condition" else " AND ($condition)"
    }

    /** Binds whatever [filterFragment] put into the query. A no-op for a blank filter, and it
     *  skips the country parameter when the fragment used the `1 = 0` half instead. */
    private fun bindFilter(query: jakarta.persistence.Query, filterText: String?) {
        if (filterText.isNullOrBlank()) return
        query.setParameter(FILTER_PARAM, likePattern(filterText))
        val countries = Country.matching(filterText)
        if (countries.isNotEmpty()) query.setParameter(COUNTRY_PARAM, countries)
    }

    override fun findAll(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        sortField: String,
        sortDir: SortDirection,
    ): List<PerDiemRate> {
        // The tab offers exactly one sortable column (country, the only legal
        // sortField value); its own direction applies to the secondary `region` field too, so
        // the previously-implicit ASC default order (`p.country, p.region`) plus an explicit
        // `p.id` tiebreak survives unchanged for the default call (sortField="country", ASC).
        val dir = sortDir.name
        val where = filterFragment(filterText, "WHERE")
        val q = em.createQuery("SELECT p FROM PerDiemRate p$where ORDER BY p.country $dir, p.region $dir, p.id $dir", PerDiemRate::class.java)
        bindFilter(q, filterText)
        return q.setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
    }

    override fun count(filterText: String?): Long {
        val where = filterFragment(filterText, "WHERE")
        val q = em.createQuery("SELECT COUNT(p) FROM PerDiemRate p$where", Long::class.java)
        bindFilter(q, filterText)
        return q.singleResult
    }

    override fun countInactive(filterText: String?): Long {
        val extra = filterFragment(filterText, "AND")
        val q = em.createQuery("SELECT COUNT(p) FROM PerDiemRate p WHERE p.isActive = false$extra", Long::class.java)
        bindFilter(q, filterText)
        return q.singleResult
    }

    override fun findById(id: Long): PerDiemRate? = findByIdInternal(id, PerDiemRate::class.java)

    override fun findDefault(): PerDiemRate? =
        findDefaultInternal(PerDiemRate::class.java)

    override fun clearAllDefaults() {
        em.createQuery("UPDATE PerDiemRate p SET p.isDefault = FALSE WHERE p.isDefault = TRUE")
            .executeUpdate()
    }

    override fun save(rate: PerDiemRate): PerDiemRate = saveEntity(rate) { rate.id }

    override fun delete(id: Long) { deleteById(id, PerDiemRate::class.java) }
}
