package de.littletrotter.application.invoice

import de.littletrotter.domain.exception.InvoiceDomainException
import de.littletrotter.domain.invoice.DocumentJobLane
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.event.TransactionPhase
import jakarta.inject.Inject
import jakarta.persistence.PersistenceException
import org.jboss.logging.Logger

/**
 * A credit note is born issued (CANCELLED) and never passes a send step, so nothing else would
 * produce its document. The job row is written in its own transaction and needs the committed
 * invoice row, hence the request runs only after the surrounding transaction (whoever owns it)
 * committed. A failure must not undo the committed storno; the periodic issued-document sweep
 * and the regular print action remain the fallback.
 */
@ApplicationScoped
class CreditNoteIssuedObserver @Inject constructor(
    private val documentJobs: GenerationJobCreationService,
) {
    private val log = Logger.getLogger(CreditNoteIssuedObserver::class.java)

    fun onCreditNoteIssued(@Observes(during = TransactionPhase.AFTER_SUCCESS) event: CreditNoteIssued) {
        try {
            documentJobs.createJob(
                event.creditNoteId,
                GenerationJobCreationService.PRINT_JOB_TYPE,
                DocumentJobLane.INTERACTIVE,
            )
        } catch (ex: InvoiceDomainException) {
            log.warn("Credit note ${event.creditNoteId} committed, but its document job was refused: ${ex.message}", ex)
        } catch (ex: PersistenceException) {
            log.warn("Credit note ${event.creditNoteId} committed, but its document job could not be stored: ${ex.message}", ex)
        }
    }
}
