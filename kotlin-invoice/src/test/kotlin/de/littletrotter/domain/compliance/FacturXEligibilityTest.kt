package de.littletrotter.domain.compliance

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * `factur-x/README.md` decision 11: [FacturXEligibility.isEligible] and its two
 * per-party forms ([FacturXEligibility.sellerInScope]/[FacturXEligibility.recipientInScope]).
 * Covers the decision matrix from `factur-x/-eligibility.md` step 4, plus the extra
 * cases the PO called out explicitly: the per-party predicates in isolation, VAT-id parsing
 * robustness, and multi-registration sellers.
 */
class FacturXEligibilityTest {

    private fun seller(vatId: String? = "DE123456789", registrations: List<Pair<Country, String>> = emptyList()) =
        SenderProfile().apply {
            name = "Absender GmbH"
            this.vatId = VatId.ofNullable(vatId)
            if (registrations.isNotEmpty()) {
                updateVatRegistrations(registrations.map { (country, vat) -> SellerVatRegistrationCommand(country, VatId(vat)) }, today = FIXED_TODAY)
            }
        }

    private fun recipient(vatId: String? = null, billingCountry: Country? = null) = Recipient().apply {
        customerNumber = CustomerNumber("K-1")
        name = "Kunde SARL"
        customerType = CustomerType.BUSINESS
        this.vatId = VatId.ofNullable(vatId)
        if (billingCountry != null) {
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Rue 1", "75001", "Paris", billingCountry, true))
        }
    }

    // ── step 4 decision matrix ────────────────────────────────────────────────

    @Test
    fun `DE seller and FR customer is eligible`() {
        assertTrue(FacturXEligibility.isEligible(seller(), recipient(billingCountry = Country.FR)))
    }

    @Test
    fun `DE seller with a LU additional registration and a DE customer is eligible`() {
        val eligibleSeller = seller(registrations = listOf(Country.LU to "LU12345678"))
        assertTrue(FacturXEligibility.isEligible(eligibleSeller, recipient(billingCountry = Country.DE)))
    }

    @Test
    fun `DE seller with DE plus LU plus NL registrations and a DE customer is eligible - the LU entry decides, NL is ignored`() {
        val multiRegisteredSeller = seller(
            registrations = listOf(Country.LU to "LU12345678", Country.NL to "NL123456789B01"),
        )
        assertTrue(FacturXEligibility.isEligible(multiRegisteredSeller, recipient(billingCountry = Country.DE)))
    }

    @Test
    fun `DE seller with only an NL additional registration and a DE customer is not eligible - NL is the Peppol route, decision 8`() {
        val nlOnlySeller = seller(registrations = listOf(Country.NL to "NL123456789B01"))
        assertFalse(FacturXEligibility.isEligible(nlOnlySeller, recipient(billingCountry = Country.DE)))
    }

    @Test
    fun `DE customer with a FR vatId and a DE address is eligible`() {
        assertTrue(FacturXEligibility.isEligible(seller(), recipient(vatId = "FR12345678901", billingCountry = Country.DE)))
    }

    @Test
    fun `DE seller and DE customer is not eligible`() {
        assertFalse(FacturXEligibility.isEligible(seller(), recipient(billingCountry = Country.DE)))
    }

    @Test
    fun `blank or garbage VAT ids are not eligible unless a country matches`() {
        // VatId's own init rejects "not-a-vat-id" at construction now; the
        // isEligible(SenderProfile?, Recipient?) entity overload can no longer even receive it,
        // so this exercises the same two String-based per-party predicates isEligible itself
        // dispatches to (see FacturXEligibility.isEligible's own KDoc).
        assertFalse(
            FacturXEligibility.sellerInScope("", emptyList()) ||
                FacturXEligibility.recipientInScope(null, "not-a-vat-id"),
        )
    }

    // ── per-party predicates, individually ( consumes these) ─────────────

    @Test
    fun `sellerInScope is true for an own FR-LU-BE vatId prefix and false for a DE one`() {
        assertTrue(FacturXEligibility.sellerInScope(seller(vatId = "FR12345678901")))
        assertTrue(FacturXEligibility.sellerInScope(seller(vatId = "LU12345678")))
        assertTrue(FacturXEligibility.sellerInScope(seller(vatId = "BE0123456789")))
        assertFalse(FacturXEligibility.sellerInScope(seller(vatId = "DE123456789")))
    }

    @Test
    fun `sellerInScope is false for a null seller`() {
        assertFalse(FacturXEligibility.sellerInScope(null))
    }

    @Test
    fun `recipientInScope is true for a FR-LU-BE billing country and false for DE`() {
        assertTrue(FacturXEligibility.recipientInScope(recipient(billingCountry = Country.LU)))
        assertTrue(FacturXEligibility.recipientInScope(recipient(billingCountry = Country.BE)))
        assertFalse(FacturXEligibility.recipientInScope(recipient(billingCountry = Country.DE)))
    }

