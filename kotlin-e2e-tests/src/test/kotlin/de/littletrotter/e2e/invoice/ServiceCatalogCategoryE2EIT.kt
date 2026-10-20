package de.littletrotter.e2e.invoice

import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Real articleQueries and committed PostgreSQL roundtrips, using only synthetic datagen catalogue data. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ServiceCatalogCategoryE2EIT {
    @Inject
    lateinit var articleQueries: ArticleQueryPort

    @Inject
    lateinit var manageArticle: ManageArticleUseCase

    private fun fixture(category: InvoiceItemCategory, suffix: String): ArticleDraft {
        val row = javaClass.getResourceAsStream("/catalogue-datagen.tsv")!!.bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("#") }.map { it.split('\t') }
                .first { it[1].toInt() == category.code }
        }
        return ArticleDraft(
            name = row[0] + suffix,
            category = category,
            defaultUnit = InvoiceItemUnit.entries[row[2].toInt()],
            defaultUnitPriceNet = Money.of(row[3]),
        )
    }

    private fun save(article: ArticleDraft): Long = manageArticle.saveArticle(article).id!!
    private fun load(id: Long): ArticleDto = QuarkusTransaction.requiringNew().call { articleQueries.findArticleById(id)!! }

    @Test
    fun `every tab queries only its category and default units survive committed save`() {
        for (category in listOf(InvoiceItemCategory.PRODUCT, InvoiceItemCategory.UNIT_PRICE, InvoiceItemCategory.SERVICE)) {
            val article = fixture(category, " category-roundtrip")
            val id = save(article)
            assertEquals(article.defaultUnit, load(id).defaultUnit)
            QuarkusTransaction.requiringNew().run {
                val rows = articleQueries.findArticles(0, 50, null, article.name, "name", SortDirection.ASC, category)
                assertEquals(listOf(id), rows.map { it.id })
                assertEquals(1L, articleQueries.countArticles(null, article.name, category))
                InvoiceItemCategory.entries.filter { it != category }.forEach { other ->
                    assertEquals(0L, articleQueries.countArticles(null, article.name, other))
                    assertTrue(articleQueries.findArticles(0, 50, null, article.name, "name", SortDirection.ASC, other).isEmpty())
                }
            }
        }
    }

    @Test
    fun `editing quantity keeps chosen nondefault unit`() {
        val article = fixture(InvoiceItemCategory.PRODUCT, " custom-unit").copy(defaultUnit = InvoiceItemUnit.CUBIC_METER)
        val id = save(article)
        val stored = load(id)
        save(
            article.copy(
                id = id,
                version = stored.version,
                articleNumber = stored.articleNumber,
                shortHash = stored.shortHash,
                defaultQuantity = java.math.BigDecimal("2.5"),
            ),
        )
        val persisted = load(id)
        assertEquals(InvoiceItemUnit.CUBIC_METER, persisted.defaultUnit)
        assertEquals(0, java.math.BigDecimal("2.5").compareTo(persisted.defaultQuantity))
    }

    @Test
    fun `invalid time unit on material is rejected without inserting a row`() {
        val article = fixture(InvoiceItemCategory.PRODUCT, " invalid-time").copy(defaultUnit = InvoiceItemUnit.HOUR)
        assertThrows(Exception::class.java) { save(article) }
        QuarkusTransaction.requiringNew().run {
            assertEquals(0L, articleQueries.countArticles(null, article.name, InvoiceItemCategory.PRODUCT))
        }
    }

    @Test
    fun `empty search match does not fall back to other categories`() {
        QuarkusTransaction.requiringNew().run {
            InvoiceItemCategory.entries.forEach { category ->
                assertEquals(0L, articleQueries.countArticles(null, "datagen-no-such-catalogue-position", category))
                assertTrue(articleQueries.findArticles(0, 50, null, "datagen-no-such-catalogue-position", "name", SortDirection.ASC, category).isEmpty())
            }
        }
    }
}
