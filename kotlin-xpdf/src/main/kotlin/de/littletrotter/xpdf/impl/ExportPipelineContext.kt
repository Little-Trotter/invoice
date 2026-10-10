package de.littletrotter.xpdf.impl

import de.littletrotter.shared.ExportKeys
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.ValidationStageResult

/**
 * The per-job inputs every stage of [PdfExportServiceImpl]'s export pipeline
 * shares, derived once at the start of `performExport`/`performFacturXExport` and threaded
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
 * The engine-specific artifacts one export route hands the shared post-export
 * machinery (parameter object, kotlin:S107): the XML (plus the key it was stored under), the final PDF and the labels the
 * validation report carries. [zugferdXmlObjectKey] is blank only for the one Factur-X outcome with
 * no XML at all (the engine rejected `generateXml`), which [hasXml] reports.
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

    companion object {
        const val FACTURX_PROFILE = "EN16931"
        const val FACTURX_ATTACHMENT = "factur-x.xml"

        /** The Factur-X route's validator-version label (moved from
         *  [PdfExportServiceImpl]'s own private companion so [FacturXExportPipeline] can share
         *  it too): SYNTAX is skipped because the engine delivers D22B. */
        const val FACTURX_XSD_LABEL = "CII D16B (SYNTAX skipped — engine delivers D22B)"

        /** The Factur-X route's fixed profile (always sent, never rewritten) and attachment name. */
        fun facturX(
            xmlBytes: ByteArray,
            zugferdXmlObjectKey: String,
            finalPdfBytes: ByteArray,
            xsdVersionLabel: String,
        ): ExportArtifacts =
            ExportArtifacts(xmlBytes, zugferdXmlObjectKey, finalPdfBytes, FACTURX_PROFILE, FACTURX_ATTACHMENT, xsdVersionLabel)
    }
}

/**
 * The validation stages computed before the shared post-export machinery runs (parameter object, kotlin:S107).
 * [syntax]/[schematron]/[kosit] are always present (a real result or an explicit skipped
 * placeholder); [profile] is `null` for every profile but `EXTENDED` (see
 * [ExportValidationStages.buildProfileStage]), [brFrCtc] is `null` unless the recipient is
 * French, [engine] is `null` on the Mustang route (no engine-of-its-own validation there).
 * [chain] fixes the report's stage order:
 * SYNTAX -> FACTURX_SCHEMATRON -> PROFILE -> KOSIT -> PDFA -> VERAPDF -> FACTURX -> BR_FR_CTC ->
 * BUSINESS, with every absent optional stage left out, never present-and-empty.
 */
internal class ExportStages(
    val syntax: ValidationStageResult,
    val schematron: ValidationStageResult,
    val kosit: ValidationStageResult,
    val profile: ValidationStageResult? = null,
    val brFrCtc: ValidationStageResult? = null,
    val engine: ValidationStageResult? = null,
) {
    val preEngine: List<ValidationStageResult> get() = listOf(syntax, schematron, kosit)

    fun withEngine(engineStage: ValidationStageResult): ExportStages =
        ExportStages(syntax, schematron, kosit, profile, brFrCtc, engineStage)

    fun chain(
        pdfa: ValidationStageResult,
        veraPdf: ValidationStageResult?,
        business: ValidationStageResult,
    ): List<ValidationStageResult> =
        listOfNotNull(syntax, schematron, profile, kosit, pdfa, veraPdf, engine, brFrCtc, business)
}
