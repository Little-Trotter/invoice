package de.littletrotter.e2e.invoice

import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Real articleQueries + committed Postgres roundtrip for the article-select combos' (GoodsItemDialog/
 * ServiceItemDialog) single-input search: [ArticleQueryPort.findArticlesForSelect]/
 * [ArticleQueryPort.countArticlesForSelect] now match number, name or description in one
 * query, number hits ranked first. Exercises the whole backend chain — articleQueries, service,
 * repository, the `CATALOGUE_DEFAULT` number range and real Postgres — the same chain both item
 * dialogs' lazy combo relies on. The JPQL itself is pinned by
 * [de.littletrotter.infrastructure.persistence.PanacheArticleRepositoryQueryTest] (mocked) and
 * [de.littletrotter.infrastructure.persistence.PanacheArticleRepositorySelectIT] (every border
 * case); this proves the end-to-end path a real save-then-search actually takes. The Playwright
 * spec drives the dialog itself and is out of this package's scope.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class PositionArticleNumberSearchE2EIT {

    @Inject
    lateinit var articleQueries: ArticleQueryPort

    @Inject
    lateinit var manageArticle: ManageArticleUseCase

    private fun article(category: InvoiceItemCategory, name: String, unit: InvoiceItemUnit = InvoiceItemUnit.PIECE): ArticleDraft =
        ArticleDraft(name = name, category = category, defaultUnit = unit, defaultUnitPriceNet = Money.of("10.00"))

    private fun save(article: ArticleDraft): Long = manageArticle.saveArticle(article).id!!
    private fun load(id: Long): ArticleDto = QuarkusTransaction.requiringNew().call { articleQueries.findArticleById(id)!! }

    private fun search(category: InvoiceItemCategory, filter: String): List<ArticleDto> =
        QuarkusTransaction.requiringNew().call { articleQueries.findArticlesForSelect(category, filter, offset = 0, limit = 60) }

    private fun searchCount(category: InvoiceItemCategory, filter: String): Int =
        QuarkusTransaction.requiringNew().call { articleQueries.countArticlesForSelect(category, filter) }

    // ── Happy path — GoodsItemDialog's PRODUCT combo, by a fragment of the assigned number ──────

    @Test
    fun `a numeric tail of the catalogue-assigned number finds the PRODUCT article, count agrees`() {
        val id = save(article(InvoiceItemCategory.PRODUCT, "II2 Happy Path Hydraulikpumpe"))
        val number = requireNotNull(load(id).articleNumber) { "CATALOGUE_DEFAULT must assign a number on save" }
        val tail = number.takeLast(4)

        val results = search(InvoiceItemCategory.PRODUCT, tail)

        assertTrue(results.any { it.id == id }, "the numeric tail of the article's own number must find it")
        assertTrue(searchCount(InvoiceItemCategory.PRODUCT, tail) >= 1)
    }

    // ── Happy path — ServiceItemDialog's SERVICE combo, same fragment search ──────────────────

    @Test
    fun `a numeric tail of the catalogue-assigned number finds the SERVICE article`() {
        val id = save(article(InvoiceItemCategory.SERVICE, "II2 Happy Path Beratung", InvoiceItemUnit.HOUR))
        val number = requireNotNull(load(id).articleNumber)
        val tail = number.takeLast(4)

        val results = search(InvoiceItemCategory.SERVICE, tail)

        assertTrue(results.any { it.id == id })
    }

    // ── a number hit ranks before a name-only hit, through the real articleQueries end to end ─────

    @Test
    fun `an article matched by its own number ranks before one matched only by name`() {
        val numberMatchId = save(article(InvoiceItemCategory.PRODUCT, "II2-B2 Kabelkanal"))
        val fragment = requireNotNull(load(numberMatchId).articleNumber).takeLast(5)
        val nameMatchId = save(article(InvoiceItemCategory.PRODUCT, "II2-B2 Sonderposten $fragment"))

        val results = search(InvoiceItemCategory.PRODUCT, fragment)

        val positionByNumber = results.indexOfFirst { it.id == numberMatchId }
        val positionByName = results.indexOfFirst { it.id == nameMatchId }
        assertTrue(positionByNumber >= 0, "the number-matched article must be offered")
        assertTrue(positionByName >= 0, "the name-matched article must be offered too")
        assertTrue(positionByNumber < positionByName, "the number hit must rank before the name-only hit")
    }

    // ── case-insensitive prefix and inner fragment, through the real articleQueries ────────────────

    @Test
    fun `an upper-cased prefix and a lower-cased inner fragment of the number both find the article`() {
        val id = save(article(InvoiceItemCategory.PRODUCT, "II2-B5 Zaunbau"))
        val number = requireNotNull(load(id).articleNumber)
        val prefix = number.take(4).uppercase()
        val inner = number.substring(number.length / 2).lowercase()

        val byPrefix = search(InvoiceItemCategory.PRODUCT, prefix)
        val byInner = search(InvoiceItemCategory.PRODUCT, inner)

        assertTrue(byPrefix.any { it.id == id }, "an upper-cased number prefix must still match")
        assertTrue(byInner.any { it.id == id }, "a lower-cased inner number fragment must still match")
    }
}
