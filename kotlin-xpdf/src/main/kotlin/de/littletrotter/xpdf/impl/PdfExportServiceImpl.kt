package de.littletrotter.xpdf.impl

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.shared.DocumentStore
import de.littletrotter.xpdf.ExportSwitches
import de.littletrotter.xpdf.NonRetryableExportException
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.PdfExportPort
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy

@ApplicationScoped
class PdfExportServiceImpl @Inject constructor(
    private val xmlExportService: XmlExportServiceImpl,
    private val documentStore: DocumentStore,
    // The deterministic validation-stage runners + report assembly, split out of this class (see
    // ExportValidationStages's own class KDoc); its own `@ApplicationScoped @Inject constructor`
    // CDI bean. [kositValidator]/[ciiSchematronValidator]/[ciiXsdValidator] are not this class's
    // own fields: CDI wires each of them straight into [exportValidationStages] instead,
    // including the SYNTAX stage runner (see [ExportValidationStages.runSyntaxStage]'s own KDoc).
    private val exportValidationStages: ExportValidationStages,
    // The shared post-export machinery (store/checksum, archive signing, LLM scheduling,
    // watermark, ExportResult assembly), split out of this class; its own CDI bean.
    // [llmAnalysisStage]/[outputIntentVerifier]/[archiveSigner] are no longer this class's own
    // fields for the same reason as above; CDI wires them straight into this stage.
    private val exportCompletionStage: ExportCompletionStage,
    // Picks the mark, renders the base document with receipt pages and embeds the XML.
    private val pdfAssembler: ExportPdfAssembler,
    // Whole-job timer and the scope the per-stage timers record into.
    private val timers: ExportStageTimers,
) : PdfExportPort {

    override fun performExport(
        invoice: InvoiceDto,
        jobId: String,
        llmOptimize: Boolean,
        skipValidation: Boolean,
        veraPdfCheck: Boolean,
        outputIntent: String?,
    ): ExportResult = timers.job(invoice) {
        val exportKeys = de.littletrotter.shared.DocumentKeyRules.deriveJobExportKeys(
            invoice.invoiceNumber.value, invoice.id, jobId, invoice.draft,
        )
        val zugferdXmlObjectKey = exportKeys.xmlObjectKey

        // 1. Generate ZUGFeRD XML (original) — always stored
        val xmlBytes = xmlExportService.generate(invoice)
        timers.record(ExportStage.STORE) { documentStore.put(zugferdXmlObjectKey, xmlBytes, "application/xml") }

        val profile = xmlExportService.resolveProfile(invoice)

        // Stages 1/2-4: stage 1 (CII-XSD) runs before KoSIT, so a pure syntax error is named as
        // such instead of as a KoSIT-Schematron follow-on error; an INVALID/ERROR at stage 1
        // skips the KoSIT run (stages 2-4) entirely — running an already schema-invalid document
        // against KoSIT only produces follow-on noise.
        // no check actually ran under skipValidation — a timestamp here would misrepresent one.
        val checkedAt = exportValidationStages.checkedAtOrNull(skipValidation)
        val stage1 = exportValidationStages.runSyntaxStage(xmlBytes, skipValidation, jobId)
        // Chain order XSD -> Factur-X Schematron -> KoSIT (XRechnung CIUS context) -> veraPDF ->
        // LLM; see CiiSchematronValidator's own class KDoc for why this stage requires
        // stage1 (SYNTAX) to have actually reported VALID first.
        val schematronStage = exportValidationStages.runCiiSchematronStage(stage1, xmlBytes, skipValidation)
        // profileStage carries the recipient warning for `EXTENDED` (PO decision 2026-09-28);
        // `null` for every other profile, see ExportValidationStages.buildProfileStage's own KDoc.
        val profileStage = exportValidationStages.buildProfileStage(profile)
        val stage2to4 = exportValidationStages.runKositStage(stage1, xmlBytes, skipValidation, jobId, profile, ExportSwitches.kositCheck())

        // 3. PDF rendern + speichern (always original XML — no LLM correction embedding).
        // Rendered before the LLM step (moved ahead of it) so the LLM can also see how the
        // XML actually laid out on the page, not just the XML in isolation.
        // If SYNTAX validation has already failed, skip ZUGFeRD embedding entirely
        // (no point attempting to embed invalid XML).
        // stage1 is only ever built with skipped=true when its outcome is VALID (the
        // skipValidation short-circuit above) — "invalid AND skipped" can never occur here.
        val finalPdfBytes = enforceSizeBackstop(
            pdfAssembler.assemble(invoice, xmlBytes, embedXml = stage1.outcome == KositOutcome.VALID, outputIntent = outputIntent),
        )

        // Everything from here on (store, checksum, PDF/A-3 + optional veraPDF, the BUSINESS
        // placeholder, LLM analysis, building ExportResult) lives in [completeExport].
        exportCompletionStage.completeExport(
            request = ExportRequest(invoice, jobId, exportKeys, checkedAt, skipValidation, veraPdfCheck, llmOptimize, outputIntent),
            artifacts = ExportArtifacts(
                xmlBytes = xmlBytes,
                zugferdXmlObjectKey = zugferdXmlObjectKey,
                finalPdfBytes = finalPdfBytes,
                profile = profile,
                expectedAttachmentFilename = pdfAssembler.expectedAttachmentFilename(profile),
                xsdVersionLabel = "CII D16B",
            ),
            stages = ExportStages(syntax = stage1, schematron = schematronStage, kosit = stage2to4, profile = profileStage),
        )
    }

    /**
     * The last-line backstop, applied right where the final PDF
     * bytes exist (after ZUGFeRD embedding, or the un-embedded base PDF on a
     * SYNTAX-invalid job) — every stage that could still grow the document (receipt-as-XML
     * embedding) has already run by this point, so [pdfBytes]' size is the size a
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
class InvoiceDocumentTooLargeException(val actual: DocumentSize, val limit: DocumentSize) : NonRetryableExportException(
    "Invoice document is ${mib(actual)} MiB, exceeding the maximum of ${mib(limit)} MiB",
) {
    private companion object {
        // Locale.ROOT, not the JVM default — this message is English document content (see class
        // KDoc), never a locale-dependent decimal separator ("20,0" on a German-default JVM).
        fun mib(size: DocumentSize) = "%.1f".format(java.util.Locale.ROOT, size.mebiBytes)
    }
}
