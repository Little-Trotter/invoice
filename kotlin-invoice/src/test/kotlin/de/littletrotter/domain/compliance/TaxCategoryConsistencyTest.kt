package de.littletrotter.domain.compliance

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/** The request builder ([Invoice.taxRuleResolutionRequestOrNull]) [reverseChargeEligibility]
 * builds on replaced this class's original coverage of the recipient-context-derived
 *  [de.littletrotter.domain.invoice.Invoice.validateReverseChargeItemConsistency] predecessor
 *  (the mismatch check the header decision now supersedes) with coverage of the eligibility
 *  functions the header toggle and the finalization gate both read. */
class TaxCategoryConsistencyTest {

    private fun sender() = SenderProfile().apply {
        name = "Anbieter GmbH"
        invoicePrefix = "AN-"
        vatId = VatId("DE123456789")
    }

    private fun buyer(withBillingAddress: Boolean = true) = Recipient().apply {
        customerNumber = CustomerNumber("K-1")
        name = "Kunde GmbH"
        customerType = CustomerType.BUSINESS
        vatId = VatId("DE987654321")
        if (withBillingAddress) {
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Kundenweg 2", "50668", "Koeln", Country.DE, true))
        }
    }

    // ── taxRuleResolutionRequestOrNull ─────────────────────────────────────────

    @Test
    fun `no request is built without a recipient at all`() {
        val invoice = Invoice().apply { senderProfile = sender() }
        assertNull(invoice.taxRuleResolutionRequestOrNull())
    }