    @Test
    fun `recipientInScope is true for a FR-LU-BE vatId prefix regardless of address`() {
        assertTrue(FacturXEligibility.recipientInScope(recipient(vatId = "BE0123456789")))
    }

    @Test
    fun `recipientInScope is false for a null recipient or one without any address or vatId`() {
        assertFalse(FacturXEligibility.recipientInScope(null))
        assertFalse(FacturXEligibility.recipientInScope(recipient()))
    }

    // ── VAT-prefix parsing robustness (step 2) ────────────────────────────────

    @Test
    fun `a blank, whitespace-only, or garbage vatId never throws and yields false`() {
        // moved to the String-based per-party predicates: a SenderProfile/Recipient can
        // no longer hold a blank or format-invalid vatId at all (VatId's own init rejects it at
        // construction), so this now exercises exactly the raw-string parsing robustness
        // ([Country.fromVatIdPrefix]'s "never throws" contract) that VatIdField/RecipientView
        // still feed arbitrary, not-yet-validated user input through.
        assertFalse(FacturXEligibility.sellerInScope("", emptyList()))
        assertFalse(FacturXEligibility.sellerInScope("   ", emptyList()))
        assertFalse(FacturXEligibility.sellerInScope("X", emptyList()))
        assertFalse(FacturXEligibility.sellerInScope("!!not-a-country-code!!", emptyList()))
        assertFalse(FacturXEligibility.recipientInScope(null, ""))
        assertFalse(FacturXEligibility.recipientInScope(null, "   "))
        assertFalse(FacturXEligibility.recipientInScope(null, "?"))
    }

    @Test
    fun `a lowercase fr vatId is recognised - uppercase-trim rule`() {
        assertTrue(FacturXEligibility.sellerInScope("  fr12345678901  ", emptyList()))
        assertTrue(FacturXEligibility.recipientInScope(null, "fr12345678901"))
    }

    // ── multi-registration seller among several countries ─────────────────────

    @Test
    fun `a seller with FR-LU-BE among several additional registrations is in scope`() {
        val richlyRegisteredSeller = seller(
            registrations = listOf(
                Country.NL to "NL123456789B01",
                Country.BE to "BE0123456789",
                Country.LU to "LU12345678",
                Country.FR to "FR12345678901",
            ),
        )
        assertTrue(FacturXEligibility.sellerInScope(richlyRegisteredSeller))
    }

    // ── the primitive-values overloads a projected read model (no domain SenderProfile/
    // Recipient loaded or constructed) calls instead ────────────────────────────────────────────

    @Test
    fun `sellerInScope(vatId, countries) is true for an own FR-LU-BE vatId and false for DE`() {
        assertTrue(FacturXEligibility.sellerInScope("FR12345678901", emptyList()))
        assertTrue(FacturXEligibility.sellerInScope("LU12345678", emptyList()))
        assertTrue(FacturXEligibility.sellerInScope("BE0123456789", emptyList()))
        assertFalse(FacturXEligibility.sellerInScope("DE123456789", emptyList()))
    }

    @Test
    fun `sellerInScope(vatId, countries) is true when an FR-LU-BE country is among the additional registrations, NL alone is ignored`() {
        assertTrue(FacturXEligibility.sellerInScope("DE123456789", listOf(Country.NL, Country.LU)))
        assertFalse(FacturXEligibility.sellerInScope("DE123456789", listOf(Country.NL)))
        assertFalse(FacturXEligibility.sellerInScope(null, emptyList()))
    }

    @Test
    fun `recipientInScope(billingCountry, vatId) mirrors the entity-based predicate`() {
        assertTrue(FacturXEligibility.recipientInScope(Country.LU, null))
        assertTrue(FacturXEligibility.recipientInScope(null, "BE0123456789"))
        assertFalse(FacturXEligibility.recipientInScope(Country.DE, "DE123456789"))
        assertFalse(FacturXEligibility.recipientInScope(null, null))
    }

    @Test
    fun `isEligible(primitive values) matches the entity-based decision matrix`() {
        assertTrue(
            FacturXEligibility.isEligible(
                sellerVatId = "DE123456789", sellerAdditionalVatRegistrationCountries = emptyList(),
                recipientBillingCountry = Country.FR, recipientVatId = null,
            ),
        )
        assertTrue(
            FacturXEligibility.isEligible(
                sellerVatId = "FR12345678901", sellerAdditionalVatRegistrationCountries = emptyList(),
                recipientBillingCountry = Country.DE, recipientVatId = null,
            ),
        )
        assertFalse(
            FacturXEligibility.isEligible(
                sellerVatId = "DE123456789", sellerAdditionalVatRegistrationCountries = emptyList(),
                recipientBillingCountry = Country.DE, recipientVatId = "DE987654321",
            ),
        )
    }
}
