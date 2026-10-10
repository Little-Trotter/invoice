package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.RegenerateInvoiceDocumentUseCase
import de.littletrotter.domain.exception.InvoiceDocumentRegenerationNotApplicableException
import de.littletrotter.domain.exception.InvoiceGenerationAlreadyRunningException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.isIssued
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Manual repair for an issued invoice that stayed without its document because the last
 * generation job ended FAILED. The new job is a recovery job: it re-creates the bytes of the
 * already issued document, so the single-generation lock (which only guards a second, different
 * issued document) does not apply, and nothing on the invoice changes.
 */
@ApplicationScoped
class RegenerateInvoiceDocumentService @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val generationJobRepository: GenerationJobRepository,
    private val generationJobCreationService: GenerationJobCreationService,
) : RegenerateInvoiceDocumentUseCase {

    @Transactional
    override fun regenerate(invoiceId: Long): Long {
        val invoice = invoiceRepository.findByIdWithItems(invoiceId) ?: throw InvoiceNotFoundException(invoiceId)
        val number = invoice.invoiceNumber.value
        if (!invoice.status.isIssued) throw InvoiceDocumentRegenerationNotApplicableException(number)
        val latest = generationJobRepository.findByInvoiceId(invoiceId)
            .filter { it.jobType == GenerationJobCreationService.PRINT_JOB_TYPE }
            .maxWithOrNull(compareBy({ it.createdAt }, { it.id ?: 0L }))
        when (latest?.status) {
            GenerationJobStatus.FAILED -> Unit
            GenerationJobStatus.PENDING, GenerationJobStatus.DELEGATED, GenerationJobStatus.RUNNING ->
                throw InvoiceGenerationAlreadyRunningException(number)
            else -> throw InvoiceDocumentRegenerationNotApplicableException(number)
        }
        val job = generationJobCreationService.createJob(
            invoiceId,
            jobType = GenerationJobCreationService.PRINT_JOB_TYPE,
            lane = DocumentJobLane.BULK,
            trigger = GenerationJobTrigger.REGENERATION,
        )
        return requireNotNull(job.id) { "Saved generation job has no id" }
    }
}
