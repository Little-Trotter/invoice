package de.littletrotter.shared.tax

import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

private val FIXED_TODAY: LocalDate = LocalDate.of(2026, 6, 15)

/** Every row of the resolver's Entscheidungstabelle. */
class TaxRuleResolverTest {

    private fun request(
        serviceType: ServiceType = ServiceType.GENERAL_SERVICE,
        serviceDate: LocalDate = LocalDate.of(2026, 6, 1),
        sellerCountry: Country = Country.DE,
        buyerCountry: Country = Country.DE,
        customerType: CustomerType = CustomerType.BUSINESS,
        sellerVatId: String? = "DE111111111",
        buyerVatId: String? = null,
        buyerVatIdVerificationStatus: VatIdVerificationStatus? = null,
        explicitSpecialPlaceOfSupply: Boolean = false,
        explicitExemption: ExplicitExemption? = null,
    ) = TaxRuleResolutionRequest(
        serviceType, serviceDate, sellerCountry, buyerCountry, customerType,
        sellerVatId, buyerVatId, buyerVatIdVerificationStatus, explicitSpecialPlaceOfSupply, explicitExemption,
    )

    private fun proposed(decision: TaxRuleResolution): TaxRuleResolution.Proposed {
        assertTrue(decision is TaxRuleResolution.Proposed, "expected Proposed, got $decision")
        return decision as TaxRuleResolution.Proposed
    }

    private fun blocked(decision: TaxRuleResolution): TaxRuleResolution.Blocked {
        assertTrue(decision is TaxRuleResolution.Blocked, "expected Blocked, got $decision")
        return decision as TaxRuleResolution.Blocked
    }

    // ── DE recipient, regardless of customer type ───────────────────────────────

    @Test
    fun `R5-T01 - DE BUSINESS proposes DE_STANDARD_19 at 19pct`() {
        val result = proposed(TaxRuleResolver.resolve(request(buyerCountry = Country.DE, customerType = CustomerType.BUSINESS), today = FIXED_TODAY))
        assertEquals("DE_STANDARD_19", result.ruleCode)
        assertEquals(VatCategory.STANDARD, result.category)
        assertEquals(0, BigDecimal("19.00").compareTo(result.rate))
    }

    @Test
    fun `R5-T02 - DE CONSUMER also proposes DE_STANDARD_19`() {
        val result = proposed(TaxRuleResolver.resolve(request(buyerCountry = Country.DE, customerType = CustomerType.CONSUMER), today = FIXED_TODAY))
        assertEquals("DE_STANDARD_19", result.ruleCode)
    }

    // ── EU B2B with/without a verified VAT id ────────────────────────────────────

    @Test
    fun `R5-T03 - FR BUSINESS with a verified FR vat id proposes EU_B2B_RC_SERVICE at 0pct AE`() {
        val result = proposed(
            TaxRuleResolver.resolve(request(
                    buyerCountry = Country.FR, customerType = CustomerType.BUSINESS,
                    buyerVatId = "FR12345678901", buyerVatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
                ), today = FIXED_TODAY)
        )
        assertEquals("EU_B2B_RC_SERVICE", result.ruleCode)
        assertEquals(VatCategory.REVERSE_CHARGE, result.category)
        assertEquals(0, BigDecimal.ZERO.compareTo(result.rate))
    }

    @Test
    fun `R5-T04 - FR BUSINESS without a verified vat id is never automatically Reverse Charge`() {
        val result = blocked(
            TaxRuleResolver.resolve(request(
                    buyerCountry = Country.FR, customerType = CustomerType.BUSINESS,
                    buyerVatId = "FR12345678901", buyerVatIdVerificationStatus = VatIdVerificationStatus.UNVERIFIED,
                ), today = FIXED_TODAY)
        )
        assertEquals(TaxRuleResolutionError.BUYER_VAT_ID_NOT_VERIFIED, result.error)
    }

    // ── EU B2C always the German standard rate, never the local rate ────────────

    @Test
    fun `R5-T05 - FR CONSUMER proposes the German standard rate, never a French one`() {
        val result = proposed(TaxRuleResolver.resolve(request(buyerCountry = Country.FR, customerType = CustomerType.CONSUMER), today = FIXED_TODAY))
        assertEquals("EU_B2C_DE_STANDARD_19", result.ruleCode)
        assertNotEquals("LEGACY_FR_20", result.ruleCode)
        assertEquals(0, BigDecimal("19.00").compareTo(result.rate))
    }

