package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.validation.CiiSchematronValidator
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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
        ciiSchematronValidator: CiiSchematronValidator = mock(),
        basePdfStage: BasePdfStage = mock(),
        ciiXsdValidator: CiiXsdValidator = mock(),
        time: TimeSource = fixedTime,
    ) = ExportValidationStages(
        kositValidator, ciiSchematronValidator, basePdfStage, ciiXsdValidator, time, ExportStageTimers(SimpleMeterRegistry()),
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

    // ── KoSIT switch ──────────────────────────────────────────────────────────────────

    @Test
    fun `runKositStage with the switch off skips non-XRechnung profiles as not enabled`() {
        val validator: KositValidatorPort = mock()
        val sut = stages(kositValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runKositStage(stage1, byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "EN16931", kositCheck = false)

        assertTrue(result.skipped)
        assertEquals("nicht aktiviert", result.skipReason)
        assertEquals(KositOutcome.VALID, result.outcome)
        verify(validator, never()).validate(any(), any())
    }

    @Test
    fun `runKositStage with the switch off still validates XRechnung`() {
        val validator: KositValidatorPort = mock()
        whenever(validator.validate(any(), any())).thenReturn(KositValidationResult(KositOutcome.INVALID))
        val sut = stages(kositValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runKositStage(stage1, byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "XRechnung", kositCheck = false)

        assertFalse(result.skipped)
        assertEquals(KositOutcome.INVALID, result.outcome)
        verify(validator).validate(any(), any())
    }

    @Test
    fun `runKositStage with the switch on runs every profile except EXTENDED`() {
        val validator: KositValidatorPort = mock()
        whenever(validator.validate(any(), any())).thenReturn(KositValidationResult(KositOutcome.VALID))
        val sut = stages(kositValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val en16931 = sut.runKositStage(stage1, byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "EN16931", kositCheck = true)
        val extended = sut.runKositStage(stage1, byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "EXTENDED", kositCheck = true)

        assertFalse(en16931.skipped)
        assertTrue(extended.skipped)
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

    // ── runCiiSchematronStage ────────────────────────────────────────────────────

    @Test
    fun `runCiiSchematronStage runs the validator when stage1 is VALID and not skipped`() {
        val validator: CiiSchematronValidator = mock()
        val expected = ValidationStageResult(CiiSchematronValidator.STAGE, KositOutcome.VALID)
        whenever(validator.validate(any())).thenReturn(expected)
        val sut = stages(ciiSchematronValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runCiiSchematronStage(stage1, byteArrayOf(1), skipValidation = false)

        assertEquals(expected, result)
    }

    @Test
    fun `runCiiSchematronStage never calls the validator under skipValidation`() {
        val validator: CiiSchematronValidator = mock()
        val sut = stages(ciiSchematronValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID)

        val result = sut.runCiiSchematronStage(stage1, byteArrayOf(1), skipValidation = true)

        assertTrue(result.skipped)
        verify(validator, never()).validate(any())
    }

    @Test
    fun `runCiiSchematronStage is skipped when stage1 failed`() {
        val validator: CiiSchematronValidator = mock()
        val sut = stages(ciiSchematronValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.INVALID)

        val result = sut.runCiiSchematronStage(stage1, byteArrayOf(1), skipValidation = false)

        assertTrue(result.skipped)
        verify(validator, never()).validate(any())
    }

    @Test
    fun `runCiiSchematronStage is skipped when stage1 is VALID but itself skipped`() {
        val validator: CiiSchematronValidator = mock()
        val sut = stages(ciiSchematronValidator = validator)
        val stage1 = ValidationStageResult("SYNTAX", KositOutcome.VALID, skipped = true, skipReason = "skipValidation")

        val result = sut.runCiiSchematronStage(stage1, byteArrayOf(1), skipValidation = false)

        assertTrue(result.skipped)
        verify(validator, never()).validate(any())
    }

    // ── buildValidationReport ────────────────────────────────────────────────────────

    @Test
    fun `buildValidationReport assembles the chain and overall verdict from the mandatory stages`() {
        val basePdfStage: BasePdfStage = mock()
        val pdfa = BasePdfStage.PdfA3CheckOutcome(ValidationStageResult("PDFA", KositOutcome.VALID), "verapdf-cli")
        whenever(basePdfStage.checkPdfA3(any(), any(), any())).thenReturn(pdfa)
        val sut = stages(basePdfStage = basePdfStage)

        val request = ExportRequest(makeInvoice(), "job-1", fakeKeys(), "2026-01-01T00:00:00Z", false, veraPdfCheck = false, llmOptimize = false)
        val artifacts = ExportArtifacts(byteArrayOf(1), "xml/key", byteArrayOf(2), "EN16931", "factur-x.xml", "CII D16B")
        val stagesArg = ExportStages(
            syntax = ValidationStageResult("SYNTAX", KositOutcome.VALID),
            schematron = ValidationStageResult(CiiSchematronValidator.STAGE, KositOutcome.VALID),
            kosit = ValidationStageResult("KOSIT", KositOutcome.VALID),
        )

        val result = sut.buildValidationReport(request, artifacts, stagesArg, byteArrayOf(9))

        assertEquals(KositOutcome.VALID, result.status)
        assertNull(result.summary)
        assertEquals("EN16931", result.profile)
    }

    @Test
    fun `buildValidationReport falls back to the injected TimeSource when the request has no checkedAt`() {
        val basePdfStage: BasePdfStage = mock()
        val pdfa = BasePdfStage.PdfA3CheckOutcome(ValidationStageResult("PDFA", KositOutcome.VALID), "verapdf-cli")
        whenever(basePdfStage.checkPdfA3(any(), any(), any())).thenReturn(pdfa)
        val sut = stages(basePdfStage = basePdfStage, time = fixedTime)

        // checkedAt = null — e.g. skipValidation on the caller's side never computed one; the
        // report's own startedAt must still come from somewhere real, not stay null.
        val request = ExportRequest(makeInvoice(), "job-1", fakeKeys(), null, false, veraPdfCheck = false, llmOptimize = false)
        val artifacts = ExportArtifacts(byteArrayOf(1), "xml/key", byteArrayOf(2), "EN16931", "factur-x.xml", "CII D16B")
        val stagesArg = ExportStages(
            syntax = ValidationStageResult("SYNTAX", KositOutcome.VALID),
            schematron = ValidationStageResult(CiiSchematronValidator.STAGE, KositOutcome.VALID),
            kosit = ValidationStageResult("KOSIT", KositOutcome.VALID),
        )

        val result = sut.buildValidationReport(request, artifacts, stagesArg, byteArrayOf(9))

        assertEquals(fixedTime.nowUtc().toString(), result.validationRun?.startedAt)
    }

    @Test
    fun `buildValidationReport reports the summary count for non-skipped findings`() {
        val basePdfStage: BasePdfStage = mock()
        val pdfaFinding = ValidationMessage(severity = "error", message = "bad pdf")
        val pdfa = BasePdfStage.PdfA3CheckOutcome(ValidationStageResult("PDFA", KositOutcome.INVALID, listOf(pdfaFinding)), "verapdf-cli")
        whenever(basePdfStage.checkPdfA3(any(), any(), any())).thenReturn(pdfa)
        val sut = stages(basePdfStage = basePdfStage)

        val request = ExportRequest(makeInvoice(), "job-1", fakeKeys(), null, true, veraPdfCheck = false, llmOptimize = false)
        val artifacts = ExportArtifacts(byteArrayOf(1), "xml/key", byteArrayOf(2), "EN16931", "factur-x.xml", "CII D16B")
        val stagesArg = ExportStages(
            syntax = ValidationStageResult("SYNTAX", KositOutcome.VALID, skipped = true, skipReason = "skipValidation"),
            schematron = ValidationStageResult(CiiSchematronValidator.STAGE, KositOutcome.VALID, skipped = true, skipReason = "skipValidation"),
            kosit = ValidationStageResult("KOSIT", KositOutcome.VALID, skipped = true, skipReason = "skipValidation"),
        )

        val result = sut.buildValidationReport(request, artifacts, stagesArg, byteArrayOf(9))

        assertEquals(KositOutcome.INVALID, result.status)
        assertEquals("1 validation finding(s) across 1 stage(s)", result.summary)
    }

    private fun veraPdfStageIn(veraPdfCheck: Boolean, skipValidation: Boolean): ValidationStageResult {
        val basePdfStage: BasePdfStage = mock()
        val pdfa = BasePdfStage.PdfA3CheckOutcome(ValidationStageResult("PDFA", KositOutcome.VALID), "pdfa-minimal")
        whenever(basePdfStage.checkPdfA3(any(), any(), any())).thenReturn(pdfa)
        val sut = stages(basePdfStage = basePdfStage)
        val request = ExportRequest(makeInvoice(), "job-1", fakeKeys(), "2026-01-01T00:00:00Z", skipValidation, veraPdfCheck = veraPdfCheck, llmOptimize = false)
        val artifacts = ExportArtifacts(byteArrayOf(1), "xml/key", byteArrayOf(2), "EN16931", "factur-x.xml", "CII D16B")
        val stagesArg = ExportStages(
            syntax = ValidationStageResult("SYNTAX", KositOutcome.VALID),
            schematron = ValidationStageResult(CiiSchematronValidator.STAGE, KositOutcome.VALID),
            kosit = ValidationStageResult("KOSIT", KositOutcome.VALID),
        )
        val result = sut.buildValidationReport(request, artifacts, stagesArg, byteArrayOf(9))
        assertEquals(KositOutcome.VALID, result.status, "the placeholder never changes the verdict")
        return result.validationRun!!.stages.single { it.stage == "VERAPDF" }
    }

    @Test
    fun `buildValidationReport leaves the veraPDF stage pending and advisory when it was requested`() {
        val stage = veraPdfStageIn(veraPdfCheck = true, skipValidation = false)

        assertTrue(stage.pending)
        assertTrue(stage.advisory)
        assertFalse(stage.skipped)
    }

    @Test
    fun `buildValidationReport states the veraPDF stage as not enabled when it was not requested`() {
        val stage = veraPdfStageIn(veraPdfCheck = false, skipValidation = false)

        assertTrue(stage.skipped)
        assertEquals("nicht aktiviert", stage.skipReason)
        assertFalse(stage.pending)
    }

    @Test
    fun `buildValidationReport skips the veraPDF stage under skipValidation`() {
        val stage = veraPdfStageIn(veraPdfCheck = true, skipValidation = true)

        assertTrue(stage.skipped)
        assertEquals("skipValidation", stage.skipReason)
    }

    // PO decision 2026-09-28: an EXTENDED run whose other stages are VALID stays VALID overall —
    // KOSIT skipped (not run), PROFILE present with its WARNING but not skipped, chain order
    // SYNTAX -> FACTURX_SCHEMATRON -> PROFILE -> KOSIT -> PDFA -> BUSINESS.
    @Test
    fun `buildValidationReport stays overall VALID for an EXTENDED run — KOSIT skipped, PROFILE present with its warning`() {
        val basePdfStage: BasePdfStage = mock()
        val pdfa = BasePdfStage.PdfA3CheckOutcome(ValidationStageResult("PDFA", KositOutcome.VALID), "verapdf-cli")
        whenever(basePdfStage.checkPdfA3(any(), any(), any())).thenReturn(pdfa)
        val sut = stages(basePdfStage = basePdfStage)

        val request = ExportRequest(makeInvoice(), "job-1", fakeKeys(), "2026-01-01T00:00:00Z", false, veraPdfCheck = false, llmOptimize = false)
        val artifacts = ExportArtifacts(byteArrayOf(1), "xml/key", byteArrayOf(2), "EXTENDED", "factur-x.xml", "CII D16B")
        val kositStage = sut.runKositStage(
            ValidationStageResult("SYNTAX", KositOutcome.VALID), byteArrayOf(1), skipValidation = false, jobId = "job-1", profile = "EXTENDED",
        )
        val stagesArg = ExportStages(
            syntax = ValidationStageResult("SYNTAX", KositOutcome.VALID),
            schematron = ValidationStageResult(CiiSchematronValidator.STAGE, KositOutcome.VALID),
            kosit = kositStage,
            profile = sut.buildProfileStage("EXTENDED"),
        )

        val result = sut.buildValidationReport(request, artifacts, stagesArg, byteArrayOf(9))

        assertEquals(KositOutcome.VALID, result.status)
        val chain = result.validationRun!!.stages
        assertEquals(listOf("SYNTAX", "FACTURX_SCHEMATRON", "PROFILE", "KOSIT", "PDFA", "VERAPDF", "BUSINESS"), chain.map { it.stage })
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
