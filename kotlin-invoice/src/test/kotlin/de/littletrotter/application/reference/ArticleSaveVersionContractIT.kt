package de.littletrotter.application.reference

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.domain.masterdata.Article
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The article editor keeps the DTO the save hands back and sends its version with the next save.
 * The save path forces a version increment at commit time, after the DTO is mapped, so this pins
 * that the DTO still carries the committed version. Calls run as the UI runs them: plain calls on
 * the port, no surrounding transaction.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ArticleSaveVersionContractIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var manageArticle: ManageArticleUseCase

    private fun seededStandardRateId(): Long = QuarkusTransaction.requiringNew().call<Long> {
        em.createQuery("select t.id from TaxRate t where t.code = 'DE_STANDARD_19'", java.lang.Long::class.java).singleResult.toLong()
    }

    private fun committedVersion(id: Long): Long? = QuarkusTransaction.requiringNew().call<Long?> {
        em.find(Article::class.java, id).version
    }

    @Test
    fun `the DTO a save hands back carries the committed version, so saving the same form again succeeds`() {
        val taxRateId = seededStandardRateId()

        val saved = manageArticle.saveArticle(ArticleDraft(name = "Beratung Versionsvertrag", defaultTaxRateId = taxRateId))
        val id = checkNotNull(saved.id)
        assertEquals(committedVersion(id), saved.version, "the version handed to the editor must be the committed one")

        val again = manageArticle.saveArticle(
            ArticleDraft(id = id, version = saved.version, name = "Beratung Versionsvertrag, Tag 2", defaultTaxRateId = taxRateId),
        )
        assertEquals(committedVersion(id), again.version, "and the same holds after an edit of an existing article")
        assertEquals("Beratung Versionsvertrag, Tag 2", again.name)
    }
}
