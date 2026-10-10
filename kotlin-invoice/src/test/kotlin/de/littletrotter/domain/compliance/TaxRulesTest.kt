package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * [TaxRules] additionally translates what
 * [TaxRuleResolver]/[de.littletrotter.domain.compliance.hasMixedReverseChargeAndStandard] deliver
 * only as throws into findings, without changing the throws themselves (those stay structurally
 * ahead of rule evaluation in `InvoiceLifecycleService.transition`). replaced the
 * recipient-context-derived throw this class' `CATEGORY_MISMATCH` finding still mirrors (below)
 * with [de.littletrotter.domain.invoice.Invoice.validateReverseChargeItemConsistency]/
 * [assertReverseChargeRecipientEligibility] — see [TaxRules]'s own KDoc for how this finding now
 * relates to that gate.
 */
class TaxRulesTest {

    private fun item(taxCategory: String?) = InvoiceItem().apply {
        positionId = "1"
        description = "Position"
        quantity = BigDecimal.ONE
        unit = InvoiceItemUnit.HOUR
        unitPriceNet = Money.of("100.00")
        this.taxCategory = taxCategory
    }

    @Test
    fun `a mix of reverse-charge and standard-rate positions is reported`() {
        val invoice = Invoice()
        invoice.addItem(item("AE"))
        invoice.addItem(item("S"))

        val findings = TaxRules.evaluate(invoice, today = FIXED_TODAY)

        val mix = findings.filter { it.ruleId == TaxRules.MIXED_CATEGORY }
        assertEquals(1, mix.size)
        assertEquals(ComplianceSeverity.ERROR, mix.single().severity)
    }

    @Test
    fun `uniform positions never trigger the mix finding`() {
        val invoice = Invoice()
        invoice.addItem(item("S"))
        invoice.addItem(item("S"))

        assertTrue(TaxRules.evaluate(invoice, today = FIXED_TODAY).none { it.ruleId == TaxRules.MIXED_CATEGORY })
    }

    private fun domesticBuyer() = Recipient().apply {
        customerNumber = CustomerNumber("K-1")
        name = "Kunde GmbH"
        customerType = CustomerType.BUSINESS
        vatId = VatId("DE987654321")
        addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Kundenweg 2", "50668", "Koeln", Country.DE, true))
    }

    private fun sender() = SenderProfile().apply {
        name = "Anbieter GmbH"
        invoicePrefix = "AN-"
        vatId = VatId("DE123456789")
    }

    @Test
    fun `a domestic invoice with a reverse-charge position conflicts with the expected standard rate`() {
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = domesticBuyer()
        }
        invoice.addItem(item("AE")) // domestic buyer -> resolver proposes STANDARD (S)

        val findings = TaxRules.evaluate(invoice, today = FIXED_TODAY)

