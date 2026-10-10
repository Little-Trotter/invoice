package de.littletrotter.application.masterdata

import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.ArticleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import jakarta.persistence.OptimisticLockException
import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [ArticleManagementService.save] and [ArticleManagementService.findRecipientRef]
 * had no direct unit test before; only [ArticleManagementService
 * .findAllInCategory]/[ArticleManagementService.countInCategory] were covered
 * ([ArticleManagementServiceTest]).
 */
class ArticleManagementServiceSaveTest {

    private val repository = mock<ArticleRepository>()
    private val numberService = mock<MasterDataNumberService>()
    private val service = ArticleManagementService(repository, numberService, time = FIXED_TIME_SOURCE)

    private fun validArticle(id: Long? = null) = Article().apply { this.id = id; name = "Schraube" }

    @Test
    fun `save fragt fuer einen neuen Artikel eine Artikelnummer an`() {
        val article = validArticle()
        whenever(numberService.saveArticleWithNumber<Article>(eq(article), eq(null), any(), any())).thenAnswer { inv ->
            inv.getArgument<() -> Article>(3).invoke()
        }
        whenever(repository.saveAndFlush(article)).thenReturn(article)

        service.save(article)

        verify(numberService).assignArticleNumber(eq(article), eq(null))
    }

    @Test
    fun `save fragt fuer einen bestehenden Artikel keine neue Artikelnummer an und aktualisiert direkt`() {
        val article = validArticle(id = 5L)
        whenever(repository.findById(5L)).thenReturn(article)
        whenever(repository.save(article)).thenReturn(article)

        val saved = service.save(article)

        assertEquals(article, saved)
        verify(numberService, never()).assignArticleNumber(any<Article>(), anyOrNull())
        verify(numberService, never()).saveArticleWithNumber<Article>(any(), any(), any(), any())
    }

    @Test
    fun `save reconciles detached customer mappings onto managed rows`() {
        val managed = validArticle(5L)
        val recipient = Recipient().apply { id = 2L }
        managed.replaceRecipientRefs(listOf(ArticleRecipientRef.of(managed, recipient, "DEMO2-1", null)))
        val existing = managed.recipientRefs.single().apply { id = 9L }
        val submitted = validArticle(5L)
        submitted.name = "Demo 2 edited catalogue position"
        submitted.replaceRecipientRefs(listOf(ArticleRecipientRef.of(submitted, recipient, "DEMO2-UPDATED", "Updated")))
        whenever(repository.findById(5L)).thenReturn(managed)
        whenever(repository.save(managed)).thenReturn(managed)

        val saved = service.save(submitted)

        assertEquals(managed, saved)
        assertEquals(submitted.name, saved.name)
        assertEquals(existing, saved.recipientRefs.single())
        assertEquals(9L, existing.id)
        assertEquals("DEMO2-UPDATED", existing.buyerArticleNumber)
        verify(repository).forceVersionIncrement(managed)
        verify(repository, never()).save(submitted)
    }

    @Test
    fun `save rejects a stale aggregate without mutating managed state`() {
        val managed = validArticle(5L).apply { version = 2L }
        whenever(repository.findById(5L)).thenReturn(managed)

        assertThrows(OptimisticLockException::class.java) { service.save(validArticle(5L)) }

        assertEquals("Schraube", managed.name)
        verify(repository, never()).save(any())
    }

    @Test
    fun `save rejects deleted aggregate without inserting it again`() {
        assertThrows(InvalidParameterException::class.java) { service.save(validArticle(5L)) }
        verify(repository, never()).save(any())
    }

    @Test
    fun `save reicht die vorgeschlagene Artikelnummer an den saveArticleWithNumber-Aufruf durch`() {
        val article = validArticle()
        whenever(numberService.saveArticleWithNumber<Article>(eq(article), eq("ART-042"), any(), any())).thenAnswer { inv ->
            inv.getArgument<() -> Article>(3).invoke()
        }
        whenever(repository.saveAndFlush(article)).thenReturn(article)

        service.save(article, proposedArticleNumber = "ART-042")

        verify(numberService).saveArticleWithNumber<Article>(eq(article), eq("ART-042"), any(), any())
    }

    // ── WP2 (0.9.10) — delete-vs-update: expectedVersion ───────────────────

    @Test
    fun `delete rejects a stale expectedVersion without deleting`() {
        val managed = validArticle(5L).apply { version = 2L }
        whenever(repository.findById(5L)).thenReturn(managed)

        assertThrows(OptimisticLockException::class.java) { service.delete(5L, expectedVersion = 1L) }

        verify(repository, never()).delete(any())
    }

    @Test
    fun `delete with a matching expectedVersion succeeds`() {
        val managed = validArticle(5L).apply { version = 2L }
        whenever(repository.findById(5L)).thenReturn(managed)

        service.delete(5L, expectedVersion = 2L)

        verify(repository).delete(5L)
    }

    @Test
    fun `delete rejects an already-deleted aggregate when an expectedVersion is given`() {
        whenever(repository.findById(5L)).thenReturn(null)

        assertThrows(InvalidParameterException::class.java) { service.delete(5L, expectedVersion = 2L) }

        verify(repository, never()).delete(any())
    }

    @Test
    fun `delete with a null expectedVersion skips the check (backward-compatible default)`() {
        service.delete(5L)

        verify(repository, never()).findById(any())
        verify(repository).delete(5L)
    }

    @Test
    fun `findRecipientRef liefert null wenn der Artikel nicht existiert`() {
        whenever(repository.findById(99L)).thenReturn(null)

        assertNull(service.findRecipientRef(99L, 1L))
    }

    @Test
    fun `findRecipientRef liefert null wenn der Artikel keine Zuordnung fuer den Kunden hat`() {
        val article = validArticle(id = 1L)
        whenever(repository.findById(1L)).thenReturn(article)

        assertNull(service.findRecipientRef(1L, 2L))
    }

    @Test
    fun `findRecipientRef liefert die Zuordnung wenn der Artikel eine fuer den Kunden hat`() {
        val article = validArticle(id = 1L)
        val recipient = Recipient().apply { id = 2L }
        val ref = ArticleRecipientRef().apply { this.article = article; this.recipient = recipient; buyerArticleNumber = "K-77" }
        article.replaceRecipientRefs(listOf(ref))
        whenever(repository.findById(1L)).thenReturn(article)

        val result = service.findRecipientRef(1L, 2L)

        assertEquals("K-77", result?.buyerArticleNumber)
    }
}
