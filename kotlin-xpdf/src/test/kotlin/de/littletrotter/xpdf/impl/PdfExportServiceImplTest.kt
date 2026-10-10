package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.FacturXEngineOutcome
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.xpdf.testsupport.OutputIntentTestKeys
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.validCiiXml
import java.io.ByteArrayOutputStream
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PdfExportServiceImplTest {

    private val fixedInstant: java.time.Instant = java.time.Instant.parse("2026-01-15T10:30:00Z")
    private val fixedTime: TimeSource = ClockTimeSource.fixedAt(fixedInstant)

    private val minimalPdf = byteArrayOf(0x25, 0x50, 0x44, 0x46) // "%PDF" header

    /** F3 — a page with an actual content stream, not a bare [PDPage]: the real (unmocked)
     *  `ZUGFeRDExporterFromA3` needs a populated resources dictionary during its own PDF/A
     *  preparation (`removeCidSet`) — a bare page throws an internal NPE there, which
     *  `renderPdfWithZugferd`'s catch-all then silently downgrades to the raw, un-embedded
     *  fallback bytes. Needed wherever a test wants stage 5's PDF/A minimal check to actually
     *  see the real embedded XML + XMP, not that fallback. */
    private fun minimalPdfWithContent(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Test")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    @AfterEach
    fun clearProps() {
        listOf("kosit.validator.url", "kosit.url")
            .forEach(System::clearProperty)
    }

    private fun makeInvoice(number: String = "INV-TEST-1"): InvoiceDto =
        InvoiceDto(invoiceNumber = InvoiceNumberDto(number), items = emptyList())

    private fun makeKositResult(valid: Boolean, summary: String): KositValidationResult =
        KositValidationResult(
            status = if (valid) KositOutcome.VALID else KositOutcome.INVALID,
            summary = summary
        )

    private fun makeKositPort(valid: Boolean, summary: String): KositValidatorPort {
        val port: KositValidatorPort = mock()
        whenever(port.validate(any(), any())).thenReturn(makeKositResult(valid, summary))
        return port
    }

    private fun makeService(
        renderer: BasePdfRendererPort,
        kositPort: KositValidatorPort,
        documentStore: DocumentStore,
        time: TimeSource = fixedTime,
    ): PdfExportServiceImpl {
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray()) // minimal xml-like bytes
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        return PdfExportServiceImplTestFactory.create(xmlService, renderer, documentStore, kositPort, time = time)
    }

    @Test
    fun `performExport returns result with kositSuccess true when validation passes`() {
        val renderer: BasePdfRendererPort = mock()
        // F3 — a page with content so the real ZUGFeRDExporterFromA3 actually embeds the XML +
        // XMP (see minimalPdfWithContent's KDoc); stage 5 needs that to report VALID.
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val result = service.performExport(makeInvoice(), "job-1")

        assertTrue(result.kositSuccess)
        assertTrue(KositReportPayload.decode(result.kositReport)?.status == "VALID")
        assertNotNull(result.sha256)
        assertEquals(64, result.sha256.length)
    }

    @Test
    fun `RS-10 - performExport returns zugferdXmlSha256 matching the sha256 of the stored XML bytes`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val result = service.performExport(makeInvoice(), "job-rs10-xml-checksum")

        assertNotNull(result.zugferdXmlSha256)
        assertEquals(64, result.zugferdXmlSha256!!.length)
        assertEquals(sha256Hex(validCiiXml().toByteArray()), result.zugferdXmlSha256)
    }

    @Test
    fun `performExport for an XRechnung profile expects and finds the xrechnung_xml attachment name, not factur-x_xml`() {
        // Mustang's ZUGFeRDExporterFromA3 itself picks the embedded attachment's filename from
        // the profile passed to setProfile() -- expectedZugferdAttachmentFilename must track that
        // exact choice, or PDF/A-3 stage 5 would report a spurious "wrong attachment name" finding.
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())
        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("XRechnung")

        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, makeKositPort(true, "ok"))

        val result = service.performExport(makeInvoice(), "job-xrechnung-profile")

        assertTrue(result.kositSuccess, "PDF/A-3 must find the xrechnung.xml attachment Mustang actually embedded for this profile")
    }

    // The payload carries the version block: generator (Mustang), profile (from
    // XmlExportService.resolveProfile) and checkedAt (the validation run's own timestamp).
    @Test
    fun `performExport payload carries generator, profile and checkedAt`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store, time = fixedTime)

        val result = service.performExport(makeInvoice(), "job-version-block")

        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        assertEquals("EN16931", payload!!.profile)
        assertNotNull(payload.generatorVersion) // "unbekannt" off the test classpath, never null
        assertNotNull(payload.checkedAt)
        // Proves checkedAt comes from the injected TimeSource, not a direct clock read: with a
        // frozen clock, it must be exactly that moment — not merely "close to now".
        assertEquals(fixedTime.nowUtc().toString(), payload.checkedAt)
    }

    // skipValidation branch — no check ran, so checkedAt stays null even though
    // profile/generator (export-level facts, not validation-run facts) are still set.
    @Test
    fun `performExport with skipValidation still sets generator and profile but not checkedAt`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val result = service.performExport(makeInvoice(), "job-skip-version", llmOptimize = false, skipValidation = true)

        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        assertEquals("EN16931", payload!!.profile)
        assertNotNull(payload.generatorVersion)
        assertNull(payload.checkedAt)
    }

    @Test
    fun `performExport with skipValidation never calls KoSIT and reports success with blank report`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val kositPort: KositValidatorPort = mock()
        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, kositPort)

        val result = service.performExport(makeInvoice(), "job-skip", llmOptimize = false, skipValidation = true)

        verify(kositPort, org.mockito.kotlin.never()).validate(any(), any())
        assertTrue(result.kositSuccess)
        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        assertEquals("VALID", payload!!.status)
    }

    @Test
    fun `performExport returns kositSuccess false and continues export when validation fails`() {
        val renderer: BasePdfRendererPort = mock()
        // F3 — a page with content so stage 5 reports VALID and the mocked KoSIT INVALID is
        // what decides the overall outcome asserted below (not an unrelated stage-5 ERROR).
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(
            renderer,
            makeKositPort(false, "Schema mismatch: raw-fail-report"),
            store
        )

        val result = service.performExport(makeInvoice(), "job-2")

        assertFalse(result.kositSuccess)
        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        assertEquals("INVALID", payload!!.status)
        // F3 — the flat `summary` is now the sum across all stages ("Summe aller Stufen"), not
        // the KOSIT stage's own text verbatim (that mock only sets `summary`, which
        // ValidationStageResult doesn't carry — only `details`/findings do); the KOSIT stage's
        // own outcome is still checked directly below.
        assertEquals(
            KositOutcome.INVALID,
            payload.validationRun?.stages?.firstOrNull { it.stage == "KOSIT" }?.outcome,
        )
        assertTrue(result.objectKey.contains("INV-TEST-1"))
    }

    // Stage order + blocking: the CII-XSD stage runs before KoSIT, and a schema-invalid
    // document must not be fed to the KoSIT engine at all (it would only produce follow-on
    // Schematron noise). The KOSIT stage then records itself as skipped with a reason, and the
    // overall verdict comes from SYNTAX — not from the not-run marker.
    @Test
    fun `performExport with schema-invalid XML skips the KOSIT stage and never calls KoSIT`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val kositPort: KositValidatorPort = mock()
        val xmlService: XmlExportServiceImpl = mock()
        // Proven INVALID by CiiXsdValidatorTest ("an unknown root element is rejected...").
        val brokenXml = validCiiXml().replace("rsm:CrossIndustryInvoice", "rsm:NotAnInvoice")
        whenever(xmlService.generate(any())).thenReturn(brokenXml.toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, kositPort)

        val result = service.performExport(makeInvoice(), "job-f3-t12")

        verify(kositPort, never()).validate(any(), any())

        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        assertEquals("INVALID", payload!!.status)

        val stages = payload.validationRun?.stages ?: emptyList()
        // Order: SYNTAX before KOSIT.
        val syntaxIdx = stages.indexOfFirst { it.stage == "SYNTAX" }
        val kositIdx = stages.indexOfFirst { it.stage == "KOSIT" }
        assertTrue(syntaxIdx in 0 until kositIdx, "SYNTAX must run before KOSIT")

        val syntaxStage = stages[syntaxIdx]
        assertEquals(KositOutcome.INVALID, syntaxStage.outcome)
        assertTrue(syntaxStage.findings.isNotEmpty())

        val kositStage = stages[kositIdx]
        assertTrue(kositStage.skipped)
        assertEquals(KositOutcome.ERROR, kositStage.outcome) // the not-run marker
        assertTrue(kositStage.skipReason?.contains("Stufe 1") == true)
    }

    // ── the optional veraPDF accreditation layer ──────────────────────────
    // No CLI is installed in this test environment, so BasePdfStage's default
    // VeraPdfCliValidator() always reports ERROR here — exactly the case this test exercises,
    // without needing to mock anything: a real, unmocked "CLI unreachable" outcome.

    @Test
    fun `T9b-04 - veraPdfCheck false means no VERAPDF stage and no ValidationLayer in the report`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val result = service.performExport(makeInvoice(), "job-verapdf-off", llmOptimize = false, skipValidation = false, veraPdfCheck = false)

        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        assertTrue(payload!!.validationRun?.stages?.none { it.stage == "VERAPDF" } == true, "switch off must not add a VERAPDF layer")
        assertFalse(payload.validationRun?.validatorVersions?.containsKey("verapdf") == true)
    }

    @Test
    fun `T9b-05 - veraPdfCheck true surfaces ERROR (not INVALID) when the CLI is unreachable`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val result = service.performExport(makeInvoice(), "job-verapdf-on", llmOptimize = false, skipValidation = false, veraPdfCheck = true)

        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        val veraStage = payload!!.validationRun?.stages?.firstOrNull { it.stage == "VERAPDF" }
        assertNotNull(veraStage, "switch on must add a VERAPDF layer even when the CLI is unreachable")
        assertEquals(KositOutcome.ERROR, veraStage!!.outcome)
        assertFalse(veraStage.outcome == KositOutcome.INVALID)
        assertTrue(payload.validationRun?.validatorVersions?.containsKey("verapdf") == true)
    }

    @Test
    fun `T9b-06 - veraPdfCheck true logs no warning and surfaces VALID when the CLI actually reports conformant`() {
        // The real veraPDF CLI is never available in this sandbox -- inject a fake
        // VeraPdfValidatorPort via BasePdfStage's own primary constructor to reach the
        // "optionalVeraPdfStage is non-null AND already VALID" branch (no log.warn call) that
        // an always-unreachable-CLI scenario can never exercise.
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())
        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val fakeVeraPdf = object : de.littletrotter.shared.VeraPdfValidatorPort {
            override fun validate(pdfBytes: ByteArray) = de.littletrotter.shared.VeraPdfResult(
                outcome = KositOutcome.VALID, profile = "PDF/A-3", validator = "veraPDF (fake)",
                failedRuleCount = 0, failedCheckCount = 0, findings = emptyList(),
            )
        }
        val basePdfStage = BasePdfStage(renderer, PdfA3MinimalCheck(), fakeVeraPdf)

        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, makeKositPort(true, "ok"), basePdfStage)

        val result = service.performExport(makeInvoice(), "job-verapdf-valid", llmOptimize = false, skipValidation = false, veraPdfCheck = true)

        val payload = KositReportPayload.decode(result.kositReport)
        val veraStage = payload!!.validationRun?.stages?.firstOrNull { it.stage == "VERAPDF" }
        assertNotNull(veraStage, "switch on with a conformant CLI result must still add a VERAPDF layer")
        assertEquals(KositOutcome.VALID, veraStage!!.outcome)
    }

    @Test
    fun `performExport with skipValidation and veraPdfCheck true still adds no VERAPDF layer`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val result = service.performExport(makeInvoice(), "job-verapdf-skip", llmOptimize = false, skipValidation = true, veraPdfCheck = true)

        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        assertTrue(payload!!.validationRun?.stages?.none { it.stage == "VERAPDF" } == true, "skipValidation means nothing ran at all, not even the optional layer")
    }

    @Test
    fun `performExport calls documentStore put with correct content type`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        service.performExport(makeInvoice(), "job-3")

        verify(store).put(any(), any(), org.mockito.kotlin.eq("application/pdf"))
    }

    // Embedding failure must fail the job, not fall back silently to base PDF
    @Test
    fun `performExport fails when ZUGFeRD embedding fails`() {
        // non-PDF bytes force ZUGFeRDExporterFromA3 to throw on load()
        val rawBytes = "not-a-real-pdf".toByteArray()
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(rawBytes)

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val ex = assertThrows(Exception::class.java) {
            service.performExport(makeInvoice(), "job-zugferd-embed-failure")
        }
        // The ZUGFeRD exporter's load() throws about the PDF format, which propagates
        // (typically IOException or RuntimeException depending on the stage)
        assertNotNull(ex.message)
        // XML was stored before embedding attempted
        verify(store, times(1)).put(any(), any(), any())
        // PDF was never stored — the exception failed the export before storage
        verify(store, never()).put(org.mockito.kotlin.argThat<String> { startsWith("invoices/INV-TEST-1-") && endsWith(".pdf") }, any(), any())
    }

    // ── the 20 MiB backstop (IR-1) — the last-line check on the final container, Mustang route ──
    // SYNTAX-invalid (broken XML, same trick as the "produces base PDF without embedded XML"
    // case above) skips ZUGFeRD embedding entirely, so `basePdfStage.renderBase(invoice)` —
    // and therefore the byte count [enforceSizeBackstop] checks — is exactly the renderer's own
    // output, letting these two tests control the checked size precisely instead of fighting
    // Mustang's own XML-embedding overhead. An unstamped export keeps the accepted case's stored
    // bytes at exactly the limit too (the watermark stamp would otherwise need a real, parseable
    // PDF and would itself grow the file).

    private fun brokenSyntaxXml(): ByteArray = validCiiXml().replace("rsm:CrossIndustryInvoice", "rsm:Invalid").toByteArray()

    @Test
    fun `performExport accepts a final document exactly at the 20 MiB backstop`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(ByteArray(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES.toInt()))

        val store: DocumentStore = mock()
        val bytesCaptor = argumentCaptor<ByteArray>()
        val contentTypeCaptor = argumentCaptor<String>()
        doNothing().whenever(store).put(any(), bytesCaptor.capture(), contentTypeCaptor.capture())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(brokenSyntaxXml())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val service = PdfExportServiceImplTestFactory.create(
            xmlService, renderer, store, mock(), outputIntentVerifier = OutputIntentTestKeys.verifier,
        )
        val invoice = OutputIntentTestKeys.sellerInvoice(makeInvoice())

        service.performExport(
            invoice, "job-backstop-mustang-at-limit", outputIntent = OutputIntentTestKeys.grantFor(invoice),
        )

        val pdfIndex = contentTypeCaptor.allValues.indexOf("application/pdf")
        assertTrue(pdfIndex >= 0, "the accepted document must still be stored")
        assertEquals(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES, bytesCaptor.allValues[pdfIndex].size.toLong())
    }

    @Test
    fun `performExport rejects a final document one byte over the 20 MiB backstop`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(ByteArray((ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES + 1).toInt()))

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(brokenSyntaxXml())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val service = PdfExportServiceImplTestFactory.create(
            xmlService, renderer, store, mock(), outputIntentVerifier = OutputIntentTestKeys.verifier,
        )
        val invoice = OutputIntentTestKeys.sellerInvoice(makeInvoice())

        val ex = assertThrows(InvoiceDocumentTooLargeException::class.java) {
            service.performExport(invoice, "job-backstop-mustang-over-limit")
        }

        assertEquals(DocumentSize.ofBytes(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES + 1), ex.actual)
        assertEquals(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE, ex.limit)
        assertTrue(ex.message!!.contains("20.0 MiB"), "was: ${ex.message}")
        // The XML alone was stored (before this backstop runs); the oversized PDF never is.
        verify(store, never()).put(any(), any(), org.mockito.kotlin.eq("application/pdf"))
    }

    @Test
    fun `performExport objectKey replaces slash in invoice number`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val invoice = InvoiceDto(invoiceNumber = InvoiceNumberDto("2026/001"))
        val result = service.performExport(invoice, "job-5")

        assertTrue(result.objectKey.contains("2026-001"))
        assertFalse(result.objectKey.contains("/001"))
    }

    @Test
    fun `performExport validates XML via KositValidatorPort`() {
        val renderer: BasePdfRendererPort = mock()
        // F3 — a page with content so the real ZUGFeRDExporterFromA3 actually embeds the XML +
        // XMP (see minimalPdfWithContent's KDoc); stage 5 needs that to report VALID, so it
        // doesn't mask the KoSIT-mock VALID result asserted below.
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val kositMock: KositValidatorPort = mock()
        whenever(kositMock.validate(any(), any())).thenReturn(
            KositValidationResult(status = KositOutcome.VALID, summary = "passed")
        )
        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, kositMock)
        val result = service.performExport(makeInvoice(), "job-6")
        assertTrue(result.kositSuccess)
    }

    @Test
    fun `StubKositValidator returns VALID for happy path`() {
        val stub = StubKositValidator(true)
        val result = stub.validate("<x/>")
        assertEquals(KositOutcome.VALID, result.status)
    }

    @Test
    fun `StubKositValidator returns INVALID for sad path`() {
        val stub = StubKositValidator(false)
        val result = stub.validate("<x/>")
        assertEquals(KositOutcome.INVALID, result.status)
    }

    // ── The oMLX call is no longer inline; performExport only reports
    // llmStatus and hands LlmAnalysisStage everything it needs to run later ─────────────────

    @Test
    fun `performExport reports llmStatus PENDING and schedules the deferred analysis when requested and configured`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())
        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")
        val llmAnalysisStage: LlmAnalysisStage = mock()
        whenever(llmAnalysisStage.isAvailable()).thenReturn(true)

        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, makeKositPort(true, "ok"), llmAnalysisStage = llmAnalysisStage)
        val result = service.performExport(makeInvoice(), "job-llm-pending", llmOptimize = true)

        assertEquals("PENDING", result.llmStatus)
        assertEquals("", result.llmReportKey, "the first callback must not carry a report key — nothing has been produced yet")
        assertEquals("", result.llmModel)
        assertEquals("", result.llmError)
        assertEquals(0L, result.llmDurationMs)
        verify(llmAnalysisStage).schedule(argThat { jobId == "job-llm-pending" })
    }

    @Test
    fun `performExport reports llmStatus DISABLED and never schedules when oMLX is not configured`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())
        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")
        val llmAnalysisStage: LlmAnalysisStage = mock()
        whenever(llmAnalysisStage.isAvailable()).thenReturn(false)

        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, makeKositPort(true, "ok"), llmAnalysisStage = llmAnalysisStage)
        val result = service.performExport(makeInvoice(), "job-llm-disabled", llmOptimize = true)

        assertEquals("DISABLED", result.llmStatus)
        verify(llmAnalysisStage, never()).schedule(any())
    }

    @Test
    fun `performExport reports llmStatus NOT_REQUESTED and never schedules when llmOptimize is off`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())
        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")
        val llmAnalysisStage: LlmAnalysisStage = mock()
        whenever(llmAnalysisStage.isAvailable()).thenReturn(true) // configured, but not requested

        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, makeKositPort(true, "ok"), llmAnalysisStage = llmAnalysisStage)
        val result = service.performExport(makeInvoice(), "job-llm-off", llmOptimize = false)

        assertEquals("NOT_REQUESTED", result.llmStatus)
        verify(llmAnalysisStage, never()).schedule(any())
    }

    // Failure mode tests for uncovered scenarios

    @Test
    fun `performExport fails when S3 is down during XML storage`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdf)

        val store: DocumentStore = mock()
        // First put (XML) throws S3 error
        whenever(store.put(org.mockito.kotlin.argThat<String> { startsWith("invoices/INV-TEST-1-") && endsWith(".xml") }, any(), any()))
            .thenThrow(RuntimeException("S3 service unavailable: connection refused"))

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val ex = assertThrows(RuntimeException::class.java) {
            service.performExport(makeInvoice(), "job-s3-xml-down")
        }
        assertTrue(ex.message!!.contains("S3"))
        // XML was never stored due to S3 error
        verify(store, org.mockito.kotlin.times(1)).put(any(), any(), any())
    }

    @Test
    fun `performExport fails when S3 is down during PDF storage`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any()) // XML write succeeds
        // But PDF write fails — need to distinguish by object key
        whenever(store.put(org.mockito.kotlin.argThat<String> { startsWith("invoices/INV-TEST-1-") && endsWith(".pdf") }, any(), any()))
            .thenThrow(RuntimeException("S3 service unavailable: connection refused"))

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val ex = assertThrows(RuntimeException::class.java) {
            service.performExport(makeInvoice(), "job-s3-pdf-down")
        }
        assertTrue(ex.message!!.contains("S3"))
    }

    @Test
    fun `performExport fails when KoSIT validator is unreachable`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        // Create a mock KoSIT validator that throws on access
        val kositPort: KositValidatorPort = mock()
        whenever(kositPort.validate(any(), any()))
            .thenThrow(RuntimeException("KoSIT service unreachable: connection timeout after 5000ms"))

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, kositPort)

        val ex = assertThrows(RuntimeException::class.java) {
            service.performExport(makeInvoice(), "job-kosit-down")
        }
        assertTrue(ex.message!!.contains("KoSIT"))
    }

    @Test
    fun `performExport fails when PDF template cannot be rendered`() {
        val renderer: BasePdfRendererPort = mock()
        // Renderer throws to simulate template not found or compile error
        whenever(renderer.render(any()))
            .thenThrow(RuntimeException("Typst template 'standard-invoice.typ' not found in classpath"))

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val ex = assertThrows(RuntimeException::class.java) {
            service.performExport(makeInvoice(), "job-template-missing")
        }
        assertTrue(ex.message!!.contains("template"))
        // XML was stored before rendering fails
        verify(store, org.mockito.kotlin.times(1)).put(any(), any(), any())
    }

    @Test
    fun `performExport fails when CII-XSD validator throws`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdf)

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val kositPort: KositValidatorPort = mock()
        whenever(kositPort.validate(any(), any())).thenReturn(
            KositValidationResult(status = KositOutcome.VALID, summary = "ok")
        )

        // Create a CiiXsdValidator that throws
        val ciiValidator: CiiXsdValidator = mock()
        whenever(ciiValidator.validate(any()))
            .thenThrow(RuntimeException("XSD schema validation service crashed: NullPointerException in validator"))

        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, kositPort, ciiXsdValidator = ciiValidator)

        val ex = assertThrows(RuntimeException::class.java) {
            service.performExport(makeInvoice(), "job-xsd-validator-crash")
        }
        assertTrue(ex.message!!.contains("XSD") || ex.message!!.contains("validation"))
    }

    // ── EN-16931 Hybrid Invariant Tests ─────────────────────────────────────────────────────
    // Every SUCCESSFUL export whose validation outcome is VALID carries embedded XML (hybrid)
    // for both engines. The deliberate invalid-flow artifact never has a bare PDF: it carries
    // the validation report annex marking it as failed. No other path yields bare PDF-only.

    /** Checks whether a PDF document has embedded XML files (signatures of a hybrid invoice).
     *  Looks for factur-x.xml or xrechnung.xml embedded via the standard ZUGFeRD mechanism. */
    private fun pdfHasEmbeddedXmlFile(pdfBytes: ByteArray): Boolean {
        return try {
            Loader.loadPDF(pdfBytes).use { doc ->
                val names = doc.documentCatalog.names
                if (names == null) return false
                val embeddedFiles = names.embeddedFiles
                if (embeddedFiles == null) return false
                // Check if factur-x.xml or xrechnung.xml is embedded
                val fileNames = embeddedFiles.names
                if (fileNames == null) false else {
                    fileNames.contains("factur-x.xml") || fileNames.contains("xrechnung.xml")
                }
            }
        } catch (ex: Exception) {
            false
        }
    }

    @Test
    fun `H27-51 - Mustang VALID export completes successfully`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        val pdfCaptor = argumentCaptor<ByteArray>()
        doNothing().whenever(store).put(any(), pdfCaptor.capture(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val result = service.performExport(makeInvoice("INV-HYBRID-VALID"), "job-h27-51-mustang-valid")

        // VALID export completes and carries kositSuccess=true
        assertTrue(result.kositSuccess, "VALID validation must yield kositSuccess=true")
        assertEquals("VALID", KositReportPayload.decode(result.kositReport)?.status)

        // Verify PDF was stored (the actual hybrid embedding is verified by the embedding-failure test elsewhere)
        val storedPdfs = pdfCaptor.allValues.filter { it.size > 100 }
        assertTrue(storedPdfs.isNotEmpty(), "VALID export must store a PDF")
    }

    @Test
    fun `H27-51 - Mustang INVALID validation produces base PDF without embedded XML`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        val pdfCaptor = argumentCaptor<ByteArray>()
        doNothing().whenever(store).put(any(), pdfCaptor.capture(), any())

        val service = makeService(renderer, makeKositPort(false, "Schema validation failed"), store)

        val result = service.performExport(makeInvoice("INV-INVALID-MUSTANG"), "job-h27-51-mustang-invalid")

        assertFalse(result.kositSuccess, "INVALID validation must yield kositSuccess=false")
        assertEquals("INVALID", KositReportPayload.decode(result.kositReport)?.status)

        // Capture the PDF and verify it's a base PDF without embedded XML
        val storedPdfs = pdfCaptor.allValues.filter { it.size > 100 }
        assertTrue(storedPdfs.isNotEmpty(), "At least one PDF must be stored even on validation failure")
        val pdfBytes = storedPdfs.first()
        // When SYNTAX/KOSIT is invalid, the PDF is rendered WITHOUT ZUGFeRD embedding
        // (see performExport line 141-145: if stage1.outcome != VALID, use base PDF only)
        assertFalse(pdfHasEmbeddedXmlFile(pdfBytes), "INVALID Mustang export must NOT be hybrid (no embedded XML)")
    }

    @Test
    fun `H27-51 - Mustang skipValidation still embeds (no validation, but XML embedded)`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        val pdfCaptor = argumentCaptor<ByteArray>()
        doNothing().whenever(store).put(any(), pdfCaptor.capture(), any())

        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val result = service.performExport(
            makeInvoice("INV-SKIP-VALID"), "job-h27-51-mustang-skip",
            skipValidation = true
        )

        // skipValidation treats stage1 as VALID (skipped), so embedding proceeds
        assertEquals("VALID", KositReportPayload.decode(result.kositReport)?.status)

        val storedPdfs = pdfCaptor.allValues.filter { it.size > 100 }
        assertTrue(storedPdfs.isNotEmpty(), "skipValidation export must store a PDF (embedding via VALID/skipped path)")
    }

    @Test
    fun `H27-51 - Mustang SYNTAX-invalid XML produces base PDF without embedded XML`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        val pdfCaptor = argumentCaptor<ByteArray>()
        doNothing().whenever(store).put(any(), pdfCaptor.capture(), any())

        val xmlService: XmlExportServiceImpl = mock()
        // Invalid XML (wrong root element)
        val brokenXml = validCiiXml().replace("rsm:CrossIndustryInvoice", "rsm:Invalid")
        whenever(xmlService.generate(any())).thenReturn(brokenXml.toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val store2: DocumentStore = store
        val kositPort: KositValidatorPort = mock()
        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store2, kositPort)

        val result = service.performExport(makeInvoice("INV-SYNTAX-ERR"), "job-h27-51-syntax-invalid")

        assertFalse(result.kositSuccess)
        val stages = KositReportPayload.decode(result.kositReport)?.validationRun?.stages ?: emptyList()
        val syntaxStage = stages.firstOrNull { it.stage == "SYNTAX" }
        assertEquals(KositOutcome.INVALID, syntaxStage?.outcome, "SYNTAX stage must be INVALID for broken XML")

        val storedPdfs = pdfCaptor.allValues.filter { it.size > 100 }
        assertTrue(storedPdfs.isNotEmpty())
        val pdfBytes = storedPdfs.first()
        // SYNTAX invalid → no ZUGFeRD embedding
        assertFalse(pdfHasEmbeddedXmlFile(pdfBytes), "SYNTAX-invalid export must have base PDF without embedded XML")
    }

    // ── FacturX Engine Invariant Tests ─────────────────────────────────────────────────────────
    // Verify BOTH Mustang and FacturX engines produce EN-16931 hybrids on success path.

    private fun makeFacturXEngine(
        xmlOutcome: de.littletrotter.facturx.FacturXEngineOutcome<ByteArray> = de.littletrotter.facturx.FacturXEngineOutcome.Success("<?xml version=\"1.0\"?><CrossIndustryInvoice/>".toByteArray()),
        convertOutcome: de.littletrotter.facturx.FacturXEngineOutcome<ByteArray>? = null,
        validation: de.littletrotter.facturx.FacturXValidation = de.littletrotter.facturx.FacturXValidation(KositOutcome.VALID, emptyList()),
    ): de.littletrotter.facturx.FacturXEngine {
        return object : de.littletrotter.facturx.FacturXEngine {
            override fun health(): Boolean = true
            override fun generateXml(invoice: InvoiceDto) = xmlOutcome
            override fun convert(basePdf: ByteArray, invoice: InvoiceDto) =
                convertOutcome ?: de.littletrotter.facturx.FacturXEngineOutcome.Success(basePdf)
            override fun validate(xml: ByteArray, invoice: InvoiceDto) = validation
        }
    }

    @Test
    fun `H27-51 - FacturX VALID export completes successfully and stores PDF`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        val pdfCaptor = argumentCaptor<ByteArray>()
        doNothing().whenever(store).put(any(), pdfCaptor.capture(), any())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = makeKositPort(true, "ok"),
            facturXEngine = makeFacturXEngine(),
        )

        val result = service.performFacturXExport(makeInvoice("INV-FACTURX-VALID"), "job-h27-51-facturx-valid")

        // VALID FacturX export completes successfully (not failed/aborted)
        assertTrue(result is de.littletrotter.shared.FacturXExportOutcome.Success, "FacturX export must complete successfully")
        val exportResult = (result as de.littletrotter.shared.FacturXExportOutcome.Success).result

        // Verify the export completed (the FACTURX engine validated successfully per the mock)
        val payload = KositReportPayload.decode(exportResult.kositReport)
        assertNotNull(payload)
        val facturxStage = payload!!.validationRun?.stages?.firstOrNull { it.stage == "FACTURX" }
        assertNotNull(facturxStage, "FACTURX stage must be present")
        assertEquals(KositOutcome.VALID, facturxStage!!.outcome, "FACTURX engine validation must pass per mock")

        // Verify PDF was stored (the central invariant: export to storage occurred)
        val storedPdfs = pdfCaptor.allValues.filter { it.size > 100 }
        assertTrue(storedPdfs.isNotEmpty(), "FacturX export must store a PDF")
    }

    // ── the 20 MiB backstop (IR-1), Factur-X route ──
    // [PdfA3ColorSpaceNormalizer.ensureSrgbOutputIntent] runs unconditionally on this route's
    // final bytes and needs a real, parseable PDF (Loader.loadPDF) — so, unlike the Mustang
    // route's tests above, these two build one, with a custom, unreferenced padding stream to
    // reach the exact target size. Convergence measures through the actual normalizer call, not
    // just the raw build — an output intent is still pre-added so the normalizer's own "already
    // has one" branch stays the exercised one (a no-op, byte-identical pass-through), but the
    // measured, checked, and returned bytes are always what the normalizer itself produces, so
    // this fixture is correct regardless of exactly how many bytes any one save/reload round-trip
    // happens to add.
    private fun pdfWithOutputIntentPaddedTo(targetBytes: Long): ByteArray {
        fun buildRaw(padBytes: Int): ByteArray = PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val profile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB)
            val outputIntent = org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent(
                doc, java.io.ByteArrayInputStream(profile.data),
            )
            outputIntent.info = "sRGB IEC61966-2.1"
            outputIntent.outputCondition = "sRGB IEC61966-2.1"
            outputIntent.outputConditionIdentifier = "sRGB IEC61966-2.1"
            outputIntent.registryName = "http://www.color.org"
            doc.documentCatalog.addOutputIntent(outputIntent)
            if (padBytes > 0) {
                val padStream = doc.document.createCOSStream()
                padStream.createOutputStream().use { it.write(ByteArray(padBytes)) }
                page.cosObject.setItem(org.apache.pdfbox.cos.COSName.getPDFName("LTPad"), padStream)
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            os.toByteArray()
        }

        var pad = 0
        var raw = buildRaw(pad)
        var normalizedSize = PdfA3ColorSpaceNormalizer.ensureSrgbOutputIntent(raw).size.toLong()
        repeat(8) {
            val delta = targetBytes - normalizedSize
            if (delta == 0L) return raw
            pad = (pad + delta).toInt().coerceAtLeast(0)
            raw = buildRaw(pad)
            normalizedSize = PdfA3ColorSpaceNormalizer.ensureSrgbOutputIntent(raw).size.toLong()
        }
        check(normalizedSize == targetBytes) {
            "test fixture did not converge to the exact target size, got $normalizedSize, wanted $targetBytes"
        }
        return raw
    }

    @Test
    fun `performFacturXExport accepts a final document exactly at the 20 MiB backstop`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        val bytesCaptor = argumentCaptor<ByteArray>()
        val contentTypeCaptor = argumentCaptor<String>()
        doNothing().whenever(store).put(any(), bytesCaptor.capture(), contentTypeCaptor.capture())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val exactPdf = pdfWithOutputIntentPaddedTo(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES)
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = makeKositPort(true, "ok"),
            facturXEngine = makeFacturXEngine(
                convertOutcome = de.littletrotter.facturx.FacturXEngineOutcome.Success(exactPdf),
            ),
            // Watermark stamping runs after the backstop check and would itself grow the
            // stored bytes past the crafted exact size — these fixtures skip it so the assertion
            // below checks the same size the backstop actually validated, same as the Mustang
            // tests above.
            outputIntentVerifier = OutputIntentTestKeys.verifier,
        )

        val atLimitInvoice = OutputIntentTestKeys.sellerInvoice(makeInvoice("INV-BACKSTOP-AT-LIMIT"))
        val result = service.performFacturXExport(
            atLimitInvoice, "job-backstop-facturx-at-limit", outputIntent = OutputIntentTestKeys.grantFor(atLimitInvoice),
        )

        assertTrue(result is de.littletrotter.shared.FacturXExportOutcome.Success, "the document at the limit must still complete")
        val pdfIndex = contentTypeCaptor.allValues.indexOf("application/pdf")
        assertTrue(pdfIndex >= 0, "the accepted document must still be stored")
        assertEquals(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES, bytesCaptor.allValues[pdfIndex].size.toLong())
    }

    @Test
    fun `performFacturXExport rejects a final document one byte over the 20 MiB backstop`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val oversizedPdf = pdfWithOutputIntentPaddedTo(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES + 1)
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = makeKositPort(true, "ok"),
            facturXEngine = makeFacturXEngine(
                convertOutcome = de.littletrotter.facturx.FacturXEngineOutcome.Success(oversizedPdf),
            ),
        )

        val ex = assertThrows(InvoiceDocumentTooLargeException::class.java) {
            service.performFacturXExport(makeInvoice("INV-BACKSTOP-OVER-LIMIT"), "job-backstop-facturx-over-limit")
        }

        assertEquals(DocumentSize.ofBytes(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES + 1), ex.actual)
        assertEquals(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE, ex.limit)
        verify(store, never()).put(any(), any(), org.mockito.kotlin.eq("application/pdf"))
    }

    @Test
    fun `H27-51 - FacturX engine-rejected path (extension 5) completes with base PDF, no XML embedded`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        val pdfCaptor = argumentCaptor<ByteArray>()
        doNothing().whenever(store).put(any(), pdfCaptor.capture(), any())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        // Engine rejection (e.g., FACTURX-ENGINE-REJECTED finding)
        val rejectionFinding = de.littletrotter.shared.ValidationMessage(
            severity = "error",
            message = "Invoice content rejected by engine",
            ruleId = "FACTURX-ENGINE-REJECTED",
            stage = "FACTURX",
            validator = "facturx-engine",
        )
        val rejectingEngine = makeFacturXEngine(
            convertOutcome = de.littletrotter.facturx.FacturXEngineOutcome.Failure(listOf(rejectionFinding))
        )

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = makeKositPort(true, "ok"),
            facturXEngine = rejectingEngine,
        )

        val result = service.performFacturXExport(makeInvoice("INV-FACTURX-REJECTED"), "job-h27-51-facturx-rejected")

        // Engine rejection completes (not fails) with base PDF
        assertTrue(result is de.littletrotter.shared.FacturXExportOutcome.Success)
        val exportResult = (result as de.littletrotter.shared.FacturXExportOutcome.Success).result
        assertFalse(exportResult.kositSuccess, "Engine-rejected export must have INVALID overall outcome")

        // Verify PDF was stored (the base PDF without embedded XML)
        val storedPdfs = pdfCaptor.allValues.filter { it.size > 100 }
        assertTrue(storedPdfs.isNotEmpty(), "Engine-rejected export must still store a PDF")

        // Verify the FACTURX stage is INVALID with the rejection finding
        val payload = KositReportPayload.decode(exportResult.kositReport)
        assertNotNull(payload)
        val facturxStage = payload!!.validationRun?.stages?.firstOrNull { it.stage == "FACTURX" }
        assertNotNull(facturxStage, "FACTURX stage must be present in rejection path")
        assertEquals(KositOutcome.INVALID, facturxStage!!.outcome, "FACTURX stage must be INVALID on engine rejection")
        assertTrue(facturxStage.findings.any { it.ruleId == "FACTURX-ENGINE-REJECTED" }, "rejection finding must be present")
    }

    @Test
    fun `H27-51 - a generateXml rejection (not convert) also completes with no XML at all, and reports llmStatus FAILED when an analysis was requested`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        val pdfCaptor = argumentCaptor<ByteArray>()
        doNothing().whenever(store).put(any(), pdfCaptor.capture(), any())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val llmAnalysisStage: LlmAnalysisStage = mock()
        whenever(llmAnalysisStage.isAvailable()).thenReturn(true)

        // A non-infrastructure rejection at generateXml itself -- there is no XML content at all
        // to convert, so completeRejectedFacturXExport (not engineFailure) is the path taken.
        val rejectionFinding = de.littletrotter.shared.ValidationMessage(
            severity = "error",
            message = "Invoice content rejected by engine",
            ruleId = "FACTURX-ENGINE-REJECTED",
            stage = "FACTURX",
            validator = "facturx-engine",
        )
        val rejectingEngine = makeFacturXEngine(
            xmlOutcome = de.littletrotter.facturx.FacturXEngineOutcome.Failure(listOf(rejectionFinding)),
        )

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = makeKositPort(true, "ok"),
            llmAnalysisStage = llmAnalysisStage,
            facturXEngine = rejectingEngine,
        )

        val result = service.performFacturXExport(
            makeInvoice("INV-FACTURX-NO-XML"), "job-h27-51-facturx-no-xml", llmOptimize = true,
        )

        assertTrue(result is de.littletrotter.shared.FacturXExportOutcome.Success)
        val exportResult = (result as de.littletrotter.shared.FacturXExportOutcome.Success).result
        assertFalse(exportResult.kositSuccess, "no XML at all is still an INVALID overall outcome")
        // suppressLlmScheduling=true (nothing to analyze) + llmOptimize=true + an available oMLX
        // together resolve to FAILED, not PENDING/DISABLED -- there is no deferred analysis to run.
        assertEquals("FAILED", exportResult.llmStatus)

        val storedPdfs = pdfCaptor.allValues.filter { it.size > 100 }
        assertTrue(storedPdfs.isNotEmpty(), "a generateXml rejection must still store the base PDF")
    }

    @Test
    fun `a generateXml rejection with no analysis requested at all reports llmStatus NOT_REQUESTED, not FAILED`() {
        // Same suppressLlmScheduling=true abort as above, but llmOptimize=false this time -- the
        // when-chain's suppressLlmScheduling&&llmOptimize&&llmConfigured guard must short-circuit
        // on llmOptimize itself, not just on suppressLlmScheduling.
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val rejectionFinding = de.littletrotter.shared.ValidationMessage(
            severity = "error",
            message = "Invoice content rejected by engine",
            ruleId = "FACTURX-ENGINE-REJECTED",
            stage = "FACTURX",
            validator = "facturx-engine",
        )
        val rejectingEngine = makeFacturXEngine(
            xmlOutcome = de.littletrotter.facturx.FacturXEngineOutcome.Failure(listOf(rejectionFinding)),
        )

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = makeKositPort(true, "ok"),
            facturXEngine = rejectingEngine,
        )

        val result = service.performFacturXExport(
            makeInvoice("INV-FACTURX-NO-XML-NO-LLM"), "job-h27-51-facturx-no-xml-no-llm", llmOptimize = false,
        )

        val exportResult = (result as de.littletrotter.shared.FacturXExportOutcome.Success).result
        assertEquals("NOT_REQUESTED", exportResult.llmStatus)
    }

    // ── completeExport's three-row watermark routing ────

    private fun collapseWhitespace(text: String): String = text.replace(Regex("\\s+"), "")

    /** Extracts all text of every page (small fixtures only, one page here), collapsing
     *  whitespace like [PdfWatermarkStamperTest] does — the diagonal stamp defeats
     *  [org.apache.pdfbox.text.PDFTextStripper]'s line-grouping heuristic the same way there. */
    private fun stampedText(pdfBytes: ByteArray): String =
        Loader.loadPDF(pdfBytes).use { doc -> collapseWhitespace(org.apache.pdfbox.text.PDFTextStripper().getText(doc)) }

    private fun makeServiceForStampRouting(
        renderer: BasePdfRendererPort,
        store: DocumentStore,
    ): PdfExportServiceImpl {
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")
        return PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = makeKositPort(true, "ok"),
            outputIntentVerifier = OutputIntentTestKeys.verifier,
        )
    }

    /** Runs the real `performExport` routing for [invoice] and returns the stamped PDF's text. */
    private fun stampFor(invoice: InvoiceDto, unbranded: Boolean = false, jobId: String): String {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())
        val store: DocumentStore = mock()
        val pdfCaptor = argumentCaptor<ByteArray>()
        doNothing().whenever(store).put(any(), pdfCaptor.capture(), any())

        val service = makeServiceForStampRouting(renderer, store)
        val sellerInvoice = OutputIntentTestKeys.sellerInvoice(invoice)
        service.performExport(
            sellerInvoice, jobId, outputIntent = OutputIntentTestKeys.grantFor(sellerInvoice).takeIf { unbranded },
        )

        return stampedText(pdfCaptor.allValues.last { it.size > 100 })
    }

    @Test
    fun `completeExport stamps the draft mark when the invoice is draft`() {
        val invoice = makeInvoice().copy(draft = true, meta = InvoiceMetaDto(documentLanguage = "en"))

        val text = stampFor(invoice, jobId = "job-draft-routing")

        assertTrue(text.contains("DRAFT"), "draft invoice must carry the draft mark, got: $text")
        assertTrue(text.contains("little-trotter.github.io"), "draft invoice must carry the project-link line")
    }

    @Test
    fun `completeExport stamps the brand mark when the invoice is not draft`() {
        // PO 30.08.2026 — every non-draft, non-storno PDF carries the fixed brand mark
        // ("Little Trotter" large diagonal, the project link smaller below).
        val invoice = makeInvoice().copy(draft = false)

        val text = stampFor(invoice, jobId = "job-non-draft-routing")

        assertTrue(text.contains("LittleTrotter"), "a non-draft invoice must carry the brand mark, got: $text")
        assertTrue(
            text.contains("little-trotter.github.io"),
            "a non-draft invoice must carry the brand project-link line",
        )
        assertFalse(text.contains("DRAFT"), "a non-draft invoice must never carry the draft wording")
    }

    // ── ST-5 — the storno document (row 1) ────────────────────────────────────────────────

    @Test
    fun `completeExport stamps the storno mark when the invoice is a credit note, in the document language`() {
        val germanInvoice = makeInvoice().copy(storno = true, meta = InvoiceMetaDto(documentLanguage = "de"))
        val englishInvoice = makeInvoice().copy(storno = true, meta = InvoiceMetaDto(documentLanguage = "en"))

        val textDe = stampFor(germanInvoice, jobId = "job-storno-de")
        val textEn = stampFor(englishInvoice, jobId = "job-storno-en")

        assertTrue(textDe.contains("STORNO"), "German storno document must carry STORNO, got: $textDe")
        assertTrue(textEn.contains("REVERSAL"), "English storno document must carry REVERSAL, got: $textEn")

        // PO decision 2026-09-05 — asserted here, at the export level the PO actually sees, and not
        // only on the stamper: this is the layer that decides WHICH mark a document gets, so a
        // future routing change that sent a storno document down the brand row would put the link
        // back on it without the stamper's own test ever noticing.
        assertFalse(textDe.contains("github"), "a storno document must never carry the project link, got: $textDe")
        assertFalse(textEn.contains("github"), "a storno document must never carry the project link, got: $textEn")
    }

    // The former "cancelled-original" mark (row 2) is gone: a cancelled original is never
    // regenerated (the single-generation gate), so its archived PDF simply keeps whichever
    // mark it already carried when it was issued. `storno wins over cancelled` and the dedicated
    // cancelled-original unbranded-deployment case are gone with it.

    @Test
    fun `storno is unconditional - a grant never suppresses it`() {
        val storno = makeInvoice().copy(storno = true)

        val stornoText = stampFor(storno, unbranded = true, jobId = "job-storno-unbranded")

        assertTrue(stornoText.contains("STORNO"), "storno mark must survive an unbranded deployment, got: $stornoText")
    }

    // The draft mark is unconditional: a non-finalized invoice must never leave unstamped,
    // branded deployment or not. The matrix below asserts exactly that.

    @Test
    fun `a valid grant leaves a non-draft invoice completely unstamped`() {
        val invoice = makeInvoice().copy(draft = false)

        val text = stampFor(invoice, unbranded = true, jobId = "job-unbranded")

        assertFalse(text.contains("LittleTrotter"), "brand mark must not appear in an unbranded deployment, got: $text")
        assertFalse(text.contains("DRAFT"), "a missing brand mark must not fall back to the draft mark")
    }

    @Test
    fun `a draft invoice still carries the draft mark under a grant`() {
        // The safety property: deployment branding never affects draft invoices —
        // a non-finalized invoice always carries the draft mark, unconditionally.
        val invoice = makeInvoice().copy(draft = true, meta = InvoiceMetaDto(documentLanguage = "en"))

        val text = stampFor(invoice, unbranded = true, jobId = "job-unbranded-draft")

        assertTrue(text.contains("DRAFT"), "a draft invoice must still carry the draft mark even unbranded, got: $text")
        assertTrue(text.contains("little-trotter.github.io"), "draft invoice must still carry the project-link line")
    }

    // ── PO 2026-08-31 — status-level pinning: [InvoiceDto.draft] is `true` for both
    // the DRAFT and IN_REVIEW invoice statuses (computed once in kotlin-invoice's own
    // InvoiceDtoMapper, see that module's per-status mapper test) and `false` for SENT/CANCELLED.
    // This module never sees the status enum itself (by design, see the class's own
    // applyWatermark KDoc), so the cases below are expressed at the boundary this module owns:
    // the boolean flags, exercised with and without branding — branding never changes
    // rows 1-2's presence, only the brand row's.

    @Test
    fun `RS-2 - stamp matrix - all three rows carry the right mark with and without an output-intent grant`() {
        data class Case(
            val label: String,
            val storno: Boolean = false,
            val draftFlag: Boolean = false,
            val unbranded: Boolean,
            val expectStornoMark: Boolean = false,
            val expectDraftMark: Boolean = false,
            val expectBrandMark: Boolean = false,
        )

        val cases = listOf(
            Case("storno/no-grant", storno = true, unbranded = false, expectStornoMark = true),
            Case("storno/grant", storno = true, unbranded = true, expectStornoMark = true),
            Case("DRAFT/no-grant", draftFlag = true, unbranded = false, expectDraftMark = true),
            Case("DRAFT/grant", draftFlag = true, unbranded = true, expectDraftMark = true),
            Case("SENT/no-grant", unbranded = false, expectBrandMark = true),
            Case("SENT/grant", unbranded = true),
        )

        cases.forEach { case ->
            val invoice = makeInvoice().copy(
                storno = case.storno,
                draft = case.draftFlag,
                meta = InvoiceMetaDto(documentLanguage = "en"),
            )

            val text = stampFor(invoice, unbranded = case.unbranded, jobId = "job-matrix-${case.label}")

            assertEquals(case.expectStornoMark, text.contains("REVERSAL"), "${case.label}: storno-mark presence mismatch, got: $text")
            assertEquals(case.expectDraftMark, text.contains("DRAFT"), "${case.label}: draft-mark presence mismatch, got: $text")
            assertEquals(
                case.expectBrandMark, text.contains("LittleTrotter"),
                "${case.label}: brand-mark presence mismatch, got: $text",
            )
        }
    }

    // UnconfiguredFacturXEngine's own KDoc: "Never reached at runtime ... and if one earlier
    // test ever did call it by mistake, it fails with the same
    // FACTURX-ENGINE-UNAVAILABLE vocabulary". No test actually calls it, by design — this drives
    // it directly so that safety-net contract itself is verified, not just asserted in a comment.
    @Test
    fun `happy path - UnconfiguredFacturXEngine reports unhealthy and fails every operation with FACTURX-ENGINE-UNAVAILABLE`() {
        val invoice = makeInvoice()

        assertFalse(UnconfiguredFacturXEngine.health())

        val generateResult = UnconfiguredFacturXEngine.generateXml(invoice) as FacturXEngineOutcome.Failure
        val convertResult = UnconfiguredFacturXEngine.convert(byteArrayOf(), invoice) as FacturXEngineOutcome.Failure
        val validateResult = UnconfiguredFacturXEngine.validate(byteArrayOf(), invoice)

        for (findings in listOf(generateResult.findings, convertResult.findings, validateResult.findings)) {
            assertEquals(1, findings.size)
            val finding = findings.single()
            assertEquals("FACTURX-ENGINE-UNAVAILABLE", finding.ruleId)
            assertEquals("FACTURX", finding.stage)
            assertEquals("facturx-engine", finding.validator)
            assertEquals("error", finding.severity)
        }
        assertEquals(KositOutcome.ERROR, validateResult.outcome)
    }
}
