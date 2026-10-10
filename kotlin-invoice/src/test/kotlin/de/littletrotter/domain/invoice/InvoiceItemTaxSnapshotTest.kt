package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.test.FIXED_TODAY

/**
 * A position's tax snapshot must survive a later change to the [TaxRate] row it was
 * taken from (RV-02, GoBD): [InvoiceItem.snapshotTaxRule] freezes the values onto the item;
 * nothing re-reads [InvoiceItem.taxRateRef] afterwards for a snapshotted item.
 */
class InvoiceItemTaxSnapshotTest {

    private fun taxRate(): TaxRate = TaxRate().apply {
        code = "DE_STANDARD_19"
        category = "S"
        rate = TaxRatePercent(BigDecimal("19.00"))
        legalBasis = "§ 12 Abs. 1 UStG"
    }

    @Test
    fun `snapshotTaxRule copies code, category, rate and legal basis onto the item`() {
        val item = InvoiceItem().apply { taxRateRef = taxRate() }

        item.snapshotTaxRule()

        assertEquals("DE_STANDARD_19", item.taxRuleCode)
        assertEquals("S", item.taxCategory)
        assertEquals(0, BigDecimal("19.00").compareTo(item.taxRateSnapshot?.value))
        assertEquals("§ 12 Abs. 1 UStG", item.taxLegalBasis)
    }

    @Test
    fun `snapshot survives a later change to the referenced TaxRate row`() {
        val rate = taxRate()
        val item = InvoiceItem().apply { taxRateRef = rate }
        item.snapshotTaxRule()

        // Simulate exactly the scenario R1 exists to prevent: someone edits the shared
        // reference-data row after this item was finalized.
        rate.rate = TaxRatePercent(BigDecimal("99.00"))
        rate.code = "SOMETHING_ELSE"
        rate.category = "E"

        assertEquals(0, BigDecimal("19.00").compareTo(item.taxRateSnapshot?.value), "snapshot rate must not follow the live row")
        assertEquals("DE_STANDARD_19", item.taxRuleCode, "snapshot code must not follow the live row")
        assertEquals("S", item.taxCategory, "snapshot category must not follow the live row")
    }

    @Test
    fun `snapshot carries the exemption reason for a zero-rate rule`() {
        val rate = TaxRate().apply {
            code = "EU_B2B_RC_SERVICE"
            category = "AE"
            rate = TaxRatePercent(BigDecimal("0.00"))
            exemptionReasonCode = "VATEX-EU-AE"
            exemptionReasonText = "Reverse Charge"
            legalBasis = "§ 3a Abs. 2 UStG"
        }
        val item = InvoiceItem().apply { taxRateRef = rate }

        item.snapshotTaxRule()

        assertEquals("VATEX-EU-AE", item.exemptionReasonCode)
        assertEquals("Reverse Charge", item.exemptionReasonText)
    }

    @Test
    fun `snapshotTaxRule without a taxRateRef throws instead of silently doing nothing`() {
        val item = InvoiceItem()
        assertThrows(InvoiceValidationException::class.java) { item.snapshotTaxRule() }
    }

    @Test
    fun `a fresh item has no snapshot until snapshotTaxRule is called`() {
        val item = InvoiceItem().apply { taxRateRef = taxRate() }
        assertNull(item.taxRuleCode)
        assertNull(item.taxRateSnapshot)
    }

    // ── applyTaxRule selects and snapshots atomically ──────────────

    @Test
    fun `applyTaxRule sets taxRateRef and all snapshot fields in one call`() {
        val item = InvoiceItem()
        val rate = taxRate()

        item.applyTaxRule(rate)

        assertEquals(rate, item.taxRateRef)
        assertEquals("DE_STANDARD_19", item.taxRuleCode)
        assertEquals("S", item.taxCategory)
        assertEquals(0, BigDecimal("19.00").compareTo(item.taxRateSnapshot?.value))
    }

    @Test
    fun `applyTaxRule's snapshot survives a later change to the applied TaxRate row`() {
        val item = InvoiceItem()
        val rate = taxRate()
        item.applyTaxRule(rate)

        rate.rate = TaxRatePercent(BigDecimal("99.00"))
        rate.code = "SOMETHING_ELSE"

        assertEquals(0, BigDecimal("19.00").compareTo(item.taxRateSnapshot?.value))
        assertEquals("DE_STANDARD_19", item.taxRuleCode)
    }

    // ── a finalized (or cancelled) invoice's items reject snapshot changes ─

    private fun finalizedInvoice(): Invoice = Invoice().apply {
        id = 1L
        transitionTo(InvoiceStatus.IN_REVIEW)
        transitionTo(InvoiceStatus.SENT)
    }

    @Test
    fun `applyTaxRule on a finalized invoice's item is rejected`() {
        val item = InvoiceItem().apply { invoice = finalizedInvoice() }
        assertThrows(InvoiceImmutableException::class.java) { item.applyTaxRule(taxRate()) }
    }

    @Test
    fun `snapshotTaxRule on a finalized invoice's item is rejected`() {
        val item = InvoiceItem().apply { taxRateRef = taxRate(); invoice = finalizedInvoice() }
        assertThrows(InvoiceImmutableException::class.java) { item.snapshotTaxRule() }
    }

