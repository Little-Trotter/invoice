package de.littletrotter.application.reference

import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.application.masterdata.ArticleManagementService
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.anArticleRecipientRef
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [ArticleQueries.findArticlesForSelect]'s offset/limit-to-pageIndex paging
 * math.
 */
class ArticleQueriesTest {

    @Test
    fun `findArticlesForSelect converts offset and limit into a page index and passes the category and filter through`() {
        var captured: List<Any?> = emptyList()
        val article = anArticle { id = 61L; name = "Kunde jenseits der alten Kappe" }
        val service = mock<ArticleManagementService> {
            on {
                findForSelect(any(), anyOrNull(), any(), any())
            } doAnswer { invocation ->
                captured = listOf(
                    invocation.getArgument(0),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(1),
                )
                listOf(article)
            }
        }
        val queries = ArticleQueries(service)

        // offset=120, limit=60 -> page index 2, well beyond the old 1000-row eager cap's single page.
        val result = queries.findArticlesForSelect(InvoiceItemCategory.PRODUCT, "pumpe", offset = 120, limit = 60)

        assertEquals(listOf(InvoiceItemCategory.PRODUCT, 2, 60, "pumpe"), captured)
        assertEquals(listOf(ArticleDto(id = 61L, version = 0L, name = "Kunde jenseits der alten Kappe")), result)
    }

    @Test
    fun `findArticlesForSelect passes a null filter through unchanged`() {
        var capturedFilter: String? = "unset"
        val service = mock<ArticleManagementService> {
            on {
                findForSelect(any(), anyOrNull(), any(), any())
            } doAnswer { invocation ->
                capturedFilter = invocation.getArgument(1)
                emptyList<Article>()
            }
        }
        val queries = ArticleQueries(service)

        queries.findArticlesForSelect(InvoiceItemCategory.SERVICE, null, offset = 0, limit = 60)

        assertEquals(null, capturedFilter)
    }

    @Test
    fun `findArticlesForSelect treats a zero limit as page index zero instead of dividing by zero`() {
        var capturedPageIndex = -1
        val service = mock<ArticleManagementService> {
            on {
                findForSelect(any(), anyOrNull(), any(), any())
            } doAnswer { invocation ->
                capturedPageIndex = invocation.getArgument(2)
                emptyList<Article>()
            }
        }
        val queries = ArticleQueries(service)

        queries.findArticlesForSelect(InvoiceItemCategory.PRODUCT, null, offset = 0, limit = 0)

        assertEquals(0, capturedPageIndex)
    }

    @Test
    fun `findArticleById delegates to the service`() {
        val service = mock<ArticleManagementService>()
        val article = anArticle { id = 8L }
        whenever(service.findById(8L)).thenReturn(article)
        val queries = ArticleQueries(service)

        assertEquals(ArticleDto(id = 8L, version = 0L), queries.findArticleById(8L))
        assertEquals(null, queries.findArticleById(9L))
    }

    @Test
    fun `findArticles without category delegates to findAll with the sort parameters`() {
        val service = mock<ArticleManagementService>()
        whenever(service.findAll(1, 20, "n", "t", "name", SortDirection.DESC)).thenReturn(listOf(Article()))
        val queries = ArticleQueries(service)

        val result = queries.findArticles(1, 20, "n", "t", "name", SortDirection.DESC, category = null)

        assertEquals(1, result.size)
    }

    @Test
    fun `findArticles with category delegates to findAllInCategory keeping the sort parameters`() {
        val service = mock<ArticleManagementService>()
        whenever(
            service.findAllInCategory(InvoiceItemCategory.SERVICE, 1, 20, "n", "t", "name", SortDirection.DESC),
        ).thenReturn(listOf(Article()))
        val queries = ArticleQueries(service)

        val result = queries.findArticles(1, 20, "n", "t", "name", SortDirection.DESC, category = InvoiceItemCategory.SERVICE)

        assertEquals(1, result.size)
    }

    @Test
    fun `countArticles without category delegates to count`() {
        val service = mock<ArticleManagementService>()
        whenever(service.count("n", "t")).thenReturn(4L)
        val queries = ArticleQueries(service)

        assertEquals(4L, queries.countArticles("n", "t", category = null))
    }

    @Test
    fun `countArticles with category delegates to countInCategory`() {
        val service = mock<ArticleManagementService>()
        whenever(service.countInCategory(InvoiceItemCategory.PRODUCT, "n", "t")).thenReturn(5L)
        val queries = ArticleQueries(service)

        assertEquals(5L, queries.countArticles("n", "t", category = InvoiceItemCategory.PRODUCT))
    }

    @Test
    fun `findArticleRecipientRef delegates to the service`() {
        val service = mock<ArticleManagementService>()
        val ref = anArticleRecipientRef { recipient = aRecipient { id = 2L }; buyerArticleNumber = "243538" }
        whenever(service.findRecipientRef(1L, 2L)).thenReturn(ref)
        val queries = ArticleQueries(service)

        assertEquals(ArticleRecipientRefDto(2L, "243538", null), queries.findArticleRecipientRef(1L, 2L))
        assertEquals(null, queries.findArticleRecipientRef(1L, 3L))
    }

    @Test
    fun `countArticlesForSelect delegates the category and filter, converting the Long result to Int`() {
        val service = mock<ArticleManagementService>()
        whenever(service.countForSelect(InvoiceItemCategory.PRODUCT, "pumpe")).thenReturn(7L)
        val queries = ArticleQueries(service)

        assertEquals(7, queries.countArticlesForSelect(InvoiceItemCategory.PRODUCT, "pumpe"))
    }

    @Test
    fun `findArticles, countArticles and the select lookups accept their default arguments through the port type`() {
        val service = mock<ArticleManagementService>()
        whenever(service.findAll(0, 60, null, null, "name", SortDirection.ASC)).thenReturn(emptyList())
        whenever(service.count(null, null)).thenReturn(0L)
        whenever(service.findForSelect(InvoiceItemCategory.PRODUCT, null, 0, 60)).thenReturn(emptyList())
        whenever(service.countForSelect(InvoiceItemCategory.PRODUCT, null)).thenReturn(0L)
        val queries: ArticleQueryPort = ArticleQueries(service)

        assertEquals(emptyList<ArticleDto>(), queries.findArticles())
        assertEquals(0L, queries.countArticles())
        assertEquals(emptyList<ArticleDto>(), queries.findArticlesForSelect(InvoiceItemCategory.PRODUCT))
        assertEquals(0, queries.countArticlesForSelect(InvoiceItemCategory.PRODUCT))
    }
}
