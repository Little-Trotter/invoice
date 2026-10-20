package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDto
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Produces the exported PDF bytes: picks the mark, renders the base document with the receipt
 * pages and mark in one pass ([BasePdfStage]) and, when the XML is valid, embeds the XML
 * ([MustangPdfRenderer]). Kept apart from the export orchestration so that class only sequences
 * the stages.
 */
@ApplicationScoped
class ExportPdfAssembler @Inject constructor(
    private val basePdfStage: BasePdfStage,
    private val mustangPdfRenderer: MustangPdfRenderer,
    // Picks the mark that is stamped while the receipt pages are appended.
    private val watermarkSelector: ExportWatermarkSelector,
) {

    /** The final PDF: with the XML embedded when [embedXml], otherwise the plain base document. */
    fun assemble(invoice: InvoiceDto, xmlBytes: ByteArray, embedXml: Boolean, outputIntent: String?): ByteArray {
        val stamp = watermarkSelector.select(invoice, outputIntent)
        return if (embedXml) {
            mustangPdfRenderer.renderPdfWithZugferd(invoice, xmlBytes, stamp)
        } else {
            basePdfStage.renderBase(invoice, stamp)
        }
    }

    /** Filename the embedded XML carries for [profile]. */
    fun expectedAttachmentFilename(profile: String): String =
        mustangPdfRenderer.expectedZugferdAttachmentFilename(profile)
}
