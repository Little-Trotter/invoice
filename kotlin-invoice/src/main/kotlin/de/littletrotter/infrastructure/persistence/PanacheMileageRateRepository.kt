package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.domain.repository.MileageRateRepository
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate
import de.littletrotter.domain.shared.SortDirection

@ApplicationScoped
class PanacheMileageRateRepository : BasePanacheRepository(), MileageRateRepository {

    companion object {
        /** Entity-specific allowlist, kept local to this repository and
         *  allocated once instead of on every [findAll] call. */
        private val SORT_ALLOWLIST = mapOf(
            "country" to "m.country",
            "vehicleType" to "m.vehicleType",
        )
    }

    override fun findByVehicleTypeAndDate(vehicleType: VehicleType, date: LocalDate): MileageRate? =
        em.createQuery(
            """SELECT m FROM MileageRate m
               WHERE m.vehicleType = :v
               AND m.isActive = TRUE
               AND (m.validFrom IS NULL OR m.validFrom <= :d)
               AND (m.validTo IS NULL OR m.validTo >= :d)
               ORDER BY m.validFrom DESC NULLS LAST""",
            MileageRate::class.java
        ).setParameter("v", vehicleType)
            .setParameter("d", date)
            .resultList.firstOrNull()

    override fun findAll(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        sortField: String,
        sortDir: SortDirection,
    ): List<MileageRate> {
        val (sortExpr, dir) = resolveSortExpr(sortField, sortDir, SORT_ALLOWLIST, "m.country")
        val countries = Country.matching(filterText)
        val hasFilter = !filterText.isNullOrBlank()
        // QOD2/SqlSourceToSinkFlow — see COUNTRY_PARAM's KDoc: one of two hardcoded literals,
        // selected by a boolean, never string-built from the search text.
        val where = when {
            !hasFilter -> ""
            countries.isEmpty() -> " WHERE 1 = 0"
            else -> " WHERE m.country IN :$COUNTRY_PARAM"
        }
        val q = em.createQuery(
            "SELECT m FROM MileageRate m$where ORDER BY $sortExpr $dir, m.id $dir",
            MileageRate::class.java,
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
            else -> " WHERE m.country IN :$COUNTRY_PARAM"
        }
        val q = em.createQuery("SELECT COUNT(m) FROM MileageRate m$where", Long::class.java)
        if (hasFilter && countries.isNotEmpty()) q.setParameter(COUNTRY_PARAM, countries)
        return q.singleResult
    }

    override fun countInactive(filterText: String?): Long {
        val countries = Country.matching(filterText)
        val hasFilter = !filterText.isNullOrBlank()
        val extra = when {
            !hasFilter -> ""
            countries.isEmpty() -> " AND 1 = 0"
            else -> " AND m.country IN :$COUNTRY_PARAM"
        }
        val q = em.createQuery("SELECT COUNT(m) FROM MileageRate m WHERE m.isActive = false$extra", Long::class.java)
        if (hasFilter && countries.isNotEmpty()) q.setParameter(COUNTRY_PARAM, countries)
        return q.singleResult
    }

    override fun findById(id: Long): MileageRate? = findByIdInternal(id, MileageRate::class.java)

    override fun save(rate: MileageRate): MileageRate = saveEntity(rate) { rate.id }

    override fun delete(id: Long) { deleteById(id, MileageRate::class.java) }
}
