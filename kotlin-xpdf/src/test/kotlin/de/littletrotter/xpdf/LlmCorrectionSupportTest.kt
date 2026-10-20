package de.littletrotter.xpdf

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Covers the logic shared by the LLM pipeline ([MlxClient]). The semantic guard
 * ([LlmCorrectionSupport.validateCorrection]) is the part worth protecting with tests:
 * it is the only thing standing between a hallucinating model and a silently corrupted
 * ZUGFeRD invoice. The improvement-specification prompt ([buildImprovementPrompt]) is
 * what production uses today.
 */
class LlmCorrectionSupportTest {

    private val log = LoggerFactory.getLogger(LlmCorrectionSupportTest::class.java)
    private val invoice = InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-2026-00042"))

    private val originalXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rsm:CrossIndustryInvoice
            xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
            xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
            xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
          <rsm:ExchangedDocument>
            <ram:ID>INV-2026-00042</ram:ID>
            <ram:IssueDateTime><udt:DateTimeString>20260315</udt:DateTimeString></ram:IssueDateTime>
          </rsm:ExchangedDocument>
          <ram:SpecifiedTradeSettlementHeaderMonetarySummation>
            <ram:LineTotalAmount>100.00</ram:LineTotalAmount>
            <ram:TaxBasisTotalAmount>100.00</ram:TaxBasisTotalAmount>
            <ram:TaxTotalAmount>19.00</ram:TaxTotalAmount>
            <ram:GrandTotalAmount>119.00</ram:GrandTotalAmount>
            <ram:DuePayableAmount>119.00</ram:DuePayableAmount>
          </ram:SpecifiedTradeSettlementHeaderMonetarySummation>
          <ram:ApplicableTradeTax><ram:RateApplicablePercent>19</ram:RateApplicablePercent></ram:ApplicableTradeTax>
        </rsm:CrossIndustryInvoice>
    """.trimIndent()

    private fun withCorrectedGrandTotal(newValue: String): String =
        originalXml.replace("<ram:GrandTotalAmount>119.00</ram:GrandTotalAmount>", "<ram:GrandTotalAmount>$newValue</ram:GrandTotalAmount>")

    // ─── validateCorrection ──────────────────────────────────────────────────

    @Test
    fun `validateCorrection accepts a correction that leaves protected fields untouched`() {
        val corrected = originalXml.replace(
            "<rsm:ExchangedDocument>",
            "<rsm:ExchangedDocument><ram:LanguageID>de</ram:LanguageID>",
        )
        assertTrue(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), corrected.toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects a correction that changes the grand total`() {
        val corrected = withCorrectedGrandTotal("999.00")
        assertFalse(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), corrected.toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects a correction that changes the tax rate`() {
        val corrected = originalXml.replace("<ram:RateApplicablePercent>19</ram:RateApplicablePercent>", "<ram:RateApplicablePercent>7</ram:RateApplicablePercent>")
        assertFalse(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), corrected.toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects a correction that changes the invoice number`() {
        val corrected = originalXml.replace("<ram:ID>INV-2026-00042</ram:ID>", "<ram:ID>INV-2026-99999</ram:ID>")
        assertFalse(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), corrected.toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects malformed corrected xml`() {
        assertFalse(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), "not xml at all".toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects malformed original xml`() {
        assertFalse(LlmCorrectionSupport.validateCorrection("not xml at all".toByteArray(), originalXml.toByteArray(), log))
    }

    // ─── parseResponse ───────────────────────────────────────────────────────

    @Test
    fun `parseResponse returns report only when no xml section present`() {
        val result = LlmCorrectionSupport.parseResponse("## Zusammenfassung\nAlles gut.", originalXml.toByteArray(), invoice, log)
        assertEquals("## Zusammenfassung\nAlles gut.", result.markdownReport)
        assertNull(result.correctedXmlBytes)
    }

    @Test
    fun `parseResponse extracts corrected xml when guard accepts it`() {
        val correctedInner = originalXml.replace(
            "<rsm:ExchangedDocument>",
            "<rsm:ExchangedDocument><ram:LanguageID>de</ram:LanguageID>",
        )
        val raw = """
            ## Zusammenfassung
            Sprachkennzeichnung ergaenzt.
            ===KORRIGIERTES_XML===
            $correctedInner
            ===ENDE===
        """.trimIndent()

        val result = LlmCorrectionSupport.parseResponse(raw, originalXml.toByteArray(), invoice, log)

        assertTrue(result.markdownReport.contains("Sprachkennzeichnung"))
        assertNotNull(result.correctedXmlBytes)
        assertTrue(String(result.correctedXmlBytes!!, Charsets.UTF_8).contains("LanguageID"))
    }

    @Test
    fun `parseResponse falls back to report only when guard rejects the correction`() {
        val correctedInner = withCorrectedGrandTotal("999.00")
        val raw = """
            ## Zusammenfassung
            Betrag angepasst.
            ===KORRIGIERTES_XML===
            $correctedInner
            ===ENDE===
        """.trimIndent()

        val result = LlmCorrectionSupport.parseResponse(raw, originalXml.toByteArray(), invoice, log)

        assertTrue(result.markdownReport.contains("Betrag angepasst"))
        assertNull(result.correctedXmlBytes)
    }

    @Test
    fun `parseResponse strips markdown code fences around the xml block`() {
        val raw = """
            ## Zusammenfassung
            Keine Korrekturen erforderlich.
            ===KORRIGIERTES_XML===
            ```xml
            $originalXml
            ```
            ===ENDE===
        """.trimIndent()

        val result = LlmCorrectionSupport.parseResponse(raw, originalXml.toByteArray(), invoice, log)

        assertNotNull(result.correctedXmlBytes)
        assertFalse(String(result.correctedXmlBytes!!, Charsets.UTF_8).contains("```"))
    }

    // ─── buildPrompt / buildImprovementPrompt ────────────────────────────────

    @Test
    fun `buildPrompt embeds invoice number and xml and lists protected fields`() {
        val prompt = LlmCorrectionSupport.buildPrompt(originalXml, invoice)
        assertTrue(prompt.contains("INV-2026-00042"))
        assertTrue(prompt.contains(originalXml))
        assertTrue(prompt.contains("Rechnungsnummer (ExchangedDocument/ID)"))
    }

    @Test
    fun `buildImprovementPrompt embeds invoice number, xml and kosit report`() {
        val kositReport = """{"status":"INVALID","summary":"2 Fehler","details":[{"severity":"ERROR","message":"BT-22 fehlt"}]}"""
        val prompt = LlmCorrectionSupport.buildImprovementPrompt(originalXml, invoice, kositReport)
        assertTrue(prompt.contains("INV-2026-00042"))
        assertTrue(prompt.contains(originalXml))
        assertTrue(prompt.contains(kositReport))
        assertTrue(prompt.contains("Verbesserungsspezifikation"))
        assertTrue(prompt.contains("BT-22 fehlt"))
    }

    @Test
    fun `parseResponse with end marker before start marker treats content before start as report`() {
        val raw = "===ENDE===\n===KORRIGIERTES_XML===\n$originalXml"
        val result = LlmCorrectionSupport.parseResponse(raw, originalXml.toByteArray(), invoice, log)

        // When end marker comes before start marker, it treats everything before start marker as report
        assertEquals("===ENDE===", result.markdownReport)
        assertNull(result.correctedXmlBytes)
    }

    @Test
    fun `parseResponse with only start marker and no end returns report only`() {
        val raw = "## Report\n===KORRIGIERTES_XML===\n<?xml version=\"1.0\"?>\n<root/>"
        val result = LlmCorrectionSupport.parseResponse(raw, originalXml.toByteArray(), invoice, log)

        assertEquals("## Report", result.markdownReport)
        assertNull(result.correctedXmlBytes)
    }

    @Test
    fun `parseResponse strips leading and trailing triple backticks`() {
        val raw = """
            ## Zusammenfassung
            Keine Korrektionen.
            ===KORRIGIERTES_XML===
            ```
            $originalXml
            ```
            ===ENDE===
        """.trimIndent()

        val result = LlmCorrectionSupport.parseResponse(raw, originalXml.toByteArray(), invoice, log)

        assertNotNull(result.correctedXmlBytes)
        assertFalse(String(result.correctedXmlBytes!!, Charsets.UTF_8).contains("```"))
    }

    @Test
    fun `validateCorrection accepts when guarded path is blank in original and changed in corrected`() {
        // If original has no invoice ID, a change is allowed (no protection for blank fields)
        val originalNoBlanks = originalXml.replace("<ram:ID>INV-2026-00042</ram:ID>", "")
        val correctedWithId = originalNoBlanks.replace(
            "</rsm:ExchangedDocument>",
            "<ram:ID>NEW-ID</ram:ID></rsm:ExchangedDocument>"
        )
        assertTrue(LlmCorrectionSupport.validateCorrection(originalNoBlanks.toByteArray(), correctedWithId.toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects when original has non-blank value and corrected has blank value`() {
        // If original has an ID and corrected removes it, that's a change of a protected field
        val correctedNoId = originalXml.replace("<ram:ID>INV-2026-00042</ram:ID>", "")
        assertFalse(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), correctedNoId.toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects when tax basis total amount is changed`() {
        val corrected = originalXml.replace(
            "<ram:TaxBasisTotalAmount>100.00</ram:TaxBasisTotalAmount>",
            "<ram:TaxBasisTotalAmount>200.00</ram:TaxBasisTotalAmount>"
        )
        assertFalse(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), corrected.toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects when tax total amount is changed`() {
        val corrected = originalXml.replace(
            "<ram:TaxTotalAmount>19.00</ram:TaxTotalAmount>",
            "<ram:TaxTotalAmount>50.00</ram:TaxTotalAmount>"
        )
        assertFalse(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), corrected.toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects when line total amount is changed`() {
        val corrected = originalXml.replace(
            "<ram:LineTotalAmount>100.00</ram:LineTotalAmount>",
            "<ram:LineTotalAmount>50.00</ram:LineTotalAmount>"
        )
        assertFalse(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), corrected.toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects when due payable amount is changed`() {
        val corrected = originalXml.replace(
            "<ram:DuePayableAmount>119.00</ram:DuePayableAmount>",
            "<ram:DuePayableAmount>200.00</ram:DuePayableAmount>"
        )
        assertFalse(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), corrected.toByteArray(), log))
    }

    @Test
    fun `validateCorrection rejects when issue date is changed`() {
        val corrected = originalXml.replace(
            "<udt:DateTimeString>20260315</udt:DateTimeString>",
            "<udt:DateTimeString>20250101</udt:DateTimeString>"
        )
        assertFalse(LlmCorrectionSupport.validateCorrection(originalXml.toByteArray(), corrected.toByteArray(), log))
    }

    @Test
    fun `tryParseXml parses valid xml`() {
        val doc = LlmCorrectionSupport.tryParseXml(originalXml.toByteArray())
        assertNotNull(doc)
    }

    @Test
    fun `tryParseXml returns null for invalid xml`() {
        val doc = LlmCorrectionSupport.tryParseXml("not xml".toByteArray())
        assertNull(doc)
    }

    @Test
    fun `tryParseXml returns null for empty bytes`() {
        val doc = LlmCorrectionSupport.tryParseXml(byteArrayOf())
        assertNull(doc)
    }

    @Test
    fun `buildImprovementPrompt with blank layout report includes empty placeholder`() {
        val kositReport = """{"status":"VALID"}"""
        val prompt = LlmCorrectionSupport.buildImprovementPrompt(originalXml, invoice, kositReport, layoutReport = "")

        assertTrue(prompt.contains("INV-2026-00042"))
        // layoutReport placeholder should exist but be empty
        assertTrue(prompt.contains(kositReport))
    }

    @Test
    fun `buildImprovementPrompt includes all four placeholders`() {
        val kositReport = """{"status":"VALID"}"""
        val layoutReport = "page 1: ..."
        val prompt = LlmCorrectionSupport.buildImprovementPrompt(originalXml, invoice, kositReport, layoutReport)

        assertTrue(prompt.contains("INV-2026-00042"))
        assertTrue(prompt.contains(originalXml))
        assertTrue(prompt.contains(kositReport))
        assertTrue(prompt.contains("page 1: ..."))
    }

    @Test
    fun `parseResponse with whitespace around markers still extracts correctly`() {
        val raw = """
            ## Report
             ===KORRIGIERTES_XML===
            $originalXml
             ===ENDE===
        """.trimIndent()

        val result = LlmCorrectionSupport.parseResponse(raw, originalXml.toByteArray(), invoice, log)

        assertNotNull(result.correctedXmlBytes)
        assertFalse(String(result.correctedXmlBytes!!, Charsets.UTF_8).contains("==="))
    }

    @Test
    fun `validateCorrection rejects when seller legal ID is changed`() {
        val withSellerId = originalXml.replace(
            "</rsm:ExchangedDocument>",
            "</rsm:ExchangedDocument><ram:SellerTradeParty><ram:SpecifiedLegalOrganization><ram:ID>DE123456789</ram:ID></ram:SpecifiedLegalOrganization></ram:SellerTradeParty>"
        )
        val corrected = withSellerId.replace(
            "<ram:ID>DE123456789</ram:ID>",
            "<ram:ID>DE987654321</ram:ID>"
        )
        assertFalse(LlmCorrectionSupport.validateCorrection(withSellerId.toByteArray(), corrected.toByteArray(), log))
    }

    // ─── ParsedResponse content-based equality ─────────────────────────────────

    @Test
    fun `boundary - ParsedResponse considers two instances with the same content equal`() {
        val a = LlmCorrectionSupport.ParsedResponse("report", byteArrayOf(1, 2, 3))
        val b = LlmCorrectionSupport.ParsedResponse("report", byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `boundary - ParsedResponse with null xml bytes on both sides is equal`() {
        val a = LlmCorrectionSupport.ParsedResponse("report", null)
        val b = LlmCorrectionSupport.ParsedResponse("report", null)
        assertEquals(a, b)
    }

    @Test
    fun `boundary - ParsedResponse differs when xml bytes content differs`() {
        val a = LlmCorrectionSupport.ParsedResponse("report", byteArrayOf(1, 2, 3))
        val b = LlmCorrectionSupport.ParsedResponse("report", byteArrayOf(9, 9, 9))
        assertFalse(a == b)
        assertFalse(a.equals("not a ParsedResponse"))
    }

    // ─── resolvePromptTemplate — test seam around the memoized `promptTemplate` ───────────────

    @Test
    fun `boundary - resolvePromptTemplate throws when the resource is absent from the classloader`() {
        val loader = object : ClassLoader() {
            override fun getResourceAsStream(name: String?): java.io.InputStream? = null
        }
        val ex = assertThrows(IllegalStateException::class.java) {
            LlmCorrectionSupport.resolvePromptTemplate(loader)
        }
        assertTrue(ex.message!!.contains("zugferd-improvement-prompt.md"))
    }

    @Test
    fun `boundary - resolvePromptTemplate propagates a failure while reading the resource stream`() {
        val loader = object : ClassLoader() {
            override fun getResourceAsStream(name: String?): java.io.InputStream =
                object : java.io.InputStream() {
                    override fun read(): Int = throw java.io.IOException("boom")
                }
        }
        assertThrows(java.io.IOException::class.java) {
            LlmCorrectionSupport.resolvePromptTemplate(loader)
        }
    }

    @Test
    fun `boundary - resolvePromptTemplate reads the template when the resource is present`() {
        val loader = object : ClassLoader() {
            override fun getResourceAsStream(name: String?): java.io.InputStream =
                "{{xmlText}} {{invoiceNumber}}".byteInputStream()
        }
        assertEquals("{{xmlText}} {{invoiceNumber}}", LlmCorrectionSupport.resolvePromptTemplate(loader))
    }

    @Test
    fun `validateCorrection rejects when buyer legal ID is changed`() {
        val withBuyerId = originalXml.replace(
            "</rsm:ExchangedDocument>",
            "</rsm:ExchangedDocument><ram:BuyerTradeParty><ram:SpecifiedLegalOrganization><ram:ID>DE111111111</ram:ID></ram:SpecifiedLegalOrganization></ram:BuyerTradeParty>"
        )
        val corrected = withBuyerId.replace(
            "<ram:ID>DE111111111</ram:ID>",
            "<ram:ID>DE222222222</ram:ID>"
        )
        assertFalse(LlmCorrectionSupport.validateCorrection(withBuyerId.toByteArray(), corrected.toByteArray(), log))
    }
}
