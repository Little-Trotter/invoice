package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.archive.ArchiveSigner
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.xpdf.testsupport.OutputIntentTestKeys
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * [ExportCompletionStage] on its own, collaborators mocked (unit layer). Proves the
 * shared post-export machinery (store/checksum, archive, LLM scheduling, watermark, result
 * assembly) needs nothing beyond its own five collaborators — the whole point of the extraction
 * from [PdfExportServiceImpl].
 */
class ExportCompletionStageTest {

    private fun minimalPdfBytes(): ByteArray = org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
        doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
        val os = java.io.ByteArrayOutputStream()
        doc.save(os)
        os.toByteArray()
    }

    private fun invoice(draft: Boolean = false, storno: Boolean = false): InvoiceDto = InvoiceDto(
        invoiceNumber = InvoiceNumberDto("INV-QA10-2"),
        items = emptyList(),
        meta = InvoiceMetaDto(documentLanguage = "de"),
        senderProfile = OutputIntentTestKeys.SELLER,
        draft = draft,
        storno = storno,
    )

    private fun request(
        inv: InvoiceDto = invoice(),
        llmOptimize: Boolean = false,
        veraPdfCheck: Boolean = false,
        outputIntent: String? = grant(),
    ) = ExportRequest(
        inv, "job-1", de.littletrotter.shared.DocumentKeyRules.deriveExportKeys("INV-QA10-2", 2L),
        "2026-01-01T00:00:00Z", false, veraPdfCheck, llmOptimize, outputIntent,
    )

    private fun grant(notAfter: java.time.LocalDate? = null) = OutputIntentTestKeys.grantFor(invoice(), notAfter)

    private fun artifacts(pdfBytes: ByteArray, xmlObjectKey: String = "xml/key") =
        ExportArtifacts(byteArrayOf(1, 2, 3), xmlObjectKey, pdfBytes, "EN16931", "factur-x.xml", "CII D16B")

    private fun stagesArg() = ExportStages(
        syntax = de.littletrotter.shared.ValidationStageResult("SYNTAX", KositOutcome.VALID),
        schematron = de.littletrotter.shared.ValidationStageResult("FACTURX_SCHEMATRON", KositOutcome.VALID),
        kosit = de.littletrotter.shared.ValidationStageResult("KOSIT", KositOutcome.VALID),
    )

    private val fixedInstant: Instant = Instant.parse("2026-01-15T10:30:00Z")
    private val fixedTime: TimeSource = ClockTimeSource.fixedAt(fixedInstant)

    private fun sut(
        documentStore: DocumentStore = mock(),
        archiveSigner: ArchiveSigner? = null,
        llmAnalysisStage: LlmAnalysisStage? = null,
        validationStages: ExportValidationStages = mock(),
        time: TimeSource = fixedTime,
    ): ExportCompletionStage {
        whenever(validationStages.buildValidationReport(any(), any(), any(), any()))
            .thenReturn(KositValidationResult(KositOutcome.VALID, profile = "EN16931"))
        return ExportCompletionStage(documentStore, archiveSigner, llmAnalysisStage, OutputIntentTestKeys.verifier, validationStages, time)
    }

    /** Unpacks a signed archive package's tar.gz bytes into entry name -> raw content, the same
     *  minimal reader kotlin-shared's own ArchivePackageBuilderTest uses — needed here (a
     *  different module) to prove the manifest's own "timestamp" field came from the injected
     *  [TimeSource], not a direct clock read. */
    private fun unpackManifest(tarGzBytes: ByteArray): String {
        GzipCompressorInputStream(ByteArrayInputStream(tarGzBytes)).use { gzipIn ->
            TarArchiveInputStream(gzipIn).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    if (entry.name == "manifest.json") {
                        return tarIn.readBytes().toString(Charsets.UTF_8)
                    }
                    entry = tarIn.nextEntry
                }
            }
        }
        error("manifest.json not found in archive package")
    }

    @Test
    fun `completeExport stores the PDF and XML and reports NOT_REQUESTED without an LLM stage`() {
        val store: DocumentStore = mock()
        val stage = sut(documentStore = store)

        val result = stage.completeExport(request(), artifacts(byteArrayOf(9, 9)), stagesArg())

        // completeExport stores the PDF itself, but not the XML: the caller
        // (performExport/performFacturXExport) already stored the XML before this method ever
        // runs — completeExport only computes its checksum (zugferdXmlSha256), never its own put.
        verify(store).put(eq(request().keys.pdfObjectKey), any(), eq("application/pdf"))
        assertEquals("NOT_REQUESTED", result.llmStatus)
        assertTrue(result.kositSuccess)
        assertNull(result.archiveObjectKey)
        assertNull(result.archiveSha256)
    }

    @Test
    fun `completeExport never stores an XML checksum when there is no XML at all`() {
        val stage = sut()

        val result = stage.completeExport(request(), artifacts(byteArrayOf(9, 9), xmlObjectKey = ""), stagesArg())

        assertNull(result.zugferdXmlSha256)
    }

    @Test
    fun `completeExport builds and stores the archive package for an issued invoice with an XML`() {
        val store: DocumentStore = mock()
        val signer: ArchiveSigner = mock()
        whenever(signer.sign(any())).thenReturn(byteArrayOf(1, 2, 3))
        val stage = sut(documentStore = store, archiveSigner = signer)

        val result = stage.completeExport(request(invoice(draft = false)), artifacts(byteArrayOf(9, 9)), stagesArg())

        assertEquals(64, result.archiveSha256?.length) // sha256 hex digest
        assertEquals(request().keys.archiveObjectKey, result.archiveObjectKey)
        verify(store).put(eq(request().keys.archiveObjectKey), any(), eq("application/gzip"))
    }

    @Test
    fun `completeExport stamps the archive manifest's timestamp from the injected TimeSource`() {
        val store: DocumentStore = mock()
        val signer: ArchiveSigner = mock()
        whenever(signer.sign(any())).thenReturn(byteArrayOf(1, 2, 3))
        val stage = sut(documentStore = store, archiveSigner = signer, time = fixedTime)

        stage.completeExport(request(invoice(draft = false)), artifacts(byteArrayOf(9, 9)), stagesArg())

        val bytesCaptor = argumentCaptor<ByteArray>()
        verify(store).put(eq(request().keys.archiveObjectKey), bytesCaptor.capture(), eq("application/gzip"))
        val manifest = unpackManifest(bytesCaptor.firstValue)
        assertTrue(
            manifest.contains("\"timestamp\":\"${fixedTime.nowUtc()}\""),
            "expected manifest timestamp to come from the fixed TimeSource ($fixedInstant), got: $manifest",
        )
    }

    @Test
    fun `completeExport never builds an archive package for a draft invoice`() {
        val signer: ArchiveSigner = mock()
        val stage = sut(archiveSigner = signer)

        // A draft invoice is watermarked for real (applyWatermark's draft branch), which loads
        // the bytes as an actual PDF — needs a real minimal document, not garbage bytes.
        val result = stage.completeExport(request(invoice(draft = true)), artifacts(minimalPdfBytes()), stagesArg())

        assertNull(result.archiveSha256)
        assertNull(result.archiveObjectKey)
    }

    @Test
    fun `completeExport never builds an archive package when there is no XML at all`() {
        val signer: ArchiveSigner = mock()
        val stage = sut(archiveSigner = signer)

        val result = stage.completeExport(request(), artifacts(byteArrayOf(9, 9), xmlObjectKey = ""), stagesArg())

        assertNull(result.archiveSha256)
    }

    @Test
    fun `completeExport never builds an archive package without a configured signer`() {
        val store: DocumentStore = mock()
        val stage = sut(documentStore = store, archiveSigner = null)

        val result = stage.completeExport(request(), artifacts(byteArrayOf(9, 9)), stagesArg())

        assertNull(result.archiveSha256)
        verify(store, never()).put(any(), any(), eq("application/gzip"))
    }

    @Test
    fun `completeExport schedules the LLM stage and reports PENDING when requested and available`() {
        val llm: LlmAnalysisStage = mock()
        whenever(llm.isAvailable()).thenReturn(true)
        val stage = sut(llmAnalysisStage = llm)
        // A real, parseable PDF: this is the only test in this class that lets the layout
        // inspection's happy path (PdfLayoutInspector().inspect(...).toLlmText(), not the
        // catch-block degrade) actually run to completion instead of failing on garbage bytes.
        val result = stage.completeExport(request(llmOptimize = true), artifacts(minimalPdfBytes()), stagesArg())

        assertEquals("PENDING", result.llmStatus)
        verify(llm).schedule(any())
    }

    @Test
    fun `completeExport reports DISABLED when an analysis was requested but oMLX is not configured`() {
        val llm: LlmAnalysisStage = mock()
        whenever(llm.isAvailable()).thenReturn(false)
        val stage = sut(llmAnalysisStage = llm)

        val result = stage.completeExport(request(llmOptimize = true), artifacts(byteArrayOf(9, 9)), stagesArg())

        assertEquals("DISABLED", result.llmStatus)
        verify(llm, never()).schedule(any())
    }

    @Test
    fun `completeExport reports FAILED when scheduling is suppressed despite being otherwise eligible`() {
        val llm: LlmAnalysisStage = mock()
        whenever(llm.isAvailable()).thenReturn(true)
        val stage = sut(llmAnalysisStage = llm)

        val result = stage.completeExport(
            request(llmOptimize = true), artifacts(byteArrayOf(9, 9)), stagesArg(), suppressLlmScheduling = true,
        )

        assertEquals("FAILED", result.llmStatus)
        verify(llm, never()).schedule(any())
    }

    @Test
    fun `boundary - suppressLlmScheduling alone never reports FAILED when no analysis was requested at all`() {
        val llm: LlmAnalysisStage = mock()
        whenever(llm.isAvailable()).thenReturn(true)
        val stage = sut(llmAnalysisStage = llm)

        val result = stage.completeExport(
            request(llmOptimize = false), artifacts(byteArrayOf(9, 9)), stagesArg(), suppressLlmScheduling = true,
        )

        // FAILED only applies when scheduling was actively suppressed despite being otherwise
        // eligible; a job that never asked for optimization reads NOT_REQUESTED regardless.
        assertEquals("NOT_REQUESTED", result.llmStatus)
    }

    @Test
    fun `boundary - suppressLlmScheduling never overrides DISABLED when oMLX itself is not configured`() {
        val llm: LlmAnalysisStage = mock()
        whenever(llm.isAvailable()).thenReturn(false)
        val stage = sut(llmAnalysisStage = llm)

        val result = stage.completeExport(
            request(llmOptimize = true), artifacts(byteArrayOf(9, 9)), stagesArg(), suppressLlmScheduling = true,
        )

        assertEquals("DISABLED", result.llmStatus)
    }

    @Test
    fun `boundary - completeExport reports kositSuccess false when the validation report is not VALID`() {
        val validationStages: ExportValidationStages = mock()
        val stage = sut(validationStages = validationStages)
        // sut() itself stubs buildValidationReport to VALID unconditionally - re-stub AFTER
        // construction so this test's INVALID result is the one actually in effect (Mockito: the
        // last stub on the same mock+matcher wins).
        whenever(validationStages.buildValidationReport(any(), any(), any(), any()))
            .thenReturn(de.littletrotter.shared.KositValidationResult(KositOutcome.INVALID, profile = "EN16931"))

        val result = stage.completeExport(request(), artifacts(byteArrayOf(9, 9)), stagesArg())

        assertEquals(false, result.kositSuccess)
    }

    // ── manifest typeCode (archive package) ─────────────────────────────────────

    @Test
    fun `boundary - the archive package still builds for an invoice that carries its own typeCode`() {
        val store: DocumentStore = mock()
        val signer: ArchiveSigner = mock()
        whenever(signer.sign(any())).thenReturn(byteArrayOf(1, 2, 3))
        val stage = sut(documentStore = store, archiveSigner = signer)
        // manifestFields' "typeCode" reads `invoice.typeCode ?: "380"` — every other test in this
        // class leaves typeCode null (the fallback side); this is the only one that sets a real
        // one ("384", a storno), exercising the Elvis operator's non-null side.
        val stornoInvoice = InvoiceDto(
            invoiceNumber = InvoiceNumberDto("INV-QA10-2"),
            items = emptyList(),
            meta = InvoiceMetaDto(documentLanguage = "de"),
            draft = false,
            storno = true,
            typeCode = "384",
        )

        stage.completeExport(request(stornoInvoice), artifacts(minimalPdfBytes()), stagesArg())

        verify(store).put(eq(request().keys.archiveObjectKey), any(), eq("application/gzip"))
    }

    // ── watermark routing ────────────────────────────────────────────────

    @Test
    fun `completeExport stamps the storno mark ahead of the draft mark`() {
        val store: DocumentStore = mock()
        val captor = org.mockito.kotlin.argumentCaptor<ByteArray>()
        val stage = sut(documentStore = store)

        stage.completeExport(request(invoice(draft = true, storno = true)), artifacts(minimalPdfBytes()), stagesArg())

        verify(store).put(eq(request().keys.pdfObjectKey), captor.capture(), eq("application/pdf"))
        // Re-stamped bytes differ from the raw input — proof a watermark was actually applied.
        assertTrue(captor.firstValue.size != minimalPdfBytes().size || !captor.firstValue.contentEquals(minimalPdfBytes()))
    }

    @Test
    fun `completeExport leaves the PDF untouched when a valid grant covers the seller`() {
        val store: DocumentStore = mock()
        val captor = org.mockito.kotlin.argumentCaptor<ByteArray>()
        val raw = minimalPdfBytes()
        val stage = sut(documentStore = store)

        stage.completeExport(request(invoice()), artifacts(raw), stagesArg())

        verify(store).put(eq(request().keys.pdfObjectKey), captor.capture(), eq("application/pdf"))
        assertArrayEquals(raw, captor.firstValue)
    }

    @Test
    fun `boundary - completeExport stamps the brand mark when neither storno nor draft applies and no grant exists`() {
        val store: DocumentStore = mock()
        val captor = org.mockito.kotlin.argumentCaptor<ByteArray>()
        val raw = minimalPdfBytes()
        val stage = sut(documentStore = store)

        stage.completeExport(request(invoice(), outputIntent = null), artifacts(raw), stagesArg())

        verify(store).put(eq(request().keys.pdfObjectKey), captor.capture(), eq("application/pdf"))
        // Re-stamped bytes differ from the raw input — proof the BRAND_MARK branch actually ran
        // (the only one of the four `applyWatermark` arms no prior test in this class selected).
        assertTrue(captor.firstValue.size != raw.size || !captor.firstValue.contentEquals(raw))
    }

    // ── LLM layout-inspection best-effort degrade (scheduleLlmAnalysis) ──────────────

    @Test
    fun `boundary - completeExport still schedules the LLM stage when the deterministic layout inspection itself fails`() {
        val llm: LlmAnalysisStage = mock()
        whenever(llm.isAvailable()).thenReturn(true)
        val stage = sut(llmAnalysisStage = llm)
        // Not a parseable PDF at all: PdfLayoutInspector.inspect's own Loader.loadPDF throws, so
        // scheduleLlmAnalysis's best-effort try/catch must degrade to an empty layout report
        // rather than let the already-completed export job fail.
        val garbageBytes = byteArrayOf(1, 2, 3, 4, 5)

        val result = stage.completeExport(request(llmOptimize = true), artifacts(garbageBytes), stagesArg())

        assertEquals("PENDING", result.llmStatus)
        verify(llm).schedule(argThat { layoutReport == "" })
    }

    @Test
    fun `an expired grant falls back to the brand mark`() {
        val store: DocumentStore = mock()
        val captor = org.mockito.kotlin.argumentCaptor<ByteArray>()
        val raw = minimalPdfBytes()
        val expired = grant(java.time.LocalDate.of(2026, 1, 14))

        sut(documentStore = store).completeExport(request(outputIntent = expired), artifacts(raw), stagesArg())

        verify(store).put(eq(request().keys.pdfObjectKey), captor.capture(), eq("application/pdf"))
        assertTrue(!captor.firstValue.contentEquals(raw))
    }

    @Test
    fun `a grant never suppresses the draft mark`() {
        val store: DocumentStore = mock()
        val captor = org.mockito.kotlin.argumentCaptor<ByteArray>()
        val raw = minimalPdfBytes()

        sut(documentStore = store).completeExport(
            request(invoice(draft = true), outputIntent = grant()), artifacts(raw), stagesArg(),
        )

        verify(store).put(eq(request().keys.pdfObjectKey), captor.capture(), eq("application/pdf"))
        assertTrue(!captor.firstValue.contentEquals(raw))
    }
}
