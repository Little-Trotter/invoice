package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.ArticleNumber
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * article-two-field-search — the real [PanacheArticleRepository] against real Postgres:
 *
 * (a) the Nr. filter matches the articleNumber with CONTAINS semantics — the trailing digits
 *     people say out loud ("…-0003") find the row, a prefix works too — page and count
 *     consistently;
 * (b) the text filter searches name and description ONLY, and ANDed with the Nr. filter both
 *     narrow together with consistent page/count;
 * (c) name/articleNumber/defaultUnit sorting actually orders SERVER-side — page 0 of a
 *     more-than-one-page set differs between ASC and DESC;
 * (d) category + both filters + sort combined keep page and count consistent.
 *
 * All queries filter on the test-owned `PASIT` prefix: the IT Postgres container is shared
 * across classes, so unconstrained extremes could see other tests' rows.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheArticleRepositorySearchSortIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: ArticleRepository

    @BeforeEach
    fun removeOwnRows() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM article WHERE article_number LIKE 'PASIT-%'").executeUpdate()
        }
    }

    private fun seedArticle(
        number: String,
        name: String,
        category: InvoiceItemCategory,
        unit: InvoiceItemUnit,
        description: String? = null,
    ): Article = QuarkusTransaction.requiringNew().call {
        val article = Article().apply {
            this.articleNumber = ArticleNumber(number)
            this.name = name
            this.category = category
            this.defaultUnit = unit
            this.description = description
        }
        em.persist(article)
        em.flush()
        article
    }

    /** Five articles, names deliberately NOT in number order so name and number sorts differ. */
    private fun seedFive() {
        seedArticle("PASIT-A-0001", "Zaunbau PASIT", InvoiceItemCategory.PRODUCT, InvoiceItemUnit.PIECE)
        seedArticle("PASIT-A-0002", "Montage PASIT", InvoiceItemCategory.SERVICE, InvoiceItemUnit.HOUR)
        seedArticle("PASIT-A-0003", "Anfahrt PASIT", InvoiceItemCategory.TRIP, InvoiceItemUnit.KILOMETER)
        seedArticle("PASIT-B-0004", "Beratung PASIT", InvoiceItemCategory.SERVICE, InvoiceItemUnit.DAY)
        seedArticle("PASIT-B-0005", "Wartung PASIT", InvoiceItemCategory.SERVICE, InvoiceItemUnit.FLAT_RATE)
    }

    @Test
    fun `a number prefix in the Nr filter narrows page and count to the number-matching rows`() {
        seedFive()

        val rows = repository.findAll(numberFilter = "pasit-a-")
        val count = repository.count(numberFilter = "pasit-a-")

        assertEquals(listOf("PASIT-A-0001", "PASIT-A-0002", "PASIT-A-0003"), rows.mapNotNull { it.articleNumber?.value }.sorted())
        assertEquals(3L, count)
    }

    @Test
    fun `the Nr filter matches CONTAINS — the trailing digits find the row with correct count`() {
        seedFive()

        // The PO says "36" out loud, not "ART-2026-00036" — here: "-0003" mid-number.
        val rows = repository.findAll(numberFilter = "-0003")
        val count = repository.count(numberFilter = "-0003")

        assertEquals(listOf("PASIT-A-0003"), rows.map { it.articleNumber?.value })
        assertEquals(1L, count)
    }

    @Test
    fun `the text filter matches name and description but never the number`() {
        seedFive()
        seedArticle(
            "PASIT-C-0006", "Sonderposten", InvoiceItemCategory.PRODUCT, InvoiceItemUnit.PIECE,
            description = "Beschreibung mit PASIT-Beratungsanteil",
        )

        val byName = repository.findAll(textFilter = "beratung pasit")
        val byDescription = repository.findAll(textFilter = "pasit-beratungsanteil")
        val byNumberInTextField = repository.findAll(textFilter = "pasit-a-0001")

        assertEquals(listOf("PASIT-B-0004"), byName.map { it.articleNumber?.value })
        assertEquals(listOf("PASIT-C-0006"), byDescription.map { it.articleNumber?.value })
        assertEquals(emptyList<String>(), byNumberInTextField.mapNotNull { it.articleNumber?.value })
    }

    @Test
    fun `number and text filters AND-combine and keep page and count consistent`() {
        seedFive()

        // "pasit-a-" alone matches 3 rows, "montage" alone 1 — together exactly the overlap.
        val rows = repository.findAll(numberFilter = "pasit-a-", textFilter = "montage")
        val count = repository.count(numberFilter = "pasit-a-", textFilter = "montage")
        val disjointCount = repository.count(numberFilter = "pasit-b-", textFilter = "montage")

        assertEquals(listOf("PASIT-A-0002"), rows.map { it.articleNumber?.value })
        assertEquals(1L, count)
        assertEquals(0L, disjointCount)
    }

    @Test
    fun `name sort orders server-side, page 0 differs between asc and desc`() {
        seedFive()

        val ascPage0 = repository.findAll(0, 2, "PASIT-", null, "name", SortDirection.ASC)
        val descPage0 = repository.findAll(0, 2, "PASIT-", null, "name", SortDirection.DESC)

        assertEquals(listOf("Anfahrt PASIT", "Beratung PASIT"), ascPage0.map { it.name })
        assertEquals(listOf("Zaunbau PASIT", "Wartung PASIT"), descPage0.map { it.name })
        assertNotEquals(ascPage0.map { it.id }, descPage0.map { it.id })
    }

    @Test
    fun `articleNumber sort orders server-side, page 0 differs between asc and desc`() {
        seedFive()

        val ascPage0 = repository.findAll(0, 2, "PASIT-", null, "articleNumber", SortDirection.ASC)
        val descPage0 = repository.findAll(0, 2, "PASIT-", null, "articleNumber", SortDirection.DESC)

        assertEquals(listOf("PASIT-A-0001", "PASIT-A-0002"), ascPage0.map { it.articleNumber?.value })
        assertEquals(listOf("PASIT-B-0005", "PASIT-B-0004"), descPage0.map { it.articleNumber?.value })
    }

    @Test
    fun `defaultUnit sort orders server-side and desc reverses asc`() {
        seedFive()

        val asc = repository.findAll(0, 5, "PASIT-", null, "defaultUnit", SortDirection.ASC)
        val desc = repository.findAll(0, 5, "PASIT-", null, "defaultUnit", SortDirection.DESC)
        val ascPage0 = repository.findAll(0, 2, "PASIT-", null, "defaultUnit", SortDirection.ASC)
        val descPage0 = repository.findAll(0, 2, "PASIT-", null, "defaultUnit", SortDirection.DESC)

        // All five units are distinct, so DESC over the full set is exactly ASC reversed —
        // whatever smallint code the converter persists per unit.
        assertEquals(asc.map { it.id }.reversed(), desc.map { it.id })
        assertNotEquals(ascPage0.map { it.id }, descPage0.map { it.id })
    }

    @Test
    fun `unknown sort fields fall back to the name default instead of failing`() {
        seedFive()

        val rows = repository.findAll(0, 5, "PASIT-", null, "no-such-column", SortDirection.ASC)

        assertEquals(listOf("Anfahrt PASIT", "Beratung PASIT", "Montage PASIT", "Wartung PASIT", "Zaunbau PASIT"), rows.map { it.name })
    }

    @Test
    fun `category with number filter and sort keeps page and count consistent`() {
        seedFive()

        val descPage0 = repository.findAllInCategory(
            InvoiceItemCategory.SERVICE, 0, 2, "pasit-", null, "articleNumber", SortDirection.DESC,
        )
        val descPage1 = repository.findAllInCategory(
            InvoiceItemCategory.SERVICE, 1, 2, "pasit-", null, "articleNumber", SortDirection.DESC,
        )
        val count = repository.countInCategory(InvoiceItemCategory.SERVICE, "pasit-")

        assertEquals(listOf("PASIT-B-0005", "PASIT-B-0004"), descPage0.map { it.articleNumber?.value })
        assertEquals(listOf("PASIT-A-0002"), descPage1.map { it.articleNumber?.value })
        assertEquals(3L, count)
        assertTrue(descPage0.all { it.category == InvoiceItemCategory.SERVICE })
    }

    @Test
    fun `category count narrows by the number filter too`() {
        seedFive()

        assertEquals(1L, repository.countInCategory(InvoiceItemCategory.SERVICE, "pasit-b-0004"))
        assertEquals(0L, repository.countInCategory(InvoiceItemCategory.PRODUCT, "pasit-b-"))
    }

    // ── the four buildFiltered*Query helpers' own "no filter at all" branch: every ────────
    // ── existing count()/findAllInCategory()/countInCategory() call above always passes at least ──
    // ── one filter, so filterPredicate's null (both flags false) result was never reached here ────
    // ── for these three (findAll's own null-clause branch is already covered by the mock-based ────
    // ── PanacheArticleRepositoryQueryTest's "findAll without filters has no WHERE clause" case). ──

    // Both of the following count against the raw table rather than a fixed literal: `article` is
    // a shared, never-cleaned table across the whole IT suite, and these two branches specifically
    // mean "no WHERE clause at all" / "no filter clause ANDed onto the category" -- a hardcoded
    // expected count would be wrong (and flaky) the moment any other test class's fixture rows
    // are present, which is exactly the state a real shared database is normally in.

    @Test
    fun `count without any filter counts the whole table -- buildFilteredCountQuery's own no-filter branch`() {
        seedFive()

        val expectedTotal = (em.createNativeQuery("SELECT COUNT(*) FROM article").singleResult as Number).toLong()
        assertEquals(expectedTotal, repository.count(numberFilter = null, textFilter = null))
    }

    @Test
    fun `findAllInCategory and countInCategory without any filter still narrow by category alone`() {
        seedFive()

        val expectedCount = (
            em.createNativeQuery("SELECT COUNT(*) FROM article WHERE category = :cat")
                .setParameter("cat", InvoiceItemCategory.SERVICE.code)
                .singleResult as Number
            ).toLong()

        val rows = repository.findAllInCategory(InvoiceItemCategory.SERVICE, 0, 500, numberFilter = null, textFilter = null)
        val count = repository.countInCategory(InvoiceItemCategory.SERVICE, numberFilter = null, textFilter = null)

        assertEquals(expectedCount, count)
        assertTrue(rows.all { it.category == InvoiceItemCategory.SERVICE })
        assertTrue(
            rows.mapNotNull { it.articleNumber?.value }.toSet().containsAll(setOf("PASIT-A-0002", "PASIT-B-0004", "PASIT-B-0005")),
            "our own three SERVICE-category PASIT rows must be among the unfiltered category results",
        )
    }

    @Test
    fun `category plus number plus text together keep page and count consistent`() {
        seedFive()

        // SERVICE ∩ number "pasit-" (all five) ∩ text "wartung" → exactly PASIT-B-0005.
        val rows = repository.findAllInCategory(
            InvoiceItemCategory.SERVICE, 0, 60, "pasit-", "wartung", "articleNumber", SortDirection.ASC,
        )
        val count = repository.countInCategory(InvoiceItemCategory.SERVICE, "pasit-", "wartung")
        val wrongCategoryCount = repository.countInCategory(InvoiceItemCategory.PRODUCT, "pasit-", "wartung")

        assertEquals(listOf("PASIT-B-0005"), rows.map { it.articleNumber?.value })
        assertEquals(1L, count)
        assertEquals(0L, wrongCategoryCount)
    }
}
