package de.littletrotter.quotationxml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class UblQuotationWriterTest {

    private val xsd = QuotationXsdValidator()

    @Test
    fun `written quotation is valid against the embedded UBL XSD`() {
        val findings = xsd.validate(UblQuotationWriter().write(QuotationFixtures.model()))
        assertEquals(emptyList<Any>(), findings)
    }

    @Test
    fun `quotation without optional parts is still schema valid`() {
        val model = QuotationFixtures.model(buyerEmail = null).copy(notes = emptyList())
        assertEquals(emptyList<Any>(), xsd.validate(UblQuotationWriter().write(model)))
    }

    @Test
    fun `root element uses the UBL Quotation namespace and carries the stated values`() {
        val xml = QuotationFixtures.xml()
        assertTrue(xml.contains("<Quotation xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Quotation-2\""))
        assertTrue(xml.contains("<cbc:ID>AN-2026-0001</cbc:ID>"))
        assertTrue(xml.contains("<cbc:EndDate>2026-11-15</cbc:EndDate>"))
        assertTrue(xml.contains("<cbc:PayableAmount currencyID=\"EUR\">345.00</cbc:PayableAmount>"))
        assertTrue(xml.contains("<cbc:Quantity unitCode=\"HUR\">2</cbc:Quantity>"))
        assertTrue(xml.contains("<cbc:CompanyID>DE123456789</cbc:CompanyID>"))
    }

    @Test
    fun `special characters are escaped`() {
        val model = QuotationFixtures.model(disclaimer = "A & B <freibleibend>")
        val xml = QuotationFixtures.xml(model)
        assertTrue(xml.contains("A &amp; B &lt;freibleibend&gt;"))
        assertEquals(emptyList<Any>(), xsd.validate(xml.toByteArray()))
    }

    @Test
    fun `written document passes every business rule`() {
        val findings = QuotationSchematronValidator().validate(UblQuotationWriter().write(QuotationFixtures.model()))
        assertEquals(emptyList<Any>(), findings)
    }

    @Test
    fun `attached documents are written as hash references in order and never embedded`() {
        val model = QuotationFixtures.model(attachedDocuments = QuotationFixtures.references())
        val xml = QuotationFixtures.xml(model)
        assertEquals(2, Regex("<cac:AdditionalDocumentReference>").findAll(xml).count())
        assertTrue(xml.indexOf("<cbc:ID>ATT-1</cbc:ID>") < xml.indexOf("<cbc:ID>ATT-2</cbc:ID>"))
        assertTrue(xml.indexOf("</cac:ValidityPeriod>") < xml.indexOf("<cac:AdditionalDocumentReference>"))
        assertTrue(xml.indexOf("</cac:AdditionalDocumentReference>") < xml.indexOf("<cac:SellerSupplierParty>"))
        assertTrue(xml.contains("<cbc:DocumentHash>${QuotationFixtures.HASH_A}</cbc:DocumentHash>"))
        assertTrue(xml.contains("<cbc:DocumentHash>${QuotationFixtures.HASH_B}</cbc:DocumentHash>"))
        assertEquals(2, Regex("<cbc:HashAlgorithmMethod>SHA-256</cbc:HashAlgorithmMethod>").findAll(xml).count())
        assertTrue(xml.contains("<cbc:MimeCode>application/pdf</cbc:MimeCode>"))
        assertTrue(xml.contains("<cbc:FileName>foto.png</cbc:FileName>"))
        assertTrue(xml.contains("<cbc:DocumentDescription>Lageplan der Baustelle</cbc:DocumentDescription>"))
        assertEquals(1, Regex("<cbc:DocumentDescription>").findAll(xml).count())
        assertTrue(!xml.contains("EmbeddedDocumentBinaryObject"))
        assertEquals(emptyList<Any>(), xsd.validate(xml.toByteArray()))
    }

    @Test
    fun `document reference rejects blank id file name mime code and malformed hash`() {
        val h = QuotationFixtures.HASH_A
        assertThrows(IllegalArgumentException::class.java) { QuotationDocumentReference(" ", "a.pdf", h, "application/pdf") }
        assertThrows(IllegalArgumentException::class.java) { QuotationDocumentReference("1", " ", h, "application/pdf") }
        assertThrows(IllegalArgumentException::class.java) { QuotationDocumentReference("1", "a.pdf", h, " ") }
        assertThrows(IllegalArgumentException::class.java) { QuotationDocumentReference("1", "a.pdf", "ABC", "application/pdf") }
    }

    @Test
    fun `model rejects an empty line list`() {
        assertThrows(IllegalArgumentException::class.java) { QuotationFixtures.model().copy(lines = emptyList()) }
    }

    @Test
    fun `model rejects a blank number`() {
        assertThrows(IllegalArgumentException::class.java) { QuotationFixtures.model().copy(number = " ") }
    }

    @Test
    fun `line rejects non positive position and blank unit code`() {
        val line = QuotationFixtures.model().lines.first()
        assertThrows(IllegalArgumentException::class.java) { line.copy(position = 0) }
        assertThrows(IllegalArgumentException::class.java) { line.copy(unitCode = "") }
        assertEquals(BigDecimal("2"), line.quantity)
    }
}
