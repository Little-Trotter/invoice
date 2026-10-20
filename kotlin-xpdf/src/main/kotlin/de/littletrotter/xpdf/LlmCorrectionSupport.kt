package de.littletrotter.xpdf

import de.littletrotter.shared.InvoiceDto
import org.slf4j.Logger
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Shared prompt/response handling for LLM-assisted ZUGFeRD XML review.
 * The semantic guard ([validateCorrection]) is the valuable part of this pipeline: it
 * rejects any "correction" that touches amounts, tax rates, the invoice number, dates,
 * or party legal/tax IDs. The improvement-specification prompt ([buildImprovementPrompt])
 * is used by [MlxClient] in production.
 */
internal object LlmCorrectionSupport {

    data class ParsedResponse(val markdownReport: String, val correctedXmlBytes: ByteArray?) {
        // Content-based equality for the byte payload — a data class would otherwise compare
        // the ByteArray by identity (ArrayInDataClass). List<Byte> was rejected: it
        // would box every byte of the XML payload.
        override fun equals(other: Any?): Boolean =
            other is ParsedResponse &&
                markdownReport == other.markdownReport &&
                correctedXmlBytes.contentEquals(other.correctedXmlBytes)

        override fun hashCode(): Int =
            31 * markdownReport.hashCode() + correctedXmlBytes.contentHashCode()
    }

    /**
     * Splits model response into report and corrected XML section.
     * Expects markers ===KORRIGIERTES_XML=== and ===ENDE===.
     */
    fun parseResponse(raw: String, originalXmlBytes: ByteArray, invoice: InvoiceDto, log: Logger): ParsedResponse {
        val xmlStart = raw.indexOf("===KORRIGIERTES_XML===")
        // Search end marker only after the start marker to avoid mis-slicing. String.indexOf(needle,
        // fromIndex) never returns an index below fromIndex, so once xmlStart >= 0, xmlEnd is either
        // -1 or strictly >= xmlStart + the start marker's own length (the two markers have different
        // literal text, so they can never start at the same position) — `xmlEnd <= xmlStart` can
        // never be true and was dead code, deleted here.
        val xmlEnd = if (xmlStart >= 0) raw.indexOf("===ENDE===", xmlStart) else -1

        // Report is everything before the XML block (empty string if marker is at position 0)
        val reportPart = if (xmlStart >= 0) raw.substring(0, xmlStart).trim() else raw.trim()

        if (xmlStart < 0 || xmlEnd < 0) {
            log.info("Model returned no corrected XML section – report only")
            return ParsedResponse(markdownReport = reportPart, correctedXmlBytes = null)
        }

        val xmlRaw = raw.substring(xmlStart + "===KORRIGIERTES_XML===".length, xmlEnd)
            .trim()
            .removePrefix("```xml").removePrefix("```").removeSuffix("```")
            .trim()

        val correctedBytes = xmlRaw.toByteArray(Charsets.UTF_8)

        val accepted = validateCorrection(originalXmlBytes, correctedBytes, log)
        if (!accepted) {
            log.warn("Model correction rejected (semantic guard) – using report only")
            return ParsedResponse(markdownReport = reportPart, correctedXmlBytes = null)
        }

        log.info("Model correction accepted for invoice ${invoice.invoiceNumber.value}")
        return ParsedResponse(markdownReport = reportPart, correctedXmlBytes = correctedBytes)
    }

    /** Rejects corrections that alter protected fields (ID, dates, amounts, tax rates, party IDs). */
    fun validateCorrection(original: ByteArray, corrected: ByteArray, log: Logger): Boolean {
        val origDoc = tryParseXml(original) ?: return false
        val corrDoc = tryParseXml(corrected) ?: return false

        val nsCtx = SimpleNamespaceContext(mapOf(
            "rsm" to "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100",
            "ram" to "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100",
            "udt" to "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100",
        ))
        val xp = XPathFactory.newInstance().newXPath().also { it.namespaceContext = nsCtx }

        // javax.xml.xpath guarantees a non-null String result when the requested return type is
        // XPathConstants.STRING (empty string when nothing matches, never null and never another
        // type), so the cast/null-safety wrapping this used to have was dead code — deleted here.
        fun text(doc: Document, expr: String) =
            (xp.evaluate(expr, doc, XPathConstants.STRING) as String).trim()

        val guardPaths = listOf(
            // Invoice number (ExchangedDocument is in rsm namespace)
            "//rsm:ExchangedDocument/ram:ID",
            // Issue date
            "//rsm:ExchangedDocument/ram:IssueDateTime/udt:DateTimeString",
            // Monetary totals
            "//ram:SpecifiedTradeSettlementHeaderMonetarySummation/ram:GrandTotalAmount",
            "//ram:SpecifiedTradeSettlementHeaderMonetarySummation/ram:TaxBasisTotalAmount",
            "//ram:SpecifiedTradeSettlementHeaderMonetarySummation/ram:TaxTotalAmount",
            "//ram:SpecifiedTradeSettlementHeaderMonetarySummation/ram:LineTotalAmount",
            "//ram:SpecifiedTradeSettlementHeaderMonetarySummation/ram:DuePayableAmount",
            // Tax rates
            "//ram:RateApplicablePercent",
            // Seller/Buyer legal IDs
            "//ram:SellerTradeParty/ram:SpecifiedLegalOrganization/ram:ID",
            "//ram:BuyerTradeParty/ram:SpecifiedLegalOrganization/ram:ID",
            "//ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID",
            "//ram:BuyerTradeParty/ram:SpecifiedTaxRegistration/ram:ID",
        )

        for (path in guardPaths) {
            val orig = text(origDoc, path)
            val corr = text(corrDoc, path)
            if (orig.isNotBlank() && orig != corr) {
                log.warn("Model correction changed protected field '$path': '$orig' -> '$corr'")
                return false
            }
        }
        return true
    }

