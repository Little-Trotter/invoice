package de.littletrotter.xpdf.impl

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

    // ── the asynchronous veraPDF stage ──────────────────────────────────
    // The worker only states the stage as the document is stored: pending when requested, skipped
    // otherwise. The verdict arrives later from the conformance service and replaces it.

    private fun veraPdfStageOf(jobId: String, skipValidation: Boolean, veraPdfCheck: Boolean): Pair<de.littletrotter.shared.ValidationStageResult, KositReportPayload> {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())
        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())
        val service = makeService(renderer, makeKositPort(true, "ok"), store)

        val result = service.performExport(makeInvoice(), jobId, llmOptimize = false, skipValidation = skipValidation, veraPdfCheck = veraPdfCheck)

        val payload = KositReportPayload.decode(result.kositReport)!!
        return payload.validationRun!!.stages.first { it.stage == "VERAPDF" } to payload
    }

    @Test
    fun `veraPdfCheck false leaves the VERAPDF stage skipped as not enabled`() {
        val (stage, payload) = veraPdfStageOf("job-verapdf-off", skipValidation = false, veraPdfCheck = false)

        assertTrue(stage.skipped)
        assertEquals("nicht aktiviert", stage.skipReason)
        assertFalse(stage.pending)
        assertTrue(stage.advisory)
        assertFalse(payload.validationRun!!.validatorVersions.containsKey("verapdf"))
    }

    @Test
    fun `veraPdfCheck true leaves the VERAPDF stage pending and advisory`() {
        val (stage, payload) = veraPdfStageOf("job-verapdf-on", skipValidation = false, veraPdfCheck = true)

        assertTrue(stage.pending)
        assertFalse(stage.skipped)
        assertTrue(stage.advisory)
        val withoutVera = payload.validationRun!!.stages.filterNot { it.stage == "VERAPDF" }
        assertEquals(
            de.littletrotter.shared.ValidationRun.overallOutcomeOf(withoutVera),
            payload.validationRun!!.overall,
            "a pending stage must not change the verdict the send decision is made on",
        )
    }

    @Test
    fun `skipValidation skips the VERAPDF stage even when veraPdfCheck is true`() {
        val (stage, _) = veraPdfStageOf("job-verapdf-skip", skipValidation = true, veraPdfCheck = true)

        assertTrue(stage.skipped)
        assertEquals("skipValidation", stage.skipReason)
        assertFalse(stage.pending)
    }

    // ── the KoSIT switch as the pipeline sees it ────────────────────────────

    private fun kositStageWith(profile: String, kositCheck: Boolean, kositPort: KositValidatorPort): de.littletrotter.shared.ValidationStageResult {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())
        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn(profile)
        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, kositPort)

        val result = de.littletrotter.xpdf.ExportSwitches.withKositCheck(kositCheck) {
            service.performExport(makeInvoice(), "job-kosit-$profile-$kositCheck")
        }

        return KositReportPayload.decode(result.kositReport)!!.validationRun!!.stages.single { it.stage == "KOSIT" }
    }

    @Test
    fun `KoSIT switched off skips a plain EN16931 export as not enabled and never calls the validator`() {
        val kosit = makeKositPort(true, "ok")

        val stage = kositStageWith("EN16931", kositCheck = false, kositPort = kosit)

        assertTrue(stage.skipped)
        assertEquals("nicht aktiviert", stage.skipReason)
        org.mockito.kotlin.verify(kosit, org.mockito.kotlin.never()).validate(any(), any())
    }

    @Test
    fun `KoSIT switched off still validates XRechnung`() {
        val kosit = makeKositPort(true, "ok")

        val stage = kositStageWith("XRechnung", kositCheck = false, kositPort = kosit)

        assertFalse(stage.skipped)
        org.mockito.kotlin.verify(kosit).validate(any(), any())
    }

    @Test
    fun `KoSIT switched on validates a plain EN16931 export`() {
        val kosit = makeKositPort(true, "ok")

        val stage = kositStageWith("EN16931", kositCheck = true, kositPort = kosit)

        assertFalse(stage.skipped)
        org.mockito.kotlin.verify(kosit).validate(any(), any())
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
            service.performExport(
                invoice, "job-backstop-mustang-over-limit", outputIntent = OutputIntentTestKeys.grantFor(invoice),
            )
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
    // (Mustang). The deliberate invalid-flow artifact never has a bare PDF: it carries
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
        // PO 30.08.2026 — every non-draft, non-cancellation PDF carries the fixed brand mark
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

    // ── ST-5 — the cancellation document (row 1) ────────────────────────────────────────────────

    @Test
    fun `completeExport stamps the cancellation mark when the invoice is a credit note, in the document language`() {
        val germanInvoice = makeInvoice().copy(cancellation = true, meta = InvoiceMetaDto(documentLanguage = "de"))
        val englishInvoice = makeInvoice().copy(cancellation = true, meta = InvoiceMetaDto(documentLanguage = "en"))

        val textDe = stampFor(germanInvoice, jobId = "job-storno-de")
        val textEn = stampFor(englishInvoice, jobId = "job-storno-en")

        assertTrue(textDe.contains("STORNO"), "German storno document must carry STORNO, got: $textDe")
        assertTrue(textEn.contains("REVERSAL"), "English storno document must carry REVERSAL, got: $textEn")

        // PO decision 2026-09-05 — asserted here, at the export level the PO actually sees, and not
        // only on the stamper: this is the layer that decides WHICH mark a document gets, so a
        // future routing change that sent a cancellation document down the brand row would put the link
        // back on it without the stamper's own test ever noticing.
        assertFalse(textDe.contains("github"), "a storno document must never carry the project link, got: $textDe")
        assertFalse(textEn.contains("github"), "a storno document must never carry the project link, got: $textEn")
    }

    // The former "cancelled-original" mark (row 2) is gone: a cancelled original is never
    // regenerated (the single-generation gate), so its archived PDF simply keeps whichever
    // mark it already carried when it was issued. `cancellation wins over cancelled` and the dedicated
    // cancelled-original unbranded-deployment case are gone with it.

    @Test
    fun `cancellation is unconditional - a grant never suppresses it`() {
        val cancellation = makeInvoice().copy(cancellation = true)

        val cancellationText = stampFor(cancellation, unbranded = true, jobId = "job-storno-unbranded")

        assertTrue(cancellationText.contains("STORNO"), "storno mark must survive an unbranded deployment, got: $cancellationText")
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
    // InvoiceDtoMapper, see that module's per-status mapper test) and `false` for ISSUED/CANCELLED.
    // This module never sees the status enum itself (by design, see the class's own
    // applyWatermark KDoc), so the cases below are expressed at the boundary this module owns:
    // the boolean flags, exercised with and without branding — branding never changes
    // rows 1-2's presence, only the brand row's.

    @Test
    fun `RS-2 - stamp matrix - all three rows carry the right mark with and without an output-intent grant`() {
        data class Case(
            val label: String,
            val cancellation: Boolean = false,
            val draftFlag: Boolean = false,
            val unbranded: Boolean,
            val expectCancellationMark: Boolean = false,
            val expectDraftMark: Boolean = false,
            val expectBrandMark: Boolean = false,
        )

        val cases = listOf(
            Case("storno/no-grant", cancellation = true, unbranded = false, expectCancellationMark = true),
            Case("storno/grant", cancellation = true, unbranded = true, expectCancellationMark = true),
            Case("DRAFT/no-grant", draftFlag = true, unbranded = false, expectDraftMark = true),
            Case("DRAFT/grant", draftFlag = true, unbranded = true, expectDraftMark = true),
            Case("ISSUED/no-grant", unbranded = false, expectBrandMark = true),
            Case("ISSUED/grant", unbranded = true),
        )

        cases.forEach { case ->
            val invoice = makeInvoice().copy(
                cancellation = case.cancellation,
                draft = case.draftFlag,
                meta = InvoiceMetaDto(documentLanguage = "en"),
            )

            val text = stampFor(invoice, unbranded = case.unbranded, jobId = "job-matrix-${case.label}")

            assertEquals(case.expectCancellationMark, text.contains("REVERSAL"), "${case.label}: storno-mark presence mismatch, got: $text")
            assertEquals(case.expectDraftMark, text.contains("DRAFT"), "${case.label}: draft-mark presence mismatch, got: $text")
            assertEquals(
                case.expectBrandMark, text.contains("LittleTrotter"),
                "${case.label}: brand-mark presence mismatch, got: $text",
            )
        }
    }
}
