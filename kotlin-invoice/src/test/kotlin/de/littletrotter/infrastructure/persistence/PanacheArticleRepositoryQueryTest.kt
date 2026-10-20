package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.masterdata.Article
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.shared.likeFilter
import de.littletrotter.domain.shared.likeFilterNamed
import jakarta.persistence.EntityManager
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * article-two-field-search — pins the JPQL the article repository builds:
 *
 * 1. TWO independent filters from ONE shared clause builder on all four query methods — page
 *    and count, with and without category — so the searched fields can never drift apart
 *    between them (L18 discipline): the Nr. filter matches ONLY `articleNumber` (contains,
 *    own `:fnr` parameter), the text filter matches name OR description, both AND when set.
 * 2. The sort allowlist accepts `articleNumber` and maps unknown input to the `a.name`
 * default instead of interpolating it ( §2).
 *
 * The string contract is what a real Postgres run executes; the IT
 * ([PanacheArticleRepositorySearchSortIT]) proves the resulting behavior against real data.
 */
class PanacheArticleRepositoryQueryTest {

    private lateinit var em: EntityManager
    private lateinit var repository: PanacheArticleRepository
    private lateinit var listQuery: TypedQuery<Article>
    private lateinit var countQuery: TypedQuery<Long>

    private val numberPredicate = likeFilterNamed("a.articleNumber", "fnr")
    private val textPredicate = "(${likeFilter("a.name")} OR ${likeFilter("a.description")})"
    private val combinedPredicate = "$numberPredicate AND $textPredicate"

    @BeforeEach
    fun setup() {
        em = mock()
        listQuery = mock(defaultAnswer = Mockito.RETURNS_SELF)
        whenever(listQuery.resultList).thenReturn(emptyList())
        countQuery = mock(defaultAnswer = Mockito.RETURNS_SELF)
        whenever(countQuery.singleResult).thenReturn(0L)
        whenever(em.createQuery(any<String>(), eq(Article::class.java))).thenReturn(listQuery)
        whenever(em.createQuery(any<String>(), eq(Long::class.java))).thenReturn(countQuery)

        repository = PanacheArticleRepository()
        val emField = BasePanacheRepository::class.java.getDeclaredField("em")
        emField.isAccessible = true
        emField.set(repository, em)
    }

    private fun capturedJpql(resultClass: Class<*>): String {
        val captor = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(em).createQuery(captor.capture(), eq(resultClass))
        return captor.value
    }

    @Test
    fun `findAll with a number filter only constrains articleNumber through its own parameter`() {
        repository.findAll(numberFilter = "36")

        val jpql = capturedJpql(Article::class.java)
        assertTrue(jpql.contains(" WHERE $numberPredicate"), "number predicate drifted: $jpql")
        assertFalse(jpql.contains("a.name) LIKE"), "number-only filter must not search the name: $jpql")
    }

    @Test
    fun `findAll with a text filter only searches name and description, never the number`() {
        repository.findAll(textFilter = "Beratung")

        val jpql = capturedJpql(Article::class.java)
        assertTrue(jpql.contains(" WHERE $textPredicate"), "text predicate drifted: $jpql")
        assertFalse(jpql.contains("a.articleNumber"), "text filter must not search the number: $jpql")
    }

    @Test
    fun `findAll with both filters ANDs number and text predicates`() {
        repository.findAll(numberFilter = "36", textFilter = "Beratung")

        val jpql = capturedJpql(Article::class.java)
        assertTrue(jpql.contains(" WHERE $combinedPredicate"), "combined predicate drifted: $jpql")
    }

    @Test
    fun `count uses the identical combined predicate as findAll`() {
        repository.count(numberFilter = "36", textFilter = "Beratung")

        val jpql = capturedJpql(Long::class.java)
        assertTrue(jpql.startsWith("SELECT COUNT(a) FROM Article a WHERE $combinedPredicate"), jpql)
    }

    @Test
    fun `findAll without filters has no WHERE clause`() {
        repository.findAll(numberFilter = null, textFilter = null)

        val jpql = capturedJpql(Article::class.java)
        assertFalse(jpql.contains("WHERE"), jpql)
    }

    @Test
    fun `findAll treats a blank but non-null filter the same as no filter at all`() {
        // every other "no filter" fixture above passes null; isNullOrBlank's own
        // blank-not-null branch is otherwise never exercised for this call site.
        repository.findAll(numberFilter = "   ", textFilter = "")

        val jpql = capturedJpql(Article::class.java)
        assertFalse(jpql.contains("WHERE"), jpql)
    }

    @Test
    fun `count without filters has no WHERE clause`() {
        // count() was previously only ever exercised with both filters set.
        repository.count(numberFilter = null, textFilter = null)

        val jpql = capturedJpql(Long::class.java)
        assertEquals("SELECT COUNT(a) FROM Article a", jpql)
    }

    @Test
    fun `sort allowlist accepts articleNumber in both directions`() {
        repository.findAll(sortField = "articleNumber", sortDir = SortDirection.DESC)

        val jpql = capturedJpql(Article::class.java)
        assertTrue(jpql.endsWith(" ORDER BY a.articleNumber DESC, a.id DESC"), jpql)
    }

    @Test
    fun `sort allowlist maps arbitrary input to the name default instead of interpolating it`() {
        repository.findAll(sortField = "a.name; DROP TABLE article", sortDir = SortDirection.ASC)

        val jpql = capturedJpql(Article::class.java)
        assertFalse(jpql.contains("DROP TABLE"), jpql)
        assertTrue(jpql.endsWith(" ORDER BY a.name ASC, a.id ASC"), jpql)
    }

