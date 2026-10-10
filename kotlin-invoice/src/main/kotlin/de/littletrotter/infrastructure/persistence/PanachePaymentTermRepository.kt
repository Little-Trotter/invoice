package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.exception.DefaultPaymentTermAlreadySetException
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.repository.PaymentTermRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import de.littletrotter.domain.shared.likeFilter
import de.littletrotter.domain.shared.likePattern
import de.littletrotter.domain.shared.SortDirection
import org.hibernate.Session

private const val LT_VALUE = "lt.value"
private const val P_DESCRIPTION = "p.description"

@ApplicationScoped
class PanachePaymentTermRepository : BasePanacheRepository(), PaymentTermRepository {

    companion object {
        /** ST-B4 — the finding's named unique index (`V1__baseline_schema.sql`, a partial index
         *  on `is_default = TRUE`). */
        private const val UQ_SINGLE_DEFAULT = "uq_payment_term_single_default"
        private const val DEFAULT_SWITCH_LOCK_KEY = 7_315_001L
        private const val NO_ID = -1L

        /**
         * "name" no longer maps to a plain `payment_term` column
         * (`payment_term.name_group_id` resolves through `localized_text`); sort/filter instead
         * join the fallback-language (German) entry — the admin grid's own working language, and
         * the one `uq_language_single_fallback` guarantees exactly one row for. Native SQL, not
         * JPQL: an entity attribute path (`p.name`) no longer exists to sort/filter by, the same
         * reason [isInUse]/[usageCounts] below are already native.
         */
        private const val NAME_JOIN_SQL =
            "LEFT JOIN language fl ON fl.is_fallback = TRUE " +
                "LEFT JOIN localized_text lt ON lt.group_id = p.name_group_id AND lt.language_id = fl.id"

        /** Entity-specific allowlist, kept local to this repository and
         *  allocated once instead of on every [findAll] call. */
        private val SORT_ALLOWLIST = mapOf(
            "name" to LT_VALUE,
        )
    }

    /**
     * ST-B5 — [initializeNames] force-reads every returned term's LAZY `nameGroup`/`entries`/
     * `entry.language` while this method's own transaction is still open, so
     * [PaymentTerm.nameFor] never throws `LazyInitializationException` once the list reaches a
     * Vaadin `ComboBox` item label generator (`SenderProfileView.defaultPaymentTermField`,
     * `InvoiceDetailViewOverview.paymentTermBox` — both call `nameFor` there, well outside the
     * request that loaded this list). No managed entity's *other* relations are touched — only
     * the one lazy path the finding's renderer actually reads.
     */
    @Transactional
    override fun findAll(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        sortField: String,
        sortDir: SortDirection,
    ): List<PaymentTerm> {
        val (sortExpr, dir) = resolveSortExpr(sortField, sortDir, SORT_ALLOWLIST, LT_VALUE)
        val sql = StringBuilder("SELECT p.* FROM payment_term p $NAME_JOIN_SQL")
        if (!filterText.isNullOrBlank()) sql.append(" WHERE ${likeFilter(LT_VALUE)} OR ${likeFilter(P_DESCRIPTION)}")
        sql.append(" ORDER BY $sortExpr $dir, p.id $dir")
        // Hibernate's own Session.createNativeQuery(sql, Class<R>) returns a typed
        // NativeQuery<R> (unlike jakarta.persistence.EntityManager's raw Query for the same
        // overload), so the entity result list is List<PaymentTerm> already, no cast.
        val query = em.unwrap(Session::class.java).createNativeQuery(sql.toString(), PaymentTerm::class.java)
        if (!filterText.isNullOrBlank()) query.setParameter("f", likePattern(filterText))
        val results = query.setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
        initializeNames(results)
        return results
    }

    /** See [findAll]'s KDoc — same guard, for the two `isDefault`-lookup entry points a payment
     *  term's default value ComboBox fallback (InvoiceDetailViewOverview.paymentTermBox's
     *  binder converter) and the reference-data tab's own listing both reach. */
    private fun initializeNames(terms: List<PaymentTerm>) {
        // nameGroup (name_group_id NOT NULL) and each entry's language (language_id NOT
        // NULL, LocalizedTextEntry.language) are both schema-guaranteed non-null for any persisted
        // row, so the `?.` null arms here were unreachable — `!!` still force-initializes the lazy
        // proxy, which is this method's whole point (see its own KDoc).
        terms.forEach { term -> term.nameGroup!!.entries.forEach { it.language!!.code } }
    }

