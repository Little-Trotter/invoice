package de.littletrotter.quotationxml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuotationXsdValidatorTest {

    private val validator = QuotationXsdValidator()

    @Test
    fun `valid document yields no findings`() {
        assertEquals(emptyList<Any>(), validator.validate(QuotationFixtures.xml().toByteArray()))
    }

    @Test
    fun `document with attached document references is schema valid`() {
        val xml = QuotationFixtures.xml(QuotationFixtures.model(attachedDocuments = QuotationFixtures.references()))
        assertEquals(emptyList<Any>(), validator.validate(xml.toByteArray()))
    }

    @Test
    fun `missing mandatory element is reported with stage and location`() {
        val xml = QuotationFixtures.xml().replace(Regex("<cbc:IssueDate>[^<]*</cbc:IssueDate>"), "")
        val findings = validator.validate(xml.toByteArray())
        assertTrue(findings.isNotEmpty())
        assertEquals("error", findings.first().severity)
        assertEquals("SYNTAX", findings.first().stage)
        assertEquals("xsd", findings.first().validator)
        assertTrue(findings.first().location!!.startsWith("line "))
    }

    @Test
    fun `wrong element order is reported`() {
        val xml = QuotationFixtures.xml().replace("<cbc:UBLVersionID>2.1</cbc:UBLVersionID>", "") +
            ""
        val reordered = xml.replace("<cbc:ID>", "<cbc:UBLVersionID>2.1</cbc:UBLVersionID><cbc:ID>")
            .replace("<cbc:IssueDate>", "<cbc:UBLVersionID>2.1</cbc:UBLVersionID><cbc:IssueDate>")
        assertTrue(validator.validate(reordered.toByteArray()).isNotEmpty())
    }

    @Test
    fun `foreign root element is reported`() {
        assertTrue(validator.validate("<Invoice/>".toByteArray()).isNotEmpty())
    }

    @Test
    fun `not well formed input is reported as a finding not thrown`() {
        val findings = validator.validate("<Quotation".toByteArray())
        assertEquals(1, findings.size)
        assertEquals("xsd", findings.single().validator)
    }

    @Test
    fun `document type declarations are not resolved`() {
        val xml = "<!DOCTYPE q [<!ENTITY x SYSTEM \"file:///etc/hostname\">]><Quotation>&x;</Quotation>"
        assertTrue(validator.validate(xml.toByteArray()).isNotEmpty())
    }
}
