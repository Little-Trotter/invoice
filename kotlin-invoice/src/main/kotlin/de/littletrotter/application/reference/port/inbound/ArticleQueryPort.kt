package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.SortDirection

/**
 * Input port for reading the article catalogue: the article view's paged grid and the
 * article-select combos of the invoice item dialogs. Only [findArticleById] fills an article's tax
 * variants and recipient mappings; list and combo rows leave them empty.
 */
interface ArticleQueryPort {
    fun findArticleById(id: Long): ArticleDto?

    /** [category] constrains the query itself when set (the article view's category filter), so
     *  page and count stay consistent. [numberFilter] matches the article number (contains),
     *  [textFilter] name/description; both AND when set. */
    fun findArticles(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        numberFilter: String? = null,
        textFilter: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
        category: InvoiceItemCategory? = null,
    ): List<ArticleDto>

    /** Counterpart to [findArticles] with the same optional [category] constraint. */
    fun countArticles(numberFilter: String? = null, textFilter: String? = null, category: InvoiceItemCategory? = null): Long

    /** The mapping for (articleId, recipientId), or `null` when none is maintained. */
    fun findArticleRecipientRef(articleId: Long, recipientId: Long): ArticleRecipientRefDto?

    /** The article-select combos' lazy fetch: the category constrains the query itself,
     *  [offset]/[limit] are the ComboBox's own paging. */
    fun findArticlesForSelect(category: InvoiceItemCategory, filter: String? = null, offset: Int = 0, limit: Int = 60): List<ArticleDto>

    /** Counterpart to [findArticlesForSelect]: the lazy combo's item count. */
    fun countArticlesForSelect(category: InvoiceItemCategory, filter: String? = null): Int
}