    @Transactional
    override fun findDefault(): PaymentTerm? =
        findDefaultInternal(PaymentTerm::class.java)?.also { initializeNames(listOf(it)) }

    @Transactional
    override fun findDefaults(): List<PaymentTerm> =
        findDefaultsInternal(PaymentTerm::class.java).also { initializeNames(it) }

    override fun count(filterText: String?): Long {
        val sql = StringBuilder("SELECT COUNT(*) FROM payment_term p $NAME_JOIN_SQL")
        if (!filterText.isNullOrBlank()) sql.append(" WHERE ${likeFilter(LT_VALUE)} OR ${likeFilter(P_DESCRIPTION)}")
        val query = em.createNativeQuery(sql.toString())
        if (!filterText.isNullOrBlank()) query.setParameter("f", likePattern(filterText))
        return (query.singleResult as Number).toLong()
    }

    override fun countInactive(filterText: String?): Long {
        val sql = StringBuilder("SELECT COUNT(*) FROM payment_term p $NAME_JOIN_SQL WHERE p.is_active = false")
        if (!filterText.isNullOrBlank()) sql.append(" AND (${likeFilter(LT_VALUE)} OR ${likeFilter(P_DESCRIPTION)})")
        val query = em.createNativeQuery(sql.toString())
        if (!filterText.isNullOrBlank()) query.setParameter("f", likePattern(filterText))
        return (query.singleResult as Number).toLong()
    }

    override fun findById(id: Long): PaymentTerm? = findByIdInternal(id, PaymentTerm::class.java)

    // The advisory lock closes the gap a bare UPDATE leaves: two transactions that both find
    // nothing to demote would otherwise both insert a default and the loser fails on the index.
    override fun releaseDefaultsExcept(keepId: Long?) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
            .setParameter("key", DEFAULT_SWITCH_LOCK_KEY)
            .singleResult
        val keep = keepId ?: NO_ID
        val demoted = em.createQuery(
            "SELECT t FROM PaymentTerm t WHERE t.isDefault = TRUE AND t.id <> :keep",
            PaymentTerm::class.java,
        ).setParameter("keep", keep).resultList
        em.createNativeQuery("UPDATE payment_term SET is_default = FALSE WHERE is_default = TRUE AND id <> :keep")
            .setParameter("keep", keep)
            .executeUpdate()
        // The bulk update bypasses the persistence context; reload so the change event sees the new flag.
        demoted.forEach {
            em.refresh(it)
            masterdataChanges.saved(it)
        }
    }

    // ST-B4 — flushes immediately and translates uq_payment_term_single_default into
    // DefaultPaymentTermAlreadySetException instead of a raw ConstraintViolationException; only
    // reachable when isDefault = true (every other save keeps the row out of the partial index).
    override fun save(term: PaymentTerm): PaymentTerm =
        saveEntityFlushingOrThrow(term, { term.id }, UQ_SINGLE_DEFAULT) { cause ->
            DefaultPaymentTermAlreadySetException(term.nameFor("de") ?: term.id?.toString() ?: "?", cause)
        }

    override fun delete(id: Long) { deleteById(id, PaymentTerm::class.java) }

    // One native UNION ALL over both referencing
    // tables; mirrors PanacheTaxRateRepository.isInUse/usageCounts.
    override fun isInUse(id: Long): Boolean =
        (em.createNativeQuery(
            """SELECT COUNT(*) FROM (
                 SELECT 1 FROM invoice WHERE payment_term_id = :id
                 UNION ALL
                 SELECT 1 FROM sender_profile WHERE default_payment_term_id = :id
               ) x""",
        ).setParameter("id", id).singleResult as Number).toLong() > 0

    // Each row is cast to the reified `Array<*>` (its real runtime shape), a checked
    // cast; only casting the whole `resultList` to `List<Array<Any>>` was unchecked.
    override fun usageCounts(): Map<Long, Long> =
        em.createNativeQuery(
            """SELECT payment_term_id, COUNT(*) FROM (
                 SELECT payment_term_id FROM invoice WHERE payment_term_id IS NOT NULL
                 UNION ALL
                 SELECT default_payment_term_id AS payment_term_id FROM sender_profile WHERE default_payment_term_id IS NOT NULL
               ) x GROUP BY payment_term_id""",
        ).resultList.associate { row ->
            val cols = row as Array<*>
            (cols[0] as Number).toLong() to (cols[1] as Number).toLong()
        }
}
