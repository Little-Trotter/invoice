package de.littletrotter.application.invoice

import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.event.GenerationJobFailed
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.invoice.InvoiceStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.ObservesAsync
import jakarta.inject.Inject
import org.jboss.logging.Logger
import de.littletrotter.application.invoice.port.outbound.EventPublisher
import de.littletrotter.application.invoice.port.outbound.InvoiceStatusPublisher

/**
 * The sole place [EventPublisher.publishJobFinished] (the Vaadin UI push) is called
 * for a document-generation outcome. Before this task,
 * [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer] fired BOTH the
 * domain-event bus (via [GenerationJobService.markCompleted]/[GenerationJobService.markFailed])
 * AND this UI bus directly, at the same call site — one could be forgotten independently of the
 * other, and historically was. UI push is now a plain listener on the SAME domain events the
 * message center already observes ([de.littletrotter.application.message.MessageCenterEventHandler]),
 * so every caller fires exactly one bus.
 *
 * L24 (fix-l24-draft-job-status-never-pushed) — this handler is deliberately the one
 * [DocumentGenerated]/[GenerationJobFailed] consumer that never reads [DocumentGenerated.issued]/
 * [GenerationJobFailed.issued]: the versions-panel row needs to hear about a job finishing
 * regardless of whether the invoice is a draft or issued, so this is the authoritative,
 * unconditional push for that row (see [de.littletrotter.ui.view.invoice.InvoiceExportPanel]'s own
 * KDoc for how the poll fallback stays a secondary, independent path rather than a second writer).
 *
 * It also announces a finalized invoice's ISSUED status from the after-commit [InvoiceFinalized] event, so open screens re-read the committed row on an async thread.
 */
@ApplicationScoped
class GenerationJobUiPushHandler @Inject constructor(
    private val uiEventBus: EventPublisher,
    private val statusPublisher: InvoiceStatusPublisher,
) {
    private val log = Logger.getLogger(GenerationJobUiPushHandler::class.java)

    fun onDocumentGenerated(@ObservesAsync event: DocumentGenerated) {
        publish(event.generationJobId, event.invoiceId, success = true)
    }

    fun onGenerationJobFailed(@ObservesAsync event: GenerationJobFailed) {
        publish(event.generationJobId, event.invoiceId, success = false)
    }

    fun onInvoiceFinalized(@ObservesAsync event: InvoiceFinalized) {
        try {
            statusPublisher.publishInvoiceStatusChanged(event.invoiceId, InvoiceStatus.ISSUED)
        } catch (e: Exception) {
            // Process boundary: a failed UI push (e.g. no active session) must not propagate onto the async CDI event thread.
            log.warn("Failed to publish UI status change for invoice ${event.invoiceId}: ${e.message}", e)
        }
    }

    private fun publish(jobId: Long, invoiceId: Long?, success: Boolean) {
        try {
            uiEventBus.publishJobFinished(jobId, invoiceId, success)
        } catch (e: Exception) {
            // Process boundary — sole catch-all of this flow: called from two @ObservesAsync CDI
            // observers (onDocumentGenerated/onGenerationJobFailed). A failure pushing to the UI
            // event bus (e.g. no active session) must not propagate onto the async CDI event
            // thread. Already logged at warn with the job id and the exception.
            log.warn("Failed to publish UI job-finished event for job $jobId: ${e.message}", e)
        }
    }
}
