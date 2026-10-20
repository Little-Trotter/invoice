package de.littletrotter.domain.compliance

import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.domain.masterdata.PartyIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TODAY

/**
 * [SchemeIdComplianceRules]: a scheme-ID membership violation is a **blocking**
 * finding (`ComplianceSeverity.ERROR`), an absent identifier/address is not this rule's concern
 * (no finding, not even a warning — [SellerDataRules]/[BuyerDataRules] own completeness), and a
 * valid scheme produces no finding at all.
 */
class SchemeIdComplianceRulesTest {

    private fun sellerWith(identifierScheme: String?, addressScheme: String?) = aSenderProfile {
        sellerIdentifier = identifierScheme?.let { PartyIdentifier.of("123456", it) }
        electronicAddress = addressScheme?.let { ElectronicAddress.of("seller@example.test", it) }
    }

    private fun buyerWith(identifierScheme: String?, addressScheme: String?) = aRecipient {
        buyerIdentifier = identifierScheme?.let { PartyIdentifier.of("654321", it) }
        electronicAddress = addressScheme?.let { ElectronicAddress.of("buyer@example.test", it) }
    }

    // ── Seller ────────────────────────────────────────────────────────────────

    @Test
    fun `a valid ICD and EAS scheme produce no finding`() {
        val findings = SchemeIdComplianceRules.sellerFindings(sellerWith("0088", "EM"))
        assertTrue(findings.isEmpty(), "valid schemes must not produce a finding: $findings")
    }

    @Test
    fun `an unknown ICD scheme on the seller identifier is a blocking finding naming the value`() {
        val findings = SchemeIdComplianceRules.sellerFindings(sellerWith("9999", null))

        val finding = findings.single()
        assertEquals(SchemeIdComplianceRules.SELLER_IDENTIFIER_SCHEME, finding.ruleId)
        assertEquals(ComplianceSeverity.ERROR, finding.severity)
        assertTrue(finding.isBlocking)
        assertEquals(listOf(B2bTerm.BT_29), finding.businessTerms)
        assertEquals(FindingTarget.Seller, finding.target)
        assertEquals(listOf("9999"), finding.args)
    }

    @Test
    fun `an unknown EAS scheme on the seller electronic address is a blocking finding naming the value`() {
        val findings = SchemeIdComplianceRules.sellerFindings(sellerWith(null, "0299"))

        val finding = findings.single()
        assertEquals(SchemeIdComplianceRules.SELLER_ELECTRONIC_ADDRESS_SCHEME, finding.ruleId)
        assertEquals(listOf(B2bTerm.BT_34), finding.businessTerms)
        assertEquals(listOf("0299"), finding.args)
    }

    @Test
    fun `no seller identifier or electronic address at all produces no finding`() {
        assertTrue(SchemeIdComplianceRules.sellerFindings(sellerWith(null, null)).isEmpty())
        assertTrue(SchemeIdComplianceRules.sellerFindings(null).isEmpty())
    }

    // ── Buyer ─────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown ICD scheme on the buyer identifier is a blocking finding naming the value`() {
        val findings = SchemeIdComplianceRules.buyerFindings(buyerWith("9999", null))

        val finding = findings.single()
        assertEquals(SchemeIdComplianceRules.BUYER_IDENTIFIER_SCHEME, finding.ruleId)
        assertEquals(listOf(B2bTerm.BT_46), finding.businessTerms)
        assertEquals(FindingTarget.Buyer, finding.target)
        assertEquals(listOf("9999"), finding.args)
    }

    @Test
    fun `an unknown EAS scheme on the buyer electronic address is a blocking finding naming the value`() {
        val findings = SchemeIdComplianceRules.buyerFindings(buyerWith(null, "0299"))

        val finding = findings.single()
        assertEquals(SchemeIdComplianceRules.BUYER_ELECTRONIC_ADDRESS_SCHEME, finding.ruleId)
        assertEquals(listOf(B2bTerm.BT_49), finding.businessTerms)
        assertEquals(listOf("0299"), finding.args)
    }

    @Test
    fun `no buyer identifier or electronic address at all produces no finding`() {
        assertTrue(SchemeIdComplianceRules.buyerFindings(buyerWith(null, null)).isEmpty())
        assertTrue(SchemeIdComplianceRules.buyerFindings(null).isEmpty())
    }

    // ── ComplianceRuleSet dispatch (evaluate/levels/id) ──────────────────────

    @Test
    fun `id and levels are stable`() {
        assertEquals("SCHEME_ID", SchemeIdComplianceRules.id)
        assertEquals(setOf(ComplianceLevel.SELLER, ComplianceLevel.BUYER, ComplianceLevel.INVOICE), SchemeIdComplianceRules.levels)
    }

    @Test
    fun `evaluate dispatches OfSeller to sellerFindings`() {
        val findings = SchemeIdComplianceRules.evaluate(ComplianceSubject.OfSeller(sellerWith("9999", null)), today = FIXED_TODAY)
        assertEquals(listOf(SchemeIdComplianceRules.SELLER_IDENTIFIER_SCHEME), findings.map { it.ruleId })
    }

    @Test
    fun `evaluate dispatches OfBuyer to buyerFindings`() {
        val findings = SchemeIdComplianceRules.evaluate(ComplianceSubject.OfBuyer(buyerWith("9999", null)), today = FIXED_TODAY)
        assertEquals(listOf(SchemeIdComplianceRules.BUYER_IDENTIFIER_SCHEME), findings.map { it.ruleId })
    }

    @Test
    fun `evaluate ignores a subject it is not responsible for`() {
        val article = anArticle { name = "Beratung" }
        assertTrue(SchemeIdComplianceRules.evaluate(ComplianceSubject.OfArticle(article), today = FIXED_TODAY).isEmpty())
    }

    @Test
    fun `registered in ComplianceRuleSets DEFAULT`() {
        assertTrue(ComplianceRuleSets.DEFAULT.contains(SchemeIdComplianceRules))
    }
}