        val mismatch = findings.filter { it.ruleId == TaxRules.CATEGORY_MISMATCH }
        assertEquals(1, mismatch.size)
        assertEquals(ComplianceSeverity.ERROR, mismatch.single().severity)
    }

    @Test
    fun `a domestic invoice whose positions already match the expected category is not reported`() {
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = domesticBuyer()
        }
        invoice.addItem(item("S"))

        assertTrue(TaxRules.evaluate(invoice, today = FIXED_TODAY).none { it.ruleId == TaxRules.CATEGORY_MISMATCH })
    }

    @Test
    fun `an EU B2B recipient with a verified VAT id resolves to reverse charge without conflict`() {
        val buyer = Recipient().apply {
            customerNumber = CustomerNumber("K-2")
            name = "Client SARL"
            customerType = CustomerType.BUSINESS
            vatId = VatId("FR12345678901")
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Rue de Paris 1", "75001", "Paris", Country.FR, true))
        }
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = buyer
        }
        invoice.addItem(item("AE"))

        assertTrue(TaxRules.evaluate(invoice, today = FIXED_TODAY).none { it.ruleId == TaxRules.CATEGORY_MISMATCH })
    }

    @Test
    fun `an EU B2B invoice expecting reverse charge conflicts with a standard-rate position`() {
        val buyer = Recipient().apply {
            customerNumber = CustomerNumber("K-2")
            name = "Client SARL"
            customerType = CustomerType.BUSINESS
            vatId = VatId("FR12345678901")
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Rue de Paris 1", "75001", "Paris", Country.FR, true))
        }
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = buyer
        }
        invoice.addItem(item("S")) // expected REVERSE_CHARGE, actual STANDARD -> conflict

        val findings = TaxRules.evaluate(invoice, today = FIXED_TODAY)

        val mismatch = findings.filter { it.ruleId == TaxRules.CATEGORY_MISMATCH }
        assertEquals(1, mismatch.size)
    }

    @Test
    fun `an active seller VAT registration suppresses the recipient-context derivation entirely`() {
        // Live constellation (dev invoice 601, PO report 2026-09-03): LU business recipient with a
        // verified VAT id — R5 would propose REVERSE_CHARGE — but the invoice runs under the
        // seller's own LU registration, so its items correctly carry the article's
        // LU country variant (S, 17%). Judging those items against the DE-seller reference table
        // raised a false TAX.CATEGORY_MISMATCH; with the registration active, no recipient-context
        // claim may be made at all (the active-registration path's own gate is
        // validateItemArticleTaxVariants).
        val buyer = Recipient().apply {
            customerNumber = CustomerNumber("K-3")
            name = "Nekar Supermarkt Luxembourg S.a r.l."
            customerType = CustomerType.BUSINESS
            vatId = VatId("LU19876543")
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Rue de Gare 1", "L-1611", "Luxembourg", Country.LU, true))
        }
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = buyer
            sellerVatRegistrationCountry = Country.LU
        }
        invoice.addItem(item("S")) // the LU country variant's own category — correct on the V4 path

        assertTrue(TaxRules.evaluate(invoice, today = FIXED_TODAY).none { it.ruleId == TaxRules.CATEGORY_MISMATCH })
        // Control: the identical invoice WITHOUT the registration is back on the R5 path and the
        // mismatch (expected AE, actual S) must still be reported — the guard must not blanket-
        // silence the rule.
        invoice.sellerVatRegistrationCountry = null
        assertEquals(1, TaxRules.evaluate(invoice, today = FIXED_TODAY).count { it.ruleId == TaxRules.CATEGORY_MISMATCH })
    }

    @Test
    fun `an unresolvable tax case surfaces as a WARNING finding, not as a blocking one`() {
        // GB is deliberately excluded from the supported EU B2B target list regardless of date
        // (TaxRuleResolver) -> Blocked(UNSUPPORTED_TAX_CASE).
        val buyer = Recipient().apply {
            customerNumber = CustomerNumber("K-3")
            name = "UK Ltd"
            customerType = CustomerType.BUSINESS
            vatId = VatId("GB123456789")
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Baker Street 1", "NW1", "London", Country.GB, true))
        }
        val invoice = Invoice().apply {
            senderProfile = sender()
            recipient = buyer
        }
        invoice.addItem(item("S"))

        val findings = TaxRules.evaluate(invoice, today = FIXED_TODAY)

        val blocked = findings.filter { it.ruleId.startsWith("TAX.") && it.ruleId != TaxRules.MIXED_CATEGORY && it.ruleId != TaxRules.CATEGORY_MISMATCH }
        assertEquals(1, blocked.size)
        assertEquals(ComplianceSeverity.WARNING, blocked.single().severity, "ein nicht aufloesbarer Steuerfall blockiert heute nirgends und soll das hier auch nicht neu tun")
    }

    @Test
    fun `no recipient at all yields no tax findings beyond a possible mix`() {
        val invoice = Invoice().apply { senderProfile = sender() }
        invoice.addItem(item("S"))

        assertTrue(TaxRules.evaluate(invoice, today = FIXED_TODAY).isEmpty())
    }

    @Test
    fun `TaxRuleSet only declares the INVOICE level and delegates to TaxRules`() {
        assertEquals(setOf(ComplianceLevel.INVOICE), TaxRuleSet.levels)
        assertTrue(TaxRuleSet.evaluate(ComplianceSubject.OfSeller(SenderProfile()), today = FIXED_TODAY).isEmpty())
    }
}
