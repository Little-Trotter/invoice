package de.littletrotter.application.invoice

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.application.cache.NoopInvoiceCachePort
import de.littletrotter.application.cache.port.outbound.InvoiceCachePort
import de.littletrotter.application.dto.InvoiceCursor
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceOverviewMapper
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTexts
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.domain.invoice.isIssued
import de.littletrotter.application.invoice.port.outbound.InvoiceListingRepository
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.shared.likePattern
import de.littletrotter.shared.Money
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Join
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Order
import jakarta.persistence.criteria.ParameterExpression
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.jboss.logging.Logger
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * [InvoiceOverviewQueryService] backed by Criteria scalar projections over the [Invoice] entity.
 *
 * As a hardening measure (PO-Entscheid 29.08.2026, Core-Härtung), no query here is built from a string:
 * every filter dimension becomes a [Predicate] via [overviewFilterPredicates], `filterText` travels
 * as an explicit [ParameterExpression] bound with [likePattern]'s escape contract, and sorting is a
 * `when` over [InvoiceOverviewSortField] resolving straight to a Criteria [Expression] instead of
 * interpolating a JPQL fragment into a query string (the pre-hardening `*SortField.jpql`/
 * `toJpqlFragment` string-building path this replaced has since been deleted as dead code).
 *
 * Reads are served through a short-lived Valkey overview cache (default 30 s TTL)
 * to reduce repeated DB hits from the Vaadin grid's lazy loading.
 * Cache keys include all query parameters so different pages / filters never alias.
 * The cache degrades gracefully when Valkey is unavailable.
 *
 * Serves the invoice overview/list page only — statistics-page queries live in
 * [de.littletrotter.infrastructure.persistence.stats.InvoiceStatisticsQueryServiceImpl] (Z2 split;
 * that class moved into `infrastructure.persistence.stats` alongside the read-model
 * entities it queries).
 */
