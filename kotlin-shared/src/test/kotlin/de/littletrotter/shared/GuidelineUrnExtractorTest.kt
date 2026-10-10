package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [extractGuidelineUrn] must read the guideline URN from a real
 * generated CII document on both export routes and never throw on absent/malformed input.
 */
class GuidelineUrnExtractorTest {

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "test fixture missing: $path" }
            .use { it.readBytes() }.toString(Charsets.UTF_8)

    @Test
    fun `extracts the composed guideline URN from a real Mustang-generated fixture`() {
        val urn = extractGuidelineUrn(resource("golden/community-invoice-cii.xml"))
        assertEquals("urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0", urn)
    }

    @Test
    fun `extracts the bare EN16931 guideline URN from a Factur-X engine sample`() {
        val urn = extractGuidelineUrn(resource("facturx/br-fr-ctc/golden-fr-cii.xml"))
        assertEquals("urn:cen.eu:en16931:2017", urn)
    }

    @Test
    fun `absent element returns null, no throw`() {
        assertNull(extractGuidelineUrn("<rsm:CrossIndustryInvoice><foo/></rsm:CrossIndustryInvoice>"))
    }

    @Test
    fun `malformed input returns null, no throw`() {
        assertNull(extractGuidelineUrn("not xml at all <<<"))
    }

    @Test
    fun `blank input returns null, no throw`() {
        assertNull(extractGuidelineUrn(""))
    }

    @Test
    fun `byte-array overload delegates and never throws on garbage bytes`() {
        assertNull(extractGuidelineUrn(byteArrayOf(0, 1, 2, -1, -2)))
    }

    @Test
    fun `boundary - an ID element that is only whitespace becomes blank after trim and yields null, not empty string`() {
        val xml = """
            <rsm:CrossIndustryInvoice>
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>   </ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
            </rsm:CrossIndustryInvoice>
        """.trimIndent()

        assertNull(extractGuidelineUrn(xml))
    }

    @Test
    fun `boundary - surrounding whitespace inside the ID element is trimmed away`() {
        val xml = """
            <rsm:CrossIndustryInvoice>
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>  urn:cen.eu:en16931:2017  </ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
            </rsm:CrossIndustryInvoice>
        """.trimIndent()

        assertEquals("urn:cen.eu:en16931:2017", extractGuidelineUrn(xml))
    }

    @Test
    fun `byte-array overload extracts from valid UTF-8 XML bytes`() {
        val xml = """
            <rsm:CrossIndustryInvoice>
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:factur-x.eu:1p0:en16931</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
            </rsm:CrossIndustryInvoice>
        """.trimIndent()
        assertEquals("urn:factur-x.eu:1p0:en16931", extractGuidelineUrn(xml.toByteArray(Charsets.UTF_8)))
    }
}
