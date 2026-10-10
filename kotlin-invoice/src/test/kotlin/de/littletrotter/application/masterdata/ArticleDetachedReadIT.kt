package de.littletrotter.application.masterdata

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.value.ArticleNumber
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hibernate.LazyInitializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Production bug investigation — the originally reported crash site was believed to be the
 * article-picker `ComboBox` in an invoice item dialog: `org.hibernate.LazyInitializationException:
 * Unable to perform requested lazy initialization [de.littletrotter.domain.masterdata.Article
 * .articleNumber] - no session and settings disallow loading outside the Session`, thrown from
 * [de.littletrotter.ui.view.invoice.BaseItemDialog.open]'s `article.articleNumber` read.
 *
 * These two tests rule that specific path out, against real PostgreSQL: [ArticleManagementService
 * .findAll] and [ArticleQueryPort.findArticlesForSelect]/`findArticleById` (the calls that
 * populate and refresh that lazy-loading `ComboBox`) hand out `Article`
 * instances that are fully hydrated at query time
 * -- a plain `@Column` attribute like `articleNumber` is never routed through a lazy-attribute
 * interceptor for them, so reading it stays safe no matter how long the entity sits detached
 * afterward. Kept as regression guards for that finding.
 *
 * The actual reproducible mechanism turned out to be a different one: [de.littletrotter.domain
 * .invoice.InvoiceItem.articleRef] is a genuine `@ManyToOne(fetch = FetchType.LAZY)`. Hibernate's
 * bytecode enhancement represents an untouched target as the real `Article` class with only its
 * id populated and a lazy-attribute interceptor installed on every other field -- reading any of
 * them (e.g. `articleNumber`) after the loading session closes throws exactly the reported
 * message. See [de.littletrotter.application.invoice.InvoiceDtoMapperArticleNumberLazyIT] for the
 * actual regression test and fix, in [de.littletrotter.application.invoice.InvoiceDtoMapper
 * .toDto]'s `articleNumber = articleRef?.articleNumber` -- the one place in this codebase that
 * read an `Article` attribute directly off that association.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ArticleDetachedReadIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var articleManagementService: ArticleManagementService

    @Inject
    lateinit var articleQueries: ArticleQueryPort

    @Test
    fun `articleNumber read after findAll must not throw LazyInitializationException`() {
        val id = QuarkusTransaction.requiringNew().call {
            val article = Article().apply {
                name = "Reproduktionsartikel ADR-1"
                articleNumber = ArticleNumber("ART-REPRO-ADR-1")
            }
            em.persist(article)
            em.flush()
            article.id!!
        }

        // No transaction here on purpose -- see class KDoc.
        val loaded = articleManagementService.findAll(textFilter = "Reproduktionsartikel ADR-1")
            .first { it.id == id }

        val articleNumber = try {
            loaded.articleNumber?.value
        } catch (ex: LazyInitializationException) {
            throw AssertionError(
                "Reading Article.articleNumber on a detached result of findAll() must not require " +
                    "a live Hibernate session -- the facade must hand out fully usable entities.",
                ex,
            )
        }
        assertEquals("ART-REPRO-ADR-1", articleNumber)
    }

    @Test
    fun `articleNumber read through the facade's findArticlesForSelect must not throw LazyInitializationException`() {
        val id = QuarkusTransaction.requiringNew().call {
            val article = Article().apply {
                name = "Reproduktionsartikel ADR-2"
                articleNumber = ArticleNumber("ART-REPRO-ADR-2")
                category = InvoiceItemCategory.SERVICE
            }
            em.persist(article)
            em.flush()
            article.id!!
        }

        // Exactly the call GoodsItemDialog/ServiceItemDialog's lazy articleSelect ComboBox uses to
        // populate itself -- no transaction here either.
        val loaded = articleQueries.findArticlesForSelect(InvoiceItemCategory.SERVICE, "Reproduktionsartikel ADR-2")
            .first { it.id == id }

        // The port hands out read-model rows mapped while the query ran, so the number is a
        // plain value by now -- nothing is left to load lazily once the request ends.
        assertEquals("ART-REPRO-ADR-2", loaded.articleNumber)
    }
}
