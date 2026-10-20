package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import java.time.OffsetDateTime

/**
 * Read model of one document generation job, carrying exactly what the invoice detail view, the
 * report dialogs and the download route display or decide on. The invoice the job belongs to is
 * reduced to its id and number; both are absent for a job without an invoice.
 */
data class GenerationJobView(
    val id: Long?,
    val invoiceId: Long?,
    val invoiceNumber: String?,
    val jobType: String,
    val status: GenerationJobStatus,
    val trigger: GenerationJobTrigger,
    val issued: Boolean,
    val resultRefs: String?,
    val kositReport: String?,
    val llmStatus: String?,
    val createdAt: OffsetDateTime,
    val attempts: Int,
    val lastError: String?,
    val finishedAt: OffsetDateTime?,
)
