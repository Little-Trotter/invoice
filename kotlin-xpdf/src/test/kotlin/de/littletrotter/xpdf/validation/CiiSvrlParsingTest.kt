package de.littletrotter.xpdf.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [parseSvrlFailedAsserts] and
 * [CiiSchematronValidator.toValidationMessage] against hand-crafted SVRL, so
 * every branch is covered directly ("the SVRL parsing logic fully covered") —
 * not every edge case (a missing `test`/`id`/`location` attribute, an assert with no `svrl:text`
 * element at all) actually occurs in the real embedded Schematron's output, so a fixture mutation
 * alone cannot reach them all.
 */
class CiiSvrlParsingTest {

    // No Saxon/CDI dependency needed for pure DOM parsing — a plain instance is enough.
    private val validator = CiiSchematronValidator()

    private fun svrl(vararg failedAsserts: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
        <svrl:schematron-output xmlns:svrl="http://purl.oclc.org/dsdl/svrl">
          ${failedAsserts.joinToString("\n")}
        </svrl:schematron-output>
        """.trimIndent()

    @Test
    fun `a fully populated failed-assert carries every attribute through`() {
        val doc = svrl(
            """<svrl:failed-assert test="count(ram:Name)=1" id="FX-SCH-A-000409"
                                     location="/rsm:CrossIndustryInvoice/rsm:ExchangedDocument">
                 <svrl:text>Element 'ram:Name' must occur exactly 1 times.</svrl:text>
               </svrl:failed-assert>""",
        )

        val results = parseSvrlFailedAsserts(doc)

        assertEquals(1, results.size)
        val fa = results.single()
        assertEquals("FX-SCH-A-000409", fa.id)
        assertNull(fa.flag)
        assertEquals("/rsm:CrossIndustryInvoice/rsm:ExchangedDocument", fa.location)
        assertEquals("count(ram:Name)=1", fa.test)
        assertEquals("Element 'ram:Name' must occur exactly 1 times.", fa.text)
    }

    @Test
    fun `an assert with no id, location, or test attribute at all yields nulls, never blank strings`() {
        val doc = svrl(
            """<svrl:failed-assert>
                 <svrl:text>Some assert with no attributes at all.</svrl:text>
               </svrl:failed-assert>""",
        )

        val fa = parseSvrlFailedAsserts(doc).single()

        assertNull(fa.id)
        assertNull(fa.flag)
        assertNull(fa.location)
        assertNull(fa.test)
        assertEquals("Some assert with no attributes at all.", fa.text)

        // The downstream mapping must still produce a well-formed finding: no ruleId, and the
        // absent `test` must not be mistaken for a code-list lookup.
        val message = validator.toValidationMessage(fa)
        assertNull(message.ruleId)
        assertFalse(message.codeListViolation)
    }

    @Test
    fun `an assert with a blank attribute value is treated the same as absent`() {
        val doc = svrl(
            """<svrl:failed-assert test="" id="" location="">
                 <svrl:text>text</svrl:text>
               </svrl:failed-assert>""",
        )

        val fa = parseSvrlFailedAsserts(doc).single()

        assertNull(fa.id)
        assertNull(fa.location)
        assertNull(fa.test)
    }

    @Test
    fun `an assert with no svrl_text element at all yields empty text, never a crash`() {
        val doc = svrl("""<svrl:failed-assert id="X-1"/>""")

        val fa = parseSvrlFailedAsserts(doc).single()

        assertEquals("", fa.text)
        assertTrue(extractBusinessTerms(fa.text).isEmpty())
    }

    @Test
    fun `multiple failed-asserts in one document are all parsed, in document order`() {
        val doc = svrl(
            """<svrl:failed-assert id="A-1"><svrl:text>first</svrl:text></svrl:failed-assert>""",
            """<svrl:failed-assert id="A-2"><svrl:text>second</svrl:text></svrl:failed-assert>""",
        )

        val results = parseSvrlFailedAsserts(doc)

        assertEquals(listOf("A-1", "A-2"), results.map { it.id })
    }

    @Test
    fun `a document with zero failed-asserts yields an empty list`() {
        val doc = svrl()

        assertTrue(parseSvrlFailedAsserts(doc).isEmpty())
    }

    @Test
    fun `toValidationMessage marks a test expression containing codedb_xml as a code-list violation`() {
        val fa = SvrlFailedAssert(
            id = "FX-SCH-A-000555",
            flag = null,
            location = "/x",
            test = "string-length(\$v)=0 or document('FACTUR-X_EN16931_codedb.xml')/codedb/cl[@id=2]/enumeration[@value=\$v]",
            text = "Value of 'ram:TypeCode' is not allowed.",
        )

        val message = validator.toValidationMessage(fa)

        assertTrue(message.codeListViolation)
        assertNull(message.businessTerms)
    }
}
