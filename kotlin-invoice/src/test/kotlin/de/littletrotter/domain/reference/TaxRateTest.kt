package de.littletrotter.domain.reference

import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class TaxRateTest {

    @Test
    fun `applicability checks country date and optional hotel flag`() {
        val rate = TaxRate().edit {
            country = Country.DE
            validFrom = LocalDate.of(2025, 1, 1)
            validTo = LocalDate.of(2025, 12, 31)
            isHotelRate = true
        }

        assertTrue(rate.isApplicableFor(Country.DE, LocalDate.of(2025, 6, 1), requireHotelRate = true))
        assertFalse(rate.isApplicableFor(Country.FR, LocalDate.of(2025, 6, 1), requireHotelRate = true))
        assertFalse(rate.isApplicableFor(Country.DE, LocalDate.of(2026, 1, 1), requireHotelRate = true))
        assertFalse(rate.isApplicableFor(Country.DE, LocalDate.of(2025, 6, 1), requireHotelRate = false))
        assertTrue(rate.isApplicableFor(Country.DE, LocalDate.of(2025, 6, 1), requireHotelRate = null))
    }

    // ── R1: rule-level validation (defense in depth against the DB CHECK constraints) ──

    private fun rule(
        rate: String = "0.00",
        category: String = "S",
        exemptionReasonCode: String? = null,
        exemptionReasonText: String? = null,
        isSelectable: Boolean = true,
    ): TaxRate = TaxRate().edit {
        name = "test"
        this.rate = TaxRatePercent(BigDecimal(rate))
        code = "TEST_CODE"
        this.category = category
        this.exemptionReasonCode = exemptionReasonCode
        this.exemptionReasonText = exemptionReasonText
        this.isSelectable = isSelectable
    }

    @Test
    fun `AE with a non-zero rate is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            rule(rate = "19.00", category = "AE", exemptionReasonCode = "VATEX-EU-AE").validate()
        }
    }

    @Test
    fun `AE without VATEX-EU-AE is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            rule(category = "AE").validate()
        }
    }

    @Test
    fun `E without exemption reason text is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            rule(category = "E").validate()
        }
    }

    @Test
    fun `O without exemption reason text is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            rule(category = "O").validate()
        }
    }

    @Test
    fun `a rule without a category is rejected`() {
        assertThrows(InvalidParameterException::class.java) { rule(rate = "17.00", category = "").validate() }
        assertThrows(InvalidParameterException::class.java) { rule(rate = "0.00", category = "", isSelectable = false).validate() }
    }

    @Test
    fun `a fully specified AE rule is valid`() {
        rule(category = "AE", exemptionReasonCode = "VATEX-EU-AE").validate() // must not throw
    }

    @Test
    fun `a fully specified E rule is valid`() {
        rule(category = "E", exemptionReasonText = "steuerbefreit").validate() // must not throw
    }

    // ── AE, E, O with 0% are distinguishable domain values ──────────────

    @Test
    fun `AE, E and O at 0pct are three distinguishable VatCategory values`() {
        val ae = rule(category = "AE", exemptionReasonCode = "VATEX-EU-AE")
        val e = rule(category = "E", exemptionReasonText = "steuerbefreit")
        val o = rule(category = "O", exemptionReasonText = "nicht steuerbar")

        assertEquals(VatCategory.REVERSE_CHARGE, ae.categoryEnum)
        assertEquals(VatCategory.EXEMPT, e.categoryEnum)
        assertEquals(VatCategory.OUTSIDE_SCOPE, o.categoryEnum)
        assertTrue(setOf(ae.categoryEnum, e.categoryEnum, o.categoryEnum).size == 3)
    }

    // ── existing category invariants, restated against the extended fixtures ─

    @Test
    fun `EU_B2B_RC_SERVICE without VATEX-EU-AE is rejected`() {
        val r = rule(category = "AE").edit { code = "EU_B2B_RC_SERVICE" }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `a Regelsatz (category S) with a 0pct rate is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            rule(rate = "0.00", category = "S").edit { code = "DE_STANDARD_19"; name = "USt 19%" }.validate()
        }
    }

    @Test
    fun `DE_STANDARD_19 with category AE is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            rule(rate = "19.00", category = "AE", exemptionReasonCode = "VATEX-EU-AE").validate()
        }
    }

    @Test
    fun `category E without a reason text is rejected`() {
        assertThrows(InvalidParameterException::class.java) { rule(category = "E").validate() }
    }

    // ── no two rules of the same code may have overlapping validity ────

    private fun ruleWithValidity(code: String, from: LocalDate?, to: LocalDate?): TaxRate = TaxRate().edit {
        name = "test"
        rate = TaxRatePercent(BigDecimal("19.00"))
        this.code = code
        category = "S"
        validFrom = from
        validTo = to
    }

    @Test
    fun `two rules of the same code with overlapping validity are rejected`() {
        val existing = ruleWithValidity("DE_STANDARD_19", LocalDate.of(2020, 1, 1), LocalDate.of(2025, 12, 31))
        val candidate = ruleWithValidity("DE_STANDARD_19", LocalDate.of(2025, 6, 1), null)

        assertThrows(InvalidParameterException::class.java) {
            TaxRate.assertNoOverlappingValidity(listOf(existing), candidate)
        }
    }

    @Test
    fun `two rules of the same code with adjacent, non-overlapping validity are accepted`() {
        val existing = ruleWithValidity("DE_STANDARD_19", LocalDate.of(2020, 1, 1), LocalDate.of(2025, 12, 31))
        val candidate = ruleWithValidity("DE_STANDARD_19", LocalDate.of(2026, 1, 1), null)

        TaxRate.assertNoOverlappingValidity(listOf(existing), candidate) // must not throw
    }

    @Test
    fun `two rules of different codes never overlap regardless of validity`() {
        val existing = ruleWithValidity("DE_STANDARD_19", null, null)
        val candidate = ruleWithValidity("DE_REDUCED_7", null, null)

        TaxRate.assertNoOverlappingValidity(listOf(existing), candidate) // must not throw
    }

    @Test
    fun `a rule does not overlap with itself in the existing list`() {
        val candidate = ruleWithValidity("DE_STANDARD_19", null, null)
        TaxRate.assertNoOverlappingValidity(listOf(candidate), candidate) // must not throw
    }

    // ── open validity bounds ──────────
    //
    // `overlapsValidityOf`'s `?:` fallbacks to `LocalDate.MIN`/`MAX` mean "valid since forever"
    // and "valid until further notice" respectively. The cases below exercise those open-ended
    // bounds explicitly, since a real reference-data update (a new rule without an end date) is
    // exactly this shape.

    @Test
    fun `eine Regel ohne Enddatum ueberlappt mit einer bestehenden, die spaeter noch laeuft`() {
        // "valid from 2024 with no end" against "valid 2020-2025": from 2024 both apply.
        val existing = ruleWithValidity("DE_STANDARD_19", LocalDate.of(2020, 1, 1), LocalDate.of(2025, 12, 31))
        val candidate = ruleWithValidity("DE_STANDARD_19", LocalDate.of(2024, 1, 1), null)

        assertTrue(candidate.overlapsValidityOf(existing), "ein offenes Ende reicht in die bestehende Regel hinein")
        assertThrows(InvalidParameterException::class.java) {
            TaxRate.assertNoOverlappingValidity(listOf(existing), candidate)
        }
    }

    @Test
    fun `eine Regel ohne Anfangsdatum ueberlappt mit einer bestehenden, die frueher begann`() {
        // "valid since forever until 2019" against "valid from 2015": before 2019 both apply.
        val existing = ruleWithValidity("DE_STANDARD_19", LocalDate.of(2015, 1, 1), null)
        val candidate = ruleWithValidity("DE_STANDARD_19", null, LocalDate.of(2019, 12, 31))

        assertTrue(candidate.overlapsValidityOf(existing), "ein offener Anfang reicht in die bestehende Regel hinein")
    }

    @Test
    fun `eine Regel, die vor der bestehenden endet, ueberlappt nicht`() {
        // The second part of the condition (`!bTo.isBefore(aFrom)`) is decisive here.
        val existing = ruleWithValidity("DE_STANDARD_19", LocalDate.of(2026, 1, 1), null)
        val candidate = ruleWithValidity("DE_STANDARD_19", LocalDate.of(2020, 1, 1), LocalDate.of(2025, 12, 31))

        assertFalse(candidate.overlapsValidityOf(existing), "die Kandidatin endet, bevor die bestehende beginnt")
    }

    @Test
    fun `eine Regel ohne Code kann mit nichts ueberlappen`() {
        // Without a code there is no rule identity to compare against -- `overlapsValidityOf`
        // therefore bails out immediately instead of deciding based on the dates.
        val existing = ruleWithValidity("DE_STANDARD_19", null, null)
        val ohneCode = ruleWithValidity("DE_STANDARD_19", null, null).edit { code = null }

        assertFalse(ohneCode.overlapsValidityOf(existing), "ohne Code keine Kollision")
    }

    @Test
    fun `ein gesetztes Anfangsdatum ohne Enddatum ist gueltig`() {
        // `validate()` checks `validFrom > validTo` only when BOTH are set. "Start set, end
        // open" is the normal case for a rule that is currently in force.
        val offen = ruleWithValidity("DE_STANDARD_19", LocalDate.of(2024, 1, 1), null)
        assertDoesNotThrow { offen.validate() }
    }

    // ── R4: typed views over the persisted code/category strings ────────────────

    @Test
    fun `ruleCode and categoryEnum mirror the persisted code and category`() {
        val r = rule(category = "S")
        assertEquals(TaxRuleCodes.parse("TEST_CODE"), r.ruleCode)
        assertEquals(VatCategory.STANDARD, r.categoryEnum)
    }

    @Test
    fun `ruleCode is null without a code and categoryEnum rejects a blank category`() {
        val r = TaxRate()
        assertEquals(null, r.ruleCode)
        assertThrows(IllegalArgumentException::class.java) { r.categoryEnum }
    }

    // ── isApplicableFor(date) — validity-window edge cases ───────────────────────

    @Test
    fun `a rate with no validFrom is applicable from the very beginning`() {
        val rate = TaxRate().edit { validFrom = null; validTo = LocalDate.of(2025, 12, 31) }
        assertTrue(rate.isApplicableFor(LocalDate.of(2000, 1, 1)))
    }

    @Test
    fun `a rate is not yet applicable before its validFrom date`() {
        val rate = TaxRate().edit { validFrom = LocalDate.of(2025, 6, 1) }
        assertFalse(rate.isApplicableFor(LocalDate.of(2025, 1, 1)))
    }

    // ── validate() — the R1 rate/window checks, independent of validateRule() ────

    // the 0..100 range invariant now throws at TaxRatePercent construction, before
    // TaxRate.validate() is ever reached; see TaxRatePercentTest for the construction-time guard.
    @Test
    fun `constructing a negative rate throws immediately`() {
        assertThrows(InvalidParameterException::class.java) {
            TaxRate().edit { name = "VAT"; this.rate = TaxRatePercent(BigDecimal("-1.00")) }
        }
    }

    @Test
    fun `validate rejects validFrom after validTo`() {
        val rate = TaxRate().edit {
            name = "VAT"
            validFrom = LocalDate.of(2026, 1, 1)
            validTo = LocalDate.of(2025, 1, 1)
        }
        assertThrows(InvalidParameterException::class.java) { rate.validate() }
    }

    // ── validateRule() — the remaining R1 branches beyond the category shape checks ──

    @Test
    fun `a blank, non-null rule code is rejected`() {
        val r = rule(category = "S", rate = "19.00").edit { code = "  "; name = "USt 19%" }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `a category outside S, AE, E, O is rejected`() {
        val r = rule(category = "X")
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `a Regelsatz (category S) with a non-zero rate is valid`() {
        val r = rule(rate = "19.00", category = "S").edit { code = "DE_STANDARD_19"; name = "USt 19%" }
        r.validate() // must not throw
    }

    @Test
    fun `category E with a blank, non-null exemption reason text is rejected just like a missing one`() {
        val r = rule(category = "E", exemptionReasonText = "   ")
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `an eligible reference tax rate is valid`() {
        rule(rate = "19.00", category = "S").edit { isReference = true }.validate()
    }

    @Test
    fun `a reference must satisfy every eligibility condition`() {
        val mutations: List<(TaxRate) -> Unit> = listOf(
            { it.edit { isActive = false } },
            { it.edit { isSelectable = false } },
            { it.edit { category = "E"; rate = TaxRatePercent(BigDecimal.ZERO); exemptionReasonText = "befreit" } },
            { it.edit { isHotelRate = true } },
            { it.edit { rate = TaxRatePercent(BigDecimal.ZERO) } },
        )
        mutations.forEach { mutate ->
            val candidate = rule(rate = "19.00", category = "S").edit { isReference = true }
            mutate(candidate)
            assertThrows(InvalidParameterException::class.java) { candidate.validate() }
        }
    }

    // ── IM2-B/b3 — isUsableReferenceRate, the extracted country-combobox filter predicate ──

    @Test
    fun `isUsableReferenceRate is true for a rate meeting every eligibility condition`() {
        val rate = rule(rate = "19.00", category = "S").edit { isReference = true }
        assertTrue(rate.isUsableReferenceRate)
    }

    @Test
    fun `isUsableReferenceRate is false when not marked as a reference rate at all`() {
        val rate = rule(rate = "19.00", category = "S").edit { isReference = false }
        assertFalse(rate.isUsableReferenceRate)
    }

    @Test
    fun `isUsableReferenceRate is false for each single unmet eligibility condition`() {
        val mutations: List<(TaxRate) -> Unit> = listOf(
            { it.edit { isActive = false } },
            { it.edit { isSelectable = false } },
            { it.edit { category = "E"; rate = TaxRatePercent(BigDecimal.ZERO); exemptionReasonText = "befreit" } },
            { it.edit { isHotelRate = true } },
            { it.edit { rate = TaxRatePercent(BigDecimal.ZERO) } },
        )
        mutations.forEach { mutate ->
            val candidate = rule(rate = "19.00", category = "S").edit { isReference = true }
            mutate(candidate)
            assertFalse(candidate.isUsableReferenceRate, "mutation must make the rate unusable")
        }
    }
}
