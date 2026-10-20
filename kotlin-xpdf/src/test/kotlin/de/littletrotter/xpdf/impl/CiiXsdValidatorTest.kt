package de.littletrotter.xpdf.impl

import de.littletrotter.shared.KositOutcome
import de.littletrotter.xpdf.validCiiXml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xml.sax.SAXParseException

/** F3, Stufe 1 — the CII XSD validator, run standalone against the same fixture
 *  [de.littletrotter.xpdf.KositValidationIT] uses for the full KoSIT run. */
class CiiXsdValidatorTest {

    private val validator = CiiXsdValidator().apply { init() }

    @Test
    fun `a schema-valid CII document is VALID`() {
        val result = validator.validate(validCiiXml())
        assertEquals(KositOutcome.VALID, result.outcome, "expected VALID, findings: ${result.findings}")
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `an unknown root element is rejected as INVALID with a SYNTAX-stage finding`() {
        val broken = validCiiXml().replace("rsm:CrossIndustryInvoice", "rsm:NotAnInvoice")
        val result = validator.validate(broken)
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.findings.all { it.stage == "SYNTAX" && it.validator == "xsd" })
    }

    @Test
    fun `a duplicated single-occurrence element is rejected as INVALID`() {
        // ram:TypeCode has maxOccurs=1 on rsm:ExchangedDocument — a second occurrence violates
        // the schema's xsd:sequence, independent of which specific element is chosen.
        val broken = validCiiXml().replace(
            "<ram:TypeCode>380</ram:TypeCode>",
            "<ram:TypeCode>380</ram:TypeCode><ram:TypeCode>380</ram:TypeCode>",
        )
        val result = validator.validate(broken)
        assertEquals(KositOutcome.INVALID, result.outcome)
    }

    @Test
    fun `not-well-formed XML is rejected as INVALID, not a silent VALID or a thrown exception`() {
        val result = validator.validate("<Invoice><broken>")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(result.findings.isNotEmpty())
    }

    @Test
    fun `an unsupported extra element is rejected as INVALID`() {
        val broken = validCiiXml().replace(
            "<ram:Name>Beratung</ram:Name>",
            "<ram:Name>Beratung</ram:Name><ram:ThisElementDoesNotExist>x</ram:ThisElementDoesNotExist>",
        )
        val result = validator.validate(broken)
        assertEquals(KositOutcome.INVALID, result.outcome)
    }

    @Test
    fun `an uninitialized engine yields ERROR, not VALID and not INVALID`() {
        val brokenValidator = CiiXsdValidator() // init() never called — schema stays uninitialized
        val result = brokenValidator.validate(validCiiXml())
        assertEquals(KositOutcome.ERROR, result.outcome, "an engine problem must not misreport as a document fact")
    }

    // Since initSchema always forwards its OWN resourceLookup explicitly, production
    // no longer ever calls extractXsdResources with no argument at all — only this test still
    // exercises its own default (real classloader) lookup value directly.
    @Test
    fun `happy path - extractXsdResources resolves every bundled XSD via its own default classloader lookup`() {
        val dir = CiiXsdValidator().extractXsdResources()

        assertTrue(dir.resolve("CrossIndustryInvoice_100pD16B.xsd").exists())
    }

    @Test
    fun `boundary - extractXsdResources throws when a bundled XSD is missing from the classpath`() {
        // extractXsdResources's test-seam resourceLookup parameter (same pattern as
        // MustangVersion.resolve's own KDoc for the equivalent "otherwise unreachable defensive
        // branch" problem) — XSD_RESOURCES itself is a static final field the JVM refuses to
        // set reflectively even with setAccessible, so faking the classpath is not an option.
        val ex = assertThrows(IllegalStateException::class.java) {
            CiiXsdValidator().extractXsdResources(resourceLookup = { null })
        }
        assertTrue(ex.message!!.contains("not found on classpath"), "was: ${ex.message}")
    }

    // ─── CollectingErrorHandler — a real XSD run essentially never reaches `warning` ───────────

    @Test
    fun `boundary - CollectingErrorHandler records a warning as a WARNING-severity finding`() {
        val findings = mutableListOf<de.littletrotter.shared.ValidationMessage>()
        val handler = CollectingErrorHandler(findings)

        handler.warning(SAXParseException("deprecated construct", null, null, 3, 5))

        assertEquals(1, findings.size)
        assertEquals("WARNING", findings[0].severity)
        assertEquals("deprecated construct", findings[0].message)
        assertEquals("row 3, col 5", findings[0].location)
    }

    @Test
    fun `boundary - CollectingErrorHandler falls back to a default message when the exception has none`() {
        val findings = mutableListOf<de.littletrotter.shared.ValidationMessage>()
        val handler = CollectingErrorHandler(findings)

        handler.error(SAXParseException(null, null, null, 1, 1))

        assertEquals("XSD violation", findings[0].message)
    }

    @Test
    fun `boundary - initSchema propagates a missing-resource setup failure unwrapped`() {
        // initSchema's catch now only wraps the checked exceptions SchemaFactory.newSchema
        // (SAXException) and resource extraction (IOException) can throw; extractXsdResources'
        // own IllegalStateException for a missing bundled XSD is already the right shape for an
        // engine-setup failure and propagates unwrapped, not double-wrapped into a second one.
        val ex = assertThrows(IllegalStateException::class.java) {
            CiiXsdValidator().initSchema(resourceLookup = { null })
        }
        assertTrue(ex.message!!.contains("not found on classpath"), "was: ${ex.message}")
    }

    // initSchema's own IOException/SAXException catch blocks, both genuinely reachable
    // (unlike validate()'s own dead IOException catch — see that method's own comment) but never
    // exercised before: a real I/O failure while extracting the bundled resources, and a real
    // schema-compilation failure from a malformed root XSD.

    @Test
    fun `boundary - initSchema wraps a genuine I-O failure while extracting the bundled XSD resources`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            CiiXsdValidator().initSchema(
                resourceLookup = {
                    object : java.io.InputStream() {
                        override fun read(): Int = throw java.io.IOException("disk full")
                    }
                },
            )
        }
        assertTrue(ex.message!!.contains("Failed to initialize CII XSD validator"), "was: ${ex.message}")
        assertTrue(ex.cause is java.io.IOException, "cause must be the real IOException, not swallowed: ${ex.cause}")
    }

    @Test
    fun `boundary - initSchema wraps a genuine schema-compilation failure from a malformed root XSD`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            CiiXsdValidator().initSchema(resourceLookup = { "not valid XSD content at all".byteInputStream() })
        }
        assertTrue(ex.message!!.contains("Failed to initialize CII XSD validator"), "was: ${ex.message}")
        assertTrue(ex.cause is org.xml.sax.SAXException, "cause must be the real SAXException, not swallowed: ${ex.cause}")
    }
}