@ApplicationScoped
class InvoiceOverviewQueryServiceImpl @Inject constructor(
    private val invoiceListing: InvoiceListingRepository,
    private val em: EntityManager,
    private val cache: InvoiceCachePort,
    private val objectMapper: ObjectMapper,
) : InvoiceOverviewQueryService {

    /** Backwards-compatible constructor used by tests that don't provide cache / mapper. */
    constructor(invoiceListing: InvoiceListingRepository, em: EntityManager) :
            this(invoiceListing, em, NoopInvoiceCachePort(), ObjectMapper())

    private val log = Logger.getLogger(javaClass)

    override fun findAllOverview(): List<InvoiceOverviewDto> {
        return invoiceListing.findAll().map { inv ->
            val selectedSellerBillingAddress = inv.selectedSeller?.billingAddress
            val senderBillingAddress = inv.senderProfile?.billingAddress
            InvoiceOverviewDto(
                id = inv.id,
                invoiceNumber = inv.invoiceNumber.value.ifBlank { "" },
                typeCode = inv.typeCode,
                sellerName = inv.selectedSeller?.name ?: inv.senderProfile?.name,
                sellerCity = selectedSellerBillingAddress?.city ?: senderBillingAddress?.city,
                recipientName = inv.recipient?.name,
                grossAmount = inv.totals.grossAmount,
                // W7 — the frozen snapshot wins for an already-ISSUED invoice; a later
                // master-data edit to the live PaymentTerm must not change what the list shows.
                // Per PO decision 2 of 06.09.2026, the live fallback is resolved in this
                // invoice's own documentLanguage, same rule as resolveLivePaymentTermName below.
                paymentLabel = inv.paymentTermSnapshot?.name ?: inv.paymentTerm?.nameFor(inv.documentLanguage.code) ?: inv.paymentTermsDays.toString(),
                freeText = inv.texts.freeText,
                itemsCount = inv.itemList.size,
                invoiceDate = inv.invoiceDate,
                status = inv.status,
                validationStatus = inv.validationStatus,
                currency = inv.currency,
                reverseCharge = inv.reverseCharge,
            )
        }
    }

    override fun findOverview(pageIndex: Int, pageSize: Int, filterText: String?, status: InvoiceStatus?, reverseCharge: Boolean?): List<InvoiceOverviewDto> =
        findOverview(pageIndex, pageSize, filterText, status, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC, reverseCharge)

    /** As [findOverview] above, additionally filtered on [de.littletrotter.domain.invoice.Invoice.reverseCharge] (`null` = no filter). */
    override fun findOverview(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        status: InvoiceStatus?,
        sortField: InvoiceOverviewSortField,
        sortDir: SortDirection,
        reverseCharge: Boolean?,
    ): List<InvoiceOverviewDto> = findOverview(pageIndex, pageSize, filterText, status, InvoiceOverviewSort(sortField, sortDir), InvoiceOverviewDimensions(reverseCharge = reverseCharge))

    /** As [findOverview] above, additionally filtered on
     *  [de.littletrotter.domain.invoice.Invoice.validationStatus], recipient ("Kunde"), resolved
     *  seller ("Anbieter", `selectedSeller` or `senderProfile`), and an invoice-date range
     *  (`dateFrom`/`dateTo`, both inclusive; either or both `null` for a half-open range) — all via
     *  [dimensions]. Backs the invoice-list quick-filter chip row and the
     *  Kunde/Anbieter/Zeitraum filters. */
    override fun findOverview(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        status: InvoiceStatus?,
        sort: InvoiceOverviewSort,
        dimensions: InvoiceOverviewDimensions,
    ): List<InvoiceOverviewDto> {
        val filters = OverviewFilters(
            filterText, status, dimensions.reverseCharge, dimensions.validationStatus,
            dimensions.recipientId, dimensions.senderProfileId, dimensions.dateFrom, dimensions.dateTo,
        )
        val cacheKey = overviewCacheKey(pageIndex, pageSize, sort, filters)

        // Cache read
        val cached = cache.getCachedOverview(cacheKey)
        if (cached != null) {
            return try {
                objectMapper.readValue<List<InvoiceOverviewDto>>(cached)
            } catch (e: JsonProcessingException) {
                // AP-EXC-1: narrowed — readValue only throws JsonProcessingException. Falls back to
                // a fresh DB query instead of failing the whole overview lookup.
                log.warnf("Failed to deserialize cached overview for key=%s: %s", cacheKey, e.message)
                null
            } ?: queryOverview(pageIndex, pageSize, sort, filters)
        }

        val result = queryOverview(pageIndex, pageSize, sort, filters)

        // Cache write
        try {
            cache.cacheOverview(cacheKey, objectMapper.writeValueAsString(result))
        } catch (e: JsonProcessingException) {
            // AP-EXC-1: narrowed — cache.cacheOverview (InvoiceCachePort) never throws by contract;
            // the only realistic failure is writeValueAsString. Best-effort cache write.
            log.warnf("Failed to serialize overview for caching key=%s: %s", cacheKey, e.message)
        }

        return result
    }

    private fun queryOverview(
        pageIndex: Int,
        pageSize: Int,
        sort: InvoiceOverviewSort,
        filters: OverviewFilters,
    ): List<InvoiceOverviewDto> {
        val tupleQuery = overviewTupleQuery()
        val cb = tupleQuery.cb
        val cq = tupleQuery.cq
        val i = tupleQuery.i
        val joins = tupleQuery.joins

        val (predicates, filterParam) = overviewFilterPredicates(cb, i, joins, filters)
        cq.select(cb.tuple(overviewSelections(tupleQuery))).where(*predicates)
        cq.orderBy(overviewOrder(cb, sort, i, joins))

        val q = em.createQuery(cq)
        filterParam?.let { q.setParameter(it, likePattern(filters.filterText!!)) }
        q.firstResult = pageIndex * pageSize
        q.maxResults = pageSize

        return q.resultList.map { t -> mapRowToDto(t.toArray()) }
    }

    /**
     * The scalar projection [queryOverview]/[findOverviewAfterCursor] both select,
     * in the exact column order [InvoiceOverviewMapper.fromRow] expects (its own KDoc spells out
     * the 17-column layout this must keep matching).
     */
    private fun overviewSelections(tupleQuery: OverviewTupleQuery): List<jakarta.persistence.criteria.Selection<*>> {
        val cb = tupleQuery.cb
        val i = tupleQuery.i
        val joins = tupleQuery.joins
        val livePaymentTermName = tupleQuery.livePaymentTermName
        return listOf(
            i.get<Long>("id"),
            i.get(ATTR_INVOICE_NUMBER),
            i.get("typeCode"),
            joins.s.get<String>("name"),
            joins.sp.get<String>("name"),
            joins.r.get<String>("name"),
            i.get<InvoiceTotals>(ATTR_TOTALS).get(ATTR_GROSS_AMOUNT),
            // W7 — COALESCE prefers the frozen payment-term snapshot over the live association's
            // resolved name, so an already-ISSUED invoice's list row never drifts with a later
            // master-data edit to PaymentTerm. Per PO decision 2 of 06.09.2026, the live
            // branch is itself already resolved in the invoice's own document_language
            // ([livePaymentTermName], built in [overviewTupleQuery]): an English invoice shows its
            // payment term's English name here, intentionally.
            cb.coalesce(i.get<Any>("paymentTermSnapshot").get("name"), livePaymentTermName),
            i.get<InvoiceTexts>("texts").get<String>("freeText"),
            cb.size(i.get<MutableList<InvoiceItem>>("items")),
            i.get<LocalDate>(ATTR_INVOICE_DATE),
            i.get(ATTR_STATUS),
            i.get(ATTR_VALIDATION_STATUS),
            i.get<Int>("paymentTermsDays"),
            i.get("currency"),
            i.get<Boolean>("reverseCharge"),
            documentGenerationFailed(cb, tupleQuery.cq, i),
        )
    }

    /**
     * One correlated `CASE` per row (no per-row lookup): true for an issued invoice whose newest
     * generation job is FAILED — the same rule the detail view applies, evaluated inside the page query.
     */
    private fun documentGenerationFailed(cb: CriteriaBuilder, cq: CriteriaQuery<Tuple>, i: Root<Invoice>): Expression<Boolean> {
        val failed = cq.subquery(Long::class.javaObjectType)
        val job = failed.from(GenerationJob::class.java)
        val newest = failed.subquery(OffsetDateTime::class.java)
        val other = newest.from(GenerationJob::class.java)
        newest.select(cb.greatest(other.get("createdAt"))).where(cb.equal(other.get<Invoice>("invoice"), i))
        failed.select(job.get("id")).where(
            cb.equal(job.get<Invoice>("invoice"), i),
            cb.equal(job.get<GenerationJobStatus>("status"), GenerationJobStatus.FAILED),
            cb.equal(job.get<OffsetDateTime>("createdAt"), newest),
        )
        val issued = InvoiceStatus.entries.filter { it.isIssued }
        return cb.selectCase<Boolean>()
            .`when`(cb.and(i.get<InvoiceStatus>(ATTR_STATUS).`in`(issued), cb.exists(failed)), true)
            .otherwise(false)
    }

    private fun overviewOrder(cb: CriteriaBuilder, sort: InvoiceOverviewSort, i: Root<Invoice>, joins: OverviewJoins): List<Order> {
        val (field, dir) = sort
        val primary: Expression<*> = when (field) {
            InvoiceOverviewSortField.INVOICE_DATE -> i.get<LocalDate>(ATTR_INVOICE_DATE)
            InvoiceOverviewSortField.INVOICE_NUMBER -> i.get<Any>(ATTR_INVOICE_NUMBER)
            InvoiceOverviewSortField.SELLER_NAME -> cb.coalesce<String>(joins.s.get("name"), joins.sp.get("name"))
            InvoiceOverviewSortField.RECIPIENT_NAME -> joins.r.get<String>("name")
            InvoiceOverviewSortField.GROSS_AMOUNT -> i.get<InvoiceTotals>(ATTR_TOTALS).get<Any>(ATTR_GROSS_AMOUNT)
            InvoiceOverviewSortField.STATUS -> i.get<Any>(ATTR_STATUS)
            InvoiceOverviewSortField.ITEMS_COUNT -> cb.size(i.get<MutableList<InvoiceItem>>("items"))
            InvoiceOverviewSortField.VALIDATION_STATUS -> i.get<Any>(ATTR_VALIDATION_STATUS)
        }
        val id = i.get<Long>("id")
        return if (dir == SortDirection.ASC) listOf(cb.asc(primary), cb.asc(id)) else listOf(cb.desc(primary), cb.desc(id))
    }

    companion object {
        /** `invoice` entity attribute names, reused for every Criteria [jakarta.persistence.criteria.Path.get]. */
        private const val ATTR_GROSS_AMOUNT = "grossAmount"
        private const val ATTR_INVOICE_DATE = "invoiceDate"
        private const val ATTR_INVOICE_NUMBER = "invoiceNumber"
        private const val ATTR_STATUS = "status"
        private const val ATTR_TOTALS = "totals"
        private const val ATTR_VALIDATION_STATUS = "validationStatus"

        // Exposed mapping helper so we can unit-test scalar projection mapping without an actual DB.
        @JvmStatic
        fun mapRowToDto(row: Array<Any?>): InvoiceOverviewDto {
            return InvoiceOverviewMapper.fromRow(row)
        }

        // S107 — sort/filter dimensions bundled into the existing InvoiceOverviewSort
        // plus this file's own OverviewFilters (itself already bundling filterText/status with
        // the six dimension fields for the exact same LongParameterList reason) instead of 12
        // flat parameters; no destructuring of either object left (also fixes a
        // DestructuringDeclarationWithTooManyEntries this used to trip on OverviewFilters/
        // InvoiceOverviewDimensions' six-field shape).
        @JvmStatic
        internal fun overviewCacheKey(
            pageIndex: Int,
            pageSize: Int,
            sort: InvoiceOverviewSort,
            filters: OverviewFilters = OverviewFilters(),
        ): String = "overview:$pageIndex:$pageSize:${filters.filterText ?: ""}:${filters.status?.name ?: ""}:" +
            "${sort.field.name}:${sort.dir.name}:${filters.reverseCharge?.toString() ?: ""}:" +
            "${filters.validationStatus?.name ?: ""}:${filters.recipientId ?: ""}:${filters.senderProfileId ?: ""}:" +
            "${filters.dateFrom ?: ""}:${filters.dateTo ?: ""}"
    }

    override fun findOverviewAfterCursor(
        cursor: InvoiceCursor?,
        pageSize: Int,
        filterText: String?,
        status: InvoiceStatus?,
        dimensions: InvoiceOverviewDimensions,
    ): List<InvoiceOverviewDto> {
        val tupleQuery = overviewTupleQuery()
        val cb = tupleQuery.cb
        val cq = tupleQuery.cq
        val i = tupleQuery.i
        val joins = tupleQuery.joins

        val (basePredicates, filterParam) = overviewFilterPredicates(
            cb, i, joins,
            OverviewFilters(
                filterText, status, dimensions.reverseCharge,
                recipientId = dimensions.recipientId, senderProfileId = dimensions.senderProfileId,
                dateFrom = dimensions.dateFrom, dateTo = dimensions.dateTo,
            ),
        )
        val predicates = if (cursor == null) {
            basePredicates
        } else {
            basePredicates + cb.or(
                cb.lessThan(i.get(ATTR_INVOICE_DATE), cursor.lastDate),
                cb.and(
                    cb.equal(i.get<LocalDate>(ATTR_INVOICE_DATE), cursor.lastDate),
                    cb.lessThan(i.get("id"), cursor.lastId),
                ),
            )
        }

        cq.select(cb.tuple(overviewSelections(tupleQuery))).where(*predicates)
        cq.orderBy(invoiceOverviewDefaultOrder(cb, i))

        val q = em.createQuery(cq)
        filterParam?.let { q.setParameter(it, likePattern(filterText!!)) }
        q.maxResults = pageSize

        return q.resultList.map { t -> mapRowToDto(t.toArray()) }
    }

    override fun countOverview(filterText: String?, status: InvoiceStatus?): Long {
        return invoiceListing.countForOverview(filterText, status)
    }

    /** As above, additionally filtered on reverseCharge. Queries directly here rather than
     *  extending [InvoiceListingRepository.countForOverview] (a domain port deliberately kept free of
     *  UI-driven filter combinations — see its KDoc). */
    override fun countOverview(filterText: String?, status: InvoiceStatus?, reverseCharge: Boolean?): Long {
        if (reverseCharge == null) return countOverview(filterText, status)
        return countOverviewDistinct(OverviewFilters(filterText, status, reverseCharge))
    }

    /** As above, additionally filtered on validationStatus. */
    override fun countOverview(filterText: String?, status: InvoiceStatus?, reverseCharge: Boolean?, validationStatus: ValidationStatus?): Long {
        if (validationStatus == null) return countOverview(filterText, status, reverseCharge)
        return countOverviewDistinct(OverviewFilters(filterText, status, reverseCharge, validationStatus))
    }

    /** As above, additionally filtered on recipient/seller/date-range via [dimensions].
     *  Delegates back to the overload above when none of the new filters are set, so the
     *  existing query stays untouched for every caller that predates this one. */
    override fun countOverview(
        filterText: String?,
        status: InvoiceStatus?,
        dimensions: InvoiceOverviewDimensions,
    ): Long {
        if (dimensions.hasNoLocationFilter() && dimensions.hasNoDateFilter()) {
            return countOverview(filterText, status, dimensions.reverseCharge, dimensions.validationStatus)
        }
        return countOverviewDistinct(
            OverviewFilters(
                filterText, status, dimensions.reverseCharge, dimensions.validationStatus,
                dimensions.recipientId, dimensions.senderProfileId, dimensions.dateFrom, dimensions.dateTo,
            )
        )
    }

    private fun InvoiceOverviewDimensions.hasNoLocationFilter(): Boolean = recipientId == null && senderProfileId == null

    private fun InvoiceOverviewDimensions.hasNoDateFilter(): Boolean = dateFrom == null && dateTo == null

    private fun countOverviewDistinct(filters: OverviewFilters): Long {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(Long::class.javaObjectType)
        val i = cq.from(Invoice::class.java)
        val joins = joinParties(i)
        val (predicates, filterParam) = overviewFilterPredicates(cb, i, joins, filters)
        cq.select(cb.countDistinct(i)).where(*predicates)
        val q = em.createQuery(cq)
        filterParam?.let { q.setParameter(it, likePattern(filters.filterText!!)) }
        return q.singleResult
    }

    /**
     * One batched `GROUP BY validationStatus` query backing the invoice-list
     * quick-filter chip row's per-state counts. Honors the same text/status/reverseCharge filter
     * as [findOverview]/[countOverview] but never `validationStatus` itself, so every state's
     * count (including a currently-selected one) is always present — a second click on the same
     * chip un-toggles it, and the row would otherwise lose that chip's own count once selected.
     * A single query, never one [countOverview] round-trip per state.
     *
     * Also honors recipient/seller/date-range, so the chip counts describe what the
     * filtered list would actually contain.
     */
    override fun countOverviewGroupedByValidationStatus(
        filterText: String?,
        status: InvoiceStatus?,
        reverseCharge: Boolean?,
        recipientId: Long?,
        senderProfileId: Long?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
    ): Map<ValidationStatus, Long> {
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val i = cq.from(Invoice::class.java)
        val joins = joinParties(i)
        val (predicates, filterParam) = overviewFilterPredicates(
            cb, i, joins,
            OverviewFilters(filterText, status, reverseCharge, recipientId = recipientId, senderProfileId = senderProfileId, dateFrom = dateFrom, dateTo = dateTo),
        )
        val validationStatusPath = i.get<ValidationStatus>(ATTR_VALIDATION_STATUS)
        cq.select(cb.tuple(validationStatusPath, cb.countDistinct(i))).where(*predicates).groupBy(validationStatusPath)

        val q = em.createQuery(cq)
        filterParam?.let { q.setParameter(it, likePattern(filterText!!)) }

        return q.resultList.associate { t -> (t.get(0) as ValidationStatus) to (t.get(1) as Long) }
    }

    /**
     * The `GROUP BY status` sibling of [countOverviewGroupedByValidationStatus]. Same shape, same
     * single-query guarantee; `status` is deliberately absent from [OverviewFilters] here for the
     * reason spelled out on the interface — a selected chip has to keep its own count so it can be
     * clicked again to clear the filter.
     */
    override fun countOverviewGroupedByStatus(
        filterText: String?,
        validationStatus: ValidationStatus?,
        reverseCharge: Boolean?,
        recipientId: Long?,
        senderProfileId: Long?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
    ): Map<InvoiceStatus, Long> {
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val i = cq.from(Invoice::class.java)
        val joins = joinParties(i)
        val (predicates, filterParam) = overviewFilterPredicates(
            cb, i, joins,
            OverviewFilters(filterText, reverseCharge = reverseCharge, validationStatus = validationStatus, recipientId = recipientId, senderProfileId = senderProfileId, dateFrom = dateFrom, dateTo = dateTo),
        )
        val statusPath = i.get<InvoiceStatus>(ATTR_STATUS)
        cq.select(cb.tuple(statusPath, cb.countDistinct(i))).where(*predicates).groupBy(statusPath)

        val q = em.createQuery(cq)
        filterParam?.let { q.setParameter(it, likePattern(filterText!!)) }

        return q.resultList.associate { t -> (t.get(0) as InvoiceStatus) to (t.get(1) as Long) }
    }

    override fun getTotals(filterText: String?, status: InvoiceStatus?): Triple<Money, Money, Money> {
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val i = cq.from(Invoice::class.java)
        val joins = joinParties(i)
        val (predicates, filterParam) = overviewFilterPredicates(cb, i, joins, OverviewFilters(filterText, status))
        val totals = i.get<InvoiceTotals>(ATTR_TOTALS)
        cq.select(cb.tuple(totals.get<Any>("netAmount"), totals.get<Any>("vatAmount"), totals.get<Any>(ATTR_GROSS_AMOUNT))).where(*predicates)

        val q = em.createQuery(cq)
        filterParam?.let { q.setParameter(it, likePattern(filterText!!)) }

        return q.resultList.fold(Triple(Money.ZERO, Money.ZERO, Money.ZERO)) { acc, t ->
            val net = asMoney(t.get(0))
            val vat = asMoney(t.get(1))
            val gross = asMoney(t.get(2))
            Triple(acc.first + net, acc.second + vat, acc.third + gross)
        }
    }

    /** [getTotals] sums raw scalar projection cells — routes through [Money] instead of accumulating
     *  in [java.math.BigDecimal] directly, so the running total is produced by the same one rounding rule
     *  as every other monetary computation in the project. */
    private fun asMoney(value: Any?): Money = Money.of(StatsSqlSupport.asBigDecimal(value))

    /** The three to-one joins every overview/count/totals query needs for its name-based free-text
     *  filter and/or its `SELLER_NAME`/`RECIPIENT_NAME`/`senderProfileId`/`recipientId` predicates.
     *  Always `LEFT JOIN`ed regardless of whether a given call actually needs all three: each is a
     *  single-valued `@ManyToOne`, so an unused `LEFT JOIN` here can never fan out or drop a row —
     *  no behavioral difference from the former conditional-join JPQL string-building, only fewer
     *  branches. */
    /** The shared tuple-query prologue of both overview page readers (list page + cursor page):
     *  same root, same party joins, same LEFT paymentTerm join — one definition instead of the
     *  two copies Qodana's duplicate detector flagged. */
    private data class OverviewTupleQuery(
        val cb: CriteriaBuilder,
        val cq: CriteriaQuery<Tuple>,
        val i: Root<Invoice>,
        val joins: OverviewJoins,
        /** Per PO decision 2 of 06.09.2026, the payment term's live name, already resolved in
         *  this invoice's own `document_language` (see [resolveLivePaymentTermName]). Not the raw
         *  `PaymentTerm.name` path any more — that column no longer exists. */
        val livePaymentTermName: Expression<String>,
    )

    private fun overviewTupleQuery(): OverviewTupleQuery {
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val i = cq.from(Invoice::class.java)
        return OverviewTupleQuery(cb, cq, i, joinParties(i), resolveLivePaymentTermName(cb, i))
    }

    /**
     * Per PO decision 2 of 06.09.2026, `PaymentTerm.name` used to be a plain column
     * (`pt.get("name")`); it is now a [de.littletrotter.domain.reference.LocalizedTextGroup]
     * resolved per document language, so a Criteria join alone can no longer reach it. Two
     * `ON`-conditioned `LEFT JOIN`s reproduce [de.littletrotter.domain.masterdata.PaymentTerm
     * .nameFor]'s own resolution rule in SQL: the exact match for [i]'s own `documentLanguage`
     * wins, falling back to the fallback-language (`is_fallback = true`) entry — the same rule
     * `uq_language_single_fallback` guarantees exactly one row for. A term with no name at all
     * (should never happen, `name_group_id` is `NOT NULL`) resolves to `null`, same as a `null`
     * `paymentTerm` association.
     */
    private fun resolveLivePaymentTermName(cb: CriteriaBuilder, i: Root<Invoice>): Expression<String> {
        val pt = i.join<Invoice, Any>("paymentTerm", JoinType.LEFT)
        val nameGroup = pt.join<Any, Any>("nameGroup", JoinType.LEFT)
        val exactEntry = nameGroup.join<Any, Any>("entries", JoinType.LEFT)
        exactEntry.on(cb.equal(exactEntry.get<Any>("language").get<String>("code"), i.get<Any>("documentLanguage")))
        val fallbackEntry = nameGroup.join<Any, Any>("entries", JoinType.LEFT)
        fallbackEntry.on(cb.isTrue(fallbackEntry.get<Any>("language").get("isFallback")))
        return cb.coalesce(exactEntry.get("value"), fallbackEntry.get("value"))
    }

    private fun joinParties(i: Root<Invoice>): OverviewJoins = OverviewJoins(
        r = i.join<Invoice, Any>("recipient", JoinType.LEFT),
        s = i.join<Invoice, Any>("selectedSeller", JoinType.LEFT),
        sp = i.join<Invoice, Any>("senderProfile", JoinType.LEFT),
    )

    private class OverviewJoins(val r: Join<Invoice, *>, val s: Join<Invoice, *>, val sp: Join<Invoice, *>)

    /** Parameter object for [overviewFilterPredicates] — bundles every filter dimension
     *  [findOverview]/[countOverview]/[getTotals]/[findOverviewAfterCursor] can carry so that
     *  function (and [countOverviewDistinct]) stay under detekt's `LongParameterList` threshold.
     *  `internal` (not `private`, S107) so [overviewCacheKey]'s own unit test can build one
     *  without a 12-parameter helper signature. */
    internal data class OverviewFilters(
        val filterText: String? = null,
        val status: InvoiceStatus? = null,
        val reverseCharge: Boolean? = null,
        val validationStatus: ValidationStatus? = null,
        val recipientId: Long? = null,
        val senderProfileId: Long? = null,
        val dateFrom: LocalDate? = null,
        val dateTo: LocalDate? = null,
    ) {
        /** Maps the six dimension fields to the shared predicate parameter object; the
         *  `filterText`/`status` prefix travels through [invoiceOverviewTextAndStatusPredicates]. */
        fun toDimensions() = InvoiceOverviewDimensions(
            reverseCharge = reverseCharge,
            validationStatus = validationStatus,
            recipientId = recipientId,
            senderProfileId = senderProfileId,
            dateFrom = dateFrom,
            dateTo = dateTo,
        )
    }

    /**
     * Every filter dimension in [OverviewFilters], as [Predicate]s. `filterText` is
     * the one free-text dimension and travels as a [ParameterExpression] bound by the caller with
     * [likePattern]'s escape contract. The `filterText`/`status` predicates themselves are the
     * common prefix shared with [de.littletrotter.infrastructure.persistence
     * .PanacheInvoiceRepository.overviewPredicates] — see [invoiceOverviewTextAndStatusPredicates].
     */
    private fun overviewFilterPredicates(
        cb: CriteriaBuilder,
        i: Root<Invoice>,
        joins: OverviewJoins,
        filters: OverviewFilters,
    ): Pair<Array<Predicate>, ParameterExpression<String>?> {
        val (textAndStatus, filterParam) = invoiceOverviewTextAndStatusPredicates(
            cb, i,
            InvoiceOverviewPartyNames(joins.r.get("name"), joins.s.get("name"), joins.sp.get("name")),
            InvoiceOverviewTextStatusFilter(filters.filterText, filters.status),
        )
        val predicates = textAndStatus + invoiceOverviewDimensionPredicates(
            cb, i,
            InvoiceOverviewDimensionPaths.of(joins.r, joins.s, joins.sp),
            filters.toDimensions(),
        )
        return predicates.toTypedArray() to filterParam
    }
}
