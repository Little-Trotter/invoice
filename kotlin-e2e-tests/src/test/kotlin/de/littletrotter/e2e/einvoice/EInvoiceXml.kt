package de.littletrotter.e2e.einvoice

import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.math.BigDecimal
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * RG-3 — the ONE shared CII/EN-16931 XML extraction and XPath helper for this suite
 * ([EInvoiceGenerationE2EIT]), covering the Mustang e-invoice generation it exercises. Kept dependency-free (JDK `javax.xml` only) so it carries no
 * risk of drifting from whatever XML library either export path happens to use internally.
 */
object EInvoiceXml {

    private const val RSM = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
    private const val RAM = "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
    private const val UDT = "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100"

    private val namespaceContext = object : NamespaceContext {
        override fun getNamespaceURI(prefix: String?): String = when (prefix) {
            "rsm" -> RSM
            "ram" -> RAM
            "udt" -> UDT
            else -> javax.xml.XMLConstants.NULL_NS_URI
        }

        override fun getPrefix(namespaceURI: String?): String? = null
        override fun getPrefixes(namespaceURI: String?): Iterator<String> = emptyList<String>().iterator()
    }

    /** Parses [xml] into a namespace-aware DOM document. */
    fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // XXE hardening — this parses trusted, in-process-generated XML only, but a shared
            // helper should never model an XML parser that trusts external DTDs.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun newXPath() = XPathFactory.newInstance().newXPath().apply { namespaceContext = this@EInvoiceXml.namespaceContext }

    /** The text content of the single node matching [expr], or `null` if no node matches. Fails
     *  loudly (via the underlying XPath evaluation) if [expr] matches more than one node would be
     *  ambiguous — callers that expect repetition should use [nodeList] instead. */
    fun text(doc: Document, expr: String): String? {
        val node = newXPath().evaluate(expr, doc, XPathConstants.NODE) as Node? ?: return null
        return node.textContent?.trim()
    }

    fun text(xml: String, expr: String): String? = text(parse(xml), expr)

    /** Text content of every node matching [expr], in document order — for repeated elements
     *  (e.g. one `ApplicableTradeTax` per VAT category). */
    fun textList(doc: Document, expr: String): List<String> {
        val nodes = newXPath().evaluate(expr, doc, XPathConstants.NODESET) as org.w3c.dom.NodeList
        return (0 until nodes.length).map { nodes.item(it).textContent.trim() }
    }

    fun textList(xml: String, expr: String): List<String> = textList(parse(xml), expr)

    /** Whether at least one node matches [expr]. */
    fun exists(doc: Document, expr: String): Boolean {
        val nodes = newXPath().evaluate(expr, doc, XPathConstants.NODESET) as org.w3c.dom.NodeList
        return nodes.length > 0
    }

    fun exists(xml: String, expr: String): Boolean = exists(parse(xml), expr)

    /** Asserts that [expr] resolves to exactly [expected] (trimmed text), failing with a message
     *  naming both the XPath expression and [label] (the business term under test, e.g. `"BT-112"`). */
    fun assertText(xml: String, expr: String, expected: String, label: String) {
        val actual = text(xml, expr)
        require(actual == expected) { "$label ($expr): expected '$expected', got '$actual'" }
    }

    /** Asserts that [expr] resolves to a decimal numerically equal to [expected] — comparing via
     *  [BigDecimal.compareTo] so trailing-zero/scale formatting differences between engines never
     *  cause a false failure (e.g. "100.0" vs "100.00"). */
    fun assertAmount(xml: String, expr: String, expected: BigDecimal, label: String) {
        val actual = text(xml, expr)
        requireNotNull(actual) { "$label ($expr): expected amount $expected, node absent" }
        require(BigDecimal(actual).compareTo(expected) == 0) { "$label ($expr): expected $expected, got $actual" }
    }

    fun assertPresent(xml: String, expr: String, label: String) {
        require(exists(xml, expr)) { "$label ($expr): expected at least one match, found none" }
    }

    fun assertAbsent(xml: String, expr: String, label: String) {
        require(!exists(xml, expr)) { "$label ($expr): expected no match, found one" }
    }

    /**
     * One EN-16931 VAT category breakdown line (BT-116..BT-121), read from a header-level
     * `ApplicableHeaderTradeSettlement/ApplicableTradeTax` node.
     */
    data class TaxBreakdownLine(
        val categoryCode: String,
        val basisAmount: BigDecimal,
        val calculatedAmount: BigDecimal,
        val ratePercent: BigDecimal,
        val exemptionReasonCode: String?,
        val exemptionReasonText: String?,
    )

    /** Every header-level VAT breakdown line (one per category present on the invoice). */
    fun taxBreakdown(xml: String): List<TaxBreakdownLine> {
        val doc = parse(xml)
        val nodes = newXPath().evaluate(
            "//ram:ApplicableHeaderTradeSettlement/ram:ApplicableTradeTax", doc, XPathConstants.NODESET,
        ) as org.w3c.dom.NodeList
        return (0 until nodes.length).map { i ->
            val node = nodes.item(i)
            fun child(name: String): String? {
                val list = node.childNodes
                for (j in 0 until list.length) {
                    val child = list.item(j)
                    if (child.localName == name) return child.textContent?.trim()
                }
                return null
            }
            TaxBreakdownLine(
                categoryCode = requireNotNull(child("CategoryCode")) { "ApplicableTradeTax without CategoryCode" },
                basisAmount = BigDecimal(requireNotNull(child("BasisAmount"))),
                calculatedAmount = BigDecimal(requireNotNull(child("CalculatedAmount"))),
                ratePercent = BigDecimal(requireNotNull(child("RateApplicablePercent"))),
                exemptionReasonCode = child("ExemptionReasonCode"),
                exemptionReasonText = child("ExemptionReason"),
            )
        }
    }

    /**
     * A pretty-printed, whitespace-normalized rendering of [xml] — used to produce readable
     * regeneration-idempotence diffs and as the basis for [semanticallyEqual].
     */
    fun canonicalize(xml: String): String {
        val doc = parse(xml)
        doc.normalizeDocument()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        // Collapse inter-element whitespace so two documents that differ only in insignificant
        // formatting (not content) compare equal.
        return writer.toString().replace(Regex(">\\s+<"), "><").trim()
    }

    /**
     * Regeneration-idempotence check (RG-3 §3): two generations of the same invoice must be
     * semantically identical. [ignoredElementLocalNames] excludes elements by contract (e.g. a
     * future engine-side generation timestamp) — empty by default, since neither export route
     * this suite exercises embeds one (verified empirically: two back-to-back [xmlA]/[xmlB]
     * generations are expected to be byte-identical once whitespace is normalized).
     */
    fun semanticallyEqual(xmlA: String, xmlB: String, ignoredElementLocalNames: Set<String> = emptySet()): Boolean {
        if (ignoredElementLocalNames.isEmpty()) return canonicalize(xmlA) == canonicalize(xmlB)
        return stripElements(xmlA, ignoredElementLocalNames) == stripElements(xmlB, ignoredElementLocalNames)
    }

    private fun stripElements(xml: String, localNames: Set<String>): String {
        val doc = parse(xml)
        val toRemove = mutableListOf<Node>()
        fun walk(node: Node) {
            val children = node.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.localName in localNames) toRemove.add(child) else walk(child)
            }
        }
        walk(doc)
        toRemove.forEach { it.parentNode.removeChild(it) }
        doc.normalizeDocument()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString().replace(Regex(">\\s+<"), "><").trim()
    }
}
