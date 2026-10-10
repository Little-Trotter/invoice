package de.littletrotter.application.reference

import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.application.dto.ArticleTaxVariantDto
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.domain.value.TaxRatePercent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.test.FIXED_TODAY

/** An article's read model and how a draft's rows become the article's owned rows. */
class ArticleMappingTest {

    private fun taxRate(country: Country, id: Long): TaxRate = TaxRate().apply {
        this.id = id
        name = "USt $country"
        rate = TaxRatePercent(BigDecimal("17.00"))
        this.country = country
        code = "${country.alpha2}_STANDARD_17"
        category = "S"
    }

    private val luRate = taxRate(Country.LU, 1L)
    private val frRate = taxRate(Country.FR, 2L)
    private val ratesById = listOf(luRate, frRate).associateBy { it.id }
    private val nekar = Recipient().apply { id = 1L; name = "Nekar Supermarkt" }
    private val other = Recipient().apply { id = 2L; name = "Andere GmbH" }
    private val recipientsById = listOf(nekar, other).associateBy { it.id }
    private val references = ArticleDraftReferences(
        taxRate = { id -> ratesById[id] ?: throw InvalidParameterException("Unbekannter Steuersatz (id=$id).") },
        recipient = { id -> recipientsById[id] ?: throw InvalidParameterException("Unbekannter Kunde (id=$id).") },
    )

    private fun storedArticle(): Article = Article().apply {
        id = 1L
        version = 4L
        name = "Beratung"
        defaultTaxRateRef = luRate
        articleNumber = ArticleNumber("ART-1")
        shortHash = "abc"
    }.also { article ->
        article.replaceTaxVariants(listOf(ArticleTaxVariant.of(article, Country.LU, luRate, today = FIXED_TODAY)), today = FIXED_TODAY)
        article.replaceRecipientRefs(listOf(ArticleRecipientRef.of(article, nekar, "243538", "Nekar-Bezeichnung")))
    }

    @Test
    fun `a stored article maps with its identifiers, default rate and owned rows`() {
        val dto = storedArticle().toDto()

        assertEquals(1L, dto.defaultTaxRateId)
        assertEquals(BigDecimal("17.00"), dto.defaultTaxRatePercent?.value)
        assertEquals("ART-1", dto.articleNumber)
        assertEquals("abc", dto.shortHash)
        assertEquals(listOf(ArticleTaxVariantDto(Country.LU, 1L)), dto.taxVariants)
        assertEquals(listOf(ArticleRecipientRefDto(1L, "243538", "Nekar-Bezeichnung")), dto.recipientRefs)
        assertEquals(1L, dto.taxVariantRateIdFor(Country.LU))
        assertNull(dto.taxVariantRateIdFor(Country.FR))
    }

    @Test
    fun `a list row leaves the owned rows out`() {
        val dto = storedArticle().toDto(withOwnedRows = false)

        assertTrue(dto.taxVariants.isEmpty())
        assertTrue(dto.recipientRefs.isEmpty())
        assertEquals("Beratung", dto.name)
    }

    @Test
    fun `an article without default rate or number maps to empty values`() {
        val dto = Article().toDto()

        assertEquals(ArticleDto(version = 0L), dto)
    }

    @Test
    fun `a variant or mapping without its reference maps to nothing`() {
        assertNull(ArticleTaxVariant().apply { country = Country.FR }.toDto())
        assertNull(ArticleRecipientRef().apply { buyerArticleNumber = "ORPHAN-1" }.toDto())
    }

    @Test
    fun `a draft writes its rows onto the article, resolved by id`() {
        val article = Article()
        ArticleDraft(
            name = "Beratung",
            articleNumber = "ART-7",
            shortHash = "h7",
            defaultTaxRateId = 2L,
            taxVariants = listOf(ArticleTaxVariantDto(Country.LU, 1L), ArticleTaxVariantDto(Country.FR, 2L)),
            recipientRefs = listOf(ArticleRecipientRefDto(1L, "243538", null), ArticleRecipientRefDto(2L, null, "Andere Bezeichnung")),
        ).applyTo(article, null, references, today = FIXED_TODAY)

        assertEquals(frRate, article.defaultTaxRateRef)
        assertEquals("ART-7", article.articleNumber?.value)
        assertEquals("h7", article.shortHash)
        assertEquals(2, article.taxVariants.size)
        assertEquals(luRate, article.taxVariantFor(Country.LU))
        assertEquals(2, article.recipientRefs.size)
        assertEquals("Andere Bezeichnung", article.recipientRefFor(2L)?.buyerArticleName)
    }

    @Test
    fun `empty draft rows clear the stored rows, absent draft rows keep them`() {
        val stored = storedArticle()
        val cleared = Article()
        ArticleDraft(name = "Beratung", taxVariants = emptyList(), recipientRefs = emptyList()).applyTo(cleared, stored, references, today = FIXED_TODAY)
        val kept = Article()
        ArticleDraft(name = "Beratung").applyTo(kept, stored, references, today = FIXED_TODAY)

        assertTrue(cleared.taxVariants.isEmpty())
        assertTrue(cleared.recipientRefs.isEmpty())
        assertNull(cleared.articleNumber, "a blank number lets the save assign one")
        assertEquals(luRate, kept.taxVariantFor(Country.LU))
        assertEquals("243538", kept.recipientRefFor(1L)?.buyerArticleNumber)
    }

    @Test
    fun `a rate of the wrong country, a duplicate country and a mapping without values are rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            ArticleDraft(name = "Beratung", taxVariants = listOf(ArticleTaxVariantDto(Country.LU, 2L))).applyTo(Article(), null, references, today = FIXED_TODAY)
        }
        assertThrows(InvalidParameterException::class.java) {
            ArticleDraft(name = "Beratung", taxVariants = listOf(ArticleTaxVariantDto(Country.LU, 1L), ArticleTaxVariantDto(Country.LU, 1L)))
                .applyTo(Article(), null, references, today = FIXED_TODAY)
        }
        assertThrows(InvalidParameterException::class.java) {
            ArticleDraft(name = "Beratung", recipientRefs = listOf(ArticleRecipientRefDto(1L))).applyTo(Article(), null, references, today = FIXED_TODAY)
        }
    }

    @Test
    fun `an unknown tax rate or recipient id is rejected`() {
        val rate = assertThrows(InvalidParameterException::class.java) {
            ArticleDraft(name = "Beratung", taxVariants = listOf(ArticleTaxVariantDto(Country.LU, 999L))).applyTo(Article(), null, references, today = FIXED_TODAY)
        }
        val recipient = assertThrows(InvalidParameterException::class.java) {
            ArticleDraft(name = "Beratung", recipientRefs = listOf(ArticleRecipientRefDto(999L, "1"))).applyTo(Article(), null, references, today = FIXED_TODAY)
        }

        assertEquals("Unbekannter Steuersatz (id=999).", rate.message)
        assertEquals("Unbekannter Kunde (id=999).", recipient.message)
    }
}
