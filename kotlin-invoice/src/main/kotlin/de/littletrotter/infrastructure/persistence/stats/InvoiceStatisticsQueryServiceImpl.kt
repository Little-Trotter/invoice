package de.littletrotter.infrastructure.persistence.stats

import de.littletrotter.application.dto.ArticleTotals
import de.littletrotter.application.dto.DailyTrend
import de.littletrotter.application.dto.MonthlyTotals
import de.littletrotter.application.dto.MonthlyTrend
import de.littletrotter.application.dto.QuarterlyTotals
import de.littletrotter.application.dto.RecipientTotals
import de.littletrotter.application.dto.SenderTotals
import de.littletrotter.application.dto.StatusDistribution
import de.littletrotter.application.dto.YearlyTotals
import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.application.invoice.StatsSqlSupport
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.FILTER_PARAM
import de.littletrotter.domain.shared.likePattern
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import jakarta.persistence.Tuple
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.ParameterExpression
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import jakarta.persistence.criteria.Selection
import java.math.BigDecimal

/**
 * [InvoiceStatisticsQueryPort] backed by Criteria queries over the read-only `v_stat_*` view
 * entities in `de.littletrotter.infrastructure.persistence.stats` (see that package's KDoc for the
 * view-to-entity mapping, sourced from the `v_stat_*` view hierarchy in
 * `V1__baseline_schema.sql`).
 *
 * No query here is built from a string
 * anymore: every filter dimension becomes a [Predicate] via [statsPredicates], and the one
 * free-text dimension ([StatisticsFilter.filterText]) travels as an explicit
 * [jakarta.persistence.criteria.ParameterExpression], bound only through [likePattern]'s
 * escape contract (same `\`-escape/`ESCAPE` guarantee as [de.littletrotter.domain.shared
 * .likePattern]'s KDoc). `StatsWhereClauseBuilder`, the former native-SQL `WHERE`-string builder,
 * is gone — there is no SQL string left for it to build.
 *
 * Z2 split out of `InvoiceOverviewQueryServiceImpl`, which used to serve both this page and the
 * invoice overview/list page.
 *
 * This class (and the `v_stat_*` entities/ids it queries) lives in
 * `infrastructure.persistence.stats`, not `application`: it is the one piece of the statistics
 * read path that actually touches the JPA read-model entities, so it is the adapter implementing
 * the [InvoiceStatisticsQueryPort] port defined in `application.invoice.port.inbound`.
 * Every caller — `KpiRow`, `RevenueMatrix`, `TrendChart`, `StatusCardsRow`, the Karibu-tested
 * statistics view — depends on that port only and never sees a stats entity; this file is the
 * sole exception, by construction.
 */
