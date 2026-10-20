package de.littletrotter.quotationxml

import de.littletrotter.shared.ValidationMessage
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.XPathExecutable
import net.sf.saxon.s9api.XdmAtomicValue
import net.sf.saxon.s9api.XdmEmptySequence
import net.sf.saxon.s9api.XdmNode
import net.sf.saxon.s9api.XdmValue
import net.sf.saxon.tree.util.Navigator
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.Objects
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.transform.stax.StAXSource

private const val SCHEMATRON_RESOURCE = "schematron/quotation/quotation.sch"
private const val SCHEMATRON_NS = "http://purl.oclc.org/dsdl/schematron"
private val ALLOWED_RATES = QName("allowedRates")

/**
 * Runs the offer business rules BR-Q-01 to BR-Q-05 from the embedded `quotation.sch` against a
 * quotation document. Evaluated by Saxon-HE's XPath 2.0 engine (the same Saxon the Factur-X
 * Schematron stages use): the `.sch` is read directly, so no compiled stylesheet has to be kept
 * in sync with it. Supported Schematron subset: `ns`, `pattern`, `rule@context`, `assert@test`.
 *
 * Rule BR-Q-04 can additionally be checked against the tax rates the caller knows; pass them to
 * [validate] as `allowedRates`. Without them only the range and category consistency are checked.
 *
 * Every failed assert becomes one [ValidationMessage] with the assert id as `ruleId`, the assert
 * text as `message` and the path of the context node as `location`. An empty result means valid.
 */
class QuotationSchematronValidator {

    private val processor = Processor(false)
    private val rules: List<Rule> = loadRules()

    fun validate(xml: ByteArray, allowedRates: Set<BigDecimal> = emptySet()): List<ValidationMessage> {
        val document = parse(xml)
        val bound: XdmValue = if (allowedRates.isEmpty()) {
            XdmEmptySequence.getInstance()
        } else {
            XdmValue(allowedRates.map { XdmAtomicValue(it) })
        }
        return rules.flatMap { evaluate(it, document, bound) }
    }

    private fun evaluate(rule: Rule, document: XdmNode, allowedRates: XdmValue): List<ValidationMessage> {
        val contextSelector = rule.context.load()
        contextSelector.contextItem = document
        contextSelector.setVariable(ALLOWED_RATES, allowedRates)
        return contextSelector.evaluate().filterIsInstance<XdmNode>().flatMap { node ->
            rule.assertions.filterNot { holds(it, node, allowedRates) }.map { failed ->
                ValidationMessage(
                    severity = "error",
                    message = failed.message,
                    location = Navigator.getPath(node.underlyingNode),
                    ruleId = failed.id,
                    stage = "BUSINESS",
                    validator = "schematron",
                )
            }
        }
    }

    private fun holds(assertion: Assertion, node: XdmNode, allowedRates: XdmValue): Boolean {
        val selector = assertion.test.load()
        selector.contextItem = node
        selector.setVariable(ALLOWED_RATES, allowedRates)
        return selector.effectiveBooleanValue()
    }

    private fun parse(xml: ByteArray): XdmNode {
        val inputs = XMLInputFactory.newFactory()
        inputs.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        inputs.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        val reader = inputs.createXMLStreamReader(ByteArrayInputStream(xml))
        return processor.newDocumentBuilder().build(StAXSource(reader))
    }

    private fun loadRules(): List<Rule> {
        val stream = Objects.requireNonNull(
            QuotationSchematronValidator::class.java.classLoader.getResourceAsStream(SCHEMATRON_RESOURCE),
            "Embedded Schematron $SCHEMATRON_RESOURCE is missing from the classpath",
        )
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val root = stream.use { factory.newDocumentBuilder().parse(it).documentElement }
        val compiler = processor.newXPathCompiler()
        compiler.declareVariable(ALLOWED_RATES)
        root.children("ns").forEach { compiler.declareNamespace(it.getAttribute("prefix"), it.getAttribute("uri")) }
        return root.children("pattern").flatMap { pattern ->
            pattern.children("rule").map { rule ->
                Rule(
                    context = compiler.compile(contextExpression(rule.getAttribute("context"))),
                    assertions = rule.children("assert").map { a ->
                        Assertion(a.getAttribute("id"), compiler.compile(a.getAttribute("test")), a.textContent.trim())
                    },
                )
            }
        }
    }

    /** Schematron contexts match anywhere in the document unless they are absolute paths. */
    private fun contextExpression(context: String): String = if (context.startsWith("/")) context else "//$context"

    private fun Element.children(localName: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.namespaceURI == SCHEMATRON_NS && it.localName == localName }

    private class Rule(val context: XPathExecutable, val assertions: List<Assertion>)

    private class Assertion(val id: String, val test: XPathExecutable, val message: String)
}
