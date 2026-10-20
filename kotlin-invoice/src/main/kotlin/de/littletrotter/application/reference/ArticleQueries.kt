package de.littletrotter.application.reference

import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.application.masterdata.ArticleManagementService
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [ArticleQueryPort] on top of [ArticleManagementService]. */
@ApplicationScoped
class ArticleQueries @Inject constructor(
    private val articleManagementService: ArticleManagementService,
) : ArticleQueryPort {

    override fun findArticleById(id: Long): ArticleDto? = articleManagementService.findById(id)?.toDto()

    override fun findArticles(
        pageIndex: Int,
        pageSize: Int,
        numberFilter: String?,
        textFilter: String?,
        sortField: String,
        sortDir: SortDirection,
        category: InvoiceItemCategory?,
    ): List<ArticleDto> = if (category == null) {
        articleManagementService.findAll(pageIndex, pageSize, numberFilter, textFilter, sortField, sortDir)
    } else {
        articleManagementService.findAllInCategory(category, pageIndex, pageSize, numberFilter, textFilter, sortField, sortDir)
    }.map { it.toDto(withOwnedRows = false) }

    override fun countArticles(numberFilter: String?, textFilter: String?, category: InvoiceItemCategory?): Long =
        if (category == null) {
            articleManagementService.count(numberFilter, textFilter)
        } else {
            articleManagementService.countInCategory(category, numberFilter, textFilter)
        }

    override fun findArticleRecipientRef(articleId: Long, recipientId: Long): ArticleRecipientRefDto? =
        articleManagementService.findRecipientRef(articleId, recipientId)?.toDto()

    // The combo hands in offset/limit; the page index is derived the same way as in every other
    // lazy data provider here. [filter] matches number OR name OR description in one query, number
    // hits ranked first.
    override fun findArticlesForSelect(category: InvoiceItemCategory, filter: String?, offset: Int, limit: Int): List<ArticleDto> {
        val pageIndex = if (limit == 0) 0 else offset / limit
        return articleManagementService.findForSelect(category, filter, pageIndex, limit).map { it.toDto(withOwnedRows = false) }
    }

    override fun countArticlesForSelect(category: InvoiceItemCategory, filter: String?): Int =
        articleManagementService.countForSelect(category, filter).toInt()
}
