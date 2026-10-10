package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.FacturXEngine
import de.littletrotter.facturx.FacturXEngineOutcome
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * [FacturXExportPipeline] on its own, collaborators mocked (unit layer): the Factur-X
 * engine outcome handling (XML/PDF generation results, rejection vs. infrastructure-abort,
 * completing/failing the job) needs nothing beyond its own three collaborators.
 */
class FacturXExportPipelineTest {

    private fun invoice(): InvoiceDto = InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-QA10-3"), items = emptyList())

    // completeRejectedFacturXExport normalizes the base PDF for real (PdfA3ColorSpaceNormalizer,
    // unmocked) — needs an actually parseable PDF, not garbage bytes.
    private fun minimalPdfBytes(): ByteArray = org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
        doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
        val os = java.io.ByteArrayOutputStream()
        doc.save(os)
        os.toByteArray()
    }

    private fun request(veraPdfCheck: Boolean = false) = ExportRequest(
        invoice(), "job-1", de.littletrotter.shared.DocumentKeyRules.deriveExportKeys("INV-QA10-3", 3L),
        "2026-01-01T00:00:00Z", false, veraPdfCheck = veraPdfCheck, llmOptimize = false,
    )

    private val fixedInstant: Instant = Instant.parse("2026-01-15T10:30:00Z")
    private val fixedTime: TimeSource = ClockTimeSource.fixedAt(fixedInstant)

    private fun sut(
        facturXEngine: FacturXEngine = mock(),
        basePdfStage: BasePdfStage = mock(),
        exportCompletionStage: ExportCompletionStage = mock(),
        time: TimeSource = fixedTime,
    ) = FacturXExportPipeline(facturXEngine, basePdfStage, exportCompletionStage, time)

    private fun rejectedFinding(ruleId: String) = ValidationMessage(severity = "error", message = "x", ruleId = ruleId)

    // ── generateFacturXXml ───────────────────────────────────────────────────────────

    @Test
    fun `generateFacturXXml returns Ready with the engine's own bytes on success`() {
        val engine: FacturXEngine = mock()
        whenever(engine.generateXml(any())).thenReturn(FacturXEngineOutcome.Success(byteArrayOf(1, 2, 3)))
        val sut = sut(facturXEngine = engine)

        val result = sut.generateFacturXXml(request())

        assertTrue(result is FacturXXmlGenerationResult.Ready)
        assertArrayEquals(byteArrayOf(1, 2, 3), (result as FacturXXmlGenerationResult.Ready).xmlBytes)
    }

    @Test
    fun `generateFacturXXml short-circuits as Failed on an infrastructure abort`() {
        val engine: FacturXEngine = mock()
        whenever(engine.generateXml(any())).thenReturn(
            FacturXEngineOutcome.Failure(listOf(rejectedFinding("FACTURX-ENGINE-UNAVAILABLE"))),
        )
        val sut = sut(facturXEngine = engine, time = fixedTime)

        val result = sut.generateFacturXXml(request())

        assertTrue(result is FacturXXmlGenerationResult.ShortCircuit)
        val outcome = (result as FacturXXmlGenerationResult.ShortCircuit).outcome
        assertTrue(outcome is FacturXExportOutcome.Failed)
        // Proves the failure report's own checkedAt comes from the injected TimeSource, not a
        // direct clock read — see FacturXFindings.failed's own checkedAt parameter.
        assertTrue(
            (outcome as FacturXExportOutcome.Failed).reportJson.contains(fixedTime.nowUtc().toString()),
            "expected the report to carry the fixed TimeSource's moment, got: ${outcome.reportJson}",
        )
    }

    @Test
    fun `boundary - an infrastructure abort with veraPdfCheck requested adds a not-run VERAPDF stage`() {
        val engine: FacturXEngine = mock()
        whenever(engine.generateXml(any())).thenReturn(
            FacturXEngineOutcome.Failure(listOf(rejectedFinding("FACTURX-ENGINE-UNAVAILABLE"))),
        )
        val sut = sut(facturXEngine = engine)

        val result = sut.generateFacturXXml(request(veraPdfCheck = true))

        assertTrue(result is FacturXXmlGenerationResult.ShortCircuit)
        val outcome = (result as FacturXXmlGenerationResult.ShortCircuit).outcome
        assertTrue(outcome is FacturXExportOutcome.Failed)
        assertTrue(
            (outcome as FacturXExportOutcome.Failed).reportJson.contains("VERAPDF"),
            "a veraPdfCheck-requested abort must report VERAPDF as a not-run stage, not silently absent",
        )
    }

    @Test
    fun `generateFacturXXml completes the job with no XML at all on a genuine rejection`() {
        val engine: FacturXEngine = mock()
        val basePdfStage: BasePdfStage = mock()
        val completion: ExportCompletionStage = mock()
        whenever(engine.generateXml(any())).thenReturn(
            FacturXEngineOutcome.Failure(listOf(rejectedFinding("FACTURX-ENGINE-REJECTED"))),
        )
        whenever(basePdfStage.renderBase(any())).thenReturn(minimalPdfBytes())
        whenever(completion.completeExport(any(), any(), any(), any())).thenReturn(ExportResult(objectKey = "pdf/key", zugferdXmlKey = "xml/key", kositSuccess = false, kositReport = "", sha256 = "abc"))
        val sut = sut(facturXEngine = engine, basePdfStage = basePdfStage, exportCompletionStage = completion)

        val result = sut.generateFacturXXml(request())

        assertTrue(result is FacturXXmlGenerationResult.ShortCircuit)
        assertTrue((result as FacturXXmlGenerationResult.ShortCircuit).outcome is FacturXExportOutcome.Success)
    }

    // ── convertToFacturXPdf ──────────────────────────────────────────────────────────

    @Test
    fun `convertToFacturXPdf returns Ready with the engine's own PDF bytes on success`() {
        val engine: FacturXEngine = mock()
        whenever(engine.convert(any(), any())).thenReturn(FacturXEngineOutcome.Success(byteArrayOf(4, 5, 6)))
        val sut = sut(facturXEngine = engine)
        val base = byteArrayOf(1)

        val result = sut.convertToFacturXPdf(request(), byteArrayOf(1), base, exportStages())

        assertTrue(result is FacturXPdfConversionResult.Ready)
        assertArrayEquals(byteArrayOf(4, 5, 6), (result as FacturXPdfConversionResult.Ready).pdfBytes)
    }

    @Test
    fun `convertToFacturXPdf short-circuits as Failed on an infrastructure abort, keeping stages known so far`() {
        val engine: FacturXEngine = mock()
        whenever(engine.convert(any(), any())).thenReturn(
            FacturXEngineOutcome.Failure(listOf(rejectedFinding("FACTURX-ENGINE-UNAVAILABLE"))),
        )
        val sut = sut(facturXEngine = engine)
        val base = byteArrayOf(1)

        val result = sut.convertToFacturXPdf(request(), byteArrayOf(1), base, exportStages())

        assertTrue(result is FacturXPdfConversionResult.ShortCircuit)
    }

    @Test
    fun `convertToFacturXPdf completes the job with the real XML on a genuine rejection`() {
        val engine: FacturXEngine = mock()
        val completion: ExportCompletionStage = mock()
        whenever(engine.convert(any(), any())).thenReturn(
            FacturXEngineOutcome.Failure(listOf(rejectedFinding("FACTURX-TAX-BREAKDOWN-MISMATCH"))),
        )
        whenever(completion.completeExport(any(), any(), any(), any())).thenReturn(ExportResult(objectKey = "pdf/key", zugferdXmlKey = "xml/key", kositSuccess = false, kositReport = "", sha256 = "abc"))
        val sut = sut(facturXEngine = engine, exportCompletionStage = completion)
        val base = minimalPdfBytes()

        val result = sut.convertToFacturXPdf(request(), byteArrayOf(1), base, exportStages())

        assertTrue(result is FacturXPdfConversionResult.ShortCircuit)
        assertTrue((result as FacturXPdfConversionResult.ShortCircuit).outcome is FacturXExportOutcome.Success)
    }

    // ── completeRejectedFacturXExport ────────────────────────────────────────────────

    @Test
    fun `completeRejectedFacturXExport reports the rejection as an INVALID FACTURX stage`() {
        val completion: ExportCompletionStage = mock()
        val captor = org.mockito.kotlin.argumentCaptor<ExportStages>()
        whenever(completion.completeExport(any(), any(), captor.capture(), any())).thenReturn(ExportResult(objectKey = "pdf/key", zugferdXmlKey = "xml/key", kositSuccess = false, kositReport = "", sha256 = "abc"))
        val sut = sut(exportCompletionStage = completion)
        val findings = listOf(rejectedFinding("FACTURX-ENGINE-REJECTED"))

        val outcome = sut.completeRejectedFacturXExport(
            request(), findings, ExportArtifacts.facturX(byteArrayOf(1), "xml/key", minimalPdfBytes(), "CII D16B"),
            exportStages(),
        )

        assertTrue(outcome is FacturXExportOutcome.Success)
        assertEquals(KositOutcome.INVALID, captor.firstValue.engine?.outcome)
        assertEquals(findings, captor.firstValue.engine?.findings)
    }

    private fun exportStages() = ExportStages(
        syntax = de.littletrotter.shared.ValidationStageResult("SYNTAX", KositOutcome.VALID),
        schematron = de.littletrotter.shared.ValidationStageResult("FACTURX_SCHEMATRON", KositOutcome.VALID),
        kosit = de.littletrotter.shared.ValidationStageResult("KOSIT", KositOutcome.VALID),
    )
}
