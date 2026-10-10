package de.littletrotter.application.invoice

import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.application.cache.NoopInvoiceCachePort
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.ValidationFinding
import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.resolveValidationStatusFromKositReport
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ValidationFindingRepository
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.KositReportPayload
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.ObservesAsync
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * The follow-up reactions [GenerationJobService.markCompleted] used to run inline,
 * in the SAME transaction as the [de.littletrotter.domain.invoice.GenerationJob] status write
 * (site of the 26.08.2026 optimistic-lock incident: an unrelated concurrent edit to the Invoice
 * rolled back the job's own, unrelated COMPLETED transition too). Split out here as three
 * independent [DocumentGenerated] observers — each its own CDI dispatch entry point, never a
 * self-invoked helper method (a `@Transactional` self-call bypasses the interceptor in
 * Quarkus/ArC, exactly like Spring AOP — only a direct CDI-dispatched call goes through it), so a
 * failure or a version conflict in one reaction can never again roll back `markCompleted`'s own,
 * already-committed transaction, nor any of this handler's own sibling reactions.
 *
 * Dispatch style matches [de.littletrotter.application.message.MessageCenterEventHandler] et al.:
 * `@ObservesAsync` (the event is fired with `fireAsync`, see [DocumentGenerated]'s own KDoc), each
 * method wrapping its own work in a try/catch — a reaction here is a side effect of a job having
 * completed, never the point of it, so a failure is logged and swallowed, never rethrown onto
 * whatever thread the CDI async executor runs the observer on.
 *
 * E2E Welle C, Punkt 6 — the PO kept this swallow-catch design
 * (correct: job completion must never be coupled to a side-effect reaction) but raised its
 * visibility: every catch block below logs at ERROR (was WARN — a silently degrading reaction is
 * an operational problem, not a routine one) and increments
 * [DocumentGeneratedReactionMetrics.recordFailure], tagged by which reaction failed. Behavior is
 * unchanged — still never rethrown.
 */
// onDocumentGeneratedUpdateValidationStatus writes invoice.validationStatus, captured by
// invoice_audit_journal; it carries its own SetsAuditGuc below. AuditActorProvider (through
// AuditGucInterceptor) falls back to "system" here on its own (this handler runs off an async CDI
// event, never a live HTTP request), so the journal attributes the rows to the background pipeline.
// Independent @ObservesAsync reactions to the SAME DocumentGenerated
// event, each its own genuine process boundary: an async CDI reaction must never propagate onto
// the async executor thread, and (per this class's own KDoc) one reaction failing must never
// affect the others' own transaction/CDI dispatch. Every catch already logs with cause and
// records a per-reaction failure metric, never a silent swallow.
@ApplicationScoped
class DocumentGeneratedReactionHandler @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
    private val invoiceRepository: InvoiceRepository,
    private val validationFindingRepository: ValidationFindingRepository,
    private val cache: InvoiceCachePort = NoopInvoiceCachePort(),
    private val metrics: DocumentGeneratedReactionMetrics = DocumentGeneratedReactionMetrics(SimpleMeterRegistry()),
) {
    private val log = Logger.getLogger(DocumentGeneratedReactionHandler::class.java)

    /**
     * Bug production 2026-08-30 — an invoice with two [de.littletrotter.domain.invoice
     * .GenerationJob]s (both eventually COMPLETED) could get stuck [ValidationStatus.PENDING]
     * forever: the previous version decoded [de.littletrotter.domain.invoice.GenerationJob
     * .kositReport] *before* checking which job was the latest one, so the newest job's observer
     * bailed out early whenever ITS OWN report was null/undecodable (the documented degrade
     * path — [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer
     * .resolveKositReport] returns null on an expired/missing Valkey ref, and `markCompleted`
     * persists that null) — never reaching the `isLatestJob` check at all. The older job's own
     * observer then found a perfectly good, decodable report, but its `isLatestJob` check (which
     * only compared job IDs, oblivious to whether the "latest" job had actually produced a
     * result) correctly identified it as non-latest and returned early too. Neither observer ever
     * wrote to [de.littletrotter.domain.invoice.Invoice.validationStatus] again — permanently
     * PENDING.
     *
     * Fixed by re-deriving the resolution from scratch on every call, only while the invoice is
     * still [ValidationStatus.PENDING] (a terminal invoice is never touched again — the guard
     * that used to be "is this job the latest COMPLETED one" is now simply "is there anything
     * left to resolve", which also sidesteps ever attempting an illegal terminal→terminal
     * [de.littletrotter.domain.invoice.Invoice.transitionValidationStatusTo] edge, e.g.
     * VALID→INVALID, that [ValidationStatus.canTransitionTo] would reject):
     *  1. If any [de.littletrotter.domain.invoice.GenerationJob] for the invoice is still
     *     non-terminal (PENDING/DELEGATED/RUNNING), do nothing — wait for the last one.
     *  2. Otherwise resolve from the COMPLETED jobs, newest first (`createdAt`, then `id` as a
     *     deterministic tiebreaker for jobs created in the same action, which can share a
     *     timestamp at typical column precision): the first one whose `kositReport` decodes wins.
     *  3. If none of them has a decodable report, the invoice resolves to
     *     [ValidationStatus.UNAVAILABLE] rather than staying PENDING forever.
     *
     * Re-reads every job fresh via [event]'s `invoiceId` rather than trusting anything carried on
     * the event itself: by the time this observer runs, `markCompleted`'s own transaction (which
     * wrote the triggering job's `kositReport` column, already merged with the BUSINESS
     * compliance stage) has committed, and a sibling job may have completed since the event was
     * fired.
     */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.VALIDATION_RECORDED)
    fun onDocumentGeneratedUpdateValidationStatus(@ObservesAsync event: DocumentGenerated) {
        // L24 — this reaction only ever ran for an issued/SEND job before the event started firing
        // unconditionally (see DocumentGenerated's own KDoc); a draft's job completing must not
        // start writing Invoice.validationStatus, an unrelated, larger behavior change nobody asked
        // for here.
        if (!event.issued) return
        try {
            val job = generationJobRepository.findById(event.generationJobId) ?: return
            val invoice = job.invoice ?: return
            if (invoice.validationStatus != ValidationStatus.PENDING) return

            val jobsForInvoice = generationJobRepository.findByInvoiceId(event.invoiceId)
            val hasOpenJob = jobsForInvoice.any { it.status in NON_TERMINAL_JOB_STATUSES }
            if (hasOpenJob) return

            // PO decision 2026-09-13 — the report-to-ValidationStatus mapping itself now lives in
            // resolveValidationStatusFromKositReport, shared with GenerationJobService
            // .resolveSendOutcome (both derive the identical outcome from the identical report; a
            // possible double write of the same value between the two is a known, harmless
            // overlap, see that method's own KDoc).
            val resolvedStatus = jobsForInvoice
                .filter { it.status == GenerationJobStatus.COMPLETED }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
                .asReversed()
                .firstNotNullOfOrNull { resolveValidationStatusFromKositReport(it.kositReport) }
                ?: ValidationStatus.UNAVAILABLE

            invoice.transitionValidationStatusTo(resolvedStatus)
            invoiceRepository.save(invoice)
        } catch (e: Exception) {
            log.error(
                "Failed to update validationStatus for invoice ${event.invoiceId} from job ${event.generationJobId}: ${e.message}",
                e,
            )
            metrics.recordFailure(DocumentGeneratedReactionMetrics.Reaction.UPDATE_VALIDATION_STATUS)
        }
    }

    /**
     * The auswertungssicht, written for every completed job (not just the latest one, unlike
     * validationStatus above). A write failure here must never affect anything else — finding
     * persistence is a side effect, not the point of completing a job; the blob in
     * `kositReport` already carries the truth. Runs as its own transaction/CDI dispatch, entirely
     * independent from [onDocumentGeneratedUpdateValidationStatus] above — one failing can never
     * affect the other.
     */
    @Transactional
    fun onDocumentGeneratedPersistFindings(@ObservesAsync event: DocumentGenerated) {
        // L24 — same "preserve pre-L24 behavior for a draft" reasoning as the sibling reaction
        // above; see DocumentGenerated's own KDoc.
        if (!event.issued) return
        try {
            val job = generationJobRepository.findById(event.generationJobId) ?: return
            val payload = KositReportPayload.decode(job.kositReport) ?: return
            val findings = payload.details.map { finding ->
                ValidationFinding().apply {
                    generationJobId = event.generationJobId
                    invoiceId = event.invoiceId
                    stage = finding.stage
                    validator = finding.validator
                    ruleId = finding.ruleId
                    severity = finding.severity
                    location = finding.location
                    message = finding.message
                    validatorVersion = payload.validatorVersion
                }
            }
            validationFindingRepository.saveAll(findings)
        } catch (e: Exception) {
            log.error(
                "Failed to persist validation findings for job ${event.generationJobId} (invoice ${event.invoiceId}): ${e.message}",
                e,
            )
            metrics.recordFailure(DocumentGeneratedReactionMetrics.Reaction.PERSIST_FINDINGS)
        }
    }

    /** A completed job may have changed [de.littletrotter.domain.invoice.Invoice.validationStatus]
     *  — the cached overview pages must not serve stale rows for up to the 30s TTL. No
     *  `@Transactional`: cache eviction is not a database write. */
    fun onDocumentGeneratedEvictCache(@ObservesAsync event: DocumentGenerated) {
        // L24 — same "preserve pre-L24 behavior for a draft" reasoning as the sibling reactions
        // above; see DocumentGenerated's own KDoc.
        if (!event.issued) return
        try {
            cache.evictAllOverview()
        } catch (e: Exception) {
            log.error("Failed to evict overview cache for invoice ${event.invoiceId}: ${e.message}", e)
            metrics.recordFailure(DocumentGeneratedReactionMetrics.Reaction.EVICT_CACHE)
        }
    }

    private companion object {
        /** [GenerationJobStatus] values that mean "still in flight" — a job in one of these must
         *  finish before [onDocumentGeneratedUpdateValidationStatus] may resolve the invoice. */
        val NON_TERMINAL_JOB_STATUSES = setOf(
            GenerationJobStatus.PENDING,
            GenerationJobStatus.DELEGATED,
            GenerationJobStatus.RUNNING,
        )
    }
}
