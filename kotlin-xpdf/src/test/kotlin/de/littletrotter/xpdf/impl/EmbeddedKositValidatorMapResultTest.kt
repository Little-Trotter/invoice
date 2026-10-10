package de.littletrotter.xpdf.impl

import de.kosit.validationtool.api.AcceptRecommendation
import de.kosit.validationtool.api.Result
import de.kosit.validationtool.api.XmlError
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.oclc.purl.dsdl.svrl.FailedAssert
import org.oclc.purl.dsdl.svrl.Text
import java.lang.reflect.Method

/**
 * Targeted branch coverage for [EmbeddedKositValidator]'s private report-mapping helpers
 * ([EmbeddedKositValidator] private `mapResult`/`mapSeverityFlag`/`richText`).
 *
 * [KositValidationIT] already proves the end-to-end contract against the real KoSIT engine, but it
 * can only ever observe the SVRL shapes the XRechnung 3.0.2 scenario actually produces (ids always
 * present, well-formed `Text` nodes, no `UNDEFINED` recommendation). The elvis-fallback and
 * unrecognized-flag branches (a finding without an `id`/`role`, an absent `flag`, an
 * `AcceptRecommendation` other than ACCEPTABLE/REJECT) are real defensive code paths that a
 * conformant engine run simply never exercises — so they're driven here directly, against the real
 * `de.kosit.validationtool.api.Result`/SVRL types ([Result] and [XmlError] are plain interfaces,
 * [FailedAssert]/[Text] are JAXB POJOs with public setters), via reflection on the private methods.
 */
class EmbeddedKositValidatorMapResultTest {

    private val validator = EmbeddedKositValidator() // init() never called: no real engine needed

    private fun mapResultMethod(): Method =
        EmbeddedKositValidator::class.java.getDeclaredMethod("mapResult", Result::class.java)
            .apply { isAccessible = true }

    private fun mapSeverityFlagMethod(): Method =
        EmbeddedKositValidator::class.java.getDeclaredMethod("mapSeverityFlag", String::class.java)
            .apply { isAccessible = true }

    private fun richTextMethod(): Method =
        EmbeddedKositValidator::class.java.getDeclaredMethod("richText", Text::class.java)
            .apply { isAccessible = true }

    private fun callMapResult(result: Result): KositValidationResult =
        mapResultMethod().invoke(validator, result) as KositValidationResult

    private fun callMapSeverityFlag(flag: String?): String =
        mapSeverityFlagMethod().invoke(validator, flag) as String

    private fun callRichText(text: Text?): String =
        richTextMethod().invoke(validator, text) as String

    private fun resultMock(
        recommendation: AcceptRecommendation,
        schemaViolations: List<XmlError>? = null,
        failedAsserts: List<FailedAssert> = emptyList(),
    ): Result {
        val result = Mockito.mock(Result::class.java)
        Mockito.`when`(result.acceptRecommendation).thenReturn(recommendation)
        Mockito.`when`(result.schemaViolations).thenReturn(schemaViolations)
        Mockito.`when`(result.failedAsserts).thenReturn(failedAsserts)
        return result
    }

    // ── acceptRecommendation → status (the `else` branch a real engine run never returns) ────

    @Test
    fun `an UNDEFINED recommendation maps to ERROR, the same as an engine failure`() {
        val outcome = callMapResult(resultMock(AcceptRecommendation.UNDEFINED))
        assertEquals(KositOutcome.ERROR, outcome.status)
    }

    // ── schemaViolations — null list, empty list, and a fully populated XmlError ─────────────

    @Test
    fun `a null schemaViolations list produces no SYNTAX findings`() {
        val outcome = callMapResult(resultMock(AcceptRecommendation.ACCEPTABLE, schemaViolations = null))
        assertTrue(outcome.details.none { it.stage == "SYNTAX" })
    }

    @Test
    fun `an XmlError with all fields null falls back to defaults`() {
        // Mockito's default answer returns 0 (not null) for boxed numeric getters, so every
        // field is stubbed explicitly here rather than relying on the mock's defaults.
        val err = Mockito.mock(XmlError::class.java)
        Mockito.`when`(err.severity).thenReturn(null)
        Mockito.`when`(err.message).thenReturn(null)
        Mockito.`when`(err.rowNumber).thenReturn(null)
        Mockito.`when`(err.columnNumber).thenReturn(null)
        val outcome = callMapResult(resultMock(AcceptRecommendation.REJECT, schemaViolations = listOf(err)))
        val finding = outcome.details.single { it.stage == "SYNTAX" }
        assertEquals("ERROR", finding.severity, "a schema error without a severity must default to ERROR")
        assertEquals("Unknown schema error", finding.message)
        assertEquals("row ?, col ?", finding.location)
    }