    fun tryParseXml(bytes: ByteArray): Document? = try {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Disable XXE / external DTD to prevent entity injection
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isExpandEntityReferences = false
        }
        dbf.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (_: Exception) { null }

    /**
     * Analysis prompt (legacy, for test backwards compatibility).
     * Production uses [buildImprovementPrompt] (no XML patches).
     */
    fun buildPrompt(xmlText: String, invoice: InvoiceDto): String = """
Du bist ein Experte für elektronische Rechnungen nach EN16931 und ZUGFeRD 2.x.
Analysiere ausschließlich das folgende ZUGFeRD-XML (kein PDF, keine Datenbank, keine
Annahmen über nicht Sichtbares) und führe AUSSCHLIESSLICH die unten erlaubten Korrekturen durch.

ERLAUBTE KORREKTUREN (nur diese, nichts anderes):
1. Fehlende Sprachkennzeichnung (LanguageID) → füge <ram:LanguageID>de</ram:LanguageID> ein
2. Falsch geschriebene oder falsch platzierte XML-Elemente gemäß EN16931-Schema → korrigieren
3. Fehlender DocumentTypeCode für Handelsrechnung → Standardwert 380 einfügen
4. Fehlende CurrencyCode-Angabe → EUR als Default

VERBOTEN – diese Felder darfst du NIEMALS ändern:
- Beträge (LineTotalAmount, TaxBasisTotalAmount, TaxTotalAmount, GrandTotalAmount, DuePayableAmount, jegliche ChargeAmount/AllowanceAmount)
- Steuersätze (RateApplicablePercent)
- Parteidaten (Name, Adresse, LegalRegistrationID, TaxRegistrationID jeder Partei)
- Datumsangaben (IssueDateTime, ActualDeliverySupplyChainEvent, jegliche DateTime-Felder)
- Rechnungsnummer (ExchangedDocument/ID)
- Positionen (Quantity, UnitCode, Description der Rechnungspositionen)

Antworte AUSSCHLIESSLICH im folgenden Format (keine weiteren Texte davor oder danach):

## Zusammenfassung
<2-3 Sätze zur Gesamtbewertung>

## Gemachte Änderungen
<Tabelle mit genau den Änderungen die du im XML vorgenommen hast, oder "Keine Korrekturen erforderlich." wenn du nichts geändert hast>

| XPath / Business Term | Vorher | Nachher | Begründung |
|---|---|---|---|
| ... | ... | ... | ... |

## Fazit
<Gesamteinschätzung der Rechnungsqualität>

===KORRIGIERTES_XML===
<?xml version="1.0" encoding="UTF-8"?>
<hier das vollständige korrigierte ZUGFeRD-XML einfügen>
===ENDE===

Rechnungsnummer: ${invoice.invoiceNumber.value}

ZUGFeRD-XML:
$xmlText
""".trimIndent()

    /**
     * Improvement-specification prompt: inputs are ZUGFeRD XML, KoSIT validation result,
     * and PDF layout report. Output is structured Markdown recommendations (no XML patches).
     * Template is externalized to src/main/resources/prompts/zugferd-improvement-prompt.md.
     */
    fun buildImprovementPrompt(
        xmlText: String,
        invoice: InvoiceDto,
        kositReport: String,
        layoutReport: String = "",
    ): String {
        val template = promptTemplate
        return template
            .replace("{{xmlText}}", xmlText)
            .replace("{{kositReport}}", kositReport)
            .replace("{{layoutReport}}", layoutReport)
            .replace("{{invoiceNumber}}", invoice.invoiceNumber.value)
    }

    private val promptTemplate: String by lazy { resolvePromptTemplate(javaClass.classLoader) }

    /** Test seam around the memoized [promptTemplate] above — a Kotlin `object`'s `by lazy` only
     * ever evaluates once per JVM, so the missing-resource fallback can't be exercised through it. */
    internal fun resolvePromptTemplate(loader: ClassLoader): String {
        val stream = loader.getResourceAsStream("prompts/zugferd-improvement-prompt.md")
            ?: error("Prompt template /prompts/zugferd-improvement-prompt.md not found on classpath")
        return stream.bufferedReader().use { it.readText() }
    }
}
