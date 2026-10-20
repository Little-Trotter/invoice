package de.littletrotter.quotationxml

import de.littletrotter.shared.ValidationMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class QuotationSchematronTest {

    private val validator = QuotationSchematronValidator()

    private fun check(xml: String, rates: Set<BigDecimal> = emptySet()): List<ValidationMessage> =
        validator.validate(xml.toByteArray(), rates)

    private fun failedRules(xml: String, rates: Set<BigDecimal> = emptySet()): Set<String?> =
        check(xml, rates).map { it.ruleId }.toSet()

    private val valid = QuotationFixtures.xml()

    /** Replaces only inside the quotation lines, the tax subtotals above them carry the same rates. */
    private fun inLines(from: String, to: String): String {
        val split = valid.indexOf("<cac:QuotationLine>")
        return valid.substring(0, split) + valid.substring(split).replaceFirst(from, to)
    }

    @Test
    fun `valid offer passes all rules`() {
        assertEquals(emptyList<Any>(), check(valid))
    }

    @Test
    fun `offer with attached document references passes all rules`() {
        val xml = QuotationFixtures.xml(QuotationFixtures.model(attachedDocuments = QuotationFixtures.references()))
        assertEquals(emptyList<Any>(), check(xml))
    }

    // BR-Q-01 totals consistent

    @Test
    fun `BR-Q-01 positive - rounding within half a cent is accepted`() {
        val xml = valid.replace(">300.00<", ">300.004<")
        assertTrue("BR-Q-01" !in failedRules(xml))
    }

    @Test
    fun `BR-Q-01 negative - net total differs from line sum`() {
        val xml = valid.replace("TaxExclusiveAmount currencyID=\"EUR\">300.00", "TaxExclusiveAmount currencyID=\"EUR\">299.00")
        assertEquals(setOf("BR-Q-01"), failedRules(xml).filterNotNull().toSet().intersect(setOf("BR-Q-01")))
        assertTrue(check(xml).any { it.message.contains("net total") })
    }

    @Test
    fun `BR-Q-01 negative - tax subtotals differ from tax total`() {
        val xml = valid.replace("<cbc:TaxAmount currencyID=\"EUR\">38.00", "<cbc:TaxAmount currencyID=\"EUR\">39.00")
        assertTrue(check(xml).any { it.ruleId == "BR-Q-01" && it.message.contains("tax subtotals") })
    }

    @Test
    fun `BR-Q-01 negative - gross differs from net plus tax`() {
        val xml = valid.replace("TaxInclusiveAmount currencyID=\"EUR\">345.00", "TaxInclusiveAmount currencyID=\"EUR\">340.00")
        assertTrue(check(xml).any { it.ruleId == "BR-Q-01" && it.message.contains("gross total") })
    }

    @Test
    fun `BR-Q-01 negative - payable differs from gross`() {
        val xml = valid.replace("PayableAmount currencyID=\"EUR\">345.00", "PayableAmount currencyID=\"EUR\">344.00")
        assertTrue(check(xml).any { it.ruleId == "BR-Q-01" && it.message.contains("payable amount") })
    }

    @Test
    fun `BR-Q-01 negative - line amount differs from quantity times price`() {
        val xml = valid.replaceFirst("<cbc:Quantity unitCode=\"HUR\">2<", "<cbc:Quantity unitCode=\"HUR\">3<")
        assertTrue(check(xml).any { it.ruleId == "BR-Q-01" && it.message.contains("quantity times unit price") })
    }

    // BR-Q-02 validity not before issue date

    @Test
    fun `BR-Q-02 positive - valid on the issue date itself`() {
        assertEquals(emptyList<Any>(), check(QuotationFixtures.xml(QuotationFixtures.model(validUntil = java.time.LocalDate.of(2026, 10, 15)))))
    }

    @Test
    fun `BR-Q-02 negative - validity ends before issue date`() {
        val xml = QuotationFixtures.xml(QuotationFixtures.model(validUntil = java.time.LocalDate.of(2026, 10, 14)))
        assertEquals(setOf<String?>("BR-Q-02"), failedRules(xml))
    }

    @Test
    fun `BR-Q-02 negative - validity period missing`() {
        val xml = valid.replace(Regex("<cac:ValidityPeriod>.*?</cac:ValidityPeriod>"), "")
        assertEquals(setOf<String?>("BR-Q-02"), failedRules(xml))
    }

    // BR-Q-03 parties and VAT id

    @Test
    fun `BR-Q-03 positive - seller VAT id with country prefix`() {
        assertTrue("BR-Q-03" !in failedRules(valid.replace("DE123456789", "ATU12345678")))
    }

    @Test
    fun `BR-Q-03 negative - seller VAT id missing`() {
        val xml = valid.replace(Regex("<cac:PartyTaxScheme>.*?</cac:PartyTaxScheme>"), "")
        assertEquals(setOf<String?>("BR-Q-03"), failedRules(xml))
    }

    @Test
    fun `BR-Q-03 negative - seller VAT id malformed`() {
        assertEquals(setOf<String?>("BR-Q-03"), failedRules(valid.replace("DE123456789", "12345")))
    }

    @Test
    fun `BR-Q-03 negative - seller name blank`() {
        val xml = valid.replaceFirst("<cbc:Name>Musterwerk GmbH</cbc:Name>", "<cbc:Name> </cbc:Name>")
        assertTrue(check(xml).any { it.ruleId == "BR-Q-03" && it.message.contains("seller must have a name") })
    }

    @Test
    fun `BR-Q-03 negative - buyer name blank`() {
        val xml = valid.replace("<cbc:Name>Testkunde AG</cbc:Name>", "<cbc:Name></cbc:Name>")
        assertTrue(check(xml).any { it.ruleId == "BR-Q-03" && it.message.contains("buyer must have a name") })
    }

    @Test
    fun `BR-Q-03 negative - buyer party missing`() {
        val xml = valid.replace(Regex("<cac:BuyerCustomerParty>.*?</cac:BuyerCustomerParty>"), "")
        assertTrue(check(xml).any { it.ruleId == "BR-Q-03" && it.message.contains("buyer party must be present") })
    }

    // BR-Q-04 line tax rate

    @Test
    fun `BR-Q-04 positive - rates known to the caller`() {
        assertEquals(emptyList<Any>(), check(valid, setOf(BigDecimal("19"), BigDecimal("7"), BigDecimal.ZERO)))
    }

    @Test
    fun `BR-Q-04 negative - rate not among the configured rates`() {
        val findings = check(valid, setOf(BigDecimal("19")))
        assertEquals(1, findings.size)
        assertEquals("BR-Q-04", findings.single().ruleId)
        assertTrue(findings.single().message.contains("configured tax rates"))
    }

    @Test
    fun `BR-Q-04 negative - unknown category code`() {
        val xml = inLines("<cbc:ID>S</cbc:ID><cbc:Percent>19", "<cbc:ID>X</cbc:ID><cbc:Percent>19")
        assertTrue(check(xml).any { it.ruleId == "BR-Q-04" && it.message.contains("category code") })
    }

    @Test
    fun `BR-Q-04 negative - rate out of range`() {
        val xml = inLines("<cbc:Percent>19</cbc:Percent>", "<cbc:Percent>190</cbc:Percent>")
        assertTrue(check(xml).any { it.ruleId == "BR-Q-04" && it.message.contains("between 0 and 100") })
    }

    @Test
    fun `BR-Q-04 negative - standard category with zero rate`() {
        val xml = inLines("<cbc:Percent>19</cbc:Percent>", "<cbc:Percent>0</cbc:Percent>")
        assertTrue(check(xml).any { it.ruleId == "BR-Q-04" && it.message.contains("standard category") })
    }

    // BR-Q-05 non-binding clause

    @Test
    fun `BR-Q-05 positive - clause present`() {
        assertTrue("BR-Q-05" !in failedRules(valid))
    }

    @Test
    fun `BR-Q-05 negative - clause blank`() {
        assertEquals(setOf<String?>("BR-Q-05"), failedRules(QuotationFixtures.xml(QuotationFixtures.model(disclaimer = "  "))))
    }

    @Test
    fun `BR-Q-05 negative - transaction conditions missing`() {
        val xml = valid.replace(Regex("<cac:TransactionConditions>.*?</cac:TransactionConditions>"), "")
        assertEquals(setOf<String?>("BR-Q-05"), failedRules(xml))
    }

    @Test
    fun `finding carries rule id stage validator and node path`() {
        val finding = check(valid.replace(Regex("<cac:TransactionConditions>.*?</cac:TransactionConditions>"), "")).single()
        assertEquals("error", finding.severity)
        assertEquals("BUSINESS", finding.stage)
        assertEquals("schematron", finding.validator)
        assertTrue(finding.location!!.contains("Quotation"))
    }
}