    @Test
    fun `findAllInCategory ANDs the category with both filter predicates and sorts server-side`() {
        repository.findAllInCategory(
            InvoiceItemCategory.PRODUCT,
            numberFilter = "36",
            textFilter = "Beratung",
            sortField = "articleNumber",
            sortDir = SortDirection.DESC,
        )

        val jpql = capturedJpql(Article::class.java)
        assertTrue(jpql.contains(" WHERE a.category = :category AND $combinedPredicate"), jpql)
        assertTrue(jpql.endsWith(" ORDER BY a.articleNumber DESC, a.id DESC"), jpql)
    }

    @Test
    fun `countInCategory uses the identical predicate as findAllInCategory`() {
        repository.countInCategory(InvoiceItemCategory.PRODUCT, numberFilter = "36", textFilter = "Beratung")

        val jpql = capturedJpql(Long::class.java)
        assertEquals(
            "SELECT COUNT(a) FROM Article a WHERE a.category = :category AND $combinedPredicate",
            jpql,
        )
    }

    @Test
    fun `findAllInCategory without any text-number filter still constrains on the category alone`() {
        // findAllInCategory/countInCategory were previously only ever exercised with
        // both filters set together.
        repository.findAllInCategory(InvoiceItemCategory.PRODUCT, numberFilter = null, textFilter = null)

        val jpql = capturedJpql(Article::class.java)
        assertTrue(jpql.contains(" WHERE a.category = :category ORDER BY"), jpql)
    }

    @Test
    fun `countInCategory without any text-number filter still constrains on the category alone`() {
        repository.countInCategory(InvoiceItemCategory.PRODUCT, numberFilter = null, textFilter = null)

        val jpql = capturedJpql(Long::class.java)
        assertEquals("SELECT COUNT(a) FROM Article a WHERE a.category = :category", jpql)
    }

    // ── findForSelect/countForSelect: the article-select combos' single-input predicate ────

    private val selectPredicate =
        "(${likeFilter("a.articleNumber")} OR ${likeFilter("a.name")} OR ${likeFilter("a.description")})"
    private val selectRankExpr = "CASE WHEN ${likeFilterNamed("a.articleNumber", "fRank")} THEN 0 ELSE 1 END"

    @Test
    fun `findForSelect with a filter ANDs the category with the combined number-name-description predicate`() {
        repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "36")

        val jpql = capturedJpql(Article::class.java)
        assertTrue(jpql.contains(" WHERE a.category = :category AND $selectPredicate"), "predicate drifted: $jpql")
    }

    @Test
    fun `findForSelect with a filter ranks a number hit before name-description-only hits`() {
        repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "36")

        val jpql = capturedJpql(Article::class.java)
        assertTrue(jpql.endsWith(" ORDER BY $selectRankExpr, a.name, a.id"), jpql)
    }

    @Test
    fun `findForSelect without a filter has no WHERE predicate beyond the category and no rank tiebreaker`() {
        repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = null)

        val jpql = capturedJpql(Article::class.java)
        assertEquals(
            "SELECT a FROM Article a WHERE a.category = :category ORDER BY a.name, a.id",
            jpql,
        )
    }

    @Test
    fun `findForSelect with a blank filter behaves like no filter at all`() {
        repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "   ")

        val jpql = capturedJpql(Article::class.java)
        assertEquals(
            "SELECT a FROM Article a WHERE a.category = :category ORDER BY a.name, a.id",
            jpql,
        )
    }

    @Test
    fun `countForSelect with a blank filter behaves like no filter at all`() {
        // countForSelect's own hasFilter line is a distinct branch site from
        // findForSelect's, despite the identical `!filter.isNullOrBlank()` expression.
        repository.countForSelect(InvoiceItemCategory.PRODUCT, filter = "   ")

        val jpql = capturedJpql(Long::class.java)
        assertEquals("SELECT COUNT(a) FROM Article a WHERE a.category = :category", jpql)
    }

    @Test
    fun `countForSelect uses the identical predicate as findForSelect, without an ORDER BY`() {
        repository.countForSelect(InvoiceItemCategory.PRODUCT, filter = "36")

        val jpql = capturedJpql(Long::class.java)
        assertEquals(
            "SELECT COUNT(a) FROM Article a WHERE a.category = :category AND $selectPredicate",
            jpql,
        )
    }

    @Test
    fun `countForSelect without a filter counts the whole category`() {
        repository.countForSelect(InvoiceItemCategory.PRODUCT, filter = null)

        val jpql = capturedJpql(Long::class.java)
        assertEquals("SELECT COUNT(a) FROM Article a WHERE a.category = :category", jpql)
    }

    @Test
    fun `findForSelect binds the same pattern to both the plain and the rank-tiebreaker parameter`() {
        repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "Pumpe")

        Mockito.verify(listQuery).setParameter("f", "%pumpe%")
        Mockito.verify(listQuery).setParameter("fRank", "%pumpe%")
    }

    @Test
    fun `findForSelect without a filter binds neither the plain nor the rank-tiebreaker parameter`() {
        repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = null)

        Mockito.verify(listQuery, Mockito.never()).setParameter(eq("f"), any<String>())
        Mockito.verify(listQuery, Mockito.never()).setParameter(eq("fRank"), any<String>())
    }
}
