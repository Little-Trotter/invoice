package de.littletrotter.domain.repository

import de.littletrotter.domain.masterdata.Article
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.ArticleNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * UI-8a — [ArticleRepository.findAllInCategory]/[ArticleRepository.countInCategory] carry a
 * default body: a compatibility fallback for the handful of anonymous [ArticleRepository] test
 * doubles elsewhere (e.g. `InvoiceLifecycleServiceTest.articleRepoWith`) that predate these
 * methods and never override them — see the KDoc on both methods in the interface itself.
 * [de.littletrotter.infrastructure.persistence.PanacheArticleRepository] always overrides them
 * with a real query-level constraint, so this default body never runs in production — but it is
 * still new code this AP added, so it gets its own direct test instead of relying on being
 * exercised incidentally through some other repository's fake.
 */
class ArticleRepositoryDefaultsTest {

    private fun article(id: Long, name: String, category: InvoiceItemCategory) = Article().apply {
        this.id = id
        this.name = name
        this.category = category
    }

    /** Minimal [ArticleRepository] implementing only the base [ArticleRepository.findAll]/
     *  [ArticleRepository.count] pair — [ArticleRepository.findAllInCategory]/
     *  [ArticleRepository.countInCategory] are left unimplemented, so calling them runs the
     *  interface's own default body under test. */
    private fun repoWith(vararg articles: Article): ArticleRepository = object : ArticleRepository {
        private val data = articles.toList()
        override fun findAll(pageIndex: Int, pageSize: Int, numberFilter: String?, textFilter: String?, sortField: String, sortDir: SortDirection) = data
        override fun count(numberFilter: String?, textFilter: String?): Long = data.size.toLong()
        override fun findForSelect(category: InvoiceItemCategory, filter: String?, pageIndex: Int, pageSize: Int): List<Article> =
            data.filter { it.category == category }.drop(pageIndex * pageSize).take(pageSize)
        override fun countForSelect(category: InvoiceItemCategory, filter: String?): Long =
            data.count { it.category == category }.toLong()
        override fun findById(id: Long): Article? = data.find { it.id == id }
        override fun save(article: Article): Article = article
        override fun forceVersionIncrement(article: Article) { article.version = (article.version ?: 0L) + 1L }
        override fun delete(id: Long) {}
    }

    @Test
    fun `default findAllInCategory keeps only the matching category`() {
        val repo = repoWith(
            article(1, "Pumpe", InvoiceItemCategory.PRODUCT),
            article(2, "Beratung", InvoiceItemCategory.SERVICE),
            article(3, "Ventil", InvoiceItemCategory.PRODUCT),
            article(4, "Wartung", InvoiceItemCategory.SERVICE),
        )

        val result = repo.findAllInCategory(InvoiceItemCategory.PRODUCT, pageIndex = 0, pageSize = 60)

        assertEquals(listOf(1L, 3L), result.map { it.id })
    }

