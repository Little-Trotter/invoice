package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.BrFrCtcValidator
import de.littletrotter.facturx.FacturXEngine
import de.littletrotter.facturx.FacturXSchematronValidator
import de.littletrotter.facturx.FacturXValidation
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The deterministic validation-stage runners both export routes share (SYNTAX/CII-XSD,
 * KOSIT, the Factur-X Schematron, the BR-FR-CTC gate, the FACTURX engine stage) plus the
 * PDF/A-3+veraPDF report assembly ([buildValidationReport]). Split out of [PdfExportServiceImpl]
 * (one reason to change: the stage chain's own gating rules), same "extract by moving, not
 * rewriting" contract every other collaborator on that class already follows — see
 * [BasePdfStage]'s own class KDoc.
 *
 * [runSyntaxStage] joined the other stage runners here (it used to be the one deterministic
 * stage [PdfExportServiceImpl.performExport] still ran inline against its own [ciiXsdValidator]
 * field): removing that class's default-constructed-collaborator constructor left it holding
 * [CiiXsdValidator] for a single call site, so the SYNTAX stage moved to where its four siblings
 * already live.
 */
@ApplicationScoped
class ExportValidationStages @Inject constructor(
    private val kositValidator: KositValidatorPort,
    private val facturXSchematronValidator: FacturXSchematronValidator,
    private val brFrCtcValidator: BrFrCtcValidator,
    private val facturXEngine: FacturXEngine,
    private val basePdfStage: BasePdfStage,
    private val ciiXsdValidator: CiiXsdValidator,
    // The one injected source of "now" — see de.littletrotter.xpdf.TimeSourceProducer.
    private val time: TimeSource,
) {
    private val log = Logger.getLogger(ExportValidationStages::class.java)

    private companion object {
        /** kotlin:S1192/StringLiteralDuplication — every stage's own `skipValidation` short-circuit
         *  reports this same skip reason; a shared constant keeps the four call sites from drifting. */
        const val SKIP_VALIDATION_REASON = "skipValidation"

        /** PO decision (2026-09-28): the profile that escalates a cash-discount invoice's export
         *  so the structured Skonto terms can travel as data (see
         *  [de.littletrotter.domain.invoice.resolveExportProfileId]'s own KDoc in kotlin-invoice —
         *  no dependency edge onto that module from here, so this stays a duplicate literal, the
         *  same way [de.littletrotter.xpdf.impl.XmlExportServiceImpl.resolveProfile] already is). */
        const val EXTENDED_PROFILE = "EXTENDED"

        /** Why KoSIT is skipped, not run and ignored, for [EXTENDED_PROFILE]: the bundled
         *  XRechnung 3.0.2 `scenarios.xml` has no scenario for the EXTENDED guideline URN (only
         *  the XRechnung URNs and the plain EN16931-CII URN are known there) — an EXTENDED export
         *  would hit KoSIT's own "no matching scenario" rejection, not a real finding about the
         *  document. [KositValidationIT]'s own EXTENDED-URN case is the permanent proof this
         *  reason states honestly. */
        const val KOSIT_EXTENDED_SKIP_REASON =
            "Profil EXTENDED: die KoSIT-Konfiguration XRechnung 3.0.2 enthält kein Szenario für " +
                "die EXTENDED-Leitlinie; maßgeblich ist die Factur-X-EXTENDED-Schematron-Stufe"
    }

    /**
     * The moment [PdfExportServiceImpl.performExport]/`performFacturXExport` stamp their own
     * [ExportRequest.checkedAt] with, `null` exactly when [skipValidation] is set (no check
     * actually ran, so a timestamp would misrepresent one). Lives here (not read directly by
     * [PdfExportServiceImpl] itself) so that class needs no [de.littletrotter.shared.time.TimeSource]
     * of its own — kotlin:LongParameterList territory once every route collaborator is already a
     * constructor parameter; this class already has the one injected TimeSource every stage below
     * reads "now" from.
     */
    internal fun checkedAtOrNull(skipValidation: Boolean): String? =
        if (skipValidation) null else time.nowUtc().toString()

    /**
     * Stage 1 (CII-XSD): runs before KoSIT so a pure syntax error is named as such instead
     * of as a KoSIT-Schematron follow-on error (Mustang route only; the Factur-X route always
     * reports this stage `skipped`, built directly in [PdfExportServiceImpl.performFacturXExport]
     * — the engine delivers CII D22B, the embedded XSD set is D16B, see that call site's own
     * comment).
     */
    internal fun runSyntaxStage(xmlBytes: ByteArray, skipValidation: Boolean, jobId: String): ValidationStageResult {
        if (skipValidation) {
            log.info("KoSIT validation skipped by user request (job $jobId)")
            return ValidationStageResult("SYNTAX", KositOutcome.VALID, skipped = true, skipReason = SKIP_VALIDATION_REASON)
        }
        val xsdResult = ciiXsdValidator.validate(String(xmlBytes, Charsets.UTF_8))
        return ValidationStageResult("SYNTAX", xsdResult.outcome, xsdResult.findings)
    }

    // BR_FR_CTC runs exclusively when the recipient's billing-address
    // country is France, the same source ArdennesXRules.kt:36 reads for the same question.
    // LU/BE recipients never trigger it (BR-FR-CTC is purely French) — this is a
    // gating *condition*, not a flag: for any other recipient country the stage is simply
    // never built, so it is absent from the report, not present-and-empty. Only the
    // recipient's country decides *whether* the stage runs; the *seller's* country (read
    // inside BrFrCtcValidator itself) decides only its severity branch — see that class's
    // KDoc for why those are two different questions.
    internal fun resolveBrFrCtcStage(invoice: InvoiceDto, xmlBytes: ByteArray, skipValidation: Boolean): ValidationStageResult? {
        val recipientIsFrance = invoice.recipient?.country.equals("FR", ignoreCase = true)
        return if (!recipientIsFrance) {
            null
        } else if (skipValidation) {
            ValidationStageResult(BrFrCtcValidator.STAGE, KositOutcome.VALID, skipped = true, skipReason = SKIP_VALIDATION_REASON)
        } else {
            brFrCtcValidator.validate(xmlBytes, invoice.senderProfile?.country)
        }
    }

    // Once BR_FR_CTC actually ran in this same job, the
    // engine-side FACTURX-FR-RULES-NOT-RUN caveat is redundant noise — the information now stands
    // as its own stage in the report (FacturXValidationResponseMapper's class doc deliberately
    // leaves this filtering to the caller, i.e. here, since only this method has cross-stage
    // knowledge of whether BR_FR_CTC ran).
    internal fun buildFacturXEngineStage(
        xmlBytes: ByteArray,
        invoice: InvoiceDto,
        skipValidation: Boolean,
        brFrCtcRan: Boolean,
    ): ValidationStageResult {
        if (skipValidation) {
            return ValidationStageResult("FACTURX", KositOutcome.VALID, skipped = true, skipReason = SKIP_VALIDATION_REASON)
        }
        val validation: FacturXValidation = facturXEngine.validate(xmlBytes, invoice)
        val findings = if (brFrCtcRan) {
            validation.findings.filterNot { it.ruleId == "FACTURX-FR-RULES-NOT-RUN" }
        } else {
            validation.findings
        }
        return ValidationStageResult("FACTURX", validation.outcome, findings)
    }

    /**
     * Runs KoSIT against [xmlBytes] unless [skipValidation] is set, [profile] is `EXTENDED`
     * (PO decision 2026-09-28, [KOSIT_EXTENDED_SKIP_REASON] — no bundled scenario matches that
     * guideline URN, see [KositValidationIT]'s own probe), or [stage1]'s own outcome blocks it:
     * a real CII-XSD failure on Mustang must never be followed by a KoSIT run that would only add
     * confusing follow-on Schematron noise. The Factur-X route's [stage1] is
     * always reported [KositOutcome.VALID] (deliberately `skipped`), so this always lets KoSIT
     * run there — the mandatory EN 16931 check is unchanged between engines (that route never
     * resolves `EXTENDED` either — see [ExportArtifacts.facturX]'s fixed profile).
     */
    internal fun runKositStage(
        stage1: ValidationStageResult,
        xmlBytes: ByteArray,
        skipValidation: Boolean,
        jobId: String,
        // kotlin:S107/LongParameterList (detekt.yml's `ignoreDefaultParameters`) — defaults to
        // the same "never EXTENDED" fallback [de.littletrotter.xpdf.impl.mustang.MustangExportModel
        // .exportProfile] already establishes, so every pre-existing call site (never EXTENDED)
        // keeps compiling unchanged; only [PdfExportServiceImpl.performExport]'s Mustang route
        // ever passes a real, possibly-EXTENDED value.
        profile: String = ExportArtifacts.FACTURX_PROFILE,
    ): ValidationStageResult {
        if (skipValidation) {
            return ValidationStageResult("KOSIT", KositOutcome.VALID, skipped = true, skipReason = SKIP_VALIDATION_REASON)
        }
        if (profile == EXTENDED_PROFILE) {
            return ValidationStageResult("KOSIT", KositOutcome.VALID, skipped = true, skipReason = KOSIT_EXTENDED_SKIP_REASON)
        }
        return if (stage1.outcome != KositOutcome.VALID) {
            ValidationStageResult(
                "KOSIT", KositOutcome.ERROR, skipped = true,
                skipReason = "Stufe 1 (CII-XSD) war ${stage1.outcome} — KoSIT-Lauf übersprungen",
            )
        } else {
            val raw = kositValidator.validate(String(xmlBytes, Charsets.UTF_8))
            if (raw.status != KositOutcome.VALID) {
                log.warn("KoSIT validation failed for job $jobId: ${raw.summary}")
            }
            ValidationStageResult("KOSIT", raw.status, raw.details)
        }
    }

    /**
     * Runs [facturXSchematronValidator] against [xmlBytes] unless [skipValidation] is set
     * or [stage1]'s own outcome blocks it: this stage's own precondition, mirrored from
     * [de.littletrotter.xpdf.impl.CiiXsdValidator]'s KDoc, is a schema-valid document — a
     * Schematron pass over an already-invalid or deliberately-skipped SYNTAX stage would only add
     * confusing follow-on noise (same reasoning [runKositStage] applies). No profile is
     * chosen here: [FacturXSchematronValidator.validate] reads the document's own guideline URN
     * and routes itself (EN16931, EXTENDED, or an explicit XRechnung pass-through) — see that
     * class's own KDoc, "Profile routing" section, for why a hardcoded default here would be
     * wrong (it would misreport every XRechnung-profile export, our other real "EN16931"/
     * "XRechnung" split from [de.littletrotter.xpdf.impl.XmlExportServiceImpl.resolveProfile]).
     */
    internal fun runFacturXSchematronStage(
        stage1: ValidationStageResult,
        xmlBytes: ByteArray,
        skipValidation: Boolean,
    ): ValidationStageResult {
        if (skipValidation) {
            return ValidationStageResult(
                FacturXSchematronValidator.STAGE, KositOutcome.VALID, skipped = true, skipReason = SKIP_VALIDATION_REASON,
            )
        }
        return if (stage1.outcome != KositOutcome.VALID || stage1.skipped) {
            ValidationStageResult(
                FacturXSchematronValidator.STAGE, KositOutcome.ERROR, skipped = true,
                skipReason = "Stufe 1 (CII-XSD) war ${stage1.outcome}" +
                    (if (stage1.skipped) " (übersprungen)" else "") +
                    " — Factur-X-Schematron-Lauf übersprungen: schema-valides Dokument ist Voraussetzung",
            )
        } else {
            facturXSchematronValidator.validate(xmlBytes)
        }
    }

    /**
     * The `PROFILE` stage — present only for [EXTENDED_PROFILE] (`null` for every other
     * profile, "absent, not present-and-empty", the same convention [ExportStages]' own KDoc
     * documents for [ExportStages.brFrCtc]/[ExportStages.engine]). Never `skipped`: unlike
     * [runKositStage] this always actually runs (it's a static fact about the chosen profile, not
     * a check that can fail to execute), and always [KositOutcome.VALID] — EXTENDED does not make
     * the export non-conformant, it only means a recipient without EXTENDED support could reject
     * the file, which is exactly the one [ValidationMessage] this stage carries (PO decision
     * 2026-09-28: "EXTENDED als Warning ausgeben, Validierung bleibt grün").
     */
    internal fun buildProfileStage(profile: String): ValidationStageResult? {
        if (profile != EXTENDED_PROFILE) {
            return null
        }
        return ValidationStageResult(
            "PROFILE",
            KositOutcome.VALID,
            findings = listOf(
                ValidationMessage(
                    severity = "WARNING",
                    message = "Profil ZUGFeRD EXTENDED (strukturierte Skontoterme): Empfänger ohne " +
                        "EXTENDED-Unterstützung können die Datei ablehnen; die Rechnung bleibt " +
                        "EN-16931-konform.",
                    ruleId = "LT-PROFILE-EXTENDED",
                    stage = "PROFILE",
                    validator = "littletrotter",
                ),
            ),
        )
    }

    /**
     * Stages 5-6 plus the report itself — the mandatory PDF/A-3 check (+ optional veraPDF
     * layer) on the exact [stampedPdfBytes] a customer receives, the BUSINESS placeholder, and the
     * [KositValidationResult] built over [ExportStages.chain]. Split out of [PdfExportServiceImpl]
     * (now [ExportCompletionStage.completeExport]'s caller), same order and contents as before.
     */
    internal fun buildValidationReport(
        request: ExportRequest,
        artifacts: ExportArtifacts,
        stages: ExportStages,
        stampedPdfBytes: ByteArray,
    ): KositValidationResult {
        // Stage 5 (PDF/A-3): runs on the actually generated bytes, after the export, so it
        // checks what would really be sent out — no re-render. Skipped under skipValidation, for
        // the same reason as stages 1-4.
        // Now in BasePdfStage.checkPdfA3, so a future second engine caller reaches the same
        // check (see that class's own comment).
        val pdfA3Outcome = basePdfStage.checkPdfA3(
            stampedPdfBytes, artifacts.expectedAttachmentFilename, request.skipValidation,
        )
        val stage5 = pdfA3Outcome.stage
        val pdfaEngineLabel = pdfA3Outcome.engineLabel
        // BasePdfStage.checkPdfA3 only ever returns skipped=true together with a VALID outcome
        // (the skipValidation short-circuit) — "invalid AND skipped" can never occur here.
        if (stage5.outcome != KositOutcome.VALID) {
            log.warn("PDF/A-3 check failed for job ${request.jobId}: ${stage5.findings.size} finding(s)")
        }
        // The *optional* veraPDF accreditation layer, present only when
        // `ValidationCapabilityId.VERAPDF_CHECK` is on (resolved by the caller, carried here as
        // [veraPdfCheck] — see DocumentJobPublisher). `null` (switched off, or nothing ran at all
        // under skipValidation) means the layer is simply absent from the report, not
        // present-but-empty — the same "absent, not empty" contract already used for skipped
        // stages elsewhere in this method.
        val optionalVeraPdfStage = basePdfStage.optionalVeraPdfLayer(pdfA3Outcome, request.veraPdfCheck)
        if (optionalVeraPdfStage != null && optionalVeraPdfStage.outcome != KositOutcome.VALID) {
            log.warn("Optional veraPDF check for job ${request.jobId}: ${optionalVeraPdfStage.outcome} (${optionalVeraPdfStage.findings.size} finding(s))")
        }
        // Stage 6 (business tax/identity findings): laid out empty until the seller tax/identity
        // checks are added — explicitly `skipped`, so "not checked" in the report never reads as "passed".
        val stage6 = ValidationStageResult(
            "BUSINESS", KositOutcome.VALID, skipped = true,
            skipReason = "TAX-03/PARTY-01 noch nicht integriert",
        )

        // Chain order: stage1 (SYNTAX) -> schematronStage (FACTURX_SCHEMATRON) -> profileStage (PROFILE, EXTENDED only) -> stage2to4 (KOSIT) -> stage5 (PDFA) -> optionalVeraPdfStage -> engineStage/brFrCtcStage -> stage6 (BUSINESS).
        val chain = stages.chain(pdfa = stage5, veraPdf = optionalVeraPdfStage, business = stage6)
        val overall = ValidationRun.overallOutcomeOf(chain)
        val validationRun = ValidationRun(
            stages = chain,
            overall = overall,
            validatorVersions = buildMap {
                put("xsd", artifacts.xsdVersionLabel)
                put("kosit", "embedded")
                put("pdfa", pdfaEngineLabel)
                // Only present when the optional layer actually ran, so a
                // report from a job that never requested it doesn't imply it was considered.
                pdfA3Outcome.veraPdfResult?.takeIf { optionalVeraPdfStage != null }?.let { put("verapdf", it.validator) }
            },
            startedAt = request.checkedAt ?: time.nowUtc().toString(),
        )
        // The legacy flat status/details stay populated as the sum of all (non-skipped)
        // stages' findings, so an old reader keeps working unchanged: "Das flache Feld bleibt
        // befüllt (Summe aller Stufen)".
        return KositValidationResult(
            status = overall,
            summary = chain.filterNot { it.skipped }
                .sumOf { it.findings.size }
                .let { count -> if (count == 0) null else "$count validation finding(s) across ${chain.count { !it.skipped }} stage(s)" },
            details = chain.flatMap { it.findings },
            validatorVersion = validationRun.validatorVersions.values.joinToString(", "),
            generatorVersion = MustangVersion.value,
            profile = artifacts.profile,
            checkedAt = request.checkedAt,
            validationRun = validationRun,
        )
    }
}
