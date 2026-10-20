package de.littletrotter.quotationxml

import de.littletrotter.shared.ValidationMessage
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.Objects
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory

private const val QUOTATION_XSD = "ubl/maindoc/UBL-Quotation-2.1.xsd"

/**
 * Validates a quotation document against the embedded, unmodified OASIS UBL 2.1 `Quotation`
 * schema and the common component schemas it imports; all of them are bundled on the classpath
 * so no network access is ever needed.
 *
 * Returns the findings; an empty list means schema-valid. The compiled [Schema] is thread-safe
 * and shared, each [validate] call uses its own validator.
 */
class QuotationXsdValidator {

    private val schema: Schema = loadSchema()

    fun validate(xml: ByteArray): List<ValidationMessage> {
        val findings = mutableListOf<ValidationMessage>()
        val validator = schema.newValidator()
        // DefaultHandler ignores warnings and throws on fatal errors; only schema violations are collected.
        validator.errorHandler = object : DefaultHandler() {
            override fun error(e: SAXParseException) {
                findings += finding(e)
            }
        }
        try {
            validator.validate(hardenedSource(xml))
        } catch (fatal: SAXParseException) {
            findings += finding(fatal)
        }
        return findings
    }

    /** Document type declarations are refused outright, so no external entity is ever resolved. */
    private fun hardenedSource(xml: ByteArray): SAXSource {
        val parsers = SAXParserFactory.newInstance()
        parsers.isNamespaceAware = true
        parsers.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return SAXSource(parsers.newSAXParser().xmlReader, InputSource(ByteArrayInputStream(xml)))
    }

    private fun finding(e: SAXParseException) = ValidationMessage(
        severity = "error",
        message = e.toString(),
        location = "line ${e.lineNumber}, column ${e.columnNumber}",
        stage = "SYNTAX",
        validator = "xsd",
    )

    private fun loadSchema(): Schema {
        val url = Objects.requireNonNull(
            QuotationXsdValidator::class.java.classLoader.getResource(QUOTATION_XSD),
            "Embedded schema $QUOTATION_XSD is missing from the classpath",
        )
        val factory = SchemaFactory.newDefaultInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "jar,file")
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "jar,file")
        return factory.newSchema(StreamSource(url.toExternalForm()))
    }
}
