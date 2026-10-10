package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.value.ArticleNumber
import jakarta.enterprise.context.ApplicationScoped
import de.littletrotter.domain.shared.likeFilter
import de.littletrotter.domain.shared.likeFilterNamed
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.SortDirection
import jakarta.persistence.FlushModeType
import jakarta.transaction.Transactional

private const val A_NAME = "a.name"
private const val A_ARTICLE_NUMBER = "a.articleNumber"
private const val PARAM_CATEGORY = "category"

@ApplicationScoped
class PanacheArticleRepository : BasePanacheRepository(), ArticleRepository {

    companion object {
        private const val ARTICLE_NUMBER_LOCK_NAMESPACE = 7102

        /** Entity-specific allowlist, kept local to this repository and
         *  allocated once instead of on every [findAll] call. */
        private val SORT_ALLOWLIST = mapOf(
            "name" to A_NAME,
            "articleNumber" to A_ARTICLE_NUMBER,
            PARAM_CATEGORY to "a.category",
            "defaultUnit" to "a.defaultUnit",
        )

        /** article-two-field-search — dedicated parameter for the Nr. filter, so it can be bound
         *  independently of the text filter's standard `:f` parameter in the same query. */
        private const val NUMBER_PARAM = "fnr"

        /** The Nr. field's predicate: articleNumber ONLY, contains semantics — "36" finds
         *  `ART-2026-00036` (PO requirement of 2026-09-03, article-two-field-search). */
        private val NUMBER_PREDICATE = likeFilterNamed(A_ARTICLE_NUMBER, NUMBER_PARAM)

        /** The text field's predicate: name and description ONLY — the number moved into its
         *  own field (article-two-field-search, replacing the L18 single combined predicate). */
        private val TEXT_PREDICATE = "(${likeFilter(A_NAME)} OR ${likeFilter("a.description")})"

        /** The article-select combos' single-input predicate, separate from
         *  [NUMBER_PREDICATE]/[TEXT_PREDICATE]: those stay the masterdata list's own two-field AND
         *  search. */
        /** ST-1 delete-vs-use race — `invoice_item.article_id`'s auto-named FK
         *  (`V1__baseline_schema.sql`, no `ON DELETE` clause given → Postgres default
         *  `<table>_<column>_fkey`). Fires either direction: an item add/update naming a
         *  meanwhile-deleted article, or, here, a delete racing a concurrent item add that just
         *  committed a reference to this same row, after [de.littletrotter.application.masterdata
         *  .ArticleManagementService.delete]'s own `isInUse` pre-check already passed. */
        private const val FK_INVOICE_ITEM_ARTICLE = "invoice_item_article_id_fkey"

        private val SELECT_PREDICATE =
            "(${likeFilter(A_ARTICLE_NUMBER)} OR ${likeFilter(A_NAME)} OR ${likeFilter("a.description")})"

        /** A second, independently-bound parameter for the same filter value, rather than
         *  reusing `:f` in both the WHERE and the ORDER BY CASE of the same query. */
        private const val SELECT_RANK_PARAM = "fRank"

        /** `0` when the article number matches the filter, `1` otherwise; a `null` `articleNumber`
         *  makes the LIKE unknown/false, so it falls into the ELSE branch like any other miss. */
        private val SELECT_RANK_EXPR = "CASE WHEN ${likeFilterNamed(A_ARTICLE_NUMBER, SELECT_RANK_PARAM)} THEN 0 ELSE 1 END"

        /** Compile-time constant: SQL fragment for articles list query without filters. */
        private const val BASE_SELECT = "SELECT a FROM Article a"

        /** Compile-time constant: SQL fragment for articles count query without filters. */
        private const val BASE_COUNT = "SELECT COUNT(a) FROM Article a"

        /** Compile-time constant: SQL fragment for category-filtered articles list query. */
        private const val BASE_SELECT_CATEGORY = "SELECT a FROM Article a WHERE a.category = :category"

        /** Compile-time constant: SQL fragment for category-filtered articles count query. */
        private const val BASE_COUNT_CATEGORY = "SELECT COUNT(a) FROM Article a WHERE a.category = :category"
    }

