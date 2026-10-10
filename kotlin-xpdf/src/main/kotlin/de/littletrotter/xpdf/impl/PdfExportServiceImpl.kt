package de.littletrotter.xpdf.impl

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.PdfExportPort
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.facturx.FacturXEngineOutcome

@ApplicationScoped
class PdfExportServiceImpl @Inject constructor(
    private val xmlExportService: XmlExportServiceImpl,
    private val documentStore: DocumentStore,
    // The shared base-PDF stage (render + receipt annex + PDF/A-3 baseline check), pulled out of
    // this class so a future second engine reaches the identical code instead of a
    // re-implementation (see BasePdfStage's class doc). No default: [BasePdfStage] is its own
    // `@ApplicationScoped @Inject constructor` CDI bean (CDI never read this class's own Kotlin
    // defaults to begin with, only direct Kotlin construction did) — every collaborator below
    // follows the same rule. Tests that want a plain working instance use
    // [de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory] instead of relying on
    // a hidden default here.
    private val basePdfStage: BasePdfStage,
    // The deterministic validation-stage runners + report assembly, split out of this class (see
    // ExportValidationStages's own class KDoc); its own `@ApplicationScoped @Inject constructor`
    // CDI bean. [kositValidator]/[facturXSchematronValidator]/[brFrCtcValidator]/[facturXEngine]/
    // [ciiXsdValidator] are no longer this class's own fields (the removed default-construction
    // chain was their only use here, LongParameterList territory once every default fell away):
    // CDI now wires each of them straight into [exportValidationStages]/[facturXExportPipeline]
    // themselves instead, including the SYNTAX stage runner (see
    // [ExportValidationStages.runSyntaxStage]'s own KDoc).
    private val exportValidationStages: ExportValidationStages,
    // The shared post-export machinery (store/checksum, archive signing, LLM scheduling,
    // watermark, ExportResult assembly), split out of this class; its own CDI bean.
    // [llmAnalysisStage]/[outputIntentVerifier]/[archiveSigner] are no longer this class's own
    // fields for the same reason as above; CDI wires them straight into this stage.
    private val exportCompletionStage: ExportCompletionStage,
    // The Factur-X engine outcome handling (XML/PDF generation results, rejection vs.
    // infrastructure-abort, completing/failing the job), split out of this class; its own CDI bean.
    private val facturXExportPipeline: FacturXExportPipeline,
    // The Mustang-route-only PDF finishing step (ZUGFeRD embedding + receipt-annex attachment),
    // split out of this class; never reached from the Factur-X route; its own CDI bean.
    private val mustangPdfRenderer: MustangPdfRenderer,
) : PdfExportPort {

    override fun performExport(
        invoice: InvoiceDto,
        jobId: String,
        llmOptimize: Boolean,
        skipValidation: Boolean,
        veraPdfCheck: Boolean,
        outputIntent: String?,
    ): ExportResult {
        val exportKeys = de.littletrotter.shared.DocumentKeyRules.deriveJobExportKeys(
            invoice.invoiceNumber.value, invoice.id, jobId, invoice.draft,
        )
        val zugferdXmlObjectKey = exportKeys.xmlObjectKey

        // 1. Generate ZUGFeRD XML (original) — always stored
        val xmlBytes = xmlExportService.generate(invoice)
        documentStore.put(zugferdXmlObjectKey, xmlBytes, "application/xml")

        val profile = xmlExportService.resolveProfile(invoice)

        // Stages 1/2-4: stage 1 (CII-XSD) runs before KoSIT, so a pure syntax error is named as
        // such instead of as a KoSIT-Schematron follow-on error; an INVALID/ERROR at stage 1
        // skips the KoSIT run (stages 2-4) entirely — running an already schema-invalid document
        // against KoSIT only produces follow-on noise.
        // no check actually ran under skipValidation — a timestamp here would misrepresent one.
        val checkedAt = exportValidationStages.checkedAtOrNull(skipValidation)
        val stage1 = exportValidationStages.runSyntaxStage(xmlBytes, skipValidation, jobId)
        // Chain order XSD -> Factur-X Schematron -> KoSIT (XRechnung CIUS context) -> veraPDF ->
        // LLM; see FacturXSchematronValidator's own class KDoc for why this stage requires
        // stage1 (SYNTAX) to have actually reported VALID first.
        val schematronStage = exportValidationStages.runFacturXSchematronStage(stage1, xmlBytes, skipValidation)
        // profileStage carries the recipient warning for `EXTENDED` (PO decision 2026-09-28);
        // `null` for every other profile, see ExportValidationStages.buildProfileStage's own KDoc.
        val profileStage = exportValidationStages.buildProfileStage(profile)
        val stage2to4 = exportValidationStages.runKositStage(stage1, xmlBytes, skipValidation, jobId, profile)

        // 3. PDF rendern + speichern (always original XML — no LLM correction embedding).
        // Rendered before the LLM step (moved ahead of it) so the LLM can also see how the
        // XML actually laid out on the page, not just the XML in isolation.
        // If SYNTAX validation has already failed, skip ZUGFeRD embedding entirely
        // (no point attempting to embed invalid XML).
        // stage1 is only ever built with skipped=true when its outcome is VALID (the
        // skipValidation short-circuit above) — "invalid AND skipped" can never occur here.
        val finalPdfBytes = enforceSizeBackstop(
            if (stage1.outcome != KositOutcome.VALID) {
                basePdfStage.renderBase(invoice)
            } else {
                mustangPdfRenderer.renderPdfWithZugferd(invoice, xmlBytes)
            },
        )

        // Everything from here on (store, checksum, PDF/A-3 + optional veraPDF, the BUSINESS
        // placeholder, LLM analysis, building ExportResult) is identical for Mustang and
        // Factur-X; see [completeExport]. Mustang-specific stays only
        // [renderPdfWithZugferd] above; [engineStage] is `null` because Mustang has no
        // engine-of-its-own validation stage — only the Factur-X route (`performFacturXExport`)
        // adds one.
        return exportCompletionStage.completeExport(
            request = ExportRequest(invoice, jobId, exportKeys, checkedAt, skipValidation, veraPdfCheck, llmOptimize, outputIntent),
            artifacts = ExportArtifacts(
                xmlBytes = xmlBytes,
                zugferdXmlObjectKey = zugferdXmlObjectKey,
                finalPdfBytes = finalPdfBytes,
                profile = profile,
                expectedAttachmentFilename = mustangPdfRenderer.expectedZugferdAttachmentFilename(profile),
                xsdVersionLabel = "CII D16B",
            ),
            stages = ExportStages(syntax = stage1, schematron = schematronStage, kosit = stage2to4, profile = profileStage),
        )
    }

    /**
     * The Factur-X counterpart to [performExport].
     * The engine-specific steps are exactly [BasePdfStage.renderBase] → [FacturXEngine.convert];
     * everything after that final PDF exists is the same
     * [ExportCompletionStage.completeExport] Mustang calls, so object keys/stage names/report
     * shape stay identical between engines — the only route-specific addition is the `FACTURX`
     * stage itself.
     *
     * PO decision (2026-08-25, extension #5 — "Die Factur-X-Meldung darf nie zu Abbrüchen
     * führen"): a [FacturXEngineOutcome.Failure] from [FacturXEngine.generateXml] or
     * [FacturXEngine.convert] no longer fails the job. [FacturXExportPipeline] tells the two
     * kinds of failure apart:
     * - a genuine **rejection** (the engine, or our own pre-check, made a definitive negative
     *   judgment about the invoice content — `FACTURX-ENGINE-REJECTED`,
     *   `FACTURX-TAX-BREAKDOWN-MISMATCH`, `FACTURX-PAYLOAD-TOO-LARGE`, a mapping failure) now
     *   **completes** the job via [FacturXExportPipeline.completeRejectedFacturXExport]: the base
     *   PDF is stored anyway (no silent fallback to Mustang, no ZUGFeRD embedding), the rejection
     *   becomes the `FACTURX` stage's own findings, and PDFA/VERAPDF run for real against that
     *   base PDF — a rejected e-invoice is not a valid one, but the user still gets a printable
     *   PDF and an honest report, not a failed job;
     * - a genuine **infrastructure abort** (`FACTURX-ENGINE-UNAVAILABLE` — the engine could not
     *   even be asked: unreachable, timed out) still returns [FacturXExportOutcome.Failed] via
     *   [FacturXExportPipeline]'s own `engineFailure` exactly as before this decision — "the invoice was never checked" is a
     *   different statement than "the invoice failed a check", and only the latter has a PDF to
     *   show for itself.
     *
     * An unrelated infrastructure failure elsewhere (e.g. [InvoiceDocumentTooLargeException] from
     * [enforceSizeBackstop]) still propagates as an exception exactly like it does from
     * [performExport] — only the engine's own accept/reject verdict is modeled as a return value.
     */
    override fun performFacturXExport(
        invoice: InvoiceDto,
        jobId: String,
        llmOptimize: Boolean,
        skipValidation: Boolean,
        veraPdfCheck: Boolean,
        outputIntent: String?,
    ): FacturXExportOutcome {
        val exportKeys = de.littletrotter.shared.DocumentKeyRules.deriveJobExportKeys(
            invoice.invoiceNumber.value, invoice.id, jobId, invoice.draft,
        )
        val zugferdXmlObjectKey = exportKeys.xmlObjectKey
        val checkedAt = exportValidationStages.checkedAtOrNull(skipValidation)
        val request = ExportRequest(invoice, jobId, exportKeys, checkedAt, skipValidation, veraPdfCheck, llmOptimize, outputIntent)

        // The standalone CII/D22B XML — generated separately from `convert` (see the port's own
        // KDoc: `generateXml` exists specifically so this XML can be KoSIT-validated the same way
        // Mustang's is). A rejection here means the job never even reaches `convert`
        // — there is nothing to embed and nothing to run KoSIT against (extension #5: the job
        // still completes, with a base PDF and no XML at all — see [completeRejectedFacturXExport]).
        val xmlBytes = when (
            val xmlResult = facturXExportPipeline.generateFacturXXml(request)
        ) {
            is FacturXXmlGenerationResult.ShortCircuit -> return xmlResult.outcome
            is FacturXXmlGenerationResult.Ready -> xmlResult.xmlBytes
        }
        documentStore.put(zugferdXmlObjectKey, xmlBytes, "application/xml")

        // The engine delivers CII D22B, CiiXsdValidator checks D16B; validating against the
        // wrong schema would produce false errors, so SYNTAX is reported `skipped` with a named
        // reason instead of silently checked against the wrong version. The mandatory EN 16931
        // check (KOSIT stage) still runs — `stage1.outcome == VALID` here means [runKositStage]
        // never blocks it on this route.
        val stage1 = ValidationStageResult(
            "SYNTAX", KositOutcome.VALID, skipped = true,
            skipReason = "engine delivers CII D22B, embedded XSD set is D16B",
        )
        // stage1 is always (trivially) VALID-but-skipped on this route (above), so
        // runFacturXSchematronStage always reports this stage skipped too: the bundled Factur-X
        // Schematron targets D16B-era CII, and running it against the engine's D22B output would
        // be exactly the same "meaningless follow-on noise" risk stage1's own skip exists to
        // avoid (see FacturXSchematronValidator's class KDoc, "Precondition" section). This is
        // the Mustang-route-only real check for now; a future D22B-aware stylesheet could lift
        // this restriction without touching the Mustang route at all.
        val schematronStage = exportValidationStages.runFacturXSchematronStage(stage1, xmlBytes, skipValidation)
        // This route's profile is always ExportArtifacts.FACTURX_PROFILE ("EN16931", see
        // ExportArtifacts.facturX's own KDoc) — never EXTENDED, so runKositStage's EXTENDED
        // skip branch never triggers here and buildProfileStage would always return null; the
        // PROFILE stage is therefore not built on this route at all (ExportStages.profile stays
        // its default `null`, "absent, not present-and-empty").
        val stage2to4 = exportValidationStages.runKositStage(stage1, xmlBytes, skipValidation, jobId, ExportArtifacts.FACTURX_PROFILE)

        // BR_FR_CTC runs exclusively when the recipient's billing-address country is France, the
        // same source ArdennesXRules.kt:36 reads for the same question. LU/BE recipients never
        // trigger it (BR-FR-CTC is purely French) — this is a gating *condition*, not a flag: for
        // any other recipient country the stage is simply never built, so it is absent from the
        // report, not present-and-empty. Only the recipient's country decides *whether* the stage
        // runs; the *seller's* country (read inside BrFrCtcValidator itself) decides only its
        // severity branch — see that class's KDoc for why those are two different questions.
        // Computed here (moved ahead of `convert`) — [xmlBytes] is everything this needs, so a
        // rejected `convert` can reuse this exact same, already-real result instead of
        // recomputing or dropping it.
        val brFrCtcStage = exportValidationStages.resolveBrFrCtcStage(invoice, xmlBytes, skipValidation)
        val brFrCtcRan = brFrCtcStage != null && !brFrCtcStage.skipped
        val stages = ExportStages(syntax = stage1, schematron = schematronStage, kosit = stage2to4, brFrCtc = brFrCtcStage)

        // `renderBase` produces the shared, engine-agnostic base document (template only — the
        // belege live solely in [xmlBytes]' BG-24 Base64, see [BasePdfStage]'s own class doc),
        // identical bytes Mustang starts from; `convert` (BYOPDF) embeds the engine's own
        // CII XML into it (`/v1/merge` rejects our rendered PDF, `/v1/convert` is the proven path).
        val basePdfBytes = basePdfStage.renderBase(invoice)
        val engineFinalPdfBytes = when (
            val convertResult = facturXExportPipeline.convertToFacturXPdf(request, xmlBytes, basePdfBytes, stages)
        ) {
            is FacturXPdfConversionResult.ShortCircuit -> return convertResult.outcome
            is FacturXPdfConversionResult.Ready -> convertResult.pdfBytes
        }
        // A PO-reported live job showed the engine's own convert() round-trip drops the PDF/A
        // output intent renderBase's bytes carried (see PdfA3ColorSpaceNormalizer's own KDoc:
        // ISO 19005-3:2012 clause 6.2.4.3 tests 2/4). Normalized here, before anything
        // downstream (the engine's own validate() call right below, and completeExport's PDF/A +
        // veraPDF checks and watermark stamp) ever sees these bytes — the rest of this route is
        // unaffected by whether the engine happened to preserve it.
        val finalPdfBytes = enforceSizeBackstop(PdfA3ColorSpaceNormalizer.ensureSrgbOutputIntent(engineFinalPdfBytes))

        // The `FACTURX` stage — the engine's own EN 16931 validation, XML-only (PO decision,
        // 2026-08-25): validates the standalone CII/D22B [xmlBytes] this route already generated,
        // not the rendered PDF — the engine's PDF/A-3b layer is redundant with this pipeline's own
        // authoritative PDFA/VERAPDF stages (and license-gated on the pinned image regardless).
        // KoSIT (above) and PDFA/VERAPDF (completeExport, below) remain the dual authority for
        // the XML and the PDF respectively — see [FacturXEngine.validate]'s own KDoc. Honors
        // [skipValidation] like every other validation stage in this pipeline — the engine call
        // itself is skipped, not just its result ignored.
        val engineStage = exportValidationStages.buildFacturXEngineStage(xmlBytes, invoice, skipValidation, brFrCtcRan)

        val result = exportCompletionStage.completeExport(
            request = request,
            artifacts = ExportArtifacts.facturX(xmlBytes, zugferdXmlObjectKey, finalPdfBytes, ExportArtifacts.FACTURX_XSD_LABEL),
            stages = stages.withEngine(engineStage),
        )
        return FacturXExportOutcome.Success(result)
    }

    /**
     * The last-line backstop, applied once per route right where each route's own final PDF
     * bytes exist (Mustang: after ZUGFeRD embedding, or the un-embedded base PDF on a
     * SYNTAX-invalid job; Factur-X: after the engine's `convert` and the sRGB output-intent
     * normalization) — every stage that could still grow the document (receipt-as-XML embedding,
     * engine conversion) has already run by this point, so [pdfBytes]' size is the size a
     * customer would actually receive. A beleg is carried exactly once, as Base64 inside the
     * embedded XML (BG-24) — the strictest transport profile (XRechnung) needs it there anyway —
     * so this is the one place size can still run away (many/large belege, one Base64 XML), and
     * the one place that must say so loudly instead of silently shipping (or truncating) an
     * oversized container.
     */
    private fun enforceSizeBackstop(pdfBytes: ByteArray): ByteArray {
        val actual = DocumentSize.ofBytes(pdfBytes.size.toLong())
        if (actual > ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE) {
            throw InvoiceDocumentTooLargeException(actual, ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE)
        }
        return pdfBytes
    }
}

/**
 * Thrown by [PdfExportServiceImpl.enforceSizeBackstop] when the final invoice document — after
 * every stage that could grow it, watermark included — exceeds
 * [ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE]. Replaces the former `ReceiptAnnexException`'s
 * own size check: since belege are no longer rasterized into the PDF or attached to it a second
 * time (they live solely as Base64 in the embedded XML, BG-24), the one remaining size risk is
 * this document-wide total, not a per-annex budget. Never truncated away — a caller that hits
 * this must shrink the belege (fewer, smaller, or split across invoices), not silently lose data.
 */
class InvoiceDocumentTooLargeException(val actual: DocumentSize, val limit: DocumentSize) : RuntimeException(
    "Invoice document is ${mib(actual)} MiB, exceeding the maximum of ${mib(limit)} MiB",
) {
    private companion object {
        // Locale.ROOT, not the JVM default — this message is English document content (see class
        // KDoc), never a locale-dependent decimal separator ("20,0" on a German-default JVM).
        fun mib(size: DocumentSize) = "%.1f".format(java.util.Locale.ROOT, size.mebiBytes)
    }
}
