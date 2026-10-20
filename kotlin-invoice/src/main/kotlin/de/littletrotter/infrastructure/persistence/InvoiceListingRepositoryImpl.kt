package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.invoice.InvoiceOverviewPartyNames
import de.littletrotter.application.invoice.InvoiceOverviewTextStatusFilter
import de.littletrotter.application.invoice.invoiceOverviewTextAndStatusPredicates
import de.littletrotter.application.invoice.port.outbound.InvoiceListingRepository
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.repository.InvoiceNumberAssignment
import de.littletrotter.domain.shared.likePattern
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.ParameterExpression
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate

@ApplicationScoped
class InvoiceListingRepositoryImpl @Inject constructor(
    private val em: EntityManager,
) : InvoiceListingRepository {

    // A JPA constructor expression projects straight into the target type: no
    // Array<Any?> row shape and no unchecked cast to get there.
    override fun findFinalizedInvoiceNumberAssignments(): List<InvoiceNumberAssignment> =
        em.createQuery(
            "SELECT NEW de.littletrotter.domain.repository.InvoiceNumberAssignment(i.id, i.invoiceNumberSequence) " +
                "FROM Invoice i " +
                "WHERE i.status = de.littletrotter.domain.invoice.InvoiceStatus.ISSUED AND i.invoiceNumberSequence IS NOT NULL",
            InvoiceNumberAssignment::class.java,
        ).resultList

    override fun findAll(pageIndex: Int, pageSize: Int): List<Invoice> =
        em.createQuery(
            "SELECT i FROM Invoice i " +
            "LEFT JOIN FETCH i.senderProfile " +
            "LEFT JOIN FETCH i.recipient " +
            "ORDER BY i.invoiceDate DESC, i.id DESC",
            Invoice::class.java
        )
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    override fun count(): Long =
        em.createQuery("SELECT COUNT(i) FROM Invoice i", Long::class.javaObjectType).singleResult

    override fun countByStatus(status: InvoiceStatus): Long =
        em.createQuery("SELECT COUNT(i) FROM Invoice i WHERE i.status = :s", Long::class.javaObjectType)
            .setParameter("s", status).singleResult

    /**
     * The overview queries are built with the Criteria API — no query string
     * exists for anything to run into; every value is a bind parameter by construction. The
     * `JOIN FETCH` aliases (cast Fetch→Path) remain the same three to-one relations as in the
     * previous JPQL, as do `DISTINCT` and the deterministic ordering.
     *
     * Tried replacing the `Fetch`→`Join` cast with `.get(attributeName)` navigation
     * (assuming Hibernate would resolve it against the already-registered fetch's
     * `NavigablePath`); `PanacheRepositoryCriteriaOverviewIT` proved that assumption wrong on this
     * Hibernate version — `.get(...)` after `.fetch(..., LEFT)` builds its own separate, default
     * (inner-shaped) join instead of reusing the fetch node, silently narrowing the WHERE-clause
     * predicates and dropping fixtures with a null association.
     *
     * [OverviewJoins]/[overviewPredicates] only ever call `.get(...)` on these three
     * (plain [Path] navigation, never a `Join`-specific method), so the fetch's own registered
     * node — Hibernate's `SqmAttributeJoin` implements both `Fetch` and `Join`/`Path` at runtime,
     * see the note above — only needs bridging to `Path<*>`, not the more specific `Join`.
     * A star-projected `Path<*>` target carries no generic argument for the JVM to leave
     * unverified, so the cast is checked (a plain `Fetch` → `Path` interface narrowing,
     * confirmed by Hibernate's node implementing both) — no suppression needed.
     */
    override fun countForOverview(filterText: String?, status: InvoiceStatus?): Long {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(Long::class.javaObjectType)
        val i = cq.from(Invoice::class.java)
        val recipient = i.join<Invoice, Any>("recipient", JoinType.LEFT)
        val seller = i.join<Invoice, Any>("selectedSeller", JoinType.LEFT)
        val senderProfile = i.join<Invoice, Any>("senderProfile", JoinType.LEFT)

        val (predicates, filterParam) = overviewPredicates(cb, i, OverviewJoins(recipient, seller, senderProfile), InvoiceOverviewTextStatusFilter(filterText, status))
        cq.select(cb.countDistinct(i)).where(*predicates.toTypedArray())

        return em.createQuery(cq).bindOverviewFilterParam(filterParam, filterText).singleResult
    }

    /** Binds the free-text like-pattern of [countForOverview] when a filter text was given. */
    private fun <T> jakarta.persistence.TypedQuery<T>.bindOverviewFilterParam(
        filterParam: ParameterExpression<String>?,
        filterText: String?,
    ): jakarta.persistence.TypedQuery<T> {
        filterParam?.let { setParameter(it, likePattern(filterText!!)) }
        return this
    }

    /** The three to-one joins [overviewPredicates] needs for its name-based free-text filter.
     *  Bundled into one type so that function stays under detekt's `LongParameterList` threshold. */
    private class OverviewJoins(val recipient: Path<*>, val seller: Path<*>, val senderProfile: Path<*>)

    /**
     * Shared WHERE builder for the overview queries. The like-pattern travels as an explicit
     * [ParameterExpression] (bound by the callers with [likePattern]-escaped input, mirroring the
     * former `likeFilter`/ESCAPE contract); the escape character stays `\` so `%`/`_` in user
     * input keep matching literally. Delegates to [invoiceOverviewDefaultOrder]'s sibling
     * [invoiceOverviewTextAndStatusPredicates] — the application-layer overview query
     * ([de.littletrotter.application.invoice.InvoiceOverviewQueryServiceImpl
     * .overviewFilterPredicates]) built the identical filterText/status predicates independently.
     */
    // S6524 — List<Predicate>, not Array<Predicate>: a Kotlin Array is always mutable, so
    // returning one directly would hand every caller a collection they could mutate underneath
    // this method. Callers spread it into the vararg `where(*predicates)` JPA Criteria API with
    // their own `.toTypedArray()` at the point of use instead.
    private fun overviewPredicates(
        cb: CriteriaBuilder,
        i: Path<Invoice>,
        joins: OverviewJoins,
        filter: InvoiceOverviewTextStatusFilter,
    ): Pair<List<Predicate>, ParameterExpression<String>?> {
        val (predicates, filterParam) = invoiceOverviewTextAndStatusPredicates(
            cb, i,
            InvoiceOverviewPartyNames(joins.recipient.get("name"), joins.seller.get("name"), joins.senderProfile.get("name")),
            filter,
        )
        return predicates to filterParam
    }

    /** Scalar projection — deliberately not `SELECT i`, see the interface method's KDoc. */
    override fun findAllIdsForRecalculation(): List<Long> =
        em.createQuery(
            "SELECT i.id FROM Invoice i WHERE i.needsRecalculation = true ORDER BY i.id",
            Long::class.javaObjectType,
        ).resultList.map { it.toLong() }
}
