package de.littletrotter.application.masterdata

import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.fixture.anArticle
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Assertions.assertThrows
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * UI-8a — [ArticleManagementService.findAllInCategory]/[ArticleManagementService.countInCategory] are thin pass-throughs to [ArticleRepository]'s same-named methods, backing the article-select combos' lazy fetch. This is the one place that exercises the real (non-overridden) method bodies: every other test in the suite that needs an [ArticleManagementService] collaborator overrides these two in an anonymous subclass for its own scenario ([de.littletrotter.application.reference.ReferenceDataFacadeImplTest]), which never runs this class's own delegation.
 */
class ArticleManagementServiceTest {

    @Test
    fun `deleting an article used by an invoice is rejected before repository delete`() {
        val repo = mock<ArticleRepository>()
        whenever(repo.isInUse(42L)).thenReturn(true)
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        assertThrows(InvalidParameterException::class.java) { service.delete(42L) }
        verify(repo, org.mockito.kotlin.never()).delete(42L)
    }

    @Test
    fun `deleting an article without invoice usage delegates to repository`() {
        val repo = mock<ArticleRepository>()
        whenever(repo.isInUse(42L)).thenReturn(false)
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        service.delete(42L)

        verify(repo).delete(42L)
    }

    @Test
    fun `findAllInCategory delegates category, paging and both non-blank filters to the repository unchanged`() {
        val article = anArticle { id = 61L; name = "Kunde jenseits der alten Kappe" }
        val repo = mock<ArticleRepository> {
            on { findAllInCategory(InvoiceItemCategory.PRODUCT, 2, 60, "36", "Pumpe") }.thenReturn(listOf(article))
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        val result = service.findAllInCategory(
            InvoiceItemCategory.PRODUCT, pageIndex = 2, pageSize = 60, numberFilter = "36", textFilter = "Pumpe",
        )

        assertEquals(listOf(article), result)
    }

    @Test
    fun `findAllInCategory passes null filters through unchanged`() {
        val repo = mock<ArticleRepository> {
            on { findAllInCategory(InvoiceItemCategory.SERVICE, 0, 60, null, null) }.thenReturn(emptyList())
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        val result = service.findAllInCategory(InvoiceItemCategory.SERVICE, numberFilter = null, textFilter = null)

        assertEquals(emptyList<Article>(), result)
        verify(repo).findAllInCategory(InvoiceItemCategory.SERVICE, 0, 60, null, null)
    }

    @Test
    fun `findAllInCategory passes the sort parameters through unchanged`() {
        // fix-article-search-sort — the Artikel view routes its category filter through this
        // method, so it must carry findAll's sort contract too.
        val repo = mock<ArticleRepository> {
            on {
                findAllInCategory(
                    InvoiceItemCategory.PRODUCT, 0, 60, "art", null, "articleNumber", de.littletrotter.domain.shared.SortDirection.DESC,
                )
            }.thenReturn(emptyList())
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        service.findAllInCategory(
            InvoiceItemCategory.PRODUCT,
            numberFilter = "art",
            sortField = "articleNumber",
            sortDir = de.littletrotter.domain.shared.SortDirection.DESC,
        )

        verify(repo).findAllInCategory(
            InvoiceItemCategory.PRODUCT, 0, 60, "art", null, "articleNumber", de.littletrotter.domain.shared.SortDirection.DESC,
        )
    }

    @Test
    fun `countInCategory delegates the category and both filters to the repository`() {
        val repo = mock<ArticleRepository> {
            on { countInCategory(InvoiceItemCategory.PRODUCT, "36", "Pumpe") }.thenReturn(3L)
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        assertEquals(3L, service.countInCategory(InvoiceItemCategory.PRODUCT, "36", "Pumpe"))
    }

    @Test
    fun `countInCategory passes null filters through unchanged`() {
        val repo = mock<ArticleRepository> {
            on { countInCategory(InvoiceItemCategory.SERVICE, null, null) }.thenReturn(0L)
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        assertEquals(0L, service.countInCategory(InvoiceItemCategory.SERVICE))
        verify(repo).countInCategory(InvoiceItemCategory.SERVICE, null, null)
    }

    // ── findForSelect/countForSelect: thin pass-throughs, same shape as findAllInCategory ────────

    @Test
    fun `findForSelect delegates category, paging and the single filter to the repository unchanged`() {
        val article = anArticle { id = 61L; name = "Kabelkanal" }
        val repo = mock<ArticleRepository> {
            on { findForSelect(InvoiceItemCategory.PRODUCT, "00036", 2, 60) }.thenReturn(listOf(article))
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        val result = service.findForSelect(InvoiceItemCategory.PRODUCT, filter = "00036", pageIndex = 2, pageSize = 60)

        assertEquals(listOf(article), result)
    }

    @Test
    fun `findForSelect passes a null filter through unchanged`() {
        val repo = mock<ArticleRepository> {
            on { findForSelect(InvoiceItemCategory.SERVICE, null, 0, 60) }.thenReturn(emptyList())
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        val result = service.findForSelect(InvoiceItemCategory.SERVICE, filter = null)

        assertEquals(emptyList<Article>(), result)
        verify(repo).findForSelect(InvoiceItemCategory.SERVICE, null, 0, 60)
    }

    @Test
    fun `countForSelect delegates the category and filter to the repository`() {
        val repo = mock<ArticleRepository> {
            on { countForSelect(InvoiceItemCategory.PRODUCT, "00036") }.thenReturn(3L)
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        assertEquals(3L, service.countForSelect(InvoiceItemCategory.PRODUCT, "00036"))
    }

    @Test
    fun `countForSelect passes a null filter through unchanged`() {
        val repo = mock<ArticleRepository> {
            on { countForSelect(InvoiceItemCategory.SERVICE, null) }.thenReturn(0L)
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        assertEquals(0L, service.countForSelect(InvoiceItemCategory.SERVICE))
        verify(repo).countForSelect(InvoiceItemCategory.SERVICE, null)
    }

    // ── findAll/count (the article-wide, non-category overloads) had no direct test: ─────────────
    // ── every existing case above only exercises findAllInCategory/countInCategory/-ForSelect. ──

    @Test
    fun `boundary - findAll with only a number filter defaults textFilter, paging and sort to their article-wide values`() {
        val article = anArticle { id = 61L; name = "Schraube" }
        val repo = mock<ArticleRepository> {
            on { findAll(0, 60, "36", null, "name", de.littletrotter.domain.shared.SortDirection.ASC) }.thenReturn(listOf(article))
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        val result = service.findAll(numberFilter = "36")

        assertEquals(listOf(article), result)
    }

    @Test
    fun `happy path - count with no filters delegates two null filters to the repository`() {
        val repo = mock<ArticleRepository> {
            on { count(null, null) }.thenReturn(7L)
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        assertEquals(7L, service.count())
    }

    // ── Omitting numberFilter/filter (not passing null explicitly) exercises the ────────────────
    // ── parameters' own default-value bytecode, which the explicit-null tests above skip.  ──

    @Test
    fun `boundary - findAllInCategory omitting numberFilter defaults it to null`() {
        val repo = mock<ArticleRepository> {
            on { findAllInCategory(InvoiceItemCategory.SERVICE, 0, 60, null, "Pumpe") }.thenReturn(emptyList())
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        val result = service.findAllInCategory(InvoiceItemCategory.SERVICE, textFilter = "Pumpe")

        assertEquals(emptyList<Article>(), result)
        verify(repo).findAllInCategory(InvoiceItemCategory.SERVICE, 0, 60, null, "Pumpe")
    }

    @Test
    fun `boundary - findById delegates to the repository`() {
        val article = anArticle { id = 61L; name = "Schraube" }
        val repo = mock<ArticleRepository> {
            on { findById(61L) }.thenReturn(article)
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        assertEquals(article, service.findById(61L))
    }

    @Test
    fun `boundary - findAll omitting numberFilter defaults it to null`() {
        val repo = mock<ArticleRepository> {
            on { findAll(0, 60, null, "Pumpe", "name", de.littletrotter.domain.shared.SortDirection.ASC) }.thenReturn(emptyList())
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        val result = service.findAll(textFilter = "Pumpe")

        assertEquals(emptyList<Article>(), result)
    }

    @Test
    fun `boundary - findForSelect omitting filter defaults it to null`() {
        val repo = mock<ArticleRepository> {
            on { findForSelect(InvoiceItemCategory.SERVICE, null, 0, 60) }.thenReturn(emptyList())
        }
        val service = ArticleManagementService(repo, mock(), time = FIXED_TIME_SOURCE)

        val result = service.findForSelect(InvoiceItemCategory.SERVICE)

        assertEquals(emptyList<Article>(), result)
        verify(repo).findForSelect(InvoiceItemCategory.SERVICE, null, 0, 60)
    }
}
