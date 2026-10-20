package de.littletrotter.domain.masterdata

import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.anArticleRecipientRef
import de.littletrotter.fixture.anArticleTaxVariant
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.test.FIXED_TODAY

/** N3 — [Article.validate], including the N3 article-number invariants. */
class ArticleTest {

    private fun validArticle(): Article = anArticle {
        name = "Beratung"
        defaultQuantity = BigDecimal("1.00")
        defaultUnitPriceNet = Money.of("100.00")
    }

    @Test
    fun `a fully specified article passes validation`() {
        assertDoesNotThrow { validArticle().validate(today = FIXED_TODAY) }
    }

    @Test
    fun `a negative default unit price is rejected`() {
        val article = validArticle().edit { defaultUnitPriceNet = Money.of("-1.00") }
        assertThrows(InvalidParameterException::class.java) { article.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `boundary - a blank article name is rejected`() {
        val article = validArticle().edit { name = "   " }
        assertThrows(InvalidParameterException::class.java) { article.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `boundary - a zero default quantity is rejected`() {
        val article = validArticle().edit { defaultQuantity = BigDecimal.ZERO }
        assertThrows(InvalidParameterException::class.java) { article.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `boundary - a negative default quantity is rejected`() {
        val article = validArticle().edit { defaultQuantity = BigDecimal("-1.00") }
        assertThrows(InvalidParameterException::class.java) { article.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `boundary - a unit not allowed for the category is rejected`() {
        // PRODUCT is limited to physical/countable units; HOUR belongs to SERVICE only.
        val article = validArticle().edit {
            category = de.littletrotter.shared.catalogue.InvoiceItemCategory.PRODUCT
            defaultUnit = de.littletrotter.shared.catalogue.InvoiceItemUnit.HOUR
        }
        assertThrows(InvalidParameterException::class.java) { article.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `boundary - validate walks a non-empty, valid tax variant and recipient ref list without error`() {
        val article = validArticle()
        val rate = de.littletrotter.domain.reference.TaxRate().edit {
            id = 1L
            name = "USt LU"
            rate = TaxRatePercent(BigDecimal("17.00"))
            country = Country.LU
            code = "LU_STANDARD_17"
            category = "S"
        }
        article.replaceTaxVariants(listOf(ArticleTaxVariant.of(article, Country.LU, rate, today = FIXED_TODAY)), today = FIXED_TODAY)
        val recipient = aRecipient { id = 7L; customerNumber = CustomerNumber("CUST-7"); name = "Kunde" }
        article.replaceRecipientRefs(
            listOf(ArticleRecipientRef.of(article, recipient, "BUY-7", "Kunden-Artikel")),
        )

        assertDoesNotThrow { article.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `boundary - replaceTaxVariants rejects a new candidate with no tax rate reference`() {
        // reconcileOwnedChildren validates every candidate (ArticleTaxVariant.validate(), which
        // itself rejects a null taxRateRef) before ever reaching the create lambda's own
        // requireNotNull unwrap - the validate step is the real gate.
        val article = validArticle()
        val candidate = anArticleTaxVariant { country = Country.LU; taxRateRef = null }

        assertThrows(InvalidParameterException::class.java) { article.replaceTaxVariants(listOf(candidate), today = FIXED_TODAY) }
    }

    @Test
    fun `boundary - replaceRecipientRefs rejects a new candidate with no recipient`() {
        // Same discipline as replaceTaxVariants above: ArticleRecipientRef.validate() rejects a
        // null recipient before the create lambda's requireNotNull is ever reached.
        val article = validArticle()
        val candidate = anArticleRecipientRef { recipient = null; buyerArticleNumber = "BUY-1" }

        assertThrows(InvalidParameterException::class.java) { article.replaceRecipientRefs(listOf(candidate)) }
    }

    @Test
    fun `copyEditableFieldsFrom validates and copies the source article's fields`() {
        val article = validArticle()
        val other = validArticle().edit {
            name = "Neue Beratung"
            description = "Beschreibung"
            defaultQuantity = BigDecimal("2.00")
        }

        article.copyEditableFieldsFrom(other, today = FIXED_TODAY)

        assertTrue(article.name == "Neue Beratung")
        assertTrue(article.description == "Beschreibung")
    }

    // ── N3: articleNumber is optional, but if set it must be a real value ────
    // the blank-rejection invariant moved from Article.validate() to ArticleNumber's own
    // `init` (fail-fast at construction, not deferred until validate()); see ArticleNumberTest for
    // the construction-time assertion.

    @Test
    fun `a real article number is accepted`() {
        val article = validArticle().edit { articleNumber = ArticleNumber("ART-00042") }
        assertDoesNotThrow { article.validate(today = FIXED_TODAY) }
    }

    // ── N3: articleNumberSequence, if set, must be a positive counter value ──

    @Test
    fun `a negative article number sequence is rejected`() {
        val article = validArticle().edit { articleNumberSequence = -1L }
        assertThrows(InvalidParameterException::class.java) { article.validate(today = FIXED_TODAY) }
    }

    /**
     * `NumberRange.validate()` explicitly allows `startValue == 0`, and the start value is the
     * **first** number ever allocated -- so 0 must be a valid sequence value here too, or an
     * article number range starting at 0 could never allocate a single article.
     */
    @Test
    fun `die Null wird akzeptiert, weil ein Nummernkreis mit Startwert 0 genau sie zuerst vergibt`() {
        val article = validArticle().edit { articleNumberSequence = 0L }
        assertDoesNotThrow { article.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `a positive article number sequence is accepted`() {
        val article = validArticle().edit { articleNumberSequence = 42L }
        assertDoesNotThrow { article.validate(today = FIXED_TODAY) }
    }

    // ── ensureShortHash() — a short hash is assigned at most once ────────────

    @Test
    fun `ensureShortHash assigns a hash and reports true when none existed yet`() {
        val article = validArticle().edit { shortHash = null }
        assertTrue(article.ensureShortHash())
    }

    @Test
    fun `ensureShortHash leaves an existing hash untouched and reports false`() {
        val article = validArticle().edit { shortHash = "ABCDEFGHJK" }
        assertFalse(article.ensureShortHash())
    }

    // ── recipientRefFor() — branch coverage for recipient mapping lookup ──────

    @Test
    fun `recipientRefFor returns null when no matching recipient ref exists`() {
        val article = validArticle()
        val recipientId = 999L

        assertFalse(article.recipientRefFor(recipientId) != null, "should not find non-existent recipient ref")
    }

    @Test
    fun `recipientRefFor returns the ref when matching recipient exists`() {
        val article = validArticle()
        val recipient = aRecipient { id = 42L; customerNumber = CustomerNumber("CUST-1"); name = "Test" }
        val ref = anArticleRecipientRef {
            this.article = article
            this.recipient = recipient
            buyerArticleNumber = "BUY-001"
        }
        article.replaceRecipientRefs(listOf(ref))

        val found = article.recipientRefFor(42L)
        assertTrue(found != null, "should find existing recipient ref")
        assertTrue(found?.buyerArticleNumber == "BUY-001")
    }

    @Test
    fun `boundary - recipientRefFor skips a ref whose recipient reference is itself null`() {
        // the empty-list case above short-circuits firstOrNull before its lambda ever
        // runs; this proves the lambda's own `it.recipient?.id` null-safety when a ref DOES exist
        // but carries no recipient (a data defect a raw fixture can still construct).
        val article = validArticle()
        val orphanRef = anArticleRecipientRef { this.article = null; recipient = null; buyerArticleNumber = "ORPHAN" }
        article.replaceRecipientRefs(emptyList())
        // Insert directly, bypassing replaceRecipientRefs's own validate() (which rejects a
        // recipient-less candidate, see the "rejects a new candidate with no recipient" test).
        val rowsField = Article::class.java.getDeclaredField("recipientRefRows")
        rowsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (rowsField.get(article) as MutableList<ArticleRecipientRef>).add(orphanRef)

        assertNull(article.recipientRefFor(42L))
    }

    // ── taxVariantFor() — basic branch coverage ──────────────────────────────

    @Test
    fun `taxVariantFor returns null when no matching country variant exists`() {
        val article = validArticle()
        val variant = article.taxVariantFor(Country.DE)
        assertTrue(variant == null, "should return null when no variant for country")
    }

    @Test
    fun `replaceTaxVariants with empty list clears all variants`() {
        val article = validArticle()
        article.replaceTaxVariants(emptyList(), today = FIXED_TODAY)
        assertTrue(article.taxVariants.isEmpty())
    }

    @Test
    fun `replaceRecipientRefs with empty list clears all refs`() {
        val article = validArticle()
        article.replaceRecipientRefs(emptyList())
        assertTrue(article.recipientRefs.isEmpty())
    }

    // ── intent methods ────────────────────────────────────────────────────

    @Test
    fun `update writes the plain editable fields`() {
        val a = Article()
        a.update(
            ArticleDetails(
                name = "Beratung",
                description = "Stunde",
                defaultQuantity = BigDecimal("2.00"),
                defaultUnitPriceNet = Money.of("80.00"),
                articleNumber = ArticleNumber("ART-1"),
                shortHash = "abc",
            ),
        )
        org.junit.jupiter.api.Assertions.assertEquals("Beratung", a.name)
        org.junit.jupiter.api.Assertions.assertEquals("Stunde", a.description)
        org.junit.jupiter.api.Assertions.assertEquals(BigDecimal("2.00"), a.defaultQuantity)
        org.junit.jupiter.api.Assertions.assertEquals(Money.of("80.00"), a.defaultUnitPriceNet)
        org.junit.jupiter.api.Assertions.assertEquals(ArticleNumber("ART-1"), a.articleNumber)
        org.junit.jupiter.api.Assertions.assertEquals("abc", a.shortHash)
        assertFalse(a.isDefault)
    }

    @Test
    fun `assignArticleNumber stores number prefix and sequence and clearArticleNumber drops only the number`() {
        val a = Article()
        a.assignArticleNumber("ART", 5L, ArticleNumber("ART-5"))
        org.junit.jupiter.api.Assertions.assertEquals("ART", a.articleNumberPrefix)
        org.junit.jupiter.api.Assertions.assertEquals(5L, a.articleNumberSequence)
        org.junit.jupiter.api.Assertions.assertEquals(ArticleNumber("ART-5"), a.articleNumber)
        a.clearArticleNumber()
        assertNull(a.articleNumber)
        org.junit.jupiter.api.Assertions.assertEquals(5L, a.articleNumberSequence)
    }

    @Test
    fun `detachedEdit carries identity revision and default flag`() {
        val a = Article.detachedEdit(id = 9L, version = 4L, isDefault = true)
        org.junit.jupiter.api.Assertions.assertEquals(9L, a.id)
        org.junit.jupiter.api.Assertions.assertEquals(4L, a.version)
        assertTrue(a.isDefault)
    }
}