    // ── Third country ────────────────────────────────────────────────────────

    @Test
    fun `R5-T06 - a Swiss recipient is UNSUPPORTED_TAX_CASE`() {
        val result = blocked(TaxRuleResolver.resolve(request(buyerCountry = Country.CH), today = FIXED_TODAY))
        assertEquals(TaxRuleResolutionError.UNSUPPORTED_TAX_CASE, result.error)
    }

    // ── Goods delivery ────────────────────────────────────────────────────────

    @Test
    fun `R5-T07 - a goods delivery (not GENERAL_SERVICE) is UNSUPPORTED_TAX_CASE`() {
        val result = blocked(TaxRuleResolver.resolve(request(serviceType = ServiceType.UNSUPPORTED), today = FIXED_TODAY))
        assertEquals(TaxRuleResolutionError.UNSUPPORTED_TAX_CASE, result.error)
    }

    // ── Special place-of-supply rule flagged ─────────────────────────────────────

    @Test
    fun `R5-T08 - an explicitly flagged Sonderortsregel blocks with an explanation`() {
        val result = blocked(TaxRuleResolver.resolve(request(explicitSpecialPlaceOfSupply = true), today = FIXED_TODAY))
        assertEquals(TaxRuleResolutionError.UNSUPPORTED_TAX_CASE, result.error)
        assertTrue(result.detail.isNotBlank())
    }

    // ── Non-German seller ─────────────────────────────────────────────────────

    @Test
    fun `R5-T09 - a non-German seller is unsupported`() {
        val result = blocked(TaxRuleResolver.resolve(request(sellerCountry = Country.FR), today = FIXED_TODAY))
        assertEquals(TaxRuleResolutionError.SELLER_COUNTRY_UNSUPPORTED, result.error)
    }

    // ── VAT id cross-checks ──────────────────────────────────────────────────

    @Test
    fun `R5-T10 - a buyer vat id with a BE prefix but FR recipient country is rejected`() {
        val result = blocked(
            TaxRuleResolver.resolve(request(
                    buyerCountry = Country.FR, customerType = CustomerType.BUSINESS,
                    buyerVatId = "BE0123456789", buyerVatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
                ), today = FIXED_TODAY)
        )
        assertEquals(TaxRuleResolutionError.VAT_ID_INVALID, result.error)
    }

    @Test
    fun `R5-T11 - identical seller and buyer vat id is rejected`() {
        val result = blocked(
            TaxRuleResolver.resolve(request(
                    buyerCountry = Country.FR, customerType = CustomerType.BUSINESS,
                    sellerVatId = "FR12345678901", buyerVatId = "FR12345678901",
                    buyerVatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
                ), today = FIXED_TODAY)
        )
        assertEquals(TaxRuleResolutionError.VAT_ID_INVALID, result.error)
    }

    @Test
    fun `R5-T12 - Reverse Charge without a buyer vat id is blocked and names the missing field`() {
        val result = blocked(
            TaxRuleResolver.resolve(request(buyerCountry = Country.FR, customerType = CustomerType.BUSINESS, buyerVatId = null), today = FIXED_TODAY)
        )
        assertEquals(TaxRuleResolutionError.VAT_ID_INVALID, result.error)
        assertTrue(result.detail.contains("Käufer-USt-IdNr", ignoreCase = true))
    }

    // ── PO decision 2026-08-25 — allowlist extended to all current EU members ──

    @Test
    fun `R5-T03b - IT BUSINESS with a verified IT vat id now proposes EU_B2B_RC_SERVICE, not UNSUPPORTED_TAX_CASE`() {
        // Regression for the old curated 8-country allowlist blocking a live RC invoice to
        // Italy with TAX.UNSUPPORTED_TAX_CASE even though IT is (and always was) an EU member.
        val result = proposed(
            TaxRuleResolver.resolve(request(
                    buyerCountry = Country.IT, customerType = CustomerType.BUSINESS,
                    buyerVatId = "IT12345678901", buyerVatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
                ), today = FIXED_TODAY)
        )
        assertEquals("EU_B2B_RC_SERVICE", result.ruleCode)
        assertEquals(VatCategory.REVERSE_CHARGE, result.category)
        assertEquals(0, BigDecimal.ZERO.compareTo(result.rate))
    }

    // ── an approved target country before its EU accession date ────────────────