    @Test
    fun `an XmlError with all fields populated carries them through untouched`() {
        val err = Mockito.mock(XmlError::class.java)
        Mockito.`when`(err.severity).thenReturn(XmlError.Severity.SEVERITY_WARNING)
        Mockito.`when`(err.message).thenReturn("Custom schema message")
        Mockito.`when`(err.rowNumber).thenReturn(5)
        Mockito.`when`(err.columnNumber).thenReturn(10)
        val outcome = callMapResult(resultMock(AcceptRecommendation.REJECT, schemaViolations = listOf(err)))
        val finding = outcome.details.single { it.stage == "SYNTAX" }
        assertEquals("SEVERITY_WARNING", finding.severity)
        assertEquals("Custom schema message", finding.message)
        assertEquals("row 5, col 10", finding.location)
    }

    // ── ruleId derivation: fa.id ?: fa.role ?: fa.test, each guarded by isNotBlank ───────────

    private fun failedAssert(id: String?, role: String?, test: String?, flag: String? = "error"): FailedAssert =
        FailedAssert().apply {
            this.id = id
            this.role = role
            this.test = test
            this.flag = flag
            this.location = "loc"
        }

    @Test
    fun `ruleId uses id when it is non-blank`() {
        val outcome = callMapResult(resultMock(AcceptRecommendation.REJECT, failedAsserts = listOf(failedAssert("BR-CO-25", "ROLE", "TEST"))))
        assertEquals("BR-CO-25", outcome.details.single().ruleId)
    }

    @Test
    fun `ruleId falls back to role when id is blank`() {
        val outcome = callMapResult(resultMock(AcceptRecommendation.REJECT, failedAsserts = listOf(failedAssert("", "ROLE-Y", "TEST"))))
        assertEquals("ROLE-Y", outcome.details.single().ruleId)
    }

    @Test
    fun `ruleId falls back to test when id is null and role is blank`() {
        val outcome = callMapResult(resultMock(AcceptRecommendation.REJECT, failedAsserts = listOf(failedAssert(null, "", "TEST-Z"))))
        assertEquals("TEST-Z", outcome.details.single().ruleId)
    }

    @Test
    fun `ruleId is null when id, role and test are all null`() {
        val outcome = callMapResult(resultMock(AcceptRecommendation.REJECT, failedAsserts = listOf(failedAssert(null, null, null))))
        assertNull(outcome.details.single().ruleId)
    }

    @Test
    fun `ruleId is null when id and role are blank and test is blank too`() {
        val outcome = callMapResult(resultMock(AcceptRecommendation.REJECT, failedAsserts = listOf(failedAssert("", "", ""))))
        assertNull(outcome.details.single().ruleId)
    }

    // ── mapSeverityFlag — the full SVRL flag → severity mapping table ────────────────────────

    @Test
    fun `fatal and error flags map to ERROR`() {
        assertEquals("ERROR", callMapSeverityFlag("fatal"))
        assertEquals("ERROR", callMapSeverityFlag("FATAL"))
        assertEquals("ERROR", callMapSeverityFlag("error"))
    }

    @Test
    fun `warning flag maps to WARNING`() {
        assertEquals("WARNING", callMapSeverityFlag("warning"))
        assertEquals("WARNING", callMapSeverityFlag("WARNING"))
    }

    @Test
    fun `information and info flags map to INFO`() {
        assertEquals("INFO", callMapSeverityFlag("information"))
        assertEquals("INFO", callMapSeverityFlag("info"))
    }

    @Test
    fun `a null or unrecognized flag defaults to ERROR so it never silently disappears`() {
        assertEquals("ERROR", callMapSeverityFlag(null))
        assertEquals("ERROR", callMapSeverityFlag("bogus-flag"))
    }

    // ── richText — null Text, empty content, and mixed String/non-String content ────────────

    @Test
    fun `a null Text node maps to an empty string`() {
        assertEquals("", callRichText(null))
    }

    @Test
    fun `a Text node with empty content maps to an empty string`() {
        assertEquals("", callRichText(Text()))
    }

    @Test
    fun `a Text node joins String content items with a space`() {
        val text = Text().apply { content.add("Hello"); content.add("world") }
        assertEquals("Hello world", callRichText(text))
    }

    @Test
    fun `a Text node stringifies non-String content items`() {
        val marker = object {
            override fun toString() = "MARKER"
        }
        val text = Text().apply { content.add("prefix"); content.add(marker) }
        assertEquals("prefix MARKER", callRichText(text))
    }
}
