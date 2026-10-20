package de.littletrotter.xpdf.impl

import de.littletrotter.shared.ExportKeys
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.ValidationStageResult

/**
 * The per-job inputs every stage of [PdfExportServiceImpl]'s export pipeline
 * shares, derived once at the start of `performExport` and threaded
 * unchanged through every later stage (parameter object, kotlin:S107). [checkedAt] is `null` exactly when [skipValidation] is set:
 * no check actually ran, so a timestamp would misrepresent one.
 */
internal class ExportRequest(
    val invoice: InvoiceDto,
    val jobId: String,
    val keys: ExportKeys,
    val checkedAt: String?,
    val skipValidation: Boolean,
    val veraPdfCheck: Boolean,
    val llmOptimize: Boolean,
    val outputIntent: String? = null,
)

/**
 * The artifacts the export hands the post-export machinery (parameter object, kotlin:S107): the
 * XML (plus the key it was stored under), the final PDF and the labels the validation report
 * carries. [zugferdXmlObjectKey] is blank when there is no XML at all, which [hasXml] reports.
 */
internal class ExportArtifacts(
    val xmlBytes: ByteArray,
    val zugferdXmlObjectKey: String,
    val finalPdfBytes: ByteArray,
    val profile: String,
    val expectedAttachmentFilename: String,
    val xsdVersionLabel: String,
) {
    val hasXml: Boolean get() = zugferdXmlObjectKey.isNotBlank()

    fun withFinalPdf(pdfBytes: ByteArray): ExportArtifacts =
        ExportArtifacts(xmlBytes, zugferdXmlObjectKey, pdfBytes, profile, expectedAttachmentFilename, xsdVersionLabel)
}

/**
 * The validation stages computed before the shared post-export machinery runs (parameter object, kotlin:S107).
 * [syntax]/[schematron]/[kosit] are always present (a real result or an explicit skipped
 * placeholder); [profile] is `null` for every profile but `EXTENDED` (see
 * [ExportValidationStages.buildProfileStage]). [chain] fixes the report's stage order:
 * SYNTAX -> FACTURX_SCHEMATRON -> PROFILE -> KOSIT -> PDFA -> VERAPDF -> BUSINESS, with every
 * absent optional stage left out, never present-and-empty.
 */
internal class ExportStages(
    val syntax: ValidationStageResult,
    val schematron: ValidationStageResult,
    val kosit: ValidationStageResult,
    val profile: ValidationStageResult? = null,
) {
    fun chain(
        pdfa: ValidationStageResult,
        veraPdf: ValidationStageResult?,
        business: ValidationStageResult,
    ): List<ValidationStageResult> =
        listOfNotNull(syntax, schematron, profile, kosit, pdfa, veraPdf, business)
}
