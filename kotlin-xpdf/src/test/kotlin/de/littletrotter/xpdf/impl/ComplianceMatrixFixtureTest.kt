package de.littletrotter.xpdf.impl

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.shared.KositOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * G1-T01..T07 — the document-level (SYNTAX/KOSIT) half of G1's Pflicht-Testmatrix. The
 * resolver-level half (rule code/category/rate, and the three blocked/unsupported rows) lives
 * in kotlin-invoice's `ComplianceMatrixResolverTest` — this module has no dependency on
 * kotlin-invoice's domain classes, so the ten-row list is kept twice, once per module, each
 * scoped to what that module can actually prove (see kotlin-invoice's `ComplianceMatrix.kt` for
 * the fuller rationale).
 *
 * Writes its results to a checked-in
 * artifact the report generator (`ComplianceReportGenerator` in kotlin-invoice) reads back in —
 * regenerate it by re-running this test before regenerating the report.
 */
class ComplianceMatrixFixtureTest {

    private data class Row(val id: String, val fixtureId: String)

    // Same 7 valid rows as kotlin-invoice's ComplianceMatrix; DE-B2B/DE-B2C intentionally share
    // one fixture (see that file's KDoc — the CII/KoSIT level can't tell them apart, only
    // TaxRuleResolver's customerType input distinguishes them).
    private val rows = listOf(
        Row("DE-B2B", "inland-b2b-19-s"),
        Row("DE-B2C", "inland-b2b-19-s"),
        Row("EU-B2B", "eu-b2b-reverse-charge-ae"),
        Row("EU-B2C", "eu-b2c-19-s"),
        Row("Befreit", "steuerbefreit-e-mit-pflichtgrund"),
        Row("Nicht steuerbar", "nicht-steuerbar-o"),
        Row("Behörde", "oeffentlicher-auftraggeber-leitweg-id"),
    )

    private val xsd = CiiXsdValidator().apply { init() }
    private val kosit = EmbeddedKositValidator().apply { init() }

    private data class RowResult(
        val id: String,
        val fixtureId: String,
        val syntaxOutcome: String,
        val kositOutcome: String,
        val kositFindingCount: Int,
        val kositValidatorVersion: String?,
    )

    @TestFactory
    fun `every valid matrix row is SYNTAX- and KOSIT-VALID, and results are written for the report`(): List<DynamicTest> {
        val results = mutableListOf<RowResult>()
        val tests = rows.map { row ->
            DynamicTest.dynamicTest(row.id) {
                val xml = readFixture(row.fixtureId)
                val xsdResult = xsd.validate(xml)
                val kositResult = kosit.validate(xml)
                results.add(
                    RowResult(
                        id = row.id,
                        fixtureId = row.fixtureId,
                        syntaxOutcome = xsdResult.outcome.name,
                        kositOutcome = kositResult.status.name,
                        kositFindingCount = kositResult.details.size,
                        kositValidatorVersion = kositResult.validatorVersion,
                    )
                )
                assertEquals(KositOutcome.VALID, xsdResult.outcome, "${row.id}: SYNTAX stage, findings=${xsdResult.findings}")
                assertEquals(KositOutcome.VALID, kositResult.status, "${row.id}: KOSIT stage, ${kositResult.summary} ${kositResult.details}")
                assertTrue(kositResult.details.isEmpty(), "${row.id}: expected no findings, got ${kositResult.details}")
            }
        }
        // Runs after every DynamicTest above only when they all completed without throwing —
        // JUnit5 evaluates a Stream/List<DynamicTest> lazily per-test, so a trailing "write the
        // file" dynamic test is the reliable way to run this after the real checks, not a
        // regular @AfterAll (which fires even if the factory method itself never got invoked).
        return tests + DynamicTest.dynamicTest("write kosit-matrix-results.json") {
            writeResults(results)
        }
    }

    private fun writeResults(results: List<RowResult>) {
        val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
        val repoRoot = File(System.getProperty("user.dir")).let { cwd ->
            if (cwd.name == "kotlin-xpdf") cwd.parentFile else cwd
        }
        val outFile = File(repoRoot, "docs/compliance-artifacts/kosit-matrix-results.json")
        outFile.parentFile.mkdirs()
        outFile.writeText(
            mapper.writeValueAsString(
                mapOf(
                    "generatedBy" to "kotlin-xpdf ComplianceMatrixFixtureTest",
                    "rows" to results,
                )
            )
        )
    }

    private fun readFixture(fixtureId: String): String =
        javaClass.classLoader.getResourceAsStream("fixtures/positive/$fixtureId.xml")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Fixture fixtures/positive/$fixtureId.xml not found on classpath")
}
