package de.littletrotter.shared

/**
 * Port interface for AI-assisted ZUGFeRD XML review via oMLX (OpenAI-compatible).
 * Implementations live in kotlin-xpdf. The analysis receives the generated ZUGFeRD XML
 * together with the KoSIT validation result (JSON, see [KositReportPayload]) so the model
 * can write a grounded improvement specification instead of guessing.
 *
 * This is the single LLM path: one local engine (oMLX), one configured model
 * (Qwen3.6-35B-A3B-8bit). If not configured, implementations report
 * [LlmModelResult] with [LlmModelResult.success] == false / empty report.
 */
interface LlmValidationPort {
    fun isAvailable(): Boolean

    /**
     * Runs the LLM analysis for the given ZUGFeRD XML and PDF layout.
     *
     * @param xmlBytes   Generated ZUGFeRD XML bytes.
     * @param invoice    Invoice DTO for context (invoice number, parties, amounts).
     * @param kositReport KoSIT validation result as JSON (see [KositReportPayload.encode]).
     * @param layoutReport PDF layout measurement report as text (see
     *                   PdfLayoutInspector.InspectionReport.toLlmText), or empty when
     *                   no layout inspection was performed.
     * @return A single [LlmModelResult] with a Markdown improvement specification.
     */
    fun analyze(
        xmlBytes: ByteArray,
        invoice: InvoiceDto,
        kositReport: String,
        layoutReport: String = "",
    ): LlmModelResult
}

/**
 * Result of the LLM analysis + improvement specification run.
 *
 * @param model          Name of the model that produced this result.
 * @param markdownReport Free-text Markdown specification for improvement, empty when not available.
 * @param success        True when the model responded successfully.
 * @param durationMs     Wall-clock time spent waiting for the model's response.
 * @param error          Set when [success] is false.
 */
data class LlmModelResult(
    val model: String,
    val markdownReport: String,
    val success: Boolean,
    val durationMs: Long,
    val error: String? = null,
) {
    companion object {
        fun disabled() = LlmModelResult(model = "oMLX", markdownReport = "", success = false, durationMs = 0, error = "oMLX not configured")
        fun error(reason: String) = LlmModelResult(model = "oMLX", markdownReport = "", success = false, durationMs = 0, error = reason)
    }
}
