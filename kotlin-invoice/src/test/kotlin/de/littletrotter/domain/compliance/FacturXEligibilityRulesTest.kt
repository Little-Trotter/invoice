package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * [FacturXEligibilityRules]/[FacturXEligibilityRuleSet]: the finalization-gate side of
 * decision 11. The export panel's visibility check ([FacturXEligibility.isEligible] wired into
 * `InvoiceExportPanel`) is a UI convenience, not enforcement (see the rule's own KDoc) — this
 * rule is what actually blocks a `FACTURX` invoice whose parties never entered Factur-X scope.
 */
class FacturXEligibilityRulesTest {

    private fun deSeller() = SenderProfile().apply { name = "Absender GmbH"; vatId = VatId("DE123456789") }

    private fun luSeller() = SenderProfile().apply { name = "Absender Lux Sarl"; vatId = VatId("LU12345678") }

    private fun deRecipient() = Recipient().apply {
        customerNumber = CustomerNumber("K-1")
        name = "Kunde GmbH"
        customerType = CustomerType.BUSINESS
        vatId = VatId("DE987654321")
        addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Strasse 1", "12345", "Berlin", Country.DE, true))
    }

    private fun frRecipient() = Recipient().apply {
        customerNumber = CustomerNumber("K-FX")
        name = "Client SARL"
        customerType = CustomerType.BUSINESS
        vatId = VatId("FR12345678901")
        addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Rue de Paris 1", "75001", "Paris", Country.FR, true))
    }

    private fun invoice(engine: DocumentEngine, senderProfile: SenderProfile?, recipient: Recipient?) = Invoice().apply {
        documentEngine = engine
        this.senderProfile = senderProfile
        this.recipient = recipient
    }

    @Test
    fun `finalizing a non-eligible FACTURX invoice yields the FACTURX-NOT_ELIGIBLE ERROR finding`() {
        val findings = FacturXEligibilityRules.evaluate(invoice(DocumentEngine.FACTURX, deSeller(), deRecipient()))

        assertEquals(1, findings.size)
        val finding = findings.single()
        assertEquals(FacturXEligibilityRules.NOT_ELIGIBLE, finding.ruleId)
        assertEquals("FACTURX.NOT_ELIGIBLE", finding.ruleId)
        assertEquals(ComplianceSeverity.ERROR, finding.severity)
        assertEquals("compliance.facturx.notEligible", finding.messageKey)
        assertTrue(finding.isBlocking)
    }

    @Test
    fun `an eligible FACTURX invoice yields no findings`() {
        val findings = FacturXEligibilityRules.evaluate(invoice(DocumentEngine.FACTURX, deSeller(), frRecipient()))

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `every MUSTANG invoice yields no findings, eligible or not`() {
        assertTrue(FacturXEligibilityRules.evaluate(invoice(DocumentEngine.MUSTANG, deSeller(), deRecipient())).isEmpty())
        assertTrue(FacturXEligibilityRules.evaluate(invoice(DocumentEngine.MUSTANG, deSeller(), frRecipient())).isEmpty())
    }

    /**
     * Regression — `Invoice.selectedSeller` (an invoice-level override) must decide, not the
     * master-data-current `senderProfile`, mirroring `Invoice.effectiveSender()`'s own
     * `selectedSeller ?: senderProfile` resolution ([de.littletrotter.domain.invoice
     * .effectiveSender]). A rule that read `senderProfile` directly would get both directions of
     * this test wrong: it would block an invoice whose *effective* seller is in scope, and pass
     * one whose *effective* seller is not.
     */
    @Test
    fun `selectedSeller overrides a disagreeing senderProfile in both directions`() {
        val eligibleViaSelectedSeller = invoice(DocumentEngine.FACTURX, deSeller(), deRecipient()).apply {
            selectedSeller = luSeller()
        }
        assertTrue(
            FacturXEligibilityRules.evaluate(eligibleViaSelectedSeller).isEmpty(),
            "selectedSeller (LU) is in scope and must decide over the stale senderProfile (DE)",
        )

        val notEligibleViaSelectedSeller = invoice(DocumentEngine.FACTURX, luSeller(), deRecipient()).apply {
            selectedSeller = deSeller()
        }
        val findings = FacturXEligibilityRules.evaluate(notEligibleViaSelectedSeller)
        assertEquals(1, findings.size)
        assertEquals(
            FacturXEligibilityRules.NOT_ELIGIBLE, findings.single().ruleId,
            "selectedSeller (DE) is out of scope and must decide over the eligible senderProfile (LU)",
        )
    }

    @Test
    fun `FacturXEligibilityRuleSet only declares the INVOICE level and delegates to FacturXEligibilityRules`() {
        assertEquals(setOf(ComplianceLevel.INVOICE), FacturXEligibilityRuleSet.levels)
        assertTrue(FacturXEligibilityRuleSet.evaluate(ComplianceSubject.OfSeller(SenderProfile()), today = FIXED_TODAY).isEmpty())
    }

    @Test
    fun `FacturXEligibilityRuleSet is part of the default rule-set bundle`() {
        assertTrue(ComplianceRuleSets.DEFAULT.any { it === FacturXEligibilityRuleSet })
    }
}
