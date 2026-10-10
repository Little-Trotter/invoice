package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.BrFrCtcValidator
import de.littletrotter.facturx.FacturXEngine
import de.littletrotter.facturx.FacturXSchematronValidator
import de.littletrotter.facturx.FacturXValidation
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.shared.VeraPdfValidatorPort
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * [ExportValidationStages] on its own, collaborators mocked (unit layer). Proves the
 * class needs nothing beyond its own six collaborators — the whole point of the extraction from
 * [PdfExportServiceImpl]. [CiiXsdValidator]/[runSyntaxStage] joined the other
 * five (see that method's own KDoc).
 */
class ExportValidationStagesTest {

    private fun makeInvoice(country: String? = null): InvoiceDto =
        InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-QA10-1"), items = emptyList(), recipient = PartyDto(country = country))

    private val fixedInstant: Instant = Instant.parse("2026-01-15T10:30:00Z")
    private val fixedTime: TimeSource = ClockTimeSource.fixedAt(fixedInstant)

    private fun stages(
        kositValidator: KositValidatorPort = mock(),
        facturXSchematronValidator: FacturXSchematronValidator = mock(),
        brFrCtcValidator: BrFrCtcValidator = mock(),
        facturXEngine: FacturXEngine = mock(),
        basePdfStage: BasePdfStage = mock(),
        ciiXsdValidator: CiiXsdValidator = mock(),
        time: TimeSource = fixedTime,
    ) = ExportValidationStages(
        kositValidator, facturXSchematronValidator, brFrCtcValidator, facturXEngine, basePdfStage, ciiXsdValidator, time,
    )

    // ── checkedAtOrNull ──────────────────────────────────────────────────────────────

    @Test
    fun `checkedAtOrNull returns null when validation is skipped`() {
        val sut = stages()

        assertNull(sut.checkedAtOrNull(skipValidation = true))
    }

    @Test
    fun `checkedAtOrNull returns the injected TimeSource's moment when validation runs`() {
        val sut = stages(time = fixedTime)

        assertEquals(fixedTime.nowUtc().toString(), sut.checkedAtOrNull(skipValidation = false))
    }

    // ── resolveBrFrCtcStage ──────────────────────────────────────────────────────────

    @Test
    fun `resolveBrFrCtcStage runs the validator for a French recipient`() {
        val validator: BrFrCtcValidator = mock()
        val expected = ValidationStageResult(BrFrCtcValidator.STAGE, KositOutcome.VALID)
        // anyOrNull() for the seller-country arg: the test invoice has no senderProfile, so the
        // real call passes null there — plain any() never matches a null actual argument.
        whenever(validator.validate(any(), anyOrNull())).thenReturn(expected)
        val sut = stages(brFrCtcValidator = validator)

        val result = sut.resolveBrFrCtcStage(makeInvoice(country = "FR"), byteArrayOf(1), skipValidation = false)

        assertEquals(expected, result)
        verify(validator).validate(any(), anyOrNull())
    }

    @Test
    fun `resolveBrFrCtcStage is case-insensitive on the country code`() {
        val validator: BrFrCtcValidator = mock()
        whenever(validator.validate(any(), anyOrNull())).thenReturn(ValidationStageResult(BrFrCtcValidator.STAGE, KositOutcome.VALID))
        val sut = stages(brFrCtcValidator = validator)

        val result = sut.resolveBrFrCtcStage(makeInvoice(country = "fr"), byteArrayOf(1), skipValidation = false)

        assertTrue(result != null)
    }

    @Test
    fun `resolveBrFrCtcStage is absent for a non-French recipient`() {
        val validator: BrFrCtcValidator = mock()
        val sut = stages(brFrCtcValidator = validator)

        val result = sut.resolveBrFrCtcStage(makeInvoice(country = "DE"), byteArrayOf(1), skipValidation = false)

        assertNull(result)
        verify(validator, never()).validate(any(), any())
    }

    @Test
    fun `resolveBrFrCtcStage is absent when no recipient country is set at all`() {
        val sut = stages()
        assertNull(sut.resolveBrFrCtcStage(makeInvoice(country = null), byteArrayOf(1), skipValidation = false))
    }

    @Test
    fun `resolveBrFrCtcStage reports skipped without calling the validator under skipValidation`() {
        val validator: BrFrCtcValidator = mock()
        val sut = stages(brFrCtcValidator = validator)

        val result = sut.resolveBrFrCtcStage(makeInvoice(country = "FR"), byteArrayOf(1), skipValidation = true)

        assertEquals(true, result?.skipped)
        assertEquals("skipValidation", result?.skipReason)
        verify(validator, never()).validate(any(), any())
    }

    // ── buildFacturXEngineStage ──────────────────────────────────────────────────────

    @Test
    fun `buildFacturXEngineStage runs the engine's own validate and keeps its findings when BR_FR_CTC did not run`() {
        val engine: FacturXEngine = mock()
        val finding = ValidationMessage(severity = "error", message = "x", ruleId = "FACTURX-FR-RULES-NOT-RUN")
        whenever(engine.validate(any(), any())).thenReturn(FacturXValidation(KositOutcome.INVALID, listOf(finding)))
        val sut = stages(facturXEngine = engine)

        val result = sut.buildFacturXEngineStage(byteArrayOf(1), makeInvoice(), skipValidation = false, brFrCtcRan = false)

        assertEquals(KositOutcome.INVALID, result.outcome)
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `buildFacturXEngineStage filters the FR-RULES-NOT-RUN caveat when BR_FR_CTC actually ran`() {
        val engine: FacturXEngine = mock()
        val finding = ValidationMessage(severity = "warning", message = "x", ruleId = "FACTURX-FR-RULES-NOT-RUN")
        whenever(engine.validate(any(), any())).thenReturn(FacturXValidation(KositOutcome.VALID, listOf(finding)))
        val sut = stages(facturXEngine = engine)

        val result = sut.buildFacturXEngineStage(byteArrayOf(1), makeInvoice(), skipValidation = false, brFrCtcRan = true)

        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `buildFacturXEngineStage never calls the engine under skipValidation`() {
        val engine: FacturXEngine = mock()
        val sut = stages(facturXEngine = engine)

        val result = sut.buildFacturXEngineStage(byteArrayOf(1), makeInvoice(), skipValidation = true, brFrCtcRan = false)

        assertTrue(result.skipped)
        assertEquals(KositOutcome.VALID, result.outcome)
        verify(engine, never()).validate(any(), any())
    }

    @Test
    fun `buildFacturXEngineStage keeps other findings untouched`() {
        val engine: FacturXEngine = mock()
        val other = ValidationMessage(severity = "error", message = "y", ruleId = "FACTURX-ENGINE-REJECTED")
        whenever(engine.validate(any(), any())).thenReturn(FacturXValidation(KositOutcome.INVALID, listOf(other)))
        val sut = stages(facturXEngine = engine)

        val result = sut.buildFacturXEngineStage(byteArrayOf(1), makeInvoice(), skipValidation = false, brFrCtcRan = true)

        assertEquals(listOf(other), result.findings)
    }

    // ── runSyntaxStage ───────────────────────────────────────────────────────────────

    @Test
    fun `runSyntaxStage runs the CII-XSD validator and reports VALID`() {
        val validator: CiiXsdValidator = mock()
        whenever(validator.validate(any())).thenReturn(CiiXsdValidationResult(KositOutcome.VALID, emptyList()))
        val sut = stages(ciiXsdValidator = validator)

        val result = sut.runSyntaxStage(byteArrayOf(1), skipValidation = false, jobId = "job-1")

        assertEquals("SYNTAX", result.stage)
        assertEquals(KositOutcome.VALID, result.outcome)
        assertFalse(result.skipped)
        verify(validator).validate(any())
    }

    @Test
    fun `runSyntaxStage surfaces an INVALID CII-XSD verdict with its findings unchanged`() {
        val validator: CiiXsdValidator = mock()
        val finding = ValidationMessage(severity = "error", message = "malformed XML")
        whenever(validator.validate(any())).thenReturn(CiiXsdValidationResult(KositOutcome.INVALID, listOf(finding)))
        val sut = stages(ciiXsdValidator = validator)

        val result = sut.runSyntaxStage(byteArrayOf(1), skipValidation = false, jobId = "job-1")

        assertEquals(KositOutcome.INVALID, result.outcome)
        assertEquals(listOf(finding), result.findings)
        assertFalse(result.skipped)
    }

    @Test
    fun `runSyntaxStage never calls the CII-XSD validator under skipValidation`() {
        val validator: CiiXsdValidator = mock()
        val sut = stages(ciiXsdValidator = validator)

        val result = sut.runSyntaxStage(byteArrayOf(1), skipValidation = true, jobId = "job-1")

        assertEquals("SYNTAX", result.stage)
        assertEquals(KositOutcome.VALID, result.outcome)
        assertTrue(result.skipped)
        assertEquals("skipValidation", result.skipReason)
        verify(validator, never()).validate(any())
    }

    // ── runKositStage ────────────────────────────────────────────────────────────────

    @Test
    fun `runKositStage runs KoSIT when stage1 is VALID`() {
        val validator: KositValidatorPort = mock()
        whenever(validator.validate(any(), any())).thenReturn(KositValidationResult(KositOutcome.VALID))
        val sut = stages(kositValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runKositStage(stage1, byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "XRechnung")

        assertEquals(KositOutcome.VALID, result.outcome)
        assertFalse(result.skipped)
    }

    @Test
    fun `runKositStage never calls KoSIT under skipValidation`() {
        val validator: KositValidatorPort = mock()
        val sut = stages(kositValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runKositStage(stage1, byteArrayOf(1), skipValidation = true, jobId = "job-1", profile = "XRechnung")

        assertTrue(result.skipped)
        verify(validator, never()).validate(any(), any())
    }

    @Test
    fun `runKositStage skips KoSIT when stage1 already failed`() {
        val validator: KositValidatorPort = mock()
        val sut = stages(kositValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.INVALID)

        val result = sut.runKositStage(stage1, byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "XRechnung")

        assertTrue(result.skipped)
        assertEquals(KositOutcome.ERROR, result.outcome)
        verify(validator, never()).validate(any(), any())
    }

    @Test
    fun `runKositStage surfaces an INVALID KoSIT verdict unchanged`() {
        val validator: KositValidatorPort = mock()
        val finding = ValidationMessage(severity = "error", message = "bad")
        whenever(validator.validate(any(), any())).thenReturn(KositValidationResult(KositOutcome.INVALID, details = listOf(finding)))
        val sut = stages(kositValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runKositStage(stage1, byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "XRechnung")

        assertEquals(KositOutcome.INVALID, result.outcome)
        assertEquals(listOf(finding), result.findings)
    }

    // PO decision 2026-09-28: the bundled XRechnung 3.0.2 scenarios.xml has no scenario for the
    // EXTENDED guideline URN, so this profile must never reach the real KoSIT call — it would
    // only produce a false rejection, not a real finding (see KositValidationIT's own probe).
    @Test
    fun `runKositStage skips KoSIT for profile EXTENDED with the honest reason, even when stage1 is VALID`() {
        val validator: KositValidatorPort = mock()
        val sut = stages(kositValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runKositStage(stage1, byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "EXTENDED")

        assertEquals("KOSIT", result.stage)
        assertEquals(KositOutcome.VALID, result.outcome)
        assertTrue(result.skipped)
        assertEquals(
            "Profil EXTENDED: die KoSIT-Konfiguration XRechnung 3.0.2 enthält kein Szenario für " +
                "die EXTENDED-Leitlinie; maßgeblich ist die Factur-X-EXTENDED-Schematron-Stufe",
            result.skipReason,
        )
        verify(validator, never()).validate(any(), any())
    }

    @Test
    fun `runKositStage still runs KoSIT for profile EN16931`() {
        val validator: KositValidatorPort = mock()
        whenever(validator.validate(any(), any())).thenReturn(KositValidationResult(KositOutcome.VALID))
        val sut = stages(kositValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runKositStage(stage1, byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "EN16931")

        assertFalse(result.skipped)
        verify(validator).validate(any(), any())
    }

    // ── buildProfileStage ────────────────────────────────────────────────────────────

    @Test
    fun `buildProfileStage is absent for every profile but EXTENDED`() {
        val sut = stages()

        assertNull(sut.buildProfileStage("EN16931"))
        assertNull(sut.buildProfileStage("XRechnung"))
    }

    @Test
    fun `buildProfileStage carries exactly one WARNING finding for EXTENDED and stays VALID`() {
        val sut = stages()

        val result = sut.buildProfileStage("EXTENDED")

        assertNotNull(result)
        assertEquals("PROFILE", result!!.stage)
        assertEquals(KositOutcome.VALID, result.outcome)
        assertFalse(result.skipped)
        assertEquals(1, result.findings.size)
        val finding = result.findings.single()
        assertEquals("WARNING", finding.severity)
        assertEquals("LT-PROFILE-EXTENDED", finding.ruleId)
        assertEquals("PROFILE", finding.stage)
        assertEquals("littletrotter", finding.validator)
    }

    // ── runFacturXSchematronStage ────────────────────────────────────────────────────

    @Test
    fun `runFacturXSchematronStage runs the validator when stage1 is VALID and not skipped`() {
        val validator: FacturXSchematronValidator = mock()
        val expected = ValidationStageResult(FacturXSchematronValidator.STAGE, KositOutcome.VALID)
        whenever(validator.validate(any())).thenReturn(expected)
        val sut = stages(facturXSchematronValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runFacturXSchematronStage(stage1, byteArrayOf(1), skipValidation = false)

        assertEquals(expected, result)
    }

    @Test
    fun `runFacturXSchematronStage never calls the validator under skipValidation`() {
        val validator: FacturXSchematronValidator = mock()
        val sut = stages(facturXSchematronValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runFacturXSchematronStage(stage1, byteArrayOf(1), skipValidation = true)

        assertTrue(result.skipped)
        verify(validator, never()).validate(any())
    }

    @Test
    fun `runFacturXSchematronStage is skipped when stage1 failed`() {
        val validator: FacturXSchematronValidator = mock()
        val sut = stages(facturXSchematronValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.INVALID)

        val result = sut.runFacturXSchematronStage(stage1, byteArrayOf(1), skipValidation = false)

        assertTrue(result.skipped)
        verify(validator, never()).validate(any())
    }

    @Test
    fun `runFacturXSchematronStage is skipped when stage1 is VALID but itself skipped`() {
        val validator: FacturXSchematronValidator = mock()
        val sut = stages(facturXSchematronValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID, skipped = true, skipReason = "engine delivers D22B")

        val result = sut.runFacturXSchematronStage(stage1, byteArrayOf(1), skipValidation = false)

        assertTrue(result.skipped)
        verify(validator, never()).validate(any())
    }

    // ── buildValidationReport ────────────────────────────────────────────────────────

    @Test
    fun `buildValidationReport assembles the chain and overall verdict from the mandatory stages`() {
        val basePdfStage: BasePdfStage = mock()
        val vera: VeraPdfValidatorPort = mock()
        val pdfa = BasePdfStage.PdfA3CheckOutcome(ValidationStageResult("PDFA", KositOutcome.VALID), "verapdf-cli", null)
        whenever(basePdfStage.checkPdfA3(any(), any(), any())).thenReturn(pdfa)
        whenever(basePdfStage.optionalVeraPdfLayer(any(), any())).thenReturn(null)
        val sut = stages(basePdfStage = basePdfStage)

        val request = ExportRequest(makeInvoice(), "job-1", fakeKeys(), "2026-01-01T00:00:00Z", false, veraPdfCheck = false, llmOptimize = false)
        val artifacts = ExportArtifacts(byteArrayOf(1), "xml/key", byteArrayOf(2), "EN16931", "factur-x.xml", "CII D16B")
        val stagesArg = ExportStages(
            syntax = ValidationStageResult("SYNTAX", KositOutcome.VALID),
            schematron = ValidationStageResult(FacturXSchematronValidator.STAGE, KositOutcome.VALID),
            kosit = ValidationStageResult("KOSIT", KositOutcome.VALID),
        )

        val result = sut.buildValidationReport(request, artifacts, stagesArg, byteArrayOf(9))

        assertEquals(KositOutcome.VALID, result.status)
        assertNull(result.summary)
        assertEquals("EN16931", result.profile)
        verify(vera, never()).validate(any())
    }

    @Test
    fun `buildValidationReport falls back to the injected TimeSource when the request has no checkedAt`() {
        val basePdfStage: BasePdfStage = mock()
        val pdfa = BasePdfStage.PdfA3CheckOutcome(ValidationStageResult("PDFA", KositOutcome.VALID), "verapdf-cli", null)
        whenever(basePdfStage.checkPdfA3(any(), any(), any())).thenReturn(pdfa)
        whenever(basePdfStage.optionalVeraPdfLayer(any(), any())).thenReturn(null)
        val sut = stages(basePdfStage = basePdfStage, time = fixedTime)

        // checkedAt = null — e.g. skipValidation on the caller's side never computed one; the
        // report's own startedAt must still come from somewhere real, not stay null.
        val request = ExportRequest(makeInvoice(), "job-1", fakeKeys(), null, false, veraPdfCheck = false, llmOptimize = false)
        val artifacts = ExportArtifacts(byteArrayOf(1), "xml/key", byteArrayOf(2), "EN16931", "factur-x.xml", "CII D16B")
        val stagesArg = ExportStages(
            syntax = ValidationStageResult("SYNTAX", KositOutcome.VALID),
            schematron = ValidationStageResult(FacturXSchematronValidator.STAGE, KositOutcome.VALID),
            kosit = ValidationStageResult("KOSIT", KositOutcome.VALID),
        )

        val result = sut.buildValidationReport(request, artifacts, stagesArg, byteArrayOf(9))

        assertEquals(fixedTime.nowUtc().toString(), result.validationRun?.startedAt)
    }

    @Test
    fun `buildValidationReport reports the summary count for non-skipped findings`() {
        val basePdfStage: BasePdfStage = mock()
        val pdfaFinding = ValidationMessage(severity = "error", message = "bad pdf")
        val pdfa = BasePdfStage.PdfA3CheckOutcome(ValidationStageResult("PDFA", KositOutcome.INVALID, listOf(pdfaFinding)), "verapdf-cli", null)
        whenever(basePdfStage.checkPdfA3(any(), any(), any())).thenReturn(pdfa)
        whenever(basePdfStage.optionalVeraPdfLayer(any(), any())).thenReturn(null)
        val sut = stages(basePdfStage = basePdfStage)

        val request = ExportRequest(makeInvoice(), "job-1", fakeKeys(), null, true, veraPdfCheck = false, llmOptimize = false)
        val artifacts = ExportArtifacts(byteArrayOf(1), "xml/key", byteArrayOf(2), "EN16931", "factur-x.xml", "CII D16B")
        val stagesArg = ExportStages(
            syntax = ValidationStageResult("SYNTAX", KositOutcome.VALID, skipped = true, skipReason = "skipValidation"),
            schematron = ValidationStageResult(FacturXSchematronValidator.STAGE, KositOutcome.VALID, skipped = true, skipReason = "skipValidation"),
            kosit = ValidationStageResult("KOSIT", KositOutcome.VALID, skipped = true, skipReason = "skipValidation"),
        )

        val result = sut.buildValidationReport(request, artifacts, stagesArg, byteArrayOf(9))

        assertEquals(KositOutcome.INVALID, result.status)
        assertEquals("1 validation finding(s) across 1 stage(s)", result.summary)
    }

    @Test
    fun `buildValidationReport includes the optional veraPDF layer only when present`() {
        val basePdfStage: BasePdfStage = mock()
        val pdfa = BasePdfStage.PdfA3CheckOutcome(ValidationStageResult("PDFA", KositOutcome.VALID), "veraPDF 1.30.2", null)
        val veraStage = ValidationStageResult("VERAPDF", KositOutcome.VALID)
        whenever(basePdfStage.checkPdfA3(any(), any(), any())).thenReturn(pdfa)
        whenever(basePdfStage.optionalVeraPdfLayer(any(), any())).thenReturn(veraStage)
        val sut = stages(basePdfStage = basePdfStage)

        val request = ExportRequest(makeInvoice(), "job-1", fakeKeys(), "2026-01-01T00:00:00Z", false, veraPdfCheck = true, llmOptimize = false)
        val artifacts = ExportArtifacts(byteArrayOf(1), "xml/key", byteArrayOf(2), "EN16931", "factur-x.xml", "CII D16B")
        val stagesArg = ExportStages(
            syntax = ValidationStageResult("SYNTAX", KositOutcome.VALID),
            schematron = ValidationStageResult(FacturXSchematronValidator.STAGE, KositOutcome.VALID),
            kosit = ValidationStageResult("KOSIT", KositOutcome.VALID),
        )

        val result = sut.buildValidationReport(request, artifacts, stagesArg, byteArrayOf(9))

        assertTrue(result.validationRun!!.stages.any { it.stage == "VERAPDF" })
    }

    // PO decision 2026-09-28: an EXTENDED run whose other stages are VALID stays VALID overall —
    // KOSIT skipped (not run), PROFILE present with its WARNING but not skipped, chain order
    // SYNTAX -> FACTURX_SCHEMATRON -> PROFILE -> KOSIT -> PDFA -> BUSINESS.
    @Test
    fun `buildValidationReport stays overall VALID for an EXTENDED run — KOSIT skipped, PROFILE present with its warning`() {
        val basePdfStage: BasePdfStage = mock()
        val pdfa = BasePdfStage.PdfA3CheckOutcome(ValidationStageResult("PDFA", KositOutcome.VALID), "verapdf-cli", null)
        whenever(basePdfStage.checkPdfA3(any(), any(), any())).thenReturn(pdfa)
        whenever(basePdfStage.optionalVeraPdfLayer(any(), any())).thenReturn(null)
        val sut = stages(basePdfStage = basePdfStage)

        val request = ExportRequest(makeInvoice(), "job-1", fakeKeys(), "2026-01-01T00:00:00Z", false, veraPdfCheck = false, llmOptimize = false)
        val artifacts = ExportArtifacts(byteArrayOf(1), "xml/key", byteArrayOf(2), "EXTENDED", "factur-x.xml", "CII D16B")
        val kositStage = sut.runKositStage(
            ValidationStageResult("SYNTAX", KositOutcome.VALID), byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "EXTENDED",
        )
        val stagesArg = ExportStages(
            syntax = ValidationStageResult("SYNTAX", KositOutcome.VALID),
            schematron = ValidationStageResult(FacturXSchematronValidator.STAGE, KositOutcome.VALID),
            kosit = kositStage,
            profile = sut.buildProfileStage("EXTENDED"),
        )

        val result = sut.buildValidationReport(request, artifacts, stagesArg, byteArrayOf(9))

        assertEquals(KositOutcome.VALID, result.status)
        val chain = result.validationRun!!.stages
        assertEquals(listOf("SYNTAX", "FACTURX_SCHEMATRON", "PROFILE", "KOSIT", "PDFA", "BUSINESS"), chain.map { it.stage })
        val kosit = chain.single { it.stage == "KOSIT" }
        assertTrue(kosit.skipped)
        val profileEntry = chain.single { it.stage == "PROFILE" }
        assertFalse(profileEntry.skipped)
        assertEquals(1, profileEntry.findings.size)
        assertEquals("WARNING", profileEntry.findings.single().severity)
        // The flat legacy `details` field carries the warning too — a WARNING never flips
        // the flat `status`, which stays VALID (same as `overall` above).
        assertTrue(result.details.any { it.ruleId == "LT-PROFILE-EXTENDED" })
    }

    private fun fakeKeys() = de.littletrotter.shared.DocumentKeyRules.deriveExportKeys("INV-QA10-1", 1L)
}
