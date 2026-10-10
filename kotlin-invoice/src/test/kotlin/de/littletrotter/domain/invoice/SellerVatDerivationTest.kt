package de.littletrotter.domain.invoice

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.ReverseChargePreference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every branch of [deriveSellerVatRegistrationCountry] in isolation, plain JVM,
 * no [Invoice]/no database. This is the unit the 100%-coverage jacoco gate in
 * `kotlin-invoice/pom.xml` targets — the tests below are written to exercise every one of its
 * three `if`s in both directions.
 */
class SellerVatDerivationTest {

    @Test
    fun `a null recipient country derives to null`() {
        assertNull(deriveSellerVatRegistrationCountry(recipientCountry = null, sellerRegistrationCountries = setOf(Country.LU)))
    }

    @Test
    fun `a null recipient country derives to null even with an empty registration set`() {
        // Distinct branch combination from the case above: the null-check short-circuits before
        // the registration set is even consulted.
        assertNull(deriveSellerVatRegistrationCountry(recipientCountry = null, sellerRegistrationCountries = emptySet()))
    }

    @Test
    fun `Germany never derives, even when the seller happens to hold a DE registration entry`() {
        // DE is never an "additional" registration in practice (SellerVatRegistration/Country.
        // SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES exclude it), but the function itself must
        // still refuse it explicitly rather than relying on that upstream invariant.
        assertNull(deriveSellerVatRegistrationCountry(recipientCountry = Country.DE, sellerRegistrationCountries = setOf(Country.DE, Country.LU)))
    }

    @Test
    fun `a registered non-German recipient country derives to that country`() {
        assertEquals(
            Country.LU,
            deriveSellerVatRegistrationCountry(recipientCountry = Country.LU, sellerRegistrationCountries = setOf(Country.LU, Country.FR)),
        )
    }

    @Test
    fun `an unregistered non-German recipient country derives to null`() {
        // FR is a curated, in-principle-supported country, but this seller only registered LU.
        assertNull(deriveSellerVatRegistrationCountry(recipientCountry = Country.FR, sellerRegistrationCountries = setOf(Country.LU)))
    }

    @Test
    fun `an empty registration set never derives to anything`() {
        assertNull(deriveSellerVatRegistrationCountry(recipientCountry = Country.BE, sellerRegistrationCountries = emptySet()))
    }

    @Test
    fun `different VAT countries without matching seller registration require explicit RC decision`() {
        assertTrue(requiresReverseChargeDecision("DE123456789", "LU12345678", emptySet()))
    }

    @Test
    fun `matching registration resolves VAT country without RC question`() {
        assertFalse(requiresReverseChargeDecision("DE123456789", "LU12345678", setOf(Country.LU)))
    }

    @Test
    fun `same or unknown VAT country does not ask an unanswerable RC question`() {
        assertFalse(requiresReverseChargeDecision("DE123456789", "DE987654321", emptySet()))
        assertFalse(requiresReverseChargeDecision("DE123456789", null, emptySet()))
        assertFalse(requiresReverseChargeDecision(null, "LU12345678", emptySet()))
    }

    // ── resolveReverseChargeCreationDecision, the plan.md combination matrix (10 rows) ────
    // Row numbers match docs' rc2-invoice-creation-decision-wiring.md / plan.md table order.
    // recipientCountry is passed matching what the row's VAT-ID used to imply (Fix H changed
    // hasMatchingRegistration's own signal from VAT-ID to address country, not what each row is
    // about) — the dedicated "address vs. VAT-ID disagree" tests below are what actually proves
    // Fix H's correction reaches this function too.

    @Test
    fun `row 1 - NEVER, same VAT country - Resolved false`() {
        assertEquals(
            ReverseChargeCreationDecision.Resolved(false),
            resolveReverseChargeCreationDecision(ReverseChargePreference.NEVER, "DE123456789", RecipientVatInfo("DE987654321", Country.DE), emptySet()),
        )
    }

    @Test
    fun `row 2 - NEVER, different VAT country, no matching registration - Resolved false`() {
        assertEquals(
            ReverseChargeCreationDecision.Resolved(false),
            resolveReverseChargeCreationDecision(ReverseChargePreference.NEVER, "DE123456789", RecipientVatInfo("LU12345678", Country.LU), emptySet()),
        )
    }

    @Test
    fun `row 3 - NEVER, different VAT country, matching registration - Resolved false (unaffected by preference)`() {
        assertEquals(
            ReverseChargeCreationDecision.Resolved(false),
            resolveReverseChargeCreationDecision(ReverseChargePreference.NEVER, "DE123456789", RecipientVatInfo("LU12345678", Country.LU), setOf(Country.LU)),
        )
    }

    @Test
    fun `row 4 - ALWAYS, same VAT country - Resolved true (blocked downstream by the existing eligibility gate, not here)`() {
        assertEquals(
            ReverseChargeCreationDecision.Resolved(true),
            resolveReverseChargeCreationDecision(ReverseChargePreference.ALWAYS, "DE123456789", RecipientVatInfo("DE987654321", Country.DE), emptySet()),
        )
    }

