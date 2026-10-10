package de.littletrotter.shared

/**
 * Port interface for AI-assisted **visual** PDF layout review (Approach B — optional
 * complement to `PdfLayoutInspector`'s
 * deterministic PDFBox-coordinate checks, Approach C).
 *
 * Approach C stays primary: it is deterministic, fast, and covers the hard layout rules
 * (footer-column overlap, Satzspiegel violations, address-window position, …). This port
 * is for the things coordinate measurement cannot see — "does the color scheme look
 * coherent", "does the page look overloaded" — a qualitative visual spot-check, not a
 * replacement for the hard rules. Implementations are expected to be **optional**: no
 * production export should fail or block because this is unavailable or misconfigured.
 *
 * Deliberately separate from [LlmValidationPort]: that port's model is the text-only oMLX
 * model used for ZUGFeRD XML review, which cannot be assumed to also support image input.
 * A vision-capable model is a distinct, explicitly-configured deployment concern.
 */
interface PdfVisionInspectionPort {
    fun isAvailable(): Boolean

    /**
     * Runs a visual inspection of a single rendered PDF page.
     *
     * @param pngBytes   PNG-encoded rendering of one PDF page; the caller rasterises the page
     *                   (PDFBox, ~100 DPI keeps a DIN A4 page legible yet small).
     * @param invoice    Invoice DTO for context (invoice number).
     * @param layoutReport Optional deterministic layout report (Approach C) as text, so the
     *                   model can focus on what coordinate measurement cannot see instead of
     *                   re-deriving the hard rules already checked deterministically.
     * @return A single [PdfVisionResult] with a Markdown visual-review note.
     */
    fun inspect(
        pngBytes: ByteArray,
        invoice: InvoiceDto,
        layoutReport: String = "",
    ): PdfVisionResult
}

/**
 * Result of a visual PDF inspection run.
 *
 * @param model          Name of the vision model that produced this result.
 * @param markdownReport Free-text Markdown visual-review note, empty when not available.
 * @param success        True when the model responded successfully.
 * @param durationMs     Wall-clock time spent waiting for the model's response.
 * @param error          Set when [success] is false.
 */
data class PdfVisionResult(
    val model: String,
    val markdownReport: String,
    val success: Boolean,
    val durationMs: Long,
    val error: String? = null,
) {
    companion object {
        fun disabled() = PdfVisionResult(model = "oMLX-vision", markdownReport = "", success = false, durationMs = 0, error = "vision model not configured")
    }
}