    @Test
    fun `applyTaxRule on a cancelled invoice's item is rejected`() {
        // CANCELLED is kept for backward compatibility (see InvoiceStatus's own KDoc) but no
        // longer reachable via transitionTo — set directly, the same way persisted legacy rows
        // would carry it.
        val cancelled = Invoice().apply { id = 2L }.also { it.forceStatusForTest(InvoiceStatus.CANCELLED) }
        val item = InvoiceItem().apply { invoice = cancelled }
        assertThrows(InvoiceImmutableException::class.java) { item.applyTaxRule(taxRate()) }
    }

    @Test
    fun `applyTaxRule on a voided invoice's item is rejected`() {
        val voided = Invoice().apply { id = 4L; transitionTo(InvoiceStatus.VOIDED) }
        val item = InvoiceItem().apply { invoice = voided }
        assertThrows(InvoiceImmutableException::class.java) { item.applyTaxRule(taxRate()) }
    }

    @Test
    fun `applyTaxRule on a DRAFT invoice's item still succeeds`() {
        val draft = Invoice().apply { id = 3L }
        val item = InvoiceItem().apply { invoice = draft }
        item.applyTaxRule(taxRate()) // must not throw
        assertEquals("DE_STANDARD_19", item.taxRuleCode)
    }

    @Test
    fun `an item without an invoice can still be snapshotted`() {
        val item = InvoiceItem()
        item.applyTaxRule(taxRate()) // must not throw — no invoice means no immutability to violate
        assertEquals("DE_STANDARD_19", item.taxRuleCode)
    }

    // ── effectiveTaxRate reads the snapshot once one exists ────────────

    @Test
    fun `effectiveTaxRate reads the snapshot, not the live taxRateRef, once snapshotted`() {
        val item = InvoiceItem()
        item.applyTaxRule(taxRate())

        item.taxRateRef!!.rate = TaxRatePercent(BigDecimal("99.00")) // live row changes after the fact

        assertEquals(0, BigDecimal("19.00").compareTo(item.effectiveTaxRate()))
    }

    @Test
    fun `effectiveTaxRate falls back to the live taxRateRef for a pre-R1 item without a snapshot`() {
        val item = InvoiceItem().apply { taxRateRef = taxRate() }
        assertEquals(0, BigDecimal("19.00").compareTo(item.effectiveTaxRate()))
    }

    // ── applySellerCountryTaxVariant — the seller-country-derived counterpart of applyTaxRule ────

    private fun luTaxRate(): TaxRate = TaxRate().apply {
        code = "LU_STANDARD_17"; category = "S"; rate = TaxRatePercent(BigDecimal("17.00")); country = Country.LU
    }

    private fun articleWithLuVariant(): Article {
        val article = Article().apply { id = 1L; name = "Beratung" }
        article.replaceTaxVariants(listOf(ArticleTaxVariant.of(article, Country.LU, luTaxRate(), today = FIXED_TODAY)), today = FIXED_TODAY)
        return article
    }

    @Test
    fun `applySellerCountryTaxVariant applies and snapshots the article's country variant`() {
        val item = InvoiceItem().apply { articleRef = articleWithLuVariant() }

        item.applySellerCountryTaxVariant(Country.LU)

        assertEquals("LU_STANDARD_17", item.taxRuleCode)
        assertEquals("S", item.taxCategory)
        assertEquals(0, BigDecimal("17.00").compareTo(item.taxRateSnapshot?.value))
    }

    @Test
    fun `applySellerCountryTaxVariant throws instead of falling back to Germany when the article has no variant`() {
        val item = InvoiceItem().apply { articleRef = Article().apply { id = 2L; name = "Ohne Variante" } }

        val ex = assertThrows(InvoiceValidationException::class.java) {
            item.applySellerCountryTaxVariant(Country.LU)
        }
        assertEquals(InvoiceValidationError.ITEM_ARTICLE_TAX_VARIANT_MISSING, ex.error)
    }

    @Test
    fun `applySellerCountryTaxVariant uses the same-country reference when article variant is absent`() {
        val reference = luTaxRate().apply { code = "LU_REFERENCE_17"; isReference = true }
        val item = InvoiceItem().apply { articleRef = Article().apply { id = 2L; name = "Ohne Variante" } }

        item.applySellerCountryTaxVariant(Country.LU, reference)

        assertEquals(reference, item.taxRateRef)
        assertEquals("LU_REFERENCE_17", item.taxRuleCode)
        assertEquals(0, BigDecimal("17.00").compareTo(item.taxRateSnapshot?.value))
    }

    @Test
    fun `applySellerCountryTaxVariant throws for a free-text item without a bound article`() {
        val item = InvoiceItem()
        val ex = assertThrows(InvoiceValidationException::class.java) {
            item.applySellerCountryTaxVariant(Country.LU)
        }
        assertEquals(InvoiceValidationError.ITEM_ARTICLE_TAX_VARIANT_MISSING, ex.error)
    }

    @Test
    fun `applySellerCountryTaxVariant requests a different country than the one maintained is also blocked`() {
        val item = InvoiceItem().apply { articleRef = articleWithLuVariant() }
        assertThrows(InvoiceValidationException::class.java) {
            item.applySellerCountryTaxVariant(Country.FR) // article only carries an LU variant
        }
    }

    @Test
    fun `applySellerCountryTaxVariant on a finalized invoice's item is rejected`() {
        val item = InvoiceItem().apply { articleRef = articleWithLuVariant(); invoice = finalizedInvoice() }
        assertThrows(InvoiceImmutableException::class.java) { item.applySellerCountryTaxVariant(Country.LU) }
    }
}