    /** ONE filter-clause builder for all four query methods (page and count, with and without
     *  category), so the searched fields can never drift apart between them (L18 discipline).
     *  Both filters AND when set; `null` when neither contributes a clause. Takes only booleans —
     *  the filter values themselves never reach the query-building call chain, only
     *  [applyArticleFilters] does, which binds them as named parameters. */
    private fun filterPredicate(hasNumberFilter: Boolean, hasTextFilter: Boolean): String? {
        val clauses = buildList {
            if (hasNumberFilter) add(NUMBER_PREDICATE)
            if (hasTextFilter) add(TEXT_PREDICATE)
        }
        return if (clauses.isEmpty()) null else clauses.joinToString(" AND ")
    }

    private fun <T> jakarta.persistence.TypedQuery<T>.applyArticleFilters(
        numberFilter: String?,
        textFilter: String?,
    ): jakarta.persistence.TypedQuery<T> = applyLikeParam(NUMBER_PARAM, numberFilter).applyLikeParam(textFilter)

    override fun findAll(
        pageIndex: Int,
        pageSize: Int,
        numberFilter: String?,
        textFilter: String?,
        sortField: String,
        sortDir: SortDirection,
    ): List<Article> {
        val sort = resolveSortExpr(sortField, sortDir, SORT_ALLOWLIST, A_NAME)
        val query = buildFilteredArticleQuery(
            BASE_SELECT,
            !numberFilter.isNullOrBlank(),
            !textFilter.isNullOrBlank(),
            sort,
        )
        return em.createQuery(query, Article::class.java)
            .applyArticleFilters(numberFilter, textFilter)
            .setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
    }

    override fun count(numberFilter: String?, textFilter: String?): Long {
        val query = buildFilteredCountQuery(BASE_COUNT, !numberFilter.isNullOrBlank(), !textFilter.isNullOrBlank())
        return em.createQuery(query, Long::class.java)
            .applyArticleFilters(numberFilter, textFilter)
            .singleResult
    }

    /** UI-8a — same shape as [findAll], but the category constrains the query itself: it is
     *  ANDed with the optional filters, not applied client-side afterwards. Carries the same
     *  sort parameters as [findAll] since the Artikel view routes its category filter through
     *  this method (fix-article-search-sort). */
    override fun findAllInCategory(
        category: InvoiceItemCategory,
        pageIndex: Int,
        pageSize: Int,
        numberFilter: String?,
        textFilter: String?,
        sortField: String,
        sortDir: SortDirection,
    ): List<Article> {
        val sort = resolveSortExpr(sortField, sortDir, SORT_ALLOWLIST, A_NAME)
        val query = buildFilteredCategoryArticleQuery(
            BASE_SELECT_CATEGORY,
            !numberFilter.isNullOrBlank(),
            !textFilter.isNullOrBlank(),
            sort,
        )
        return em.createQuery(query, Article::class.java)
            .setParameter(PARAM_CATEGORY, category)
            .applyArticleFilters(numberFilter, textFilter)
            .setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
    }

    override fun countInCategory(category: InvoiceItemCategory, numberFilter: String?, textFilter: String?): Long {
        val query = buildFilteredCountCategoryQuery(
            BASE_COUNT_CATEGORY,
            !numberFilter.isNullOrBlank(),
            !textFilter.isNullOrBlank(),
        )
        return em.createQuery(query, Long::class.java)
            .setParameter(PARAM_CATEGORY, category)
            .applyArticleFilters(numberFilter, textFilter)
            .singleResult
    }

