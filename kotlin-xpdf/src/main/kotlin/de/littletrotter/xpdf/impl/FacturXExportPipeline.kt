package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.FacturXEngine
import de.littletrotter.facturx.FacturXEngineOutcome
import de.littletrotter.facturx.FacturXFindings
import de.littletrotter.facturx.FacturXSchematronValidator
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Split out of [PdfExportServiceImpl.performFacturXExport] (kotlin:S3776) — a rejection or
 *  infrastructure abort from [FacturXEngine.generateXml] short-circuits the caller with a return
 *  value instead of a `return` from this function's own body, since Kotlin has no non-local
 *  return across a private-function boundary. */
internal sealed class FacturXXmlGenerationResult {
    class Ready(val xmlBytes: ByteArray) : FacturXXmlGenerationResult()
    class ShortCircuit(val outcome: FacturXExportOutcome) : FacturXXmlGenerationResult()
}

/** Split out of [PdfExportServiceImpl.performFacturXExport] (kotlin:S3776), same pattern as
 *  [FacturXXmlGenerationResult] above for [FacturXEngine.convert]'s own rejection/abort. */
internal sealed class FacturXPdfConversionResult {
    class Ready(val pdfBytes: ByteArray) : FacturXPdfConversionResult()
    class ShortCircuit(val outcome: FacturXExportOutcome) : FacturXPdfConversionResult()
}

/**
 * The Factur-X engine outcome handling [PdfExportServiceImpl.performFacturXExport]
 * delegates to: turning [FacturXEngine.generateXml]/[FacturXEngine.convert] results into
 * [FacturXXmlGenerationResult]/[FacturXPdfConversionResult], telling a genuine engine
 * **rejection** apart from an **infrastructure abort** ([isInfrastructureAbort]), and completing
 * ([completeRejectedFacturXExport]) or failing ([engineFailure]) the job accordingly — see the PO
 * decision (2026-08-25, extension #5) quoted on [completeRejectedFacturXExport] for the full
 * rationale. Split out of [PdfExportServiceImpl] (one reason to change: the Factur-X engine's own
 * accept/reject/abort contract), same "extract by moving, not rewriting" contract as every other
 * collaborator on that class.
 */
