package de.littletrotter.application.invoice

import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.ensureSendDateNotBeforeServiceEnd
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import de.littletrotter.domain.invoice.canTransitionTo

/**
 * PO decision 2026-09-13 (D1) — the ONLY entry point for the "Senden" action: creates a
 * `SEND`-triggered [GenerationJob] and returns immediately. It never changes the invoice's own
 * [InvoiceStatus] and never blocks its caller waiting for that job. The actual `IN_REVIEW -> ISSUED`
 * transition happens later, from SendCompletionProcessor, once the job's own callback reports
 * success and validation resolves `VALID` — [InvoiceLifecycleService.transition] itself is
 * unchanged and is no longer called directly from the UI for this action.
 *
 * A dedicated service rather than a new [InvoiceLifecycleService] method (reviewer's call, per
 * the spec): keeps that already-large class's own dependency list from growing by
 * [GenerationJobCreationService], and keeps this feature's one new use case in one small, obviously
 * reviewable place.
 */
@ApplicationScoped
class InvoiceSendService @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val creationService: GenerationJobCreationService,
    private val time: TimeSource,
    private val finalizationPolicy: FinalizationPolicy,
) {
    /**
     * Defense in depth, mirroring [InvoiceLifecycleService.transition]'s own `canTransitionTo`
     * guard for the same edge: the UI only ever shows "Senden" when
     * [de.littletrotter.ui.view.invoice.allowedTargetStatuses] already contains `ISSUED`, but master
     * data (or a concurrent action) can change between page load and click.
     */
    // Declared here, the outermost business entry point for "Senden", so it wins over
    // GenerationJobCreationService.createJob's own DOCUMENT_GENERATED action for the same transaction
    // (outermost-wins, see AuditGucInterceptor's own KDoc): an auditor reading the journal must see
    // "Sendevorgang gestartet", not "Dokument erzeugt", for this action.
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.SEND_REQUESTED)
    fun requestSend(invoiceId: Long): GenerationJob {
        val invoice = invoiceRepository.findById(invoiceId) ?: throw InvoiceNotFoundException(invoiceId)
        if (!invoice.canTransitionTo(InvoiceStatus.ISSUED)) {
            throw InvoiceStatusTransitionException(invoice.status.name, InvoiceStatus.ISSUED.name)
        }
        // Fail-fast pre-check (PO 2026-09-30): the click rejects synchronously instead of the async
        // SEND job leaving the invoice RS-1-locked; the transition-time guard stays as second line
        // of defence.
        invoice.ensureSendDateNotBeforeServiceEnd(time.today())
        // The SEND job renders the PDF before the ISSUED transition, so the full text check (invoice and
        // B2B values) must already pass here.
        finalizationPolicy.enforceRenderableText(invoice)
        return creationService.createJob(
            invoiceId,
            jobType = "SEND_ISSUE",
            lane = DocumentJobLane.INTERACTIVE,
            trigger = GenerationJobTrigger.SEND,
        )
    }
}