    /** The article-select combos' single-input search: [filter] matches number, name or
     *  description, number hits ranked first. Page and count share one filter clause
     *  ([buildSelectArticleQuery]/[buildCountSelectArticleQuery]) so they can never drift apart. */
    override fun findForSelect(
        category: InvoiceItemCategory,
        filter: String?,
        pageIndex: Int,
        pageSize: Int,
    ): List<Article> {
        val hasFilter = !filter.isNullOrBlank()
        val query = buildSelectArticleQuery(hasFilter)
        val typedQuery = em.createQuery(query, Article::class.java).setParameter(PARAM_CATEGORY, category)
        if (hasFilter) typedQuery.applyLikeParam(filter).applyLikeParam(SELECT_RANK_PARAM, filter)
        return typedQuery.setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
    }

    /** Counterpart to [findForSelect] — identical predicate, no ORDER BY. */
    override fun countForSelect(category: InvoiceItemCategory, filter: String?): Long {
        val hasFilter = !filter.isNullOrBlank()
        val query = buildCountSelectArticleQuery(hasFilter)
        val typedQuery = em.createQuery(query, Long::class.java).setParameter(PARAM_CATEGORY, category)
        if (hasFilter) typedQuery.applyLikeParam(filter)
        return typedQuery.singleResult
    }

    /** The maintenance UI edits a detached aggregate and reads both owned child collections.
     *  Initialize them while the repository transaction is active, matching the contract used
     *  by [de.littletrotter.infrastructure.persistence.PanacheRecipientRepository.findById]. */
    @Transactional
    override fun findById(id: Long): Article? {
        val article = findByIdInternal(id, Article::class.java)
        article?.taxVariants?.size
        article?.recipientRefs?.size
        return article
    }

    override fun isInUse(id: Long): Boolean =
        em.createQuery("SELECT COUNT(i) FROM InvoiceItem i WHERE i.articleRef.id = :articleId", Long::class.java)
            .setParameter("articleId", id)
            .singleResult > 0L

    // "Synchronize number counters to existing data" admin action — see RecipientRepository
    // .findAllCustomerNumbers KDoc for why the interface default body is a fallback only.
    // A converter-backed attribute already comes back as ArticleNumber from a JPQL
    // scalar projection (never a raw String), and the former `<> ''` guard is now dead: the
    // converter can never persist a blank value (ArticleNumber's own init rejects it).
    override fun findAllArticleNumbers(): List<String> =
        em.createQuery(
            "SELECT a.articleNumber FROM Article a WHERE a.articleNumber IS NOT NULL",
            ArticleNumber::class.java,
        ).resultList.map { it.value }

