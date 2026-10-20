package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.SchedulePrintJobUseCase
import de.littletrotter.domain.invoice.DocumentJobLane
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Creates the job in its own transaction and publishes it only after that commit, so the
 * publisher always sees the persisted job. The invoice mapped onto the wire is the one the
 * creation loaded, never a caller-supplied copy.
 */
@ApplicationScoped
class SchedulePrintJobService @Inject constructor(
    private val generationJobCreationService: GenerationJobCreationService,
    private val documentJobPublisher: DocumentJobPublisher,
) : SchedulePrintJobUseCase {

    override fun schedulePrintJob(invoiceId: Long, llmOptimize: Boolean): Long? {
        val job = generationJobCreationService.createJob(
            invoiceId,
            GenerationJobCreationService.PRINT_JOB_TYPE,
            DocumentJobLane.INTERACTIVE,
            llmOptimize = llmOptimize,
        )
        documentJobPublisher.publish(job, skipValidation = false)
        return job.id
    }
}