    @Test
    fun `row 5 - ALWAYS, different VAT country, no matching registration, VAT-ID verified - Resolved true`() {
        assertEquals(
            ReverseChargeCreationDecision.Resolved(true),
            resolveReverseChargeCreationDecision(ReverseChargePreference.ALWAYS, "DE123456789", RecipientVatInfo("LU12345678", Country.LU), emptySet()),
        )
    }

    @Test
    fun `row 6 - ALWAYS, different VAT country, no matching registration, VAT-ID unverified - Resolved true (verification is not this function's concern)`() {
        // VAT-ID verification status is not one of this function's inputs at all — it only ever
        // matters downstream, at InvoiceLifecycleService.create's reverseChargeEligibility() gate
        // (see InvoiceCreateForCustomerE2EIT-style tests for the full-eligibility assertion).
        // Same inputs as row 5 on purpose: the pure decision is identical either way, exactly
        // proving that "verified vs. unverified" is not this function's own branch.
        assertEquals(
            ReverseChargeCreationDecision.Resolved(true),
            resolveReverseChargeCreationDecision(ReverseChargePreference.ALWAYS, "DE123456789", RecipientVatInfo("LU12345678", Country.LU), emptySet()),
        )
    }

    @Test
    fun `row 7 - ALWAYS, different VAT country, matching registration - Resolved true (blocked downstream, point 3)`() {
        assertEquals(
            ReverseChargeCreationDecision.Resolved(true),
            resolveReverseChargeCreationDecision(ReverseChargePreference.ALWAYS, "DE123456789", RecipientVatInfo("LU12345678", Country.LU), setOf(Country.LU)),
        )
    }

    @Test
    fun `row 8 - ASK, same VAT country - Resolved false (unchanged today)`() {
        assertEquals(
            ReverseChargeCreationDecision.Resolved(false),
            resolveReverseChargeCreationDecision(ReverseChargePreference.ASK, "DE123456789", RecipientVatInfo("DE987654321", Country.DE), emptySet()),
        )
    }

    @Test
    fun `row 9 - ASK, different VAT country, no matching registration - Ask with Yes allowed (today's behaviour)`() {
        assertEquals(
            ReverseChargeCreationDecision.Ask(allowYes = true),
            resolveReverseChargeCreationDecision(ReverseChargePreference.ASK, "DE123456789", RecipientVatInfo("LU12345678", Country.LU), emptySet()),
        )
    }

    @Test
    fun `row 10 - ASK, different VAT country, matching registration - Ask with Yes disabled (point 4, new)`() {
        assertEquals(
            ReverseChargeCreationDecision.Ask(allowYes = false),
            resolveReverseChargeCreationDecision(ReverseChargePreference.ASK, "DE123456789", RecipientVatInfo("LU12345678", Country.LU), setOf(Country.LU)),
        )
    }

    // ── Fix H reaches too — hasMatchingRegistration follows the recipient's address, ────────
    // ── never its VAT-ID, exactly like create()/updateHeader's own derivation.               ────

    @Test
    fun `ASK, German-format VAT-ID but an LU billing address, matching LU registration - Ask with Yes disabled`() {
        // The exact shape of the original Package H bug's fixtures (SystemWorkflowCrossBorderVatIT):
        // recipientVatId stays "DE..." (never updated to match a country change) while the
        // recipient's real, billed country is LU. Before Fix H reached this function too, this
        // recipientVatId would have derived Country.DE via Country.fromVatIdPrefix, DE is
        // explicitly excluded by deriveSellerVatRegistrationCountry, so hasMatchingRegistration
        // would have been false here — wrongly allowing "Yes" even though the seller already
        // holds a real LU registration for this recipient's actual country.
        assertEquals(
            ReverseChargeCreationDecision.Ask(allowYes = false),
            resolveReverseChargeCreationDecision(
                ReverseChargePreference.ASK,
                "DE123456789",
                RecipientVatInfo("DE111111111", Country.LU),
                setOf(Country.LU),
            ),
        )
    }

    @Test
    fun `ALWAYS, German-format VAT-ID but an LU billing address, matching LU registration - still Resolved true (blocking is downstream)`() {
        // Same address/VAT-ID mismatch as above, ALWAYS preference: resolveReverseChargeCreationDecision
        // itself still resolves outright to true (ALWAYS never asks) — the matching-registration
        // block is InvoiceLifecycleService.create's own reverseChargeEligibility() gate, not this
        // function's concern (mirrors row 7's own "blocked downstream" note).
        assertEquals(
            ReverseChargeCreationDecision.Resolved(true),
            resolveReverseChargeCreationDecision(
                ReverseChargePreference.ALWAYS,
                "DE123456789",
                RecipientVatInfo("DE111111111", Country.LU),
                setOf(Country.LU),
            ),
        )
    }
}
