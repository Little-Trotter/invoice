package de.littletrotter.domain.repository

import de.littletrotter.domain.masterdata.Article
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.SortDirection

interface ArticleRepository {
    /** article-two-field-search — the Artikel view searches through TWO independent fields:
     *  [numberFilter] matches ONLY `articleNumber` (contains semantics — "36" finds
     *  `ART-2026-00036`), [textFilter] matches name and description. Both are ANDed when set;
     *  page and count always come from the same predicate. */
    fun findAll(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        numberFilter: String? = null,
        textFilter: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<Article>
    fun count(numberFilter: String? = null, textFilter: String? = null): Long

    /** UI-8a — the category-constrained twin of [findAll]: the category constrains the QUERY
     *  itself, instead of loading a capped page and filtering client-side afterwards (the bug
     *  this fixes). Backs both the article-select combos and the Artikel view's category filter,
     *  hence the same sort parameters as [findAll] (fix-article-search-sort).
     *
     *  The default body below is a compatibility fallback ONLY, for the handful of anonymous
     *  [ArticleRepository] test doubles that predate this method and never exercise it: it is the
     *  same "load then filter client-side" shape the AP fixes, so `PanacheArticleRepository`
     *  always overrides it with a real query-level constraint — production never runs this body. */
    fun findAllInCategory(
        category: InvoiceItemCategory,
        pageIndex: Int = 0,
        pageSize: Int = 60,
        numberFilter: String? = null,
        textFilter: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<Article> = findAll(0, Int.MAX_VALUE, numberFilter, textFilter, sortField, sortDir)
        .filter { it.category == category }
        .drop(pageIndex * pageSize)
        .take(pageSize)

    /** Counterpart to [findAllInCategory] — backs the lazy combo's item count. Same fallback-only
     *  default body, same reason. */
    fun countInCategory(category: InvoiceItemCategory, numberFilter: String? = null, textFilter: String? = null): Long =
        findAll(0, Int.MAX_VALUE, numberFilter, textFilter, "name", SortDirection.ASC)
            .count { it.category == category }.toLong()

    /** The article-select combos' single-input search: [filter] matches articleNumber OR
     *  name OR description, number hits ranked first. Distinct from [findAllInCategory]'s
     *  two-field AND search (masterdata list, unaffected). No default body: a text-only fallback
     *  would silently reintroduce the number-blind search this method replaces. */
    fun findForSelect(
        category: InvoiceItemCategory,
        filter: String? = null,
        pageIndex: Int = 0,
        pageSize: Int = 60,
    ): List<Article>

    /** Counterpart to [findForSelect]. */
    fun countForSelect(category: InvoiceItemCategory, filter: String? = null): Long

    fun findById(id: Long): Article?

    /** True when an invoice position already refers to this catalogue position. */
    fun isInUse(id: Long): Boolean = false

    /** Siehe [de.littletrotter.domain.repository.RecipientRepository.findAllCustomerNumbers] —
     *  same purpose and same fallback-default reasoning, for article numbers. */
    fun findAllArticleNumbers(): List<String> =
        findAll(0, Int.MAX_VALUE).mapNotNull { it.articleNumber?.value }

    /** Exact-match check against the case-sensitive unique index on `article_number`, so a typed
     *  duplicate is rejected before the insert instead of surfacing as a database error.
     *  Implementations with a database first take a transaction-scoped per-number lock, which
     *  makes check and insert one atomic step. The default scans all numbers — enough for test
     *  doubles without a real index. */
    fun isArticleNumberTaken(number: String): Boolean = findAllArticleNumbers().contains(number)

    fun save(article: Article): Article

    /** Advance the aggregate version even when only an inverse child collection changed. */
    fun forceVersionIncrement(article: Article)

    /** Like [save], but with an immediate flush so a UNIQUE violation on
     *  `article_number` (`uq_article_article_number`) is still visible within the same
     *  transaction — see `MasterDataNumberService.saveWithNumberCollisionRetry`. The default
     *  delegates to [save]: fakes/in-memory implementations without a real DB constraint don't
     *  need the distinction. */
    fun saveAndFlush(article: Article): Article = save(article)
    fun delete(id: Long)
}