    // Case-sensitive lock key: the article index is case-sensitive too. Held until commit.
    override fun isArticleNumberTaken(number: String): Boolean {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:ns, hashtext(:n))")
            .setParameter("ns", ARTICLE_NUMBER_LOCK_NAMESPACE)
            .setParameter("n", number)
            .setFlushMode(FlushModeType.COMMIT)
            .singleResult
        return em.createQuery("SELECT COUNT(a) FROM Article a WHERE a.articleNumber = :number", java.lang.Long::class.java)
            .setParameter("number", number).setFlushMode(FlushModeType.COMMIT).singleResult.toLong() > 0
    }

    // ST-1 update-vs-delete race — flushes immediately in the caller's own ambient transaction,
    // mirroring PanacheRecipientRepository/PanacheSenderProfileRepository's own save (ST-B4): no
    // named constraint to translate here (the empty checks list is deliberate), this exists only
    // to move the flush inside ArticleManagementService.save's own try/catch instead of leaving
    // it to the outer transaction's commit, where the resulting
    // jakarta.persistence.OptimisticLockException could no longer be told apart from "genuinely
    // deleted meanwhile" by that service. See PanacheArticleRepositoryCrudIT's own
    // QuarkusTransaction.requiringNew() wrapping (mirroring PanacheSenderProfileRepositoryIT) —
    // this now requires an ambient transaction, same as those sibling repositories already did.
    override fun save(article: Article): Article = saveEntityFlushingOrThrow(article, { article.id })
    override fun forceVersionIncrement(article: Article) {
        em.lock(article, jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    }
    override fun saveAndFlush(article: Article): Article = saveEntityFlushing(article) { article.id }

    // ST-1 delete-vs-use race — flushes the removal immediately so a concurrent item add
    // referencing this article (committed after ArticleManagementService.delete's own
    // "still in use" pre-check already passed) surfaces here as the SAME readable
    // InvalidParameterException that pre-check throws, not a raw ConstraintViolationException.
    override fun delete(id: Long) {
        deleteByIdFlushingOrThrow(id, Article::class.java, FK_INVOICE_ITEM_ARTICLE to { cause ->
            InvalidParameterException(
                "Eine Leistungsposition, die einer Rechnung zugeordnet ist, kann nicht gelöscht werden.",
                cause,
            )
        })
    }

    /** Builds a filtered article select query using only constant SQL fragments and the two
     *  presence flags below — no user input reaches this or the other three `buildFiltered*`
     *  helpers; the actual filter values are bound separately via named parameters
     *  ([applyArticleFilters]). */
    private fun buildFilteredArticleQuery(
        baseQuery: String,
        hasNumberFilter: Boolean,
        hasTextFilter: Boolean,
        sort: Pair<String, String>,
    ): String {
        val (sortExpr, dir) = sort
        val filterClause = filterPredicate(hasNumberFilter, hasTextFilter)
            ?.let { " WHERE $it" } ?: ""
        return "$baseQuery$filterClause ORDER BY $sortExpr $dir, a.id $dir"
    }

    /** As [buildFilteredArticleQuery], for the count query. */
    private fun buildFilteredCountQuery(
        baseQuery: String,
        hasNumberFilter: Boolean,
        hasTextFilter: Boolean,
    ): String {
        val filterClause = filterPredicate(hasNumberFilter, hasTextFilter)
            ?.let { " WHERE $it" } ?: ""
        return "$baseQuery$filterClause"
    }

    /** As [buildFilteredArticleQuery], for the category-constrained select query. */
    private fun buildFilteredCategoryArticleQuery(
        baseQuery: String,
        hasNumberFilter: Boolean,
        hasTextFilter: Boolean,
        sort: Pair<String, String>,
    ): String {
        val (sortExpr, dir) = sort
        val filterClause = filterPredicate(hasNumberFilter, hasTextFilter)
            ?.let { " AND $it" } ?: ""
        return "$baseQuery$filterClause ORDER BY $sortExpr $dir, a.id $dir"
    }

    /** As [buildFilteredArticleQuery], for the category-constrained count query. */
    private fun buildFilteredCountCategoryQuery(
        baseQuery: String,
        hasNumberFilter: Boolean,
        hasTextFilter: Boolean,
    ): String {
        val filterClause = filterPredicate(hasNumberFilter, hasTextFilter)
            ?.let { " AND $it" } ?: ""
        return "$baseQuery$filterClause"
    }

    /** [findForSelect]'s query. With a blank filter, the ORDER BY drops the rank tiebreaker
     *  entirely rather than evaluating one nothing narrowed by. */
    private fun buildSelectArticleQuery(hasFilter: Boolean): String {
        val filterClause = if (hasFilter) " AND $SELECT_PREDICATE" else ""
        val orderExpr = if (hasFilter) "$SELECT_RANK_EXPR, a.name" else A_NAME
        return "$BASE_SELECT_CATEGORY$filterClause ORDER BY $orderExpr, a.id"
    }

    /** As [buildSelectArticleQuery], for the count query. */
    private fun buildCountSelectArticleQuery(hasFilter: Boolean): String {
        val filterClause = if (hasFilter) " AND $SELECT_PREDICATE" else ""
        return "$BASE_COUNT_CATEGORY$filterClause"
    }
}