@ApplicationScoped
class FacturXExportPipeline @Inject constructor(
    private val facturXEngine: FacturXEngine,
    private val basePdfStage: BasePdfStage,
    private val exportCompletionStage: ExportCompletionStage,
    // The one injected source of "now" — see de.littletrotter.xpdf.TimeSourceProducer.
    // kotlin-facturx has no CDI of its own, so FacturXFindings.failed takes the moment as a
    // plain parameter, computed here from this bean's own injected TimeSource.
    private val time: TimeSource,
) {
    internal fun generateFacturXXml(request: ExportRequest): FacturXXmlGenerationResult =
        when (val xmlOutcome = facturXEngine.generateXml(request.invoice)) {
            is FacturXEngineOutcome.Failure -> {
                if (isInfrastructureAbort(xmlOutcome.findings)) {
                    FacturXXmlGenerationResult.ShortCircuit(engineFailure(xmlOutcome.findings, veraPdfCheck = request.veraPdfCheck))
                } else {
                    // PO decision (2026-08-25, extension #5) — a rejected `generateXml` still
                    // completes the job, with no XML at all (there is none to store or check).
                    val noXmlReason = "nicht ausgeführt — Engine hat das XML verweigert, kein Factur-X-Inhalt"
                    FacturXXmlGenerationResult.ShortCircuit(
                        completeRejectedFacturXExport(
                            request = request,
                            rejectionFindings = xmlOutcome.findings,
                            artifacts = ExportArtifacts.facturX(
                                xmlBytes = ByteArray(0),
                                zugferdXmlObjectKey = "", // nothing was stored — there is no XML at all
                                finalPdfBytes = basePdfStage.renderBase(request.invoice),
                                xsdVersionLabel = "kein Factur-X-Inhalt — Engine hat XML-Erzeugung verweigert",
                            ),
                            stages = ExportStages(
                                syntax = ValidationStageResult("SYNTAX", KositOutcome.ERROR, skipped = true, skipReason = noXmlReason),
                                schematron = ValidationStageResult(
                                    FacturXSchematronValidator.STAGE, KositOutcome.ERROR, skipped = true, skipReason = noXmlReason,
                                ),
                                kosit = ValidationStageResult("KOSIT", KositOutcome.ERROR, skipped = true, skipReason = noXmlReason),
                                brFrCtc = null, // no XML content exists for BR_FR_CTC to check, French recipient or not
                            ),
                            // suppressLlmScheduling derived as !artifacts.hasXml inside — true here (no XML at all)
                        ),
                    )
                }
            }
            // The engine's own metadata schema has no accounting-reference field at all
            // (FacturXMetadataMapper never reads BT-19), so without this the value was silently
            // lost on this route entirely, not merely missing at the header the way Mustang's
            // BT-133 line fanout at least partially covers for. Applied before storage/validation,
            // same as the Mustang route's own XmlExportServiceImpl.enrichInvoiceXml seam — see
            // Bt19HeaderInjector's class KDoc for the shared reasoning across both routes.
            is FacturXEngineOutcome.Success ->
                FacturXXmlGenerationResult.Ready(Bt19HeaderInjector.inject(xmlOutcome.value, request.invoice))
        }

    internal fun convertToFacturXPdf(
        request: ExportRequest,
        xmlBytes: ByteArray,
        basePdfBytes: ByteArray,
        stages: ExportStages,
    ): FacturXPdfConversionResult =
        when (val convertOutcome = facturXEngine.convert(basePdfBytes, request.invoice)) {
            is FacturXEngineOutcome.Failure -> {
                if (isInfrastructureAbort(convertOutcome.findings)) {
                    // SYNTAX/KOSIT genuinely already ran by this point; kept verbatim (real
                    // outcome/findings), not replaced with a not-run placeholder.
                    FacturXPdfConversionResult.ShortCircuit(
                        engineFailure(
                            convertOutcome.findings,
                            alreadyKnown = stages.preEngine,
                            veraPdfCheck = request.veraPdfCheck,
                        ),
                    )
                } else {
                    FacturXPdfConversionResult.ShortCircuit(
                        completeRejectedFacturXExport(
                            request = request,
                            rejectionFindings = convertOutcome.findings,
                            // real XML — already generated AND already stored above
                            artifacts = ExportArtifacts.facturX(
                                xmlBytes, request.keys.xmlObjectKey, basePdfBytes, ExportArtifacts.FACTURX_XSD_LABEL,
                            ),
                            // SYNTAX/SCHEMATRON/KOSIT/BR_FR_CTC genuinely ran (or were genuinely skipped/n/a) — kept verbatim
                            stages = stages,
                        ),
                    )
                }
            }
            is FacturXEngineOutcome.Success -> FacturXPdfConversionResult.Ready(convertOutcome.value)
        }

    /**
     * PO decision (2026-08-25, extension #5) — a Factur-X engine **rejection** (as opposed to an
     * infrastructure abort, see [isInfrastructureAbort]) completes the job instead of failing it:
     * the base PDF ([artifacts]' final PDF, un-embedded) is stored as the job's PDF artifact, normalized
     * for PDF/A-3 colour space exactly like the success path
     * ([PdfA3ColorSpaceNormalizer.ensureSrgbOutputIntent]) and watermark-stamped exactly like
     * every other PDF this worker produces ([ExportCompletionStage.completeExport]'s own
     * contract) — a rejected e-invoice still gets a printable, honest PDF, not a failed job and
     * not a silent Mustang fallback.
     *
     * [rejectionFindings] become the `FACTURX` stage's own findings at [KositOutcome.INVALID]
     * (not `ERROR`: the engine, or our own pre-check, made a definitive negative judgment — the
     * invoice failed a check, it was not left unchecked). PDFA/VERAPDF then run for real against
     * that base PDF via the normal [ExportCompletionStage.completeExport] machinery — a document
     * with no embedded Factur-X XML at all honestly reports that missing attachment, which is
     * exactly the point: the user should know this PDF's conformance, not just that it exists.
     *
     * Overall verdict: naturally [KositOutcome.INVALID] via [de.littletrotter.shared.ValidationRun.overallOutcomeOf] (the
     * `FACTURX` stage is the only always-active, always-`INVALID` stage here) unless PDFA/VERAPDF
     * themselves report `ERROR` (an unrelated technical failure, which correctly outranks it) —
     * no override needed, the existing priority rule already gives the PO's "INVALID, not a green
     * light" verdict for free.
     *
     * kotlin:LongParameterList — `suppressLlmScheduling` dropped as its own parameter (was always
     * exactly `!artifacts.hasXml` at both call sites: `true` for the no-XML `generateXml`
     * rejection, `false` for the `convert` rejection where the real XML already exists) and is now
     * derived here instead of duplicated at each call site.
     */
    internal fun completeRejectedFacturXExport(
        request: ExportRequest,
        rejectionFindings: List<ValidationMessage>,
        artifacts: ExportArtifacts,
        stages: ExportStages,
    ): FacturXExportOutcome.Success {
        val normalizedBasePdf = PdfA3ColorSpaceNormalizer.ensureSrgbOutputIntent(artifacts.finalPdfBytes)
        val engineStage = ValidationStageResult("FACTURX", KositOutcome.INVALID, findings = rejectionFindings)
        val result = exportCompletionStage.completeExport(
            request = request,
            artifacts = artifacts.withFinalPdf(normalizedBasePdf),
            stages = stages.withEngine(engineStage),
            suppressLlmScheduling = !artifacts.hasXml,
        )
        return FacturXExportOutcome.Success(result)
    }

    /**
     * PO decision (2026-08-25, extension #5) — the one predicate that decides which of the two
     * paths a [FacturXEngineOutcome.Failure] takes: `FACTURX-ENGINE-UNAVAILABLE` is the exact,
     * sole vocabulary [de.littletrotter.facturx.HttpFacturXEngineClient] (and [UnconfiguredFacturXEngine]) use for "the
     * engine could not even be asked" (transport failure, timeout, not configured) — every other
     * `ruleId` this port produces (`FACTURX-ENGINE-REJECTED`, `FACTURX-TAX-BREAKDOWN-MISMATCH`,
     * `FACTURX-PAYLOAD-TOO-LARGE`, a mapping failure) is a definitive judgment the engine or our
     * own pre-check already made. Each [FacturXEngineOutcome.Failure] this port ever produces
     * carries exactly one finding (see [de.littletrotter.facturx.HttpFacturXEngineClient]'s own call sites), so checking
     * `any` here is precise, not a heuristic over a mixed list.
     */
    private fun isInfrastructureAbort(findings: List<ValidationMessage>): Boolean =
        findings.any { it.ruleId == "FACTURX-ENGINE-UNAVAILABLE" }

    /**
     * Builds the clean `FAILED` outcome for an engine rejection: [findings] become the
     * `FACTURX` stage's own findings, encoded the same [KositReportPayload] shape as every other
     * job failure (see [FacturXFindings.failed]) so `JobConsumer` can publish it unchanged.
     *
     * Before this, an aborted run's report carried only
     * the `FACTURX` stage; a reader (the PO included) reasonably read that as "KoSIT doesn't run
     * on Factur-X" rather than "the job never got that far". [alreadyKnown] is whatever this
     * method's two call sites in [PdfExportServiceImpl.performFacturXExport] genuinely computed
     * before the abort (empty when `generateXml` itself is what failed; `[stage1, stage2to4]`
     * when `convert` is — SYNTAX/KOSIT already ran by then) and is kept verbatim;
     * [notRunStagesAfterAbort] fills in the rest as explicit, named "never ran" rows instead of
     * leaving them silently absent.
     */
    // veraPdfCheck dropped its default: both call sites (generateFacturXXml,
    // convertToFacturXPdf) always pass request.veraPdfCheck explicitly, so the default value was
    // dead code (a synthetic constructor-default line jacoco could never mark reached).
    private fun engineFailure(
        findings: List<ValidationMessage>,
        alreadyKnown: List<ValidationStageResult> = emptyList(),
        veraPdfCheck: Boolean,
    ): FacturXExportOutcome.Failed {
        val stages = notRunStagesAfterAbort(alreadyKnown, veraPdfCheck)
        return FacturXExportOutcome.Failed(findings, FacturXFindings.failed(findings, stages, time.nowUtc()))
    }

    /**
     * The stages a [PdfExportServiceImpl.performFacturXExport]
     * run would have gone through but never reached because the job aborted at the `FACTURX`
     * stage ([engineFailure]'s only caller). Every stage name already present in [alreadyKnown]
     * (a real SYNTAX/KOSIT result computed before a `convert()` rejection) is kept as-is, never
     * overwritten with a placeholder; every other stage this pipeline can ever produce gets an
     * explicit `skipped` row so a reader can tell "never ran, the job aborted here" apart from
     * "silently doesn't exist".
     *
     * [veraPdfCheck] mirrors [ExportCompletionStage.completeExport]'s own optional-layer contract:
     * VERAPDF is added only when the job actually requested it — absent otherwise,
     * never present-and-empty, same rule as the success path. BUSINESS keeps its own standing
     * reason ("not integrated yet") rather than the abort text — it never runs on ANY export,
     * success or failure, for a wholly unrelated reason (see
     * [ExportValidationStages.buildValidationReport]'s `stage6`).
     */
    private fun notRunStagesAfterAbort(
        alreadyKnown: List<ValidationStageResult>,
        veraPdfCheck: Boolean,
    ): List<ValidationStageResult> {
        val knownStageNames = alreadyKnown.map { it.stage }.toSet()
        val abortReason = "nicht ausgeführt — Lauf brach in der FACTURX-Stufe ab"
        val candidates = buildList {
            add(ValidationStageResult("SYNTAX", KositOutcome.ERROR, skipped = true, skipReason = abortReason))
            add(
                ValidationStageResult(
                    FacturXSchematronValidator.STAGE, KositOutcome.ERROR, skipped = true, skipReason = abortReason,
                ),
            )
            add(ValidationStageResult("KOSIT", KositOutcome.ERROR, skipped = true, skipReason = abortReason))
            add(ValidationStageResult("PDFA", KositOutcome.ERROR, skipped = true, skipReason = abortReason))
            if (veraPdfCheck) {
                add(ValidationStageResult("VERAPDF", KositOutcome.ERROR, skipped = true, skipReason = abortReason))
            }
            add(
                ValidationStageResult(
                    "BUSINESS", KositOutcome.VALID, skipped = true,
                    skipReason = "TAX-03/PARTY-01 noch nicht integriert",
                ),
            )
        }
        return alreadyKnown + candidates.filterNot { it.stage in knownStageNames }
    }
}