    @Test
    fun `R5-T13 - an approved target country blocks with the EU-membership reason before it actually joined`() {
        // CZ is on the approved B2B target list, but only became an EU member on 2004-05-01 —
        // unlike GB (never approved at all), this exercises the "approved but not yet a member"
        // combination specifically.
        val result = blocked(
            TaxRuleResolver.resolve(request(
                    buyerCountry = Country.CZ, customerType = CustomerType.BUSINESS,
                    serviceDate = LocalDate.of(2000, 1, 1),
                ), today = FIXED_TODAY)
        )
        assertEquals(TaxRuleResolutionError.UNSUPPORTED_TAX_CASE, result.error)
        assertTrue(result.detail.contains("kein EU-Mitglied"), result.detail)
    }

    // ── GB is time-relative, never actually supported ────────────────────────────

    @Test
    fun `R5-T15 - GB is blocked both before and after Brexit, with a date-relative reason`() {
        val beforeBrexit = blocked(TaxRuleResolver.resolve(request(buyerCountry = Country.GB, serviceDate = LocalDate.of(2019, 6, 1)), today = FIXED_TODAY))
        val afterBrexit = blocked(TaxRuleResolver.resolve(request(buyerCountry = Country.GB, serviceDate = LocalDate.of(2021, 6, 1)), today = FIXED_TODAY))

        assertEquals(TaxRuleResolutionError.UNSUPPORTED_TAX_CASE, beforeBrexit.error)
        assertEquals(TaxRuleResolutionError.UNSUPPORTED_TAX_CASE, afterBrexit.error)
        assertNotEquals(beforeBrexit.detail, afterBrexit.detail, "the explanation should differ by EU-membership date even though both block")
    }

    // ── Explicit exemption path (§5) ─────────────────────────────────────────

    @Test
    fun `an explicit exemption without a reason text is rejected`() {
        val result = blocked(
            TaxRuleResolver.resolve(request(explicitExemption = ExplicitExemption("DE_EXEMPT_SERVICE", VatCategory.EXEMPT, reasonText = "", legalBasis = "§ 4 UStG")), today = FIXED_TODAY)
        )
        assertEquals(TaxRuleResolutionError.EXEMPTION_REASON_MISSING, result.error)
    }

    @Test
    fun `an explicit exemption with a reason text but no legal basis is rejected too`() {
        val result = blocked(
            TaxRuleResolver.resolve(request(explicitExemption = ExplicitExemption("DE_EXEMPT_SERVICE", VatCategory.EXEMPT, reasonText = "steuerbefreit", legalBasis = "  ")), today = FIXED_TODAY)
        )
        assertEquals(TaxRuleResolutionError.EXEMPTION_REASON_MISSING, result.error)
    }

    @Test
    fun `a complete explicit exemption is proposed as-is`() {
        val result = proposed(
            TaxRuleResolver.resolve(request(explicitExemption = ExplicitExemption("DE_EXEMPT_SERVICE", VatCategory.EXEMPT, reasonText = "steuerbefreit", legalBasis = "§ 4 UStG")), today = FIXED_TODAY)
        )
        assertEquals("DE_EXEMPT_SERVICE", result.ruleCode)
        assertEquals(VatCategory.EXEMPT, result.category)
    }

    // ── No branch checks rate == 0, only the rule/category codes ────────────────

    @Test
    fun `R5-T19 - AE, E and O share a 0pct rate but the resolver never branches on the rate itself`() {
        // This is primarily a code-review property, but it's
        // reflected in the domain shape: TaxRuleResolutionError/VatCategory carry no branch on
        // BigDecimal.ZERO anywhere in TaxRuleResolver — verified indirectly by asserting that
        // an explicit AE exemption and an explicit E exemption, both at rate 0, still resolve
        // to distinguishable categories rather than collapsing into one "zero rate" bucket.
        val ae = proposed(TaxRuleResolver.resolve(request(explicitExemption = ExplicitExemption("EU_B2B_RC_SERVICE", VatCategory.REVERSE_CHARGE, "RC", "§ 3a UStG")), today = FIXED_TODAY))
        val e = proposed(TaxRuleResolver.resolve(request(explicitExemption = ExplicitExemption("DE_EXEMPT_SERVICE", VatCategory.EXEMPT, "steuerbefreit", "§ 4 UStG")), today = FIXED_TODAY))
        assertNotEquals(ae.category, e.category)
        assertEquals(0, ae.rate.compareTo(e.rate)) // same rate, still distinguishable by category
    }
}
