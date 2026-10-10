package de.littletrotter.xpdf.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.shared.KositOutcome
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayOutputStream

/**
 * A data-driven test over every fixture in `fixtures/manifest.json`: every
 * positive fixture must be VALID on every active stage it exercises (SYNTAX, KOSIT); every
 * negative fixture must fail on its *recorded* stage with its *recorded* rule ID — failing for
 * the wrong reason is itself a test failure, and a negative fixture that unexpectedly
 * passes is too. The same assertions run here are what `ci/main.go`'s fixture gate depends on
 * being red/green.
 *
 * No fixture here is marked expected-fail ("not yet implemented"): all six documented
 * negative cases were verified empirically (see the fixture headers/manifest) to already be
 * caught by the current SYNTAX/KOSIT/PDFA stages — there was no gap to record.
 */
class FixtureValidationTest {

    private val xsd = CiiXsdValidator().apply { init() }
    private val kosit = EmbeddedKositValidator().apply { init() }
    private val pdfa = PdfA3MinimalCheck()
    private val manifest = loadManifest()

    @TestFactory
    fun `positive fixtures are VALID on every active stage`(): List<DynamicTest> =
        manifest.positive.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val xml = readFixture(case.file)
                val xsdResult = xsd.validate(xml)
                assertEquals(KositOutcome.VALID, xsdResult.outcome, "${case.id}: SYNTAX stage, findings=${xsdResult.findings}")
                val kositResult = kosit.validate(xml)
                assertEquals(KositOutcome.VALID, kositResult.status, "${case.id}: KOSIT stage, ${kositResult.summary} ${kositResult.details}")
                assertTrue(kositResult.details.isEmpty(), "${case.id}: expected no findings, got ${kositResult.details}")
            }
        }

    @TestFactory
    fun `negative fixtures fail on the recorded stage with the recorded rule id`(): List<DynamicTest> =
        manifest.negative.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val xml = readFixture(case.file!!)
                when (case.expectedStage) {
                    "SYNTAX" -> {
                        val result = xsd.validate(xml)
                        assertEquals(KositOutcome.INVALID, result.outcome, "${case.id}: expected SYNTAX INVALID, got ${result.outcome} ${result.findings}")
                        assertRuleId(case, result.findings.map { it.ruleId to it.stage })
                    }
                    "KOSIT" -> {
                        val xsdResult = xsd.validate(xml)
                        assertEquals(KositOutcome.VALID, xsdResult.outcome, "${case.id}: fixture must pass SYNTAX to exercise KOSIT, got ${xsdResult.findings}")
                        val result = kosit.validate(xml)
                        assertEquals(KositOutcome.INVALID, result.status, "${case.id}: expected KOSIT INVALID, got ${result.status} ${result.summary}")
                        assertRuleId(case, result.details.map { it.ruleId to it.stage })
                    }
                    else -> throw AssertionError("${case.id}: unsupported expectedStage '${case.expectedStage}' for an XML fixture")
                }
            }
        }

    @TestFactory
    fun `special negative fixtures built at test time`(): List<DynamicTest> =
        manifest.specialNegative.map { case ->
            DynamicTest.dynamicTest(case.id) {
                when (case.id) {
                    "pdf-ohne-eingebettetes-xml" -> {
                        val result = pdfa.check(plainPdfBytes(), expectedAttachmentFilename = "factur-x.xml")
                        assertEquals(KositOutcome.INVALID, result.outcome, "${case.id}: expected PDFA INVALID, got ${result.outcome} ${result.findings}")
                        assertRuleId(case, result.findings.map { it.ruleId to it.stage })
                    }
                    else -> throw AssertionError("${case.id}: no test-time builder registered for this special fixture")
                }
            }
        }

    // A validator/scenario upgrade that shifts what fixtures expect must be
    // caught, not silently pass. The KoSIT engine version itself is out of scope: it isn't
    // pinned anywhere else in this codebase either (EmbeddedKositValidator reads it from the
    // library at runtime), so there is nothing recorded to drift against — only the scenario
    // release date (SCENARIO_RELEASE, embedded in the classpath ZIP's filename) is a project-
    // controlled artifact that could change without this test file being touched.
    @Test
    fun `KoSIT scenario release matches the version recorded in the manifest`() {
        val result = kosit.validate(manifest.positive.first().let { readFixture(it.file) })
        assertTrue(
            result.validatorVersion?.contains(manifest.versions.kositScenarioRelease) == true,
            "Expected validatorVersion to contain '${manifest.versions.kositScenarioRelease}', got '${result.validatorVersion}' -- " +
                "if the KoSIT scenario was upgraded, review every fixture's expectation before updating this manifest.",
        )
    }

    private fun assertRuleId(case: NegativeCase, found: List<Pair<String?, String?>>) {
        if (case.expectedRuleId == null) {
            assertTrue(found.any { it.second == case.expectedStage }, "${case.id}: expected a finding on stage ${case.expectedStage}, got $found")
        } else {
            assertTrue(
                found.any { it.first == case.expectedRuleId && it.second == case.expectedStage },
                "${case.id}: expected ruleId=${case.expectedRuleId} on stage=${case.expectedStage}, got $found",
            )
        }
    }

    private fun plainPdfBytes(): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private fun readFixture(path: String): String =
        javaClass.classLoader.getResourceAsStream("fixtures/$path")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Fixture fixtures/$path not found on classpath")

    private fun loadManifest(): FixtureManifest {
        val mapper = jacksonObjectMapper()
        val json = javaClass.classLoader.getResourceAsStream("fixtures/manifest.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("fixtures/manifest.json not found on classpath")
        return mapper.readValue(json)
    }
}

private data class FixtureManifest(
    val versions: FixtureVersions,
    val positive: List<PositiveCase>,
    val negative: List<NegativeCase>,
    val specialNegative: List<NegativeCase>,
)

private data class FixtureVersions(val kositScenarioRelease: String)

private data class PositiveCase(val id: String, val file: String, val description: String)

private data class NegativeCase(
    val id: String,
    val file: String? = null,
    val description: String,
    val expectedStage: String,
    val expectedRuleId: String? = null,
)
