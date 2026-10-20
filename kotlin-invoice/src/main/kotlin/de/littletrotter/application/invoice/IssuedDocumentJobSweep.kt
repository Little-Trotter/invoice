package de.littletrotter.application.invoice

import de.littletrotter.domain.exception.InvoiceDomainException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.repository.GenerationJobRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.PersistenceException
import org.jboss.logging.Logger

/**
 * Durable fallback for the document job an issued credit note gets right after its cancellation
 * commits: if the process dies between that commit and the job request, the credit note would
 * stay document-less. [GenerationJobProcessor]'s recurring scan calls this, so the gap closes
 * within one interval. Idempotent: only credit notes without any job qualify, and
 * [GenerationJobCreationService.createJob] itself rejects a second concurrent print job.
 */
@ApplicationScoped
class IssuedDocumentJobSweep @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
    private val creationService: GenerationJobCreationService,
) {
    private val log = Logger.getLogger(IssuedDocumentJobSweep::class.java)

    /** Requests the missing job for each qualifying credit note (each in its own transaction)
     *  and returns how many were created. */
    fun requestMissingCreditNoteJobs(): Int {
        var created = 0
        generationJobRepository.findCreditNoteIdsWithoutJob(BATCH_SIZE).forEach { invoiceId ->
            try {
                creationService.createJob(invoiceId, GenerationJobCreationService.PRINT_JOB_TYPE, DocumentJobLane.BULK)
                created++
            } catch (ex: InvoiceDomainException) {
                log.warnf("Document job for credit note %d refused, retried on the next scan: %s", invoiceId, ex.message)
            } catch (ex: PersistenceException) {
                log.warnf("Document job for credit note %d could not be stored, retried on the next scan: %s", invoiceId, ex.message)
            }
        }
        return created
    }

    private companion object {
        const val BATCH_SIZE = 25
    }
}
