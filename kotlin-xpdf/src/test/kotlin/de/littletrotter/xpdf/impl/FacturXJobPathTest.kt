package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.testsupport.FixtureBaseRenderer
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.facturx.FacturXEngine
import de.littletrotter.facturx.FacturXEngineOutcome
import de.littletrotter.facturx.FacturXValidation
import java.io.ByteArrayOutputStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.apache.pdfbox.Loader
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The extraction's proof, unit-level against a
 * fake [FacturXEngine] (no container required, see the addendum's Non-Goal): a `FACTURX` job's
 * stage list has the exact shape the acceptance criteria require (`SYNTAX` skipped with a named reason,
 * `KOSIT` still mandatory, a `FACTURX` stage of its own), the Mustang route is byte-for-byte
 * unaffected by the refactor, and an engine [FacturXEngineOutcome.Failure] never becomes an
 * exception. Mustang and Factur-X assertions live in the same class deliberately
 * (per the addendum's acceptance criteria) so a regression in either route's `SYNTAX` handling is caught by the
 * same run.
 */
class FacturXJobPathTest {

    private fun makeInvoice(number: String = "INV-FACTURX-1"): InvoiceDto =
        InvoiceDto(invoiceNumber = InvoiceNumberDto(number), items = emptyList())

    private fun makeKositPort(valid: Boolean = true): KositValidatorPort {
        val port: KositValidatorPort = mock()
        whenever(port.validate(any(), any())).thenReturn(
            KositValidationResult(status = if (valid) KositOutcome.VALID else KositOutcome.INVALID, summary = "kosit")
        )
        return port
    }

    private fun makeStore(): DocumentStore {
        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())
        return store
    }

    /** Reproduces the engine's own reported defect (PO job 13): DeviceRGB content, no PDF/A
     *  output intent — the shape veraPDF flags under ISO 19005-3:2012 clause 6.2.4.3 tests 2/4. */
    private fun engineOutputWithoutOutputIntent(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.setNonStrokingColor(java.awt.Color(200, 200, 200))
                cs.addRect(10f, 10f, 50f, 50f)
                cs.fill()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private fun makeReportRenderer(): BasePdfRendererPort = FixtureBaseRenderer()

    /** A fake, in-process [FacturXEngine] — no HTTP, no container (see the addendum's
     *  Non-Goal). Every method is independently overridable so a test can pin the exact outcome
     *  it wants to prove without depending on [HttpFacturXEngineClient]/a running engine. */
    private class FakeFacturXEngine(
        private val xmlOutcome: FacturXEngineOutcome<ByteArray> = FacturXEngineOutcome.Success("<cii-d22b/>".toByteArray()),
        private val convertOutcome: FacturXEngineOutcome<ByteArray>? = null,
        private val validation: FacturXValidation = FacturXValidation(KositOutcome.VALID, emptyList()),
    ) : FacturXEngine {
        var convertCalls = 0
            private set

        override fun health(): Boolean = true
        override fun generateXml(invoice: InvoiceDto): FacturXEngineOutcome<ByteArray> = xmlOutcome
        override fun convert(basePdf: ByteArray, invoice: InvoiceDto): FacturXEngineOutcome<ByteArray> {
            convertCalls++
            return convertOutcome ?: FacturXEngineOutcome.Success(basePdf) // pass the base PDF through unchanged by default
        }
        override fun validate(pdf: ByteArray, invoice: InvoiceDto): FacturXValidation = validation
    }

    // ── SYNTAX skipped (Factur-X) vs. fully enforced (Mustang), same class ────────────────────

    @Test
    fun `performFacturXExport reports SYNTAX skipped with a reason naming D22B and D16B`() {
        val store = makeStore()
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
            facturXEngine = FakeFacturXEngine(),
        )

        val outcome = service.performFacturXExport(makeInvoice(), "job-facturx-syntax")

        assertTrue(outcome is FacturXExportOutcome.Success)
        val payload = KositReportPayload.decode((outcome as FacturXExportOutcome.Success).result.kositReport)
        assertNotNull(payload)
        val syntaxStage = payload!!.validationRun!!.stages.first { it.stage == "SYNTAX" }
        assertTrue(syntaxStage.skipped, "SYNTAX must be reported skipped on the Factur-X route")
        assertEquals(KositOutcome.VALID, syntaxStage.outcome)
        assertTrue(syntaxStage.skipReason?.contains("D22B") == true, "reason must name D22B: ${syntaxStage.skipReason}")
        assertTrue(syntaxStage.skipReason?.contains("D16B") == true, "reason must name D16B: ${syntaxStage.skipReason}")
    }

    @Test
    fun `performExport (Mustang) still fully enforces SYNTAX, unaffected by the Factur-X skip`() {
        val store = makeStore()
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(
            de.littletrotter.xpdf.validCiiXml().replace("rsm:CrossIndustryInvoice", "rsm:NotAnInvoice").toByteArray()
        )
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
        )

        val result = service.performExport(makeInvoice(), "job-mustang-syntax")

        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        val syntaxStage = payload!!.validationRun!!.stages.first { it.stage == "SYNTAX" }
        assertFalse(syntaxStage.skipped, "Mustang's SYNTAX stage must never be reported skipped")
        assertEquals(KositOutcome.INVALID, syntaxStage.outcome)
        assertTrue(syntaxStage.findings.isNotEmpty())
    }

    // ── KoSIT still mandatory on the Factur-X route (decision 6) ──────────────────────────────

    @Test
    fun `performFacturXExport still runs KoSIT against the engine's standalone XML`() {
        val store = makeStore()
        val kositPort = makeKositPort(valid = true)
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = kositPort,
            facturXEngine = FakeFacturXEngine(
                xmlOutcome = FacturXEngineOutcome.Success("<cii-standalone/>".toByteArray()),
            ),
        )

        val outcome = service.performFacturXExport(makeInvoice(), "job-facturx-kosit")

        assertTrue(outcome is FacturXExportOutcome.Success)
        verify(kositPort).validate(org.mockito.kotlin.eq("<cii-standalone/>"), any())
        val payload = KositReportPayload.decode((outcome as FacturXExportOutcome.Success).result.kositReport)
        val kositStage = payload!!.validationRun!!.stages.first { it.stage == "KOSIT" }
        assertFalse(kositStage.skipped, "KOSIT stays mandatory on the Factur-X route (decision 6)")
        assertEquals(KositOutcome.VALID, kositStage.outcome)
    }

    // ── The FACTURX stage itself ───────────────────────────────────────────────────────────────

    @Test
    fun `performFacturXExport adds a FACTURX stage carrying the engine's own validate() verdict`() {
        val store = makeStore()
        val engineFinding = ValidationMessage(
            severity = "warning",
            message = "BR-FR-42 not evaluated for this seller country",
            ruleId = "FACTURX-FR-RULES-NOT-RUN",
            stage = "FACTURX",
            validator = "facturx-engine",
        )
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
            facturXEngine = FakeFacturXEngine(
                validation = FacturXValidation(KositOutcome.VALID, listOf(engineFinding)),
            ),
        )

        val outcome = service.performFacturXExport(makeInvoice(), "job-facturx-stage")

        assertTrue(outcome is FacturXExportOutcome.Success)
        val payload = KositReportPayload.decode((outcome as FacturXExportOutcome.Success).result.kositReport)
        val facturxStage = payload!!.validationRun!!.stages.firstOrNull { it.stage == "FACTURX" }
        assertNotNull(facturxStage, "the Factur-X route must add its own FACTURX stage")
        assertEquals(KositOutcome.VALID, facturxStage!!.outcome)
        assertEquals(1, facturxStage.findings.size)
        assertEquals("FACTURX-FR-RULES-NOT-RUN", facturxStage.findings.single().ruleId)
    }

    // ── PO decision (2026-08-25, extension #5) — "Die Factur-X-Meldung darf nie zu Abbrüchen
    // führen": an engine REJECTION (a definitive negative judgment, as opposed to an
    // infrastructure abort) now completes the job with a base PDF + an honest INVALID report,
    // instead of failing it. A genuine infrastructure abort (FACTURX-ENGINE-UNAVAILABLE) still
    // fails the job exactly as before — [isInfrastructureAbort] is what tells the two apart. ────

    @Test
    fun `performFacturXExport completes with the base PDF and an INVALID report when convert is rejected`() {
        val store = makeStore()
        val rejection = ValidationMessage(
            severity = "error",
            message = "Factur-X engine responded 422: urn:facturx:error:not_pdfa3",
            ruleId = "FACTURX-ENGINE-REJECTED",
            stage = "FACTURX",
            validator = "facturx-engine",
        )
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
            facturXEngine = FakeFacturXEngine(
                convertOutcome = FacturXEngineOutcome.Failure(listOf(rejection)),
            ),
        )

        val outcome = service.performFacturXExport(makeInvoice(), "job-facturx-convert-rejected")

        assertTrue(outcome is FacturXExportOutcome.Success, "a rejected convert() must complete the job (PO decision), not fail it")
        val result = (outcome as FacturXExportOutcome.Success).result
        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        assertEquals("INVALID", payload!!.status, "a rejected e-invoice is not a valid one — but the job still completed")

        val stages = payload.validationRun!!.stages
        val facturxStage = stages.first { it.stage == "FACTURX" }
        assertEquals(KositOutcome.INVALID, facturxStage.outcome, "a definitive rejection is INVALID, not ERROR — the engine did give a verdict")
        assertEquals("FACTURX-ENGINE-REJECTED", facturxStage.findings.single().ruleId)

        // SYNTAX/KOSIT genuinely ran (before convert() was even called) and are kept verbatim.
        val syntaxStage = stages.first { it.stage == "SYNTAX" }
        assertTrue(syntaxStage.skipped, "SYNTAX is always reported skipped on the Factur-X route (AP-3 §1)")
        assertTrue(syntaxStage.skipReason?.contains("D22B") == true, "the real SYNTAX result must survive verbatim")
        val kositStage = stages.first { it.stage == "KOSIT" }
        assertFalse(kositStage.skipped, "KOSIT genuinely ran before convert() was even called")
        assertEquals(KositOutcome.VALID, kositStage.outcome)

        // PDFA now genuinely runs against the stored base PDF (PO: "the user should know its
        // conformance") — not skipped, whatever its own verdict turns out to be.
        val pdfaStage = stages.first { it.stage == "PDFA" }
        assertFalse(pdfaStage.skipped, "PDFA must actually run against the base PDF now, not be skipped")

        val businessStage = stages.first { it.stage == "BUSINESS" }
        assertTrue(businessStage.skipped)
        assertEquals("TAX-03/PARTY-01 noch nicht integriert", businessStage.skipReason)

        // the base PDF (no ZUGFeRD embedding) IS stored under the normal object key — no failure,
        // no silent Mustang fallback, and the standalone XML (generated before convert() ran) is
        // stored too, exactly like the success path.
        verify(store).put(org.mockito.kotlin.argThat<String> { startsWith("invoices/INV-FACTURX-1-") && endsWith(".pdf") }, any(), org.mockito.kotlin.eq("application/pdf"))
        verify(store).put(org.mockito.kotlin.argThat<String> { startsWith("invoices/INV-FACTURX-1-") && endsWith(".xml") }, any(), org.mockito.kotlin.eq("application/xml"))

        // The standalone XML was genuinely generated and stored (generateXml succeeded;
        // only convert() was rejected), so its checksum must be present, not null.
        assertNotNull(result.zugferdXmlSha256, "the XML was stored — its checksum must not be null")
        assertEquals(64, result.zugferdXmlSha256!!.length)
    }

    @Test
    fun `performFacturXExport still returns Failed when convert fails with a genuine infrastructure abort`() {
        val store = makeStore()
        val unavailable = ValidationMessage(
            severity = "error",
            message = "Factur-X engine unreachable: ConnectException",
            ruleId = "FACTURX-ENGINE-UNAVAILABLE",
            stage = "FACTURX",
            validator = "facturx-engine",
        )
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
            facturXEngine = FakeFacturXEngine(
                convertOutcome = FacturXEngineOutcome.Failure(listOf(unavailable)),
            ),
        )

        val outcome = service.performFacturXExport(makeInvoice(), "job-facturx-convert-unavailable")

        assertTrue(outcome is FacturXExportOutcome.Failed, "an infrastructure abort must still fail the job — 'never checked' is not 'checked and rejected'")
        verify(store, never()).put(org.mockito.kotlin.argThat<String> { startsWith("invoices/INV-FACTURX-1-") && endsWith(".pdf") }, any(), any())
    }

    @Test
    fun `an infrastructure abort with veraPdfCheck requested still lists VERAPDF among the never-ran stages`() {
        val store = makeStore()
        val unavailable = ValidationMessage(
            severity = "error",
            message = "Factur-X engine unreachable: ConnectException",
            ruleId = "FACTURX-ENGINE-UNAVAILABLE",
            stage = "FACTURX",
            validator = "facturx-engine",
        )
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
            facturXEngine = FakeFacturXEngine(
                convertOutcome = FacturXEngineOutcome.Failure(listOf(unavailable)),
            ),
        )

        val outcome = service.performFacturXExport(makeInvoice(), "job-facturx-convert-unavailable-verapdf", veraPdfCheck = true)

        assertTrue(outcome is FacturXExportOutcome.Failed)
        val payload = KositReportPayload.decode((outcome as FacturXExportOutcome.Failed).reportJson)
        val veraStage = payload!!.validationRun?.stages?.firstOrNull { it.stage == "VERAPDF" }
        assertNotNull(veraStage, "veraPdfCheck=true must list VERAPDF among the never-ran stages, even on an infrastructure abort")
        assertTrue(veraStage!!.skipped)
    }

    @Test
    fun `performFacturXExport completes with the base PDF and no XML when generateXml is rejected (non-infra)`() {
        val store = makeStore()
        val rejection = ValidationMessage(
            severity = "error",
            message = "unclassified tax category",
            ruleId = "FACTURX-MAPPING-FAILED",
            stage = "FACTURX",
            validator = "facturx-engine",
        )
        val engine = FakeFacturXEngine(xmlOutcome = FacturXEngineOutcome.Failure(listOf(rejection)))
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
            facturXEngine = engine,
        )

        val outcome = service.performFacturXExport(makeInvoice(), "job-facturx-xml-rejected")

        assertTrue(outcome is FacturXExportOutcome.Success, "a rejected generateXml() must complete the job (PO decision), not fail it")
        assertEquals(0, engine.convertCalls, "without the standalone XML there is nothing to embed — convert must not run")
        val result = (outcome as FacturXExportOutcome.Success).result
        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        assertEquals("INVALID", payload!!.status)

        val stages = payload.validationRun!!.stages
        val facturxStage = stages.first { it.stage == "FACTURX" }
        assertEquals(KositOutcome.INVALID, facturxStage.outcome)
        assertEquals("FACTURX-MAPPING-FAILED", facturxStage.findings.single().ruleId)

        // No XML exists at all — SYNTAX/KOSIT are honestly reported as never having run, with a
        // reason naming why (not the generic infra-abort text — the job DID complete).
        val syntaxStage = stages.first { it.stage == "SYNTAX" }
        assertTrue(syntaxStage.skipped)
        assertTrue(syntaxStage.skipReason?.contains("kein Factur-X-Inhalt") == true, "was: ${syntaxStage.skipReason}")
        val kositStage = stages.first { it.stage == "KOSIT" }
        assertTrue(kositStage.skipped)
        assertTrue(kositStage.skipReason?.contains("kein Factur-X-Inhalt") == true, "was: ${kositStage.skipReason}")

        val pdfaStage = stages.first { it.stage == "PDFA" }
        assertFalse(pdfaStage.skipped, "PDFA must actually run against the base PDF, even with no XML at all")

        // The base PDF IS stored; no XML artifact exists, so `zugferdXmlKey` must not claim one.
        verify(store).put(org.mockito.kotlin.argThat<String> { startsWith("invoices/INV-FACTURX-1-") && endsWith(".pdf") }, any(), org.mockito.kotlin.eq("application/pdf"))
        verify(store, never()).put(org.mockito.kotlin.argThat<String> { startsWith("invoices/INV-FACTURX-1-") && endsWith(".xml") }, any(), any())
        assertEquals("", result.zugferdXmlKey, "no XML was ever generated — the key must not point at nothing")
        // No XML object key means nothing to hash either; must be null, not a digest of
        // the empty placeholder bytes.
        assertNull(result.zugferdXmlSha256, "no XML exists at all — its checksum must be null, not a hash of nothing")

        // llmStatus (DSC001 reasoning): requested-and-configured but nothing exists for the
        // deferred stage to analyze — FAILED, not a PENDING that will never resolve.
        assertEquals("NOT_REQUESTED", result.llmStatus, "llmOptimize defaults to false in this call")
    }

    @Test
    fun `performFacturXExport reports llmStatus FAILED (not PENDING) when generateXml is rejected and analysis was requested`() {
        val store = makeStore()
        val rejection = ValidationMessage(
            severity = "error", message = "unclassified tax category",
            ruleId = "FACTURX-MAPPING-FAILED", stage = "FACTURX", validator = "facturx-engine",
        )
        val llmAnalysisStage: de.littletrotter.xpdf.impl.LlmAnalysisStage = mock()
        whenever(llmAnalysisStage.isAvailable()).thenReturn(true)
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
            llmAnalysisStage = llmAnalysisStage,
            facturXEngine = FakeFacturXEngine(xmlOutcome = FacturXEngineOutcome.Failure(listOf(rejection))),
        )

        val outcome = service.performFacturXExport(makeInvoice(), "job-facturx-xml-rejected-llm", llmOptimize = true)

        assertTrue(outcome is FacturXExportOutcome.Success)
        val result = (outcome as FacturXExportOutcome.Success).result
        assertEquals("FAILED", result.llmStatus, "requested and configured, but there is no XML for the deferred stage to analyze")
        verify(llmAnalysisStage, never()).schedule(any())
    }

    @Test
    fun `performFacturXExport returns Failed when generateXml itself is rejected due to a genuine infrastructure abort`() {
        val store = makeStore()
        val rejection = ValidationMessage(
            severity = "error",
            message = "Factur-X engine unreachable: ConnectException",
            ruleId = "FACTURX-ENGINE-UNAVAILABLE",
            stage = "FACTURX",
            validator = "facturx-engine",
        )
        val engine = FakeFacturXEngine(xmlOutcome = FacturXEngineOutcome.Failure(listOf(rejection)))
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
            facturXEngine = engine,
        )

        val outcome = service.performFacturXExport(makeInvoice(), "job-facturx-xml-fail")

        assertTrue(outcome is FacturXExportOutcome.Failed, "an infrastructure abort must still fail the job")
        assertEquals(0, engine.convertCalls, "without the standalone XML there is nothing to embed — convert must not run")
    }

    // ── golden/snapshot proof: Mustang's report shape is unchanged by the refactor ────────────

    @Test
    fun `Mustang stage list shape (names, order, keys) is unchanged by the AP-3 extraction`() {
        val store = makeStore()
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(de.littletrotter.xpdf.validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
        )

        val result = service.performExport(makeInvoice(), "job-golden-mustang")

        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        // The exact stage-name sequence this report shape has always had, plus the
        // FACTURX_SCHEMATRON stage between SYNTAX and KOSIT: no VERAPDF (veraPdfCheck defaults
        // false) and no FACTURX (Mustang has no engine-of-its-own stage).
        val stageNames = payload!!.validationRun!!.stages.map { it.stage }
        assertEquals(listOf("SYNTAX", "FACTURX_SCHEMATRON", "KOSIT", "PDFA", "BUSINESS"), stageNames)
        assertEquals("EN16931", payload.profile)
        assertNotNull(payload.checkedAt)
        assertTrue(payload.validationRun!!.validatorVersions.containsKey("xsd"))
        assertTrue(payload.validationRun!!.validatorVersions.containsKey("kosit"))
        assertTrue(payload.validationRun!!.validatorVersions.containsKey("pdfa"))
        // ExportResult's own object keys — unchanged shape a reader depends on.
        assertTrue(result.objectKey.contains("INV-FACTURX-1"))
        assertTrue(result.zugferdXmlKey.contains("INV-FACTURX-1"))
        assertEquals(64, result.sha256.length)
    }

    // ── Found via PO-reported job 13 — the engine's convert() output must be
    // normalized with an sRGB output intent before it is stored, so ISO 19005-3:2012 clause
    // 6.2.4.3 stops firing on DeviceRGB/DeviceGray content the engine emits unmanaged ──────────

    @Test
    fun `performFacturXExport adds an sRGB output intent to the engine's convert output before storing it`() {
        val store = makeStore()
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = store,
            kositValidator = makeKositPort(valid = true),
            facturXEngine = FakeFacturXEngine(
                convertOutcome = de.littletrotter.facturx.FacturXEngineOutcome.Success(
                    engineOutputWithoutOutputIntent(),
                ),
            ),
        )

        val outcome = service.performFacturXExport(makeInvoice(), "job-facturx-colorspace")

        assertTrue(outcome is FacturXExportOutcome.Success)
        val pdfCaptor = argumentCaptor<ByteArray>()
        verify(store).put(org.mockito.kotlin.argThat<String> { startsWith("invoices/INV-FACTURX-1-") && endsWith(".pdf") }, pdfCaptor.capture(), any())
        Loader.loadPDF(pdfCaptor.firstValue).use { doc ->
            assertTrue(
                doc.documentCatalog.outputIntents.isNotEmpty(),
                "the stored Factur-X PDF must carry an output intent even though the engine's own bytes had none",
            )
        }
    }
}
