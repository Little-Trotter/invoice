package de.littletrotter.e2e.masterdata

import de.littletrotter.fixture.aRecipient
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.application.dto.ArticleTaxVariantDto
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.masterdata.*
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId

/** Fixtures derived from datagen --seed 42; isolated PostgreSQL, committed reads. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class CatalogueChildCollectionsE2EIT {
    @Inject lateinit var articleQueries: ArticleQueryPort
    @Inject lateinit var manageArticle: ManageArticleUseCase
    @Inject lateinit var taxRateQueries: TaxRateQueryPort
    @Inject lateinit var em: EntityManager

    private fun customer(suffix: String): Recipient = QuarkusTransaction.requiringNew().call {
        aRecipient {
            name = "Worf Tasha $suffix"
            customerNumber = CustomerNumber("KD-2026-00001-$suffix")
            email = Email.ofNullable("schaefersigfried@example.org")
            vatId = VatId("NL602563421B07")
        }.also { em.persist(it); em.flush() }
    }

    private fun load(id: Long): ArticleDto = requireNotNull(articleQueries.findArticleById(id))

    /** The stored mapping row id per recipient: the rows must survive repeated saves in place. */
    private fun refRowIds(id: Long): Map<Long?, Long?> = QuarkusTransaction.requiringNew().call {
        em.find(Article::class.java, id).recipientRefs.associate { it.recipient?.id to it.id }
    }

    /** The stored variant row id per country. */
    private fun variantRowIds(id: Long): Map<Country, Long?> = QuarkusTransaction.requiringNew().call {
        em.find(Article::class.java, id).taxVariants.associate { it.country to it.id }
    }

    private fun article(
        refs: List<ArticleRecipientRefDto>? = null,
        variants: List<ArticleTaxVariantDto>? = null,
    ) = ArticleDraft(
        name = "Backend Development",
        category = InvoiceItemCategory.SERVICE,
        defaultUnit = InvoiceItemUnit.HOUR,
        defaultUnitPriceNet = Money.of("120.00"),
        recipientRefs = refs,
        taxVariants = variants,
    )

    /** The editor's save of [stored] with its rows replaced by [refs]/[variants] (`null` keeps them). */
    private fun edit(
        stored: ArticleDto,
        refs: List<ArticleRecipientRefDto>? = null,
        variants: List<ArticleTaxVariantDto>? = null,
    ) = ArticleDraft(
        id = stored.id,
        version = stored.version,
        name = stored.name,
        category = stored.category,
        defaultUnit = stored.defaultUnit,
        defaultQuantity = stored.defaultQuantity,
        defaultUnitPriceNet = stored.defaultUnitPriceNet,
        defaultTaxRateId = stored.defaultTaxRateId,
        articleNumber = stored.articleNumber,
        shortHash = stored.shortHash,
        recipientRefs = refs,
        taxVariants = variants,
    )

    private fun references(customers: List<Recipient>, name: String = "Backend Development") =
        customers.map { ArticleRecipientRefDto(requireNotNull(it.id), it.customerNumber?.value, name) }

    @Test
    fun `adding customer references and country variants keeps existing rows through repeated committed saves`() {
        val first = customer("first")
        val second = customer("second")
        var articleId: Long? = null
        try {
            val rates = taxRateQueries.findAllTaxRates()
            val lu = ArticleTaxVariantDto(Country.LU, requireNotNull(rates.single { it.code == "LU_STANDARD_17" }.id))
            val fr = ArticleTaxVariantDto(Country.FR, requireNotNull(rates.single { it.code == "FR_STANDARD_20" }.id))
            val id = requireNotNull(manageArticle.saveArticle(article(references(listOf(first)), listOf(lu))).id)
            articleId = id
            val firstRefId = refRowIds(id).getValue(first.id)
            val luId = variantRowIds(id).getValue(Country.LU)
            manageArticle.saveArticle(edit(load(id), references(listOf(first, second)), listOf(lu, fr)))
            val saved = load(id)
            assertEquals(firstRefId, refRowIds(id).getValue(first.id))
            assertEquals(luId, variantRowIds(id).getValue(Country.LU))
            assertEquals(2, saved.recipientRefs.size)
            assertEquals(2, saved.taxVariants.size)
            val ids = refRowIds(id)
            manageArticle.saveArticle(edit(saved, references(listOf(second, first), "Backend Development updated")))
            val reopened = load(id)
            assertEquals(ids, refRowIds(id))
            assertTrue(reopened.recipientRefs.all { it.buyerArticleName == "Backend Development updated" })
            assertEquals(2, reopened.taxVariants.size, "a save without variant rows keeps the stored ones")
            manageArticle.saveArticle(edit(reopened, references(listOf(second)), listOf(fr)))
            assertEquals(second.id, load(id).recipientRefs.single().recipientId)
            assertEquals(Country.FR, load(id).taxVariants.single().country)
        } finally {
            articleId?.let(manageArticle::deleteArticle)
            QuarkusTransaction.requiringNew().run {
                listOf(first, second).forEach { em.remove(em.find(Recipient::class.java, it.id)) }
            }
        }
    }

    @Test
    fun `a stale form cannot overwrite a committed customer reference change`() {
        val first = customer("conflict")
        var articleId: Long? = null
        try {
            val id = requireNotNull(manageArticle.saveArticle(article(references(listOf(first)))).id)
            articleId = id
            val winner = load(id)
            val stale = load(id)
            manageArticle.saveArticle(edit(winner, references(listOf(first), "Backend Development updated")))
            assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
                manageArticle.saveArticle(edit(stale, emptyList()))
            }
            assertEquals("Backend Development updated", load(id).recipientRefs.single().buyerArticleName)
        } finally {
            articleId?.let(manageArticle::deleteArticle)
            QuarkusTransaction.requiringNew().run { em.remove(em.find(Recipient::class.java, first.id)) }
        }
    }
}
