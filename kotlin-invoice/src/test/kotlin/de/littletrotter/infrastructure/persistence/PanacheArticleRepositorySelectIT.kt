package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.ArticleNumber
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [PanacheArticleRepository.findForSelect]/[PanacheArticleRepository.countForSelect] against
 * real Postgres: the article-select combos' single-input search (number OR name OR description,
 * number hits ranked first). The JPQL shape itself is pinned by the mocked
 * [PanacheArticleRepositoryQueryTest]; this proves the `CASE … LIKE … ESCAPE` ORDER BY tiebreaker
 * and a nullable `article_number` actually behave against a real database, plus every border case
 * (B1-B6) the spec calls out.
 *
 * All rows carry the test-owned `IN2SEL` fragment in their name (and, unless deliberately left
 * `null`, their number) — the IT Postgres container is shared across classes, so unconstrained
 * counts could see other tests' rows.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheArticleRepositorySelectIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: ArticleRepository

    @BeforeEach
    fun removeOwnRows() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM article WHERE name LIKE 'IN2SEL%'").executeUpdate()
        }
    }

    private fun seedArticle(
        name: String,
        category: InvoiceItemCategory,
        number: String? = null,
        description: String? = null,
    ): Article = QuarkusTransaction.requiringNew().call {
        val article = Article().apply {
            this.name = name
            this.category = category
            this.defaultUnit = InvoiceItemUnit.PIECE
            this.articleNumber = number?.let(::ArticleNumber)
            this.description = description
        }
        em.persist(article)
        em.flush()
        article
    }

    // ── B1 — category constraint survives the OR (parenthesisation bug class) ─────────────────

    @Test
    fun `a number fragment shared across categories only offers the article in the queried category`() {
        val inProduct = seedArticle("IN2SEL B1 Produkt", InvoiceItemCategory.PRODUCT, number = "IN2SEL-9001")
        seedArticle("IN2SEL B1 Dienstleistung", InvoiceItemCategory.SERVICE, number = "IN2SEL-9001-SVC")

        val rows = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "9001")
        val count = repository.countForSelect(InvoiceItemCategory.PRODUCT, filter = "9001")

        assertEquals(listOf(inProduct.id), rows.map { it.id })
        assertEquals(1L, count)
    }

    // ── B2 — a number hit and a name-only hit both offered, number hit first ──────────────────

    @Test
    fun `an article matched by number ranks before an article matched only by name`() {
        val byNumber = seedArticle("IN2SEL B2 Kabelkanal", InvoiceItemCategory.PRODUCT, number = "IN2SEL-7777")
        val byName = seedArticle("IN2SEL B2 Sonderposten 7777", InvoiceItemCategory.PRODUCT, number = "IN2SEL-8888")

        val rows = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "7777")

        assertEquals(listOf(byNumber.id, byName.id), rows.map { it.id })
        assertEquals(2L, repository.countForSelect(InvoiceItemCategory.PRODUCT, filter = "7777"))
    }

    // ── B3 — a null articleNumber neither matches by number nor breaks the CASE ────────────────

    @Test
    fun `an article with a null number is found by name, never by number, and never breaks the CASE`() {
        val numberless = seedArticle("IN2SEL B3 Ohne Nummer", InvoiceItemCategory.PRODUCT, number = null)
        val numbered = seedArticle("IN2SEL B3 Zeta", InvoiceItemCategory.PRODUCT, number = "IN2SEL-3000")

        val byName = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "ohne nummer")
        val byNumberFragment = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "3000")
        // Both share the "IN2SEL B3" name fragment — a filter matching only names must still
        // return the null-number row alongside a numbered one without throwing.
        val bothByName = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "IN2SEL B3")

        assertEquals(listOf(numberless.id), byName.map { it.id })
        assertEquals(listOf(numbered.id), byNumberFragment.map { it.id }, "a null number must never match a number-fragment filter")
        assertEquals(setOf(numberless.id, numbered.id), bothByName.map { it.id }.toSet())
        assertEquals(2L, repository.countForSelect(InvoiceItemCategory.PRODUCT, filter = "IN2SEL B3"))
    }

    // ── B4 — LIKE metacharacters are literals, not wildcards ───────────────────────────────────

    @Test
    fun `a percent sign in the filter is a literal, not a wildcard matching every row`() {
        val withPercent = seedArticle("IN2SEL B4 50% Rabatt", InvoiceItemCategory.PRODUCT)
        seedArticle("IN2SEL B4 Ohne Sonderzeichen", InvoiceItemCategory.PRODUCT)

        val rows = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "50%")

        assertEquals(listOf(withPercent.id), rows.map { it.id })
    }

    @Test
    fun `an underscore in the filter is a literal, not a single-character wildcard`() {
        val withUnderscore = seedArticle("IN2SEL_B4_Underscore", InvoiceItemCategory.PRODUCT)
        seedArticle("IN2SELXB4XUnderscoreX", InvoiceItemCategory.PRODUCT)

        val rows = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "in2sel_b4_underscore")

        assertEquals(listOf(withUnderscore.id), rows.map { it.id })
    }

    // ── B5 — case-insensitive, both prefix and inner fragment ──────────────────────────────────

    @Test
    fun `the filter matches case-insensitively as both a number prefix and an inner fragment`() {
        val article = seedArticle("IN2SEL B5 Zaunbau", InvoiceItemCategory.PRODUCT, number = "IN2SEL-2026-000B5")

        val byPrefix = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "in2sel-2026")
        val byInnerFragment = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "2026-000")

        assertEquals(listOf(article.id), byPrefix.map { it.id })
        assertEquals(listOf(article.id), byInnerFragment.map { it.id })
    }

    // ── B6 — no hit is empty and exception-free; count/page agree beyond 60 hits ──────────────

    @Test
    fun `a filter with no match returns an empty page and a zero count without throwing`() {
        seedArticle("IN2SEL B6 Irgendwas", InvoiceItemCategory.PRODUCT)

        val rows = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "in2sel-b6-no-such-fragment-anywhere")
        val count = repository.countForSelect(InvoiceItemCategory.PRODUCT, filter = "in2sel-b6-no-such-fragment-anywhere")

        assertTrue(rows.isEmpty())
        assertEquals(0L, count)
    }

    @Test
    fun `paging beyond the first page keeps count and the two pages consistent for more than 60 hits`() {
        val seeded = (1..65).map { seedArticle("IN2SEL B6 Bulk ${"%03d".format(it)}", InvoiceItemCategory.PRODUCT) }

        val page0 = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "IN2SEL B6 Bulk", pageIndex = 0, pageSize = 60)
        val page1 = repository.findForSelect(InvoiceItemCategory.PRODUCT, filter = "IN2SEL B6 Bulk", pageIndex = 1, pageSize = 60)
        val count = repository.countForSelect(InvoiceItemCategory.PRODUCT, filter = "IN2SEL B6 Bulk")

        assertEquals(60, page0.size)
        assertEquals(5, page1.size)
        assertEquals(65L, count)
        assertEquals(seeded.map { it.id }.toSet(), (page0 + page1).map { it.id }.toSet())
    }
}
