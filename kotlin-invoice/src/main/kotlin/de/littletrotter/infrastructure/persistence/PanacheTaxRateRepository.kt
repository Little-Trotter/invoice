package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.shared.likePattern
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.TypedQuery
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Order
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import java.time.LocalDate
import de.littletrotter.domain.shared.FILTER_PARAM

private const val COUNTRY_ATTR = "country"

@ApplicationScoped
class PanacheTaxRateRepository : BasePanacheRepository(), TaxRateRepository {

    override fun findAll(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        sortField: String?,
        sortDir: SortDirection,
    ): List<TaxRate> {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(TaxRate::class.java)
        val t = cq.from(TaxRate::class.java)
        cq.select(t).where(*taxRateFilterPredicates(cb, t, filterText))
        cq.orderBy(*taxRateOrderBy(cb, t, sortField, sortDir))
        return bindFilter(em.createQuery(cq), filterText)
            .setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
    }

    /** fix-grid-sorting — `sortField == "name"` (the tab's one sortable column) sorts by name
     *  with an id tiebreak; any other value (in practice only `null`, unsorted) keeps the grid's
     *  pre-existing default order (`country, rate`) exactly as before. */
    private fun taxRateOrderBy(cb: CriteriaBuilder, t: Root<TaxRate>, sortField: String?, sortDir: SortDirection): Array<Order> =
        if (sortField == "name") {
            val byName = if (sortDir == SortDirection.DESC) cb.desc(t.get<Any>("name")) else cb.asc(t.get<Any>("name"))
            val byId = if (sortDir == SortDirection.DESC) cb.desc(t.get<Any>("id")) else cb.asc(t.get<Any>("id"))
            arrayOf(byName, byId)
        } else {
            arrayOf(cb.asc(t.get<Any>(COUNTRY_ATTR)), cb.asc(t.get<Any>("rate")))
        }

    override fun count(filterText: String?): Long {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(Long::class.javaObjectType)
        val t = cq.from(TaxRate::class.java)
        cq.select(cb.count(t)).where(*taxRateFilterPredicates(cb, t, filterText))
        return bindFilter(em.createQuery(cq), filterText).singleResult
    }

    override fun countInactive(filterText: String?): Long {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(Long::class.javaObjectType)
        val t = cq.from(TaxRate::class.java)
        val predicates = mutableListOf(cb.equal(t.get<Boolean>("isActive"), false))
        predicates += taxRateFilterPredicates(cb, t, filterText)
        cq.select(cb.count(t)).where(*predicates.toTypedArray())
        return bindFilter(em.createQuery(cq), filterText).singleResult
    }

    /**
     * Criteria instead of string building — every value is a bind parameter by
     * construction. Behavior unchanged: name LIKE (escaped, `\` as ESCAPE per [likePattern]'s
     * contract), plus country equality ONLY when the two-character input actually is a [Country]
     * ([countryFilterFor]) — the former unbound-parameter bug for input like "zz" cannot recur.
     */
    private fun taxRateFilterPredicates(cb: CriteriaBuilder, t: Path<TaxRate>, filterText: String?): Array<Predicate> {
        if (filterText.isNullOrBlank()) return emptyArray()
        val nameLike = cb.like(cb.lower(t.get("name")), cb.parameter(String::class.java, FILTER_PARAM), '\\')
        val country = countryFilterFor(filterText)
        return if (country != null) {
            arrayOf(cb.or(nameLike, cb.equal(t.get<Country>(COUNTRY_ATTR), country)))
        } else {
            arrayOf(nameLike)
        }
    }

    /** Binds the escaped like-pattern for [taxRateFilterPredicates]' parameter, if any. */
    private fun <T> bindFilter(q: TypedQuery<T>, filterText: String?): TypedQuery<T> {
        if (!filterText.isNullOrBlank()) q.setParameter(FILTER_PARAM, likePattern(filterText))
        return q
    }

    internal companion object {
        /** The [Country] a two-char filter input resolves to, or null for name-only filtering. */
        internal fun countryFilterFor(filterText: String?): Country? =
            filterText?.takeIf { it.length == 2 }?.let { Country.fromAlpha2(it) }
    }

    override fun findById(id: Long): TaxRate? = findByIdInternal(id, TaxRate::class.java)

    override fun findByCode(code: String): TaxRate? =
        em.createQuery("SELECT t FROM TaxRate t WHERE t.code = :code", TaxRate::class.java)
            .setParameter("code", code)
            .resultList.firstOrNull()

    override fun findValidAt(date: LocalDate): List<TaxRate> =
        em.createQuery(
            "SELECT t FROM TaxRate t WHERE (t.validFrom IS NULL OR t.validFrom <= :d) AND (t.validTo IS NULL OR t.validTo >= :d)",
            TaxRate::class.java
        ).setParameter("d", date).resultList

    override fun findDefault(): TaxRate? =
        findDefaultInternal(TaxRate::class.java)

    override fun findDefaults(): List<TaxRate> =
        findDefaultsInternal(TaxRate::class.java)

    override fun findReferences(country: Country): List<TaxRate> =
        em.createQuery(
            "SELECT t FROM TaxRate t WHERE t.country = :country AND t.isReference = true ORDER BY t.id",
            TaxRate::class.java,
        ).setParameter(COUNTRY_ATTR, country).resultList

    override fun save(rate: TaxRate): TaxRate = saveEntity(rate) { rate.id }

    override fun delete(id: Long) { deleteById(id, TaxRate::class.java) }

    // Widened from InvoiceItem-only (R7) to all three referencing tables via one
    // native UNION ALL, so a rate still hanging off an ArticleTaxVariant or an Article's own
    // default is protected exactly like one an invoice item already snapshotted.
    override fun isInUse(id: Long): Boolean =
        (em.createNativeQuery(
            """SELECT COUNT(*) FROM (
                 SELECT 1 FROM invoice_item WHERE tax_rate_id = :id
                 UNION ALL
                 SELECT 1 FROM article_tax_variant WHERE tax_rate_id = :id
                 UNION ALL
                 SELECT 1 FROM article WHERE default_tax_rate_ref_id = :id
               ) x""",
        ).setParameter("id", id).singleResult as Number).toLong() > 0

    // The same three sources as isInUse, but
    // grouped and merged across every rate in one native UNION ALL + GROUP BY instead of one
    // query per row: the tab's "Verwendung" column and its deactivate/delete confirmation both
    // read this map, never a per-row facade call.
    // Each row is cast to the reified `Array<*>` (its real runtime shape), a checked
    // cast; only casting the whole `resultList` to `List<Array<Any>>` was unchecked.
    override fun usageCounts(): Map<Long, Long> =
        em.createNativeQuery(
            """SELECT tax_rate_id, COUNT(*) FROM (
                 SELECT tax_rate_id FROM invoice_item WHERE tax_rate_id IS NOT NULL
                 UNION ALL
                 SELECT tax_rate_id FROM article_tax_variant
                 UNION ALL
                 SELECT default_tax_rate_ref_id AS tax_rate_id FROM article WHERE default_tax_rate_ref_id IS NOT NULL
               ) x GROUP BY tax_rate_id""",
        ).resultList.associate { row ->
            val cols = row as Array<*>
            (cols[0] as Number).toLong() to (cols[1] as Number).toLong()
        }
}