@ApplicationScoped
class InvoiceStatisticsQueryServiceImpl @Inject constructor(
    private val em: EntityManager,
) : InvoiceStatisticsQueryPort {

    /** The sum quartet every pre-aggregated `v_stat_*` period query selects — one definition
     *  instead of the yearly/monthly/quarterly copies Qodana's duplicate detector flagged. */
    private data class TotalsSums(
        val net: Expression<BigDecimal>,
        val vat: Expression<BigDecimal>,
        val gross: Expression<BigDecimal>,
        val count: Expression<Long>,
    )

    private fun totalsSums(cb: CriteriaBuilder, root: Root<*>): TotalsSums = TotalsSums(
        net = cb.sum(root.get(ATTR_NET_AMOUNT)),
        vat = cb.sum(root.get(ATTR_VAT_AMOUNT)),
        gross = cb.sum(root.get(ATTR_GROSS_AMOUNT)),
        count = cb.sum(root.get(ATTR_INVOICE_COUNT)),
    )

    override fun getYearlyTotals(filter: StatisticsFilter): List<YearlyTotals> {
        // No year/month/quarter dimension here — yearly totals is grouped by year itself.
        val scoped = filter.copy(year = null, month = null, quarter = null)
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val root = cq.from(StatYearly::class.java)
        val (predicates, filterParam) = statsPredicates(cb, root, scoped, hasInvoiceNumber = false)
        val sums = totalsSums(cb, root)
        val currencyPath = root.get<Int>(ATTR_CURRENCY)
        cq.select(cb.tuple(
            root.get<Int>("year").alias("year"),
            currencyPath.alias(ATTR_CURRENCY),
            sums.net.alias(ATTR_NET_AMOUNT),
            sums.vat.alias(ATTR_VAT_AMOUNT),
            sums.gross.alias(ATTR_GROSS_AMOUNT),
            sums.count.alias(ATTR_INVOICE_COUNT),
            cb.quot(sums.gross, cb.nullif(sums.count, 0L)).alias("avgInvoiceAmount"),
        ))
        cq.where(*predicates)
        cq.groupBy(root.get<Int>("year"), currencyPath)
        cq.orderBy(cb.desc(root.get<Int>("year")))
        val q = em.createQuery(cq)
        bindFilterParam(q, filterParam, scoped)
        return q.resultList.map { t ->
            YearlyTotals(
                year = (t.get("year") as Number).toInt(),
                netAmount = Money.of(asBigDecimal(t.get(ATTR_NET_AMOUNT))),
                vatAmount = Money.of(asBigDecimal(t.get(ATTR_VAT_AMOUNT))),
                grossAmount = Money.of(asBigDecimal(t.get(ATTR_GROSS_AMOUNT))),
                invoiceCount = (t.get(ATTR_INVOICE_COUNT) as? Number)?.toLong() ?: 0L,
                avgInvoiceAmount = Money.of(asBigDecimal(t.get("avgInvoiceAmount"))),
                currency = currencyOf(t.get(ATTR_CURRENCY)),
            )
        }
    }

    override fun getMonthlyTotals(year: Int, filter: StatisticsFilter): List<MonthlyTotals> =
        periodTotalTuples(StatMonthly::class.java, ATTR_MONTH, year, filter).map { t ->
            MonthlyTotals(
                year = (t.get("year") as Number).toInt(),
                month = (t.get(ATTR_MONTH) as Number).toInt(),
                netAmount = Money.of(asBigDecimal(t.get(ATTR_NET_AMOUNT))),
                vatAmount = Money.of(asBigDecimal(t.get(ATTR_VAT_AMOUNT))),
                grossAmount = Money.of(asBigDecimal(t.get(ATTR_GROSS_AMOUNT))),
                invoiceCount = (t.get(ATTR_INVOICE_COUNT) as? Number)?.toLong() ?: 0L,
                currency = currencyOf(t.get(ATTR_CURRENCY)),
            )
        }

    /**
     * The single monthly/quarterly period query — only the view entity and its period column
     * differ between the two breakdowns. Month/quarter aren't filter dimensions here: the whole
     * year's breakdown is returned, the required [year] param picks the year (not filter.year).
     *
     * money-currency-display R6 — `currency` joins the `GROUP BY` tuple exactly like the period
     * column: a period with two invoice currencies must come back as two rows, one per currency,
     * never a single row silently summed across them.
     */
    private fun periodTotalTuples(entity: Class<*>, periodColumn: String, year: Int, filter: StatisticsFilter): List<Tuple> {
        val yearFilter = filter.copy(year = year, month = null, quarter = null)
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val root = cq.from(entity)
        val (predicates, filterParam) = statsPredicates(cb, root, yearFilter, hasInvoiceNumber = false)
        val sums = totalsSums(cb, root)
        val currencyPath = root.get<Int>(ATTR_CURRENCY)
        cq.select(cb.tuple(
            root.get<Int>("year").alias("year"),
            root.get<Int>(periodColumn).alias(periodColumn),
            currencyPath.alias(ATTR_CURRENCY),
            sums.net.alias(ATTR_NET_AMOUNT),
            sums.vat.alias(ATTR_VAT_AMOUNT),
            sums.gross.alias(ATTR_GROSS_AMOUNT),
            sums.count.alias(ATTR_INVOICE_COUNT),
        ))
        cq.where(*predicates)
        cq.groupBy(root.get<Int>("year"), root.get<Int>(periodColumn), currencyPath)
        cq.orderBy(cb.asc(root.get<Int>(periodColumn)))
        val q = em.createQuery(cq)
        bindFilterParam(q, filterParam, yearFilter)
        return q.resultList
    }

    override fun getQuarterlyTotals(year: Int, filter: StatisticsFilter): List<QuarterlyTotals> =
        periodTotalTuples(StatQuarterly::class.java, ATTR_QUARTER, year, filter).map { t ->
            QuarterlyTotals(
                year = (t.get("year") as Number).toInt(),
                quarter = (t.get(ATTR_QUARTER) as Number).toInt(),
                netAmount = Money.of(asBigDecimal(t.get(ATTR_NET_AMOUNT))),
                vatAmount = Money.of(asBigDecimal(t.get(ATTR_VAT_AMOUNT))),
                grossAmount = Money.of(asBigDecimal(t.get(ATTR_GROSS_AMOUNT))),
                invoiceCount = (t.get(ATTR_INVOICE_COUNT) as? Number)?.toLong() ?: 0L,
                currency = currencyOf(t.get(ATTR_CURRENCY)),
            )
        }

    override fun getRecipientTotals(limit: Int, filter: StatisticsFilter): List<RecipientTotals> {
        // Ranking is grouped by recipient — filtering by recipientId would defeat the point,
        // so that field is never applied here (only senderId narrows the ranking).
        val scoped = filter.copy(recipientId = null)
        val src = invoiceRankingSource(scoped.month, scoped.quarter, scoped.filterText)
        val tuples = rankingTuples(
            src, scoped, limit,
            RankingAttributes(idAttr = ATTR_RECIPIENT_ID, nameAttr = "recipientName", includeNetAmount = true),
        )
        return tuples.map { t ->
            RecipientTotals(
                recipientId = longOrZero(t.get("id")),
                recipientName = nameOrUnbekannt(t.get("name")),
                grossAmount = Money.of(asBigDecimal(t.get(ATTR_GROSS_AMOUNT))),
                invoiceCount = longOrZero(t.get(ATTR_INVOICE_COUNT)),
                netAmount = Money.of(asBigDecimal(t.get(ATTR_NET_AMOUNT))),
                currency = currencyOf(t.get(ATTR_CURRENCY)),
            )
        }
    }

    override fun getSenderTotals(limit: Int, filter: StatisticsFilter): List<SenderTotals> {
        // Mirror of getRecipientTotals: grouped by sender, so senderId is never applied — only
        // recipientId narrows the ranking.
        val scoped = filter.copy(senderId = null)
        val src = invoiceRankingSource(scoped.month, scoped.quarter, scoped.filterText)
        val tuples = rankingTuples(
            src, scoped, limit,
            RankingAttributes(idAttr = ATTR_SENDER_ID, nameAttr = "senderName", includeNetAmount = false),
        )
        return tuples.map { t ->
            SenderTotals(
                senderId = longOrZero(t.get("id")),
                senderName = nameOrUnbekannt(t.get("name")),
                grossAmount = Money.of(asBigDecimal(t.get(ATTR_GROSS_AMOUNT))),
                invoiceCount = longOrZero(t.get(ATTR_INVOICE_COUNT)),
                currency = currencyOf(t.get(ATTR_CURRENCY)),
            )
        }
    }

    override fun getArticleTotals(limit: Int, filter: StatisticsFilter): List<ArticleTotals> {
        val src = itemRankingSource(filter.month, filter.quarter, filter.filterText)
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val root = cq.from(src.entityClass)
        val (predicates, filterParam) = statsPredicates(cb, root, filter, hasInvoiceNumber = src.hasInvoiceNumber, hasDescription = true)
        val sumNet = cb.sum(root.get<BigDecimal>(ATTR_NET_AMOUNT))
        val sumQty = cb.sum(root.get<BigDecimal>(ATTR_QUANTITY))
        val descPath = root.get<String>(ATTR_DESCRIPTION)
        val currencyPath = root.get<Int>(ATTR_CURRENCY)
        cq.select(cb.tuple(
            descPath.alias(ATTR_DESCRIPTION),
            currencyPath.alias(ATTR_CURRENCY),
            sumNet.alias(ATTR_NET_AMOUNT),
            sumQty.alias(ATTR_QUANTITY),
            cb.quot(sumNet, cb.nullif(sumQty, BigDecimal.ZERO)).alias("avgPrice"),
        ))
        cq.where(*predicates)
        cq.groupBy(descPath, currencyPath)
        cq.orderBy(cb.desc(sumNet))
        val q = em.createQuery(cq)
        bindFilterParam(q, filterParam, filter)
        q.maxResults = limit
        return q.resultList.map { t ->
            ArticleTotals(
                description = t.get(ATTR_DESCRIPTION) as String,
                netAmount = Money.of(asBigDecimal(t.get(ATTR_NET_AMOUNT))),
                itemCount = (t.get(ATTR_QUANTITY) as Number).toLong(),
                avgPrice = Money.of(asBigDecimal(t.get("avgPrice"))),
                currency = currencyOf(t.get(ATTR_CURRENCY)),
            )
        }
    }

    override fun getMonthlyTrend(year: Int, filter: StatisticsFilter): List<MonthlyTrend> {
        // Same as getMonthlyTotals: no month/quarter filter dimension for a full-year trend.
        val yearFilter = filter.copy(year = year, month = null, quarter = null)
        return trendTuples(StatMonthly::class.java, listOf("year", ATTR_MONTH), yearFilter).map { t ->
            MonthlyTrend(
                year = (t.get("year") as Number).toInt(),
                month = (t.get(ATTR_MONTH) as Number).toInt(),
                netAmount = Money.of(asBigDecimal(t.get(ATTR_NET_AMOUNT))),
                grossAmount = Money.of(asBigDecimal(t.get(ATTR_GROSS_AMOUNT))),
                currency = currencyOf(t.get(ATTR_CURRENCY)),
            )
        }
    }

    override fun getDailyTrend(year: Int, month: Int, filter: StatisticsFilter): List<DailyTrend> {
        // year/month are selected by the required params, not the filter's own year/month
        // fields; quarter has no meaning once a specific month is picked, so it's stripped too.
        val periodFilter = filter.copy(year = year, month = month, quarter = null)
        return trendTuples(StatDaily::class.java, listOf("year", ATTR_MONTH, "day"), periodFilter).map { t ->
            DailyTrend(
                year = (t.get("year") as Number).toInt(),
                month = (t.get(ATTR_MONTH) as Number).toInt(),
                day = (t.get("day") as Number).toInt(),
                netAmount = Money.of(asBigDecimal(t.get(ATTR_NET_AMOUNT))),
                grossAmount = Money.of(asBigDecimal(t.get(ATTR_GROSS_AMOUNT))),
                currency = currencyOf(t.get(ATTR_CURRENCY)),
            )
        }
    }

    /**
     * DuplicatedCode — the net/gross trend query shared by [getMonthlyTrend]
     * (`periodColumns` = year, month) and [getDailyTrend] (year, month, day): every entry of
     * [periodColumns] plus currency in the SELECT/GROUP BY, ordered ascending by the last (most
     * granular) period column. Only the entity, period columns and filter differ between the two
     * breakdowns — the trend counterpart of [periodTotalTuples] (that one carries the VAT/count/avg
     * quartet a trend chart never shows).
     */
    private fun trendTuples(entity: Class<*>, periodColumns: List<String>, filter: StatisticsFilter): List<Tuple> {
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val root = cq.from(entity)
        val (predicates, filterParam) = statsPredicates(cb, root, filter, hasInvoiceNumber = false)
        val sumNet = cb.sum(root.get<BigDecimal>(ATTR_NET_AMOUNT))
        val sumGross = cb.sum(root.get<BigDecimal>(ATTR_GROSS_AMOUNT))
        val currencyPath = root.get<Int>(ATTR_CURRENCY)
        val periodPaths: List<Path<Int>> = periodColumns.map { root.get(it) }

        val selections = mutableListOf<Selection<*>>()
        periodPaths.forEachIndexed { idx, path -> selections += path.alias(periodColumns[idx]) }
        selections += currencyPath.alias(ATTR_CURRENCY)
        selections += sumNet.alias(ATTR_NET_AMOUNT)
        selections += sumGross.alias(ATTR_GROSS_AMOUNT)
        cq.select(cb.tuple(selections))

        cq.where(*predicates)
        val groupByExprs = mutableListOf<Expression<*>>()
        periodPaths.forEach { groupByExprs += it }
        groupByExprs += currencyPath
        cq.groupBy(groupByExprs)
        cq.orderBy(cb.asc(periodPaths.last()))

        val q = em.createQuery(cq)
        bindFilterParam(q, filterParam, filter)
        return q.resultList
    }

    override fun getStatusDistribution(filter: StatisticsFilter): List<StatusDistribution> {
        // status distribution IS the per-status breakdown, so the `status` field of the filter
        // (were a caller to set it) is deliberately not applied here — same as before the Z2 filter split.
        val noStatusFilter = filter.copy(status = null)
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val root = cq.from(StatInvoiceBase::class.java)
        val (predicates, filterParam) = statsPredicates(cb, root, noStatusFilter)
        val statusPath = root.get<Int>(ATTR_STATUS)
        val countExpr = cb.count(root)
        cq.select(cb.tuple(statusPath.alias(ATTR_STATUS), countExpr.alias("totalCount")))
        cq.where(*predicates)
        cq.groupBy(statusPath)
        cq.orderBy(cb.desc(countExpr))
        val q = em.createQuery(cq)
        bindFilterParam(q, filterParam, noStatusFilter)
        return q.resultList.map { t ->
            StatusDistribution(
                status = InvoiceStatus.entries[(t.get(ATTR_STATUS) as Number).toInt()],
                count = (t.get("totalCount") as Number).toLong(),
            )
        }
    }

    /**
     * `senderId`/`recipientId` restrict which years are returned by joining through
     * [StatInvoiceBase] (`sender_id`/`recipient_id` are real columns there, COALESCE-derived exactly
     * like the raw `invoice` subquery this replaces); with neither set, the plain, already-`DISTINCT`
     * [StatAvailableYear] view is enough.
     */
    override fun getAvailableYears(filter: StatisticsFilter): List<Int> {
        val cb = em.criteriaBuilder
        val senderId = filter.senderId
        val recipientId = filter.recipientId
        if (senderId == null && recipientId == null) {
            val cq = cb.createQuery(Int::class.javaObjectType)
            val root = cq.from(StatAvailableYear::class.java)
            cq.select(root.get("year")).orderBy(cb.desc(root.get<Int>("year")))
            return em.createQuery(cq).resultList
        }
        val cq = cb.createQuery(Int::class.javaObjectType)
        val root = cq.from(StatInvoiceBase::class.java)
        val predicates = mutableListOf<Predicate>()
        if (senderId != null) predicates += cb.equal(root.get<Long>(ATTR_SENDER_ID), senderId)
        if (recipientId != null) predicates += cb.equal(root.get<Long>(ATTR_RECIPIENT_ID), recipientId)
        cq.select(root.get("year")).distinct(true)
            .where(*predicates.toTypedArray())
            .orderBy(cb.desc(root.get<Int>("year")))
        return em.createQuery(cq).resultList
    }

    /**
     * Every filter dimension [StatisticsFilter] can carry, as [Predicate]s against
     * whichever `v_stat_*` view entity [root] points at. `senderId`/`recipientId`/`year`/`month`/
     * `quarter`/`status`/`reverseCharge` are plain equality binds; `filterText` is the one
     * free-text dimension and travels as a [ParameterExpression] bound by the caller via
     * [bindFilterParam] with [likePattern]'s escape contract.
     *
     * [hasInvoiceNumber]/[hasDescription] gate which OR-branches participate — the pre-aggregated
     * period views ([StatYearly]/[StatQuarterly]/[StatMonthly]/[StatDaily]/[StatItemYearly]/
     * [StatItemQuarterly]/[StatItemMonthly]) don't carry an `invoice_number`/`description` column
     * (not a meaningful aggregation dimension), so text search there only matches sender/recipient
     * names; only the flat base views ([StatInvoiceBase]/[StatItemBase]) support the row-level
     * `invoice_number`/`description` branch. Every caller already scopes [filter] so a dimension a
     * given view doesn't carry is always `null` there — the same caller-side contract
     * `StatsWhereClauseBuilder.build`'s call sites relied on before this rewrite.
     */
    private fun <T> statsPredicates(
        cb: CriteriaBuilder,
        root: Root<T>,
        filter: StatisticsFilter,
        hasInvoiceNumber: Boolean = true,
        hasDescription: Boolean = false,
    ): Pair<Array<Predicate>, ParameterExpression<String>?> {
        val predicates = mutableListOf<Predicate>()
        var filterParam: ParameterExpression<String>? = null
        if (filter.senderId != null) predicates += cb.equal(root.get<Long>(ATTR_SENDER_ID), filter.senderId)
        if (filter.recipientId != null) predicates += cb.equal(root.get<Long>(ATTR_RECIPIENT_ID), filter.recipientId)
        if (filter.year != null) predicates += cb.equal(root.get<Int>("year"), filter.year)
        if (filter.month != null) predicates += cb.equal(root.get<Int>(ATTR_MONTH), filter.month)
        if (filter.quarter != null) predicates += cb.equal(root.get<Int>(ATTR_QUARTER), filter.quarter)
        if (filter.status != null) predicates += cb.equal(root.get<Int>(ATTR_STATUS), filter.status.ordinal)
        if (filter.reverseCharge != null) predicates += cb.equal(root.get<Boolean>("reverseCharge"), filter.reverseCharge)
        if (!filter.filterText.isNullOrBlank()) {
            val param = cb.parameter(String::class.java, FILTER_PARAM)
            filterParam = param
            val branches = mutableListOf(
                cb.like(cb.lower(root.get("senderName")), param, '\\'),
                cb.like(cb.lower(root.get("recipientName")), param, '\\'),
            )
            if (hasInvoiceNumber) branches += cb.like(cb.lower(root.get("invoiceNumber")), param, '\\')
            if (hasDescription) branches += cb.like(cb.lower(root.get(ATTR_DESCRIPTION)), param, '\\')
            predicates += cb.or(*branches.toTypedArray())
        }
        return predicates.toTypedArray() to filterParam
    }

    private fun bindFilterParam(q: Query, param: ParameterExpression<String>?, filter: StatisticsFilter) {
        if (param != null) q.setParameter(param, likePattern(filter.filterText!!))
    }

    /**
     * The ranking query shared by [getRecipientTotals]/[getSenderTotals]: group by
     * ([idAttr], [nameAttr]), sum `grossAmount` (and `netAmount` when [includeNetAmount]), and
     * `invoiceCount` — `COUNT(*)` over [StatInvoiceBase]'s raw rows when [src] is text-filtered,
     * `SUM(invoiceCount)` over an already-aggregated period view otherwise (mirrors the former
     * `RankingSource.countExpr` choice).
     *
     * money-currency-display R6 — `currency` joins the grouping key alongside [idAttr]/[nameAttr]:
     * a recipient/sender billed in two currencies now ranks as two rows, one per currency, rather
     * than one row whose `grossAmount` silently mixed both.
     */
    /** Which ranking dimension [rankingTuples] groups by — the recipient- vs. sender-totals shape
     *  (entity attribute names for the grouping columns, plus whether the sender ranking's own
     *  net-amount column is included). Bundled so that function stays under detekt's
     *  `LongParameterList` threshold. */
    private data class RankingAttributes(
        val idAttr: String,
        val nameAttr: String,
        val includeNetAmount: Boolean,
    )

    private fun rankingTuples(
        src: RankingSource<*>,
        filter: StatisticsFilter,
        limit: Int,
        attrs: RankingAttributes,
    ): List<Tuple> {
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val root = cq.from(src.entityClass)
        val (predicates, filterParam) = statsPredicates(cb, root, filter, hasInvoiceNumber = src.hasInvoiceNumber)
        val sumGross = cb.sum(root.get<BigDecimal>(ATTR_GROSS_AMOUNT))
        val countExpr = if (src.hasInvoiceNumber) cb.count(root) else cb.sum(root.get<Long>(ATTR_INVOICE_COUNT))
        val idPath = root.get<Long>(attrs.idAttr)
        val namePath = root.get<String>(attrs.nameAttr)
        val currencyPath = root.get<Int>(ATTR_CURRENCY)
        val selections = mutableListOf<Selection<*>>(
            idPath.alias("id"),
            namePath.alias("name"),
            currencyPath.alias(ATTR_CURRENCY),
            sumGross.alias(ATTR_GROSS_AMOUNT),
            countExpr.alias(ATTR_INVOICE_COUNT),
        )
        if (attrs.includeNetAmount) selections += cb.sum(root.get<BigDecimal>(ATTR_NET_AMOUNT)).alias(ATTR_NET_AMOUNT)
        cq.select(cb.tuple(selections))
        cq.where(*predicates)
        cq.groupBy(idPath, namePath, currencyPath)
        cq.orderBy(cb.desc(sumGross))
        val q = em.createQuery(cq)
        bindFilterParam(q, filterParam, filter)
        q.maxResults = limit
        return q.resultList
    }

    /**
     * Ranking source view + shape for the invoice-level rankings (recipient/sender totals).
     * Grouped period views ([StatYearly]/[StatQuarterly]/[StatMonthly]) are already aggregated by
     * sender/recipient per period, so the common case (no text filter) rolls up pre-summed
     * `invoiceCount` instead of scanning every invoice row. Once a text filter is active we fall
     * back to the flat [StatInvoiceBase], because invoice-number search needs row-level data that
     * the grouped views intentionally don't carry (it's not a meaningful aggregation dimension).
     */
    private class RankingSource<T>(val entityClass: Class<T>, val hasInvoiceNumber: Boolean)

    private fun invoiceRankingSource(month: Int?, quarter: Int?, filterText: String?): RankingSource<*> =
        if (!filterText.isNullOrBlank()) {
            RankingSource(StatInvoiceBase::class.java, hasInvoiceNumber = true)
        } else when {
            month != null -> RankingSource(StatMonthly::class.java, hasInvoiceNumber = false)
            quarter != null -> RankingSource(StatQuarterly::class.java, hasInvoiceNumber = false)
            else -> RankingSource(StatYearly::class.java, hasInvoiceNumber = false)
        }

    /** As [invoiceRankingSource], for the item-level article ranking. */
    private fun itemRankingSource(month: Int?, quarter: Int?, filterText: String?): RankingSource<*> =
        if (!filterText.isNullOrBlank()) {
            RankingSource(StatItemBase::class.java, hasInvoiceNumber = true)
        } else when {
            month != null -> RankingSource(StatItemMonthly::class.java, hasInvoiceNumber = false)
            quarter != null -> RankingSource(StatItemQuarterly::class.java, hasInvoiceNumber = false)
            else -> RankingSource(StatItemYearly::class.java, hasInvoiceNumber = false)
        }

    private fun asBigDecimal(value: Any?): BigDecimal = StatsSqlSupport.asBigDecimal(value)

    /** money-currency-display R6 — every grouped tuple above carries the `v_stat_*` view's own
     *  `currency` smallint (see [de.littletrotter.infrastructure.persistence.stats.StatInvoiceBase.currency]'s
     *  KDoc); this is the one place that ordinal turns back into the [Currency] the DTOs carry. */
    private fun currencyOf(value: Any?): Currency = Currency.entries[(value as Number).toInt()]

    internal companion object {
        /** `v_stat_*` view attribute names, reused for both the Criteria [Root.get] path and the
         *  matching [Tuple.get] alias so the two can never drift apart. */
        private const val ATTR_CURRENCY = "currency"
        private const val ATTR_DESCRIPTION = "description"
        private const val ATTR_GROSS_AMOUNT = "grossAmount"
        private const val ATTR_INVOICE_COUNT = "invoiceCount"
        private const val ATTR_MONTH = "month"
        private const val ATTR_NET_AMOUNT = "netAmount"
        private const val ATTR_QUANTITY = "quantity"
        private const val ATTR_QUARTER = "quarter"
        private const val ATTR_RECIPIENT_ID = "recipientId"
        private const val ATTR_SENDER_ID = "senderId"
        private const val ATTR_STATUS = "status"
        private const val ATTR_VAT_AMOUNT = "vatAmount"

        /** A recipient/sender referenced by an invoice can be deleted from master data later
         *  (invoices keep their historical FK as a snapshot); the ranking views then surface a
         *  NULL name, which must render as "Unbekannt" instead of a raw null/crash. `internal` so
         *  `InvoiceStatisticsQueryServiceImplTest` can pin it directly without a live query. */
        internal fun nameOrUnbekannt(value: Any?): String = (value as? String) ?: "Unbekannt"

        /** `SUM(...)` over zero matching rows reads back as SQL `NULL`, not `0` — this is the one
         *  spot every ranking total's id/count column funnels through on its way to the DTO. */
        internal fun longOrZero(value: Any?): Long = (value as? Number)?.toLong() ?: 0L
    }
}
