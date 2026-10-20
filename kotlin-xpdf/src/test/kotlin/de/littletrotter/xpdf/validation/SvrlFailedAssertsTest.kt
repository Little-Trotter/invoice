package de.littletrotter.xpdf.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Direct unit tests for the extracted [SvrlFailedAsserts] helper — covers [parseSvrlFailedAsserts]
 * and [mapSeverityFlag] directly, without going through the validator
 * Happy path + empty SVRL per the spec.
 */
class SvrlFailedAssertsTest {

    private fun svrl(vararg failedAsserts: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
        <svrl:schematron-output xmlns:svrl="http://purl.oclc.org/dsdl/svrl">
          ${failedAsserts.joinToString("\n")}
        </svrl:schematron-output>
        """.trimIndent()

    @Test
    fun `happy path - parses a fully populated failed-assert with all attributes`() {
        val doc = svrl(
            """<svrl:failed-assert test="count(ram:Name)=1" id="FX-SCH-A-000409"
                                    location="/rsm:CrossIndustryInvoice/rsm:ExchangedDocument"
                                    flag="error">
                 <svrl:text>Element 'ram:Name' must occur exactly 1 times.</svrl:text>
               </svrl:failed-assert>""",
        )

        val results = parseSvrlFailedAsserts(doc)

        assertEquals(1, results.size)
        val fa = results.single()
        assertEquals("FX-SCH-A-000409", fa.id)
        assertEquals("error", fa.flag)
        assertEquals("/rsm:CrossIndustryInvoice/rsm:ExchangedDocument", fa.location)
        assertEquals("count(ram:Name)=1", fa.test)
        assertEquals("Element 'ram:Name' must occur exactly 1 times.", fa.text)
    }

    @Test
    fun `empty SVRL document yields empty list`() {
        val doc = svrl()

        val results = parseSvrlFailedAsserts(doc)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `blank attributes are treated as null, never empty strings`() {
        val doc = svrl(
            """<svrl:failed-assert test="" id="" location="" flag="">
                 <svrl:text>text</svrl:text>
               </svrl:failed-assert>""",
        )

        val fa = parseSvrlFailedAsserts(doc).single()

        assertNull(fa.id)
        assertNull(fa.flag)
        assertNull(fa.location)
        assertNull(fa.test)
        assertEquals("text", fa.text)
    }

    @Test
    fun `missing svrl_text element yields empty text`() {
        val doc = svrl("""<svrl:failed-assert id="X-1"/>""")

        val fa = parseSvrlFailedAsserts(doc).single()

        assertEquals("", fa.text)
    }

    @Test
    fun `mapSeverityFlag - fatal and error map to error`() {
        assertEquals("error", mapSeverityFlag("fatal"))
        assertEquals("error", mapSeverityFlag("error"))
        assertEquals("error", mapSeverityFlag("ERROR"))
    }

    @Test
    fun `mapSeverityFlag - warning maps to warning`() {
        assertEquals("warning", mapSeverityFlag("warning"))
        assertEquals("warning", mapSeverityFlag("WARNING"))
    }

    @Test
    fun `mapSeverityFlag - information and info map to info`() {
        assertEquals("info", mapSeverityFlag("information"))
        assertEquals("info", mapSeverityFlag("info"))
    }

    @Test
    fun `mapSeverityFlag - unrecognized flag falls back to error`() {
        assertEquals("error", mapSeverityFlag("something-unexpected"))
        assertEquals("error", mapSeverityFlag(null))
    }
}
