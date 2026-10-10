package de.littletrotter.xpdf.impl.mustang

import org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Mustang's `getXML()` parses the generated string with dom4j; when it is not well-formed it only
 * logs "Failed to parse ZUGFeRD data" and then fails with a bare NullPointerException deep in the
 * writer. A document job that dies that way leaves the invoice without a document and without a
 * usable error. This provider checks well-formedness first and names the invoice in the failure.
 */
class WellFormedPullProvider(private val invoiceLabel: String) : ZUGFeRD2PullProvider() {

    override fun getXML(): ByteArray {
        val generated = zugferdData ?: error("Factur-X-XML für $invoiceLabel wurde nicht erzeugt")
        try {
            parser().parse(InputSource(StringReader(String(generated, Charsets.UTF_8))))
        } catch (e: SAXException) {
            throw IllegalStateException(
                "Ungültiges XML für $invoiceLabel (Profil ${profile?.name}): ${e.message} " +
                    "- ein Freitextfeld enthält vermutlich nicht escaptes Markup",
                e,
            )
        }
        return super.getXML()
    }

    private fun parser() =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }.newDocumentBuilder()
}