    @Test
    fun `no request is built when the recipient carries no customerType`() {
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = Recipient().apply { customerNumber = CustomerNumber("K-1"); name = "Kunde"; customerType = null }
        }
        assertNull(invoice.taxRuleResolutionRequestOrNull())
    }

    @Test
    fun `no request is built when the recipient VAT ID has no country prefix`() {
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = buyer(withBillingAddress = false).apply { vatId = null }
        }
        assertNull(invoice.taxRuleResolutionRequestOrNull())
    }

    @Test
    fun `a complete recipient produces a request carrying its VAT ID country`() {
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = buyer()
        }
        val request = invoice.taxRuleResolutionRequestOrNull()
        assertNotNull(request)
        assertEquals(Country.DE, request!!.buyerCountry)
        assertEquals(CustomerType.BUSINESS, request.customerType)
    }

    @Test
    fun `VAT ID country wins without changing a different billing address country`() {
        val recipient = buyer().apply { vatId = VatId("LU12345678") }
        val invoice = Invoice().apply { senderProfile = sender(); this.recipient = recipient }

        assertEquals(Country.LU, invoice.taxRuleResolutionRequestOrNull()?.buyerCountry)
        assertEquals(Country.DE, recipient.billingAddress?.country)
    }

    @Test
    fun `without a selectedSeller override, the request carries the senderProfile's vat id`() {
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = buyer()
            selectedSeller = null
        }
        assertEquals("DE123456789", invoice.taxRuleResolutionRequestOrNull()?.sellerVatId)
    }

    @Test
    fun `a selectedSeller overrides the stored senderProfile's vat id`() {
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = buyer()
            selectedSeller = SenderProfile().apply { name = "Zweitsitz GmbH"; invoicePrefix = "ZW-"; vatId = VatId("DE555555555") }
        }
        assertEquals("DE555555555", invoice.taxRuleResolutionRequestOrNull()?.sellerVatId)
    }

    @Test
    fun `a selectedSeller without a vat id of its own produces a null sellerVatId, not a fallback to senderProfile`() {
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = buyer()
            selectedSeller = SenderProfile().apply { name = "Zweitsitz GmbH"; invoicePrefix = "ZW-"; vatId = null }
        }
        assertNull(invoice.taxRuleResolutionRequestOrNull()?.sellerVatId)
    }

    @Test
    fun `a senderProfile without a vat id at all produces a null sellerVatId, not a crash`() {
        val invoice = Invoice().apply {
            senderProfile = SenderProfile().apply { name = "Anbieter"; invoicePrefix = "AN-"; vatId = null }
            recipient = buyer()
        }
        assertNull(invoice.taxRuleResolutionRequestOrNull()?.sellerVatId)
    }

    @Test
    fun `neither a selectedSeller nor a senderProfile at all still produces a null sellerVatId`() {
        val invoice = Invoice().apply {
            senderProfile = null
            selectedSeller = null
            recipient = buyer()
        }
        assertNull(invoice.taxRuleResolutionRequestOrNull()?.sellerVatId)
    }

    // ── reverseChargeEligibility / reverseChargeIneligibilityReason /
    // assertReverseChargeRecipientEligibility ───────────────────────────────────

    private fun euBusiness() = Recipient().apply {
        customerNumber = CustomerNumber("K-2")
        name = "Client SARL"
        customerType = CustomerType.BUSINESS
        vatId = VatId("FR12345678901")
        vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED
        addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Rue de Paris 1", "75001", "Paris", Country.FR, true))
    }

    @Test
    fun `a verified EU business recipient is eligible for reverse charge`() {
        val invoice = Invoice().apply { senderProfile = sender(); recipient = euBusiness() }
        val proposal = invoice.reverseChargeEligibility(today = FIXED_TODAY)
        assertNotNull(proposal)
        assertEquals(de.littletrotter.shared.tax.VatCategory.REVERSE_CHARGE, proposal!!.category)
        assertEquals("EU_B2B_RC_SERVICE", proposal.ruleCode)
    }

    @Test
    fun `a domestic recipient is not eligible for reverse charge`() {
        val invoice = Invoice().apply { senderProfile = sender(); recipient = buyer() } // DE billing
        assertNull(invoice.reverseChargeEligibility(today = FIXED_TODAY))
        assertTrue(invoice.reverseChargeIneligibilityReason(today = FIXED_TODAY).isNotBlank())
    }

    @Test
    fun `a recipient the resolver cannot resolve at all is not eligible`() {
        // A Swiss recipient is a third country -> TaxRuleResolution.Blocked(UNSUPPORTED_TAX_CASE).
        val chBuyer = Recipient().apply {
            customerNumber = CustomerNumber("K-3")
            name = "Kunde AG"
            customerType = CustomerType.BUSINESS
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Bahnhofstrasse 1", "8001", "Zuerich", Country.CH, true))
        }
        val invoice = Invoice().apply { senderProfile = sender(); recipient = chBuyer }
        assertNull(invoice.reverseChargeEligibility(today = FIXED_TODAY))
    }

    @Test
    fun `the ineligibility reason for a Blocked resolution is the resolver's own detail text`() {
        // Same third-country (CH) case as above, but through reverseChargeIneligibilityReason
        // itself -- exercises the TaxRuleResolution.Blocked branch of its when, not just Proposed.
        val chBuyer = Recipient().apply {
            customerNumber = CustomerNumber("K-3")
            name = "Kunde AG"
            customerType = CustomerType.BUSINESS
            vatId = VatId("CH123456789")
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Bahnhofstrasse 1", "8001", "Zuerich", Country.CH, true))
        }
        val invoice = Invoice().apply { senderProfile = sender(); recipient = chBuyer }

        val reason = invoice.reverseChargeIneligibilityReason(today = FIXED_TODAY)

        assertTrue(reason.isNotBlank())
        val resolution = de.littletrotter.shared.tax.TaxRuleResolver.resolve(invoice.taxRuleResolutionRequestOrNull()!!, today = FIXED_TODAY)
        assertTrue(resolution is de.littletrotter.shared.tax.TaxRuleResolution.Blocked, "Expected Blocked resolution but got ${resolution::class.simpleName}")
        assertEquals(
            (resolution as de.littletrotter.shared.tax.TaxRuleResolution.Blocked).detail,
            reason,
        )
    }

    @Test
    fun `no resolvable recipient context is not eligible`() {
        val invoice = Invoice().apply { senderProfile = sender() }
        assertNull(invoice.reverseChargeEligibility(today = FIXED_TODAY))
        assertEquals("Empfängerdaten sind unvollständig (Land, Kundentyp oder USt-IdNr. fehlen).", invoice.reverseChargeIneligibilityReason(today = FIXED_TODAY))
    }

    @Test
    fun `an active seller VAT registration makes an otherwise eligible recipient ineligible`() {
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = euBusiness()
            sellerVatRegistrationCountry = Country.FR
        }
        assertNull(invoice.reverseChargeEligibility(today = FIXED_TODAY))
        assertEquals(
            "Reverse Charge ist nicht verfügbar, solange eine ausländische Verkäufer-USt-IdNr.-Registrierung aktiv ist.",
            invoice.reverseChargeIneligibilityReason(today = FIXED_TODAY),
        )
    }

    @Test
    fun `assertReverseChargeRecipientEligibility is a no-op while the decision is OFF, however ineligible the recipient is`() {
        val invoice = Invoice().apply { senderProfile = sender(); recipient = buyer(); reverseCharge = false }
        assertDoesNotThrow { invoice.assertReverseChargeRecipientEligibility(today = FIXED_TODAY) }
    }

    @Test
    fun `assertReverseChargeRecipientEligibility passes when the decision is ON and the recipient is eligible`() {
        val invoice = Invoice().apply { senderProfile = sender(); recipient = euBusiness(); reverseCharge = true }
        assertDoesNotThrow { invoice.assertReverseChargeRecipientEligibility(today = FIXED_TODAY) }
    }

    @Test
    fun `assertReverseChargeRecipientEligibility blocks when the decision is ON but the recipient no longer qualifies`() {
        val invoice = Invoice().apply { senderProfile = sender(); recipient = buyer(); reverseCharge = true } // DE billing
        val ex = assertThrows(InvoiceValidationException::class.java) { invoice.assertReverseChargeRecipientEligibility(today = FIXED_TODAY) }
        assertEquals(InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE, ex.error)
        assertEquals(1, ex.args.size)
    }
}
