package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.shared.receipt.readability.PageReadability

/**
 * Outbound port that measures the scanned pages of a receipt PDF (resolution at display size,
 * sharpness). [de.littletrotter.infrastructure.receipt.PdfBoxReceiptPageProbe] is the real adapter.
 */
fun interface ReceiptPageProbe {
    /** Measures up to [maxPages] pages of [pdfData]; an unreadable file yields an empty list. */
    fun probe(pdfData: ByteArray, maxPages: Int): List<ProbedPage>
}

/** Measurement of one page; [previewPng] is filled only for pages the policy reports. */
class ProbedPage(val readability: PageReadability, val previewPng: ByteArray?)