    @Test
    fun `boundary findAllInCategory with pageIndex and pageSize omitted uses the documented defaults`() {
        val repo = repoWith(article(1, "Pumpe", InvoiceItemCategory.PRODUCT))

        val result = repo.findAllInCategory(InvoiceItemCategory.PRODUCT)

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `default findAllInCategory applies the pageIndex times pageSize offset window after filtering`() {
        val repo = repoWith(
            article(1, "A", InvoiceItemCategory.PRODUCT),
            article(2, "B", InvoiceItemCategory.PRODUCT),
            article(3, "C", InvoiceItemCategory.PRODUCT),
        )

        val result = repo.findAllInCategory(InvoiceItemCategory.PRODUCT, pageIndex = 1, pageSize = 2)

        assertEquals(listOf(3L), result.map { it.id })
    }

    @Test
    fun `default countInCategory counts only the matching category`() {
        val repo = repoWith(
            article(1, "A", InvoiceItemCategory.PRODUCT),
            article(2, "B", InvoiceItemCategory.SERVICE),
            article(3, "C", InvoiceItemCategory.PRODUCT),
        )

        assertEquals(2L, repo.countInCategory(InvoiceItemCategory.PRODUCT))
    }

    @Test
    fun `default countInCategory returns zero when nothing matches`() {
        val repo = repoWith(article(1, "A", InvoiceItemCategory.SERVICE))

        assertEquals(0L, repo.countInCategory(InvoiceItemCategory.PRODUCT))
    }

    @Test
    fun `happy path isInUse defaults to false`() {
        val repo = repoWith(article(1, "Pumpe", InvoiceItemCategory.PRODUCT))

        assertEquals(false, repo.isInUse(1))
    }

    @Test
    fun `boundary findAllArticleNumbers filters out a null article number`() {
        // a blank-but-non-null number is unrepresentable now (ArticleNumber's own init
        // rejects it at construction), so only the null case is left to exercise here.
        val repo = repoWith(
            article(1, "A", InvoiceItemCategory.PRODUCT).apply { articleNumber = ArticleNumber("ART-1") },
            article(2, "B", InvoiceItemCategory.PRODUCT),
            article(3, "C", InvoiceItemCategory.PRODUCT).apply { articleNumber = ArticleNumber("ART-3") },
        )

        assertEquals(listOf("ART-1", "ART-3"), repo.findAllArticleNumbers())
    }

    @Test
    fun `boundary findAllArticleNumbers is empty without articles`() {
        val repo = repoWith()

        assertEquals(emptyList<String>(), repo.findAllArticleNumbers())
    }

    @Test
    fun `boundary saveAndFlush delegates to save`() {
        val repo = repoWith()
        val toSave = article(9, "Ventil", InvoiceItemCategory.PRODUCT)

        assertEquals(toSave, repo.saveAndFlush(toSave))
    }

    @Test
    fun `boundary findAll with every argument omitted uses the documented defaults`() {
        var captured: List<Any?>? = null
        val repo = object : ArticleRepository {
            override fun findAll(pageIndex: Int, pageSize: Int, numberFilter: String?, textFilter: String?, sortField: String, sortDir: SortDirection): List<Article> {
                captured = listOf(pageIndex, pageSize, numberFilter, textFilter, sortField, sortDir)
                return emptyList()
            }
            override fun count(numberFilter: String?, textFilter: String?) = 0L
            override fun findForSelect(category: InvoiceItemCategory, filter: String?, pageIndex: Int, pageSize: Int) = emptyList<Article>()
            override fun countForSelect(category: InvoiceItemCategory, filter: String?) = 0L
            override fun findById(id: Long): Article? = null
            override fun save(article: Article) = article
            override fun forceVersionIncrement(article: Article) {}
            override fun delete(id: Long) {}
        }

        repo.findAll()

        assertEquals(listOf(0, 60, null, null, "name", SortDirection.ASC), captured)
    }

    @Test
    fun `boundary count and countForSelect are exercised directly through the interface`() {
        val repo = repoWith(article(1, "Pumpe", InvoiceItemCategory.PRODUCT))

        assertEquals(1L, repo.count())
        assertEquals(0L, repo.countForSelect(InvoiceItemCategory.SERVICE))
    }

    @Test
    fun `boundary findForSelect with only category given uses the documented defaults`() {
        var captured: List<Any?>? = null
        val repo = object : ArticleRepository {
            override fun findAll(pageIndex: Int, pageSize: Int, numberFilter: String?, textFilter: String?, sortField: String, sortDir: SortDirection) = emptyList<Article>()
            override fun count(numberFilter: String?, textFilter: String?) = 0L
            override fun findForSelect(category: InvoiceItemCategory, filter: String?, pageIndex: Int, pageSize: Int): List<Article> {
                captured = listOf(category, filter, pageIndex, pageSize)
                return emptyList()
            }
            override fun countForSelect(category: InvoiceItemCategory, filter: String?) = 0L
            override fun findById(id: Long): Article? = null
            override fun save(article: Article) = article
            override fun forceVersionIncrement(article: Article) {}
            override fun delete(id: Long) {}
        }

        repo.findForSelect(InvoiceItemCategory.SERVICE)

        assertEquals(listOf(InvoiceItemCategory.SERVICE, null, 0, 60), captured)
    }
}
