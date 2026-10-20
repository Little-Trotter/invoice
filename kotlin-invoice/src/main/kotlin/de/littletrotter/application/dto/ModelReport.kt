package de.littletrotter.application.dto

import de.littletrotter.application.command.CommandResult
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import java.time.OffsetDateTime

/**
 * Per-model summary projected from an [InvoiceLlmAnalysis] row. Lives in the application layer
 * rather than next to the validation report view because
 * [de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort.findLlmAnalyses]
 * must not hand a JPA entity across the input-port boundary
 * (de.littletrotter.arch.CommandBoundaryArchitectureTest). The mapping itself
 * (`from`) now happens once, in the application layer, at the read boundary — exactly the
 * "Read query: DTO projection" responsibility the persistence decision's boundary table assigns.
 */
data class ModelReport(
    val modelName: String,
    val status: LlmAnalysisStatus,
    val rating: Int?,
    val summary: String?,
    val issues: String?,
    val suggestions: String?,
    val analyzedAt: OffsetDateTime?,
) : CommandResult {
    /** A model "has a finding" when its analysis completed and reported issues. */
    val hasFinding: Boolean
        get() = status == LlmAnalysisStatus.COMPLETED && !issues.isNullOrBlank()

    val isError: Boolean
        get() = status == LlmAnalysisStatus.FAILED

    companion object {
        fun from(a: InvoiceLlmAnalysis): ModelReport =
            ModelReport(
                modelName = a.modelName,
                status = a.status,
                rating = a.rating,
                summary = a.summary,
                issues = a.issues,
                suggestions = a.suggestions,
                analyzedAt = a.analyzedAt,
            )
    }
}
