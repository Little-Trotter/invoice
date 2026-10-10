package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * [ArdennesXRules]/[ArdennesXRuleSet]: the SIRET gate blocks exactly the one combination Art. 289
 * CGI requires it for (Factur-X engine + French customer + missing SIRET), and never anything
 * else.
 */
class ArdennesXRulesTest {

    private fun recipient(country: Country, siret: String? = null, nafCode: String? = null) = Recipient().apply {
        customerNumber = CustomerNumber("K-FX")
        name = "Client SARL"
        customerType = CustomerType.BUSINESS
        vatId = VatId("FR12345678901")
        this.siret = siret
        this.nafCode = nafCode
        addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Rue de Paris 1", "75001", "Paris", country, true))
    }

    private fun invoice(engine: DocumentEngine, recipient: Recipient?) = Invoice().apply {
        documentEngine = engine
        this.recipient = recipient
    }

    @Test
    fun `T8-04 - FACTURX engine, French customer, missing SIRET blocks`() {
        val findings = ArdennesXRules.evaluate(invoice(DocumentEngine.FACTURX, recipient(Country.FR)))

        assertEquals(1, findings.size)
        val finding = findings.single()
        assertEquals(ArdennesXRules.SIRET_REQUIRED, finding.ruleId)
        assertEquals(ComplianceSeverity.ERROR, finding.severity)
        assertTrue(finding.isBlocking)
        assertEquals(FindingTarget.Buyer, finding.target)
    }

    @Test
    fun `T8-05 - MUSTANG engine never triggers the rule, even for a French customer without SIRET`() {
        val findings = ArdennesXRules.evaluate(invoice(DocumentEngine.MUSTANG, recipient(Country.FR)))

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `T8-06 - FACTURX engine with a German customer never triggers the rule`() {
        val findings = ArdennesXRules.evaluate(invoice(DocumentEngine.FACTURX, recipient(Country.DE)))

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `T8-16 - FACTURX engine with a Belgian or Luxembourg customer without SIRET never triggers the rule`() {
        assertTrue(ArdennesXRules.evaluate(invoice(DocumentEngine.FACTURX, recipient(Country.BE))).isEmpty())
        assertTrue(ArdennesXRules.evaluate(invoice(DocumentEngine.FACTURX, recipient(Country.LU))).isEmpty())
    }

    @Test
    fun `T8-07 - a missing NAF code is never blocking, with or without SIRET`() {
        assertTrue(ArdennesXRules.evaluate(invoice(DocumentEngine.FACTURX, recipient(Country.FR, siret = "12345678901234"))).isEmpty())
        // SIRET present, NAF absent — still nothing to report; NAF has no gate at all (T8-07).
    }

    @Test
    fun `FACTURX engine, French customer, SIRET present does not block`() {
        val findings = ArdennesXRules.evaluate(invoice(DocumentEngine.FACTURX, recipient(Country.FR, siret = "12345678901234")))

        assertTrue(findings.isEmpty())
    }

    /**
     * The case between "no recipient" and "recipient in France": a recipient **without any
     * address**. Without a country the rule doesn't apply; it must neither produce a finding
     * (or every unfinished draft would block) nor stumble over the missing address.
     */
    @Test
    fun `a recipient without any address yields no Ardennes-X findings`() {
        val addressless = Recipient().apply {
            customerNumber = CustomerNumber("K-FX")
            name = "Client SARL"
            customerType = CustomerType.BUSINESS
        }

        assertTrue(ArdennesXRules.evaluate(invoice(DocumentEngine.FACTURX, addressless)).isEmpty())
    }

    /**
     * An empty string is not a SIRET. Without this case, a field accidentally stored as `""`
     * would be enough to bypass Art. 289 CGI — `null` and `""` make no difference here.
     */
    @Test
    fun `a blank SIRET blocks exactly like a missing one`() {
        val findings = ArdennesXRules.evaluate(invoice(DocumentEngine.FACTURX, recipient(Country.FR, siret = "   ")))

        assertEquals(1, findings.size)
        assertEquals(ArdennesXRules.SIRET_REQUIRED, findings.single().ruleId)
    }

    @Test
    fun `no recipient at all yields no Ardennes-X findings`() {
        val findings = ArdennesXRules.evaluate(invoice(DocumentEngine.FACTURX, null))

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `ArdennesXRuleSet only declares the INVOICE level and delegates to ArdennesXRules`() {
        assertEquals(setOf(ComplianceLevel.INVOICE), ArdennesXRuleSet.levels)
        assertTrue(ArdennesXRuleSet.evaluate(ComplianceSubject.OfSeller(de.littletrotter.domain.masterdata.SenderProfile()), today = FIXED_TODAY).isEmpty())
    }

    @Test
    fun `ArdennesXRuleSet is part of the default rule-set bundle`() {
        assertTrue(ComplianceRuleSets.DEFAULT.any { it === ArdennesXRuleSet })
    }
}
