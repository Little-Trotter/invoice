package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.validation.CiiSchematronValidator
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
 * The deterministic validation-stage runners (SYNTAX/CII-XSD,
 * KOSIT, the Factur-X Schematron) plus the
 * PDF/A-3 and veraPDF-placeholder report assembly ([buildValidationReport]). Split out of [PdfExportServiceImpl]
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
    private val ciiSchematronValidator: CiiSchematronValidator,
    private val basePdfStage: BasePdfStage,
    private val ciiXsdValidator: CiiXsdValidator,
    // The one injected source of "now" — see de.littletrotter.xpdf.TimeSourceProducer.
    private val time: TimeSource,
    private val timers: ExportStageTimers,
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

        /** The profile used when a caller does not name one (plain EN 16931). */
        const val DEFAULT_PROFILE = "EN16931"

        /** The profile of invoices to public bodies. Their recipient portals reject on the
         *  data-dependent XRechnung rules (buyer reference, Leitweg-ID), which only KoSIT
         *  evaluates, so the KoSIT switch never applies to it. */
        const val XRECHNUNG_PROFILE = "XRechnung"

        /** Why KoSIT did not run although the profile would allow it: the user switched it off. */
        const val KOSIT_NOT_ENABLED_REASON = "nicht aktiviert"

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
     * The moment [PdfExportServiceImpl.performExport] stamps its own
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
     * of as a KoSIT-Schematron follow-on error.
     */
    internal fun runSyntaxStage(xmlBytes: ByteArray, skipValidation: Boolean, jobId: String): ValidationStageResult {
        if (skipValidation) {
            log.info("KoSIT validation skipped by user request (job $jobId)")
            return ValidationStageResult("SYNTAX", KositOutcome.VALID, skipped = true, skipReason = SKIP_VALIDATION_REASON)
        }
        val xsdResult = timers.record(ExportStage.SYNTAX) { ciiXsdValidator.validate(String(xmlBytes, Charsets.UTF_8)) }
        return ValidationStageResult("SYNTAX", xsdResult.outcome, xsdResult.findings)
    }

    /**
     * Runs KoSIT against [xmlBytes] unless [skipValidation] is set, [profile] is `EXTENDED`
     * (PO decision 2026-09-28, [KOSIT_EXTENDED_SKIP_REASON] — no bundled scenario matches that
     * guideline URN, see [KositValidationIT]'s own probe), or [stage1]'s own outcome blocks it:
     * a real CII-XSD failure on Mustang must never be followed by a KoSIT run that would only add
     * confusing follow-on Schematron noise. With [kositCheck] off the stage is skipped as
     * [KOSIT_NOT_ENABLED_REASON] — except for [XRECHNUNG_PROFILE], which is always validated.
     */
    internal fun runKositStage(
        stage1: ValidationStageResult,
        xmlBytes: ByteArray,
        skipValidation: Boolean,
        jobId: String,
        // kotlin:S107/LongParameterList (detekt.yml's `ignoreDefaultParameters`) — defaults to
        // the same "never EXTENDED" fallback [de.littletrotter.xpdf.impl.mustang.MustangExportModel
        // .exportProfile] already establishes; only [PdfExportServiceImpl.performExport] passes a
        // real, possibly-EXTENDED value.
        profile: String = DEFAULT_PROFILE,
        kositCheck: Boolean = true,
    ): ValidationStageResult {
        if (skipValidation) {
            return ValidationStageResult("KOSIT", KositOutcome.VALID, skipped = true, skipReason = SKIP_VALIDATION_REASON)
        }
        if (profile == EXTENDED_PROFILE) {
            return ValidationStageResult("KOSIT", KositOutcome.VALID, skipped = true, skipReason = KOSIT_EXTENDED_SKIP_REASON)
        }
        if (!kositCheck && profile != XRECHNUNG_PROFILE) {
            return ValidationStageResult("KOSIT", KositOutcome.VALID, skipped = true, skipReason = KOSIT_NOT_ENABLED_REASON)
        }
        return if (stage1.outcome != KositOutcome.VALID) {
            ValidationStageResult(
                "KOSIT", KositOutcome.ERROR, skipped = true,
                skipReason = "Stufe 1 (CII-XSD) war ${stage1.outcome} — KoSIT-Lauf übersprungen",
            )
        } else {
            val raw = timers.record(ExportStage.KOSIT) { kositValidator.validate(String(xmlBytes, Charsets.UTF_8)) }
            if (raw.status != KositOutcome.VALID) {
                log.warn("KoSIT validation failed for job $jobId: ${raw.summary}")
            }
            ValidationStageResult("KOSIT", raw.status, raw.details)
        }
    }

    /**
     * Runs [ciiSchematronValidator] against [xmlBytes] unless [skipValidation] is set
     * or [stage1]'s own outcome blocks it: this stage's own precondition, mirrored from
     * [de.littletrotter.xpdf.impl.CiiXsdValidator]'s KDoc, is a schema-valid document — a
     * Schematron pass over an already-invalid or deliberately-skipped SYNTAX stage would only add
     * confusing follow-on noise (same reasoning [runKositStage] applies). No profile is
     * chosen here: [CiiSchematronValidator.validate] reads the document's own guideline URN
     * and routes itself (EN16931, EXTENDED, or an explicit XRechnung pass-through) — see that
     * class's own KDoc, "Profile routing" section, for why a hardcoded default here would be
     * wrong (it would misreport every XRechnung-profile export, our other real "EN16931"/
     * "XRechnung" split from [de.littletrotter.xpdf.impl.XmlExportServiceImpl.resolveProfile]).
     */
    internal fun runCiiSchematronStage(
        stage1: ValidationStageResult,
        xmlBytes: ByteArray,
        skipValidation: Boolean,
    ): ValidationStageResult {
        if (skipValidation) {
            return ValidationStageResult(
                CiiSchematronValidator.STAGE, KositOutcome.VALID, skipped = true, skipReason = SKIP_VALIDATION_REASON,
            )
        }
        return if (stage1.outcome != KositOutcome.VALID || stage1.skipped) {
            ValidationStageResult(
                CiiSchematronValidator.STAGE, KositOutcome.ERROR, skipped = true,
                skipReason = "Stufe 1 (CII-XSD) war ${stage1.outcome}" +
                    (if (stage1.skipped) " (übersprungen)" else "") +
                    " — Factur-X-Schematron-Lauf übersprungen: schema-valides Dokument ist Voraussetzung",
            )
        } else {
            timers.record(ExportStage.SCHEMATRON) { ciiSchematronValidator.validate(xmlBytes) }
        }
    }

    /**
     * The `PROFILE` stage — present only for [EXTENDED_PROFILE] (`null` for every other
     * profile, "absent, not present-and-empty", the same convention [ExportStages]' own KDoc
     * documents for the other optional stages). Never `skipped`: unlike
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
     * The VERAPDF stage as the worker can state it when the document is stored: pending when the
     * check was requested (its result arrives later and replaces this stage), skipped as not
     * enabled otherwise. Always advisory: findings of this stage never decide the overall verdict.
     */
    private fun veraPdfPlaceholderStage(request: ExportRequest): ValidationStageResult = when {
        request.skipValidation ->
            ValidationStageResult(
                ValidationStageResult.VERAPDF, KositOutcome.VALID, skipped = true,
                skipReason = SKIP_VALIDATION_REASON, advisory = true,
            )
        request.veraPdfCheck ->
            ValidationStageResult(ValidationStageResult.VERAPDF, KositOutcome.VALID, pending = true, advisory = true)
        else ->
            ValidationStageResult(
                ValidationStageResult.VERAPDF, KositOutcome.VALID, skipped = true,
                skipReason = KOSIT_NOT_ENABLED_REASON, advisory = true,
            )
    }

    /**
     * Stages 5-6 plus the report itself — the mandatory PDF/A-3 check (+ the veraPDF
     * placeholder) on the exact [stampedPdfBytes] a customer receives, the BUSINESS placeholder, and the
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
        // veraPDF conformance runs afterwards as its own service on the stored document, so the
        // stage leaves here as "pending" when requested and is replaced once the result arrives.
        val veraPdfStage = veraPdfPlaceholderStage(request)
        // Stage 6 (business tax/identity findings): laid out empty until the seller tax/identity
        // checks are added — explicitly `skipped`, so "not checked" in the report never reads as "passed".
        val stage6 = ValidationStageResult(
            "BUSINESS", KositOutcome.VALID, skipped = true,
            skipReason = "TAX-03/PARTY-01 noch nicht integriert",
        )

        // Chain order: stage1 (SYNTAX) -> schematronStage (FACTURX_SCHEMATRON) -> profileStage (PROFILE, EXTENDED only) -> stage2to4 (KOSIT) -> stage5 (PDFA) -> optionalVeraPdfStage -> stage6 (BUSINESS).
        val chain = stages.chain(pdfa = stage5, veraPdf = veraPdfStage, business = stage6)
        val overall = ValidationRun.overallOutcomeOf(chain)
        val validationRun = ValidationRun(
            stages = chain,
            overall = overall,
            validatorVersions = buildMap {
                put("xsd", artifacts.xsdVersionLabel)
                put("kosit", "embedded")
                put("pdfa", pdfaEngineLabel)
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
