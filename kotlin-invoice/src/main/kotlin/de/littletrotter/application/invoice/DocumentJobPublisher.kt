package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentJobLane
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.application.settings.OutputIntentSettingGate
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.domain.repository.InvoiceRepository
import io.nats.client.JetStream
import io.nats.client.api.PublishAck
import org.jboss.logging.Logger
import java.util.concurrent.CompletableFuture

/**
 * Lightweight NATS JetStream-based publisher used by kotlin-invoice. Uses the jnats client
 * directly rather than a reactive-messaging connector, since there is no mature Quarkus
 * connector for NATS yet.
 *
 * Refactored to accept an optional JetStream provider and subject override to make unit
 * testing easier (tests can inject a mocked JetStream via the provider).
 *
 * Z4 — the `Invoice` → `InvoiceDto` mapping itself moved to [InvoiceDtoMapper]; this class keeps
 * only envelope building, `Nats-Msg-Id` dedup, counters, the send path, and error handling.
 *
 * PO-Grundsatz 29.08.2026 (CDI-Injektionen nie ausnehmen) — the actual envelope-building/send
 * machinery moved wholesale into [DocumentJobPublishTransaction] (see its own KDoc), when the former
 * `Provider<DocumentJobPublisher>` self-reference (`selfProvider`) was replaced by that dedicated
 * collaborator bean. This class now only owns the retry-orchestration ([publish]), the
 * optimistic-lock detection it relies on, and the compatibility overload — all of
 * [DocumentJobPublishTransaction]'s collaborators (JetStream, the mapper, the counters, etc.) live on
 * that bean now.
 */
@ApplicationScoped
class DocumentJobPublisher {
    private val publishTransaction: DocumentJobPublishTransaction
    private val gate: DocumentJobDispatchGate
    /**
     * concurrency-fix — kept here too (not just on [DocumentJobPublishTransaction]) since [publish]'s
     * own retry-reload of a stale [Invoice] happens in this class's retry-orchestration layer,
     * not inside the transactional bean. `null` in every plain unit test that doesn't wire one —
     * the retry then falls back to reusing the same (possibly stale) instance, same as before
     * this class was split.
     */
    private var invoiceRepository: InvoiceRepository? = null

    @Inject
    constructor(publishTransaction: DocumentJobPublishTransaction, gate: DocumentJobDispatchGate, invoiceRepository: InvoiceRepository) {
        this.publishTransaction = publishTransaction
        this.gate = gate
        this.invoiceRepository = invoiceRepository
    }

    constructor(
        jetStreamProvider: (() -> JetStream)? = null,
        jobsSubjectOverride: String? = null,
        validationSettingsGate: ValidationSettingsGate? = null,
        expectedStreamOverride: String? = null,
        mapper: InvoiceDtoMapper? = null,
        invoiceRepository: InvoiceRepository? = null,
        interactiveSubjectOverride: String? = null,
    ) {
        this.publishTransaction = DocumentJobPublishTransaction(
            jetStreamProvider = jetStreamProvider,
            jobsSubjectOverride = jobsSubjectOverride,
            validationSettingsGate = validationSettingsGate,
            expectedStreamOverride = expectedStreamOverride,
            mapper = mapper,
            invoiceRepository = invoiceRepository,
        )
        this.publishTransaction.overrideInteractiveSubjectForTest(interactiveSubjectOverride)
        this.gate = DocumentJobDispatchGate(this.publishTransaction)
        this.invoiceRepository = invoiceRepository
    }

    private fun dispatch(invoice: Invoice, job: GenerationJob, skipValidation: Boolean): DispatchedJob =
        if (job.lane == DocumentJobLane.INTERACTIVE) {
            gate.dispatchInteractive(invoice, job, skipValidation)
        } else {
            gate.dispatch(invoice, job, skipValidation)
        }

    /** Test seam — [WordingSettingsGate] is field-injected on [DocumentJobPublishTransaction] (see
     *  its own KDoc on why it isn't a constructor parameter), so a plain unit test has no
     *  constructor path to wire one; this reaches it directly. */
    internal fun wireWordingSettingsGateForTest(gate: WordingSettingsGate?) {
        publishTransaction.wordingSettingsGate = gate
    }

    /** Test seam for the output-intent setting gate, same reasoning as [wireWordingSettingsGateForTest]. */
    internal fun wireOutputIntentSettingGateForTest(gate: OutputIntentSettingGate?) {
        publishTransaction.outputIntentSettingGate = gate
    }

    private val log = Logger.getLogger(DocumentJobPublisher::class.java)

    /**
     * concurrency-fix — walks the full [Throwable.cause] chain for an optimistic-lock conflict.
     * Under Narayana/Arjuna (the JTA provider in Quarkus) a flush failure at transaction commit
     * time — i.e. one that happens *after*
     * [DocumentJobPublishTransaction.publishTransactional]'s own method body already returned, inside
     * the `@Transactional` interceptor's commit call — reaches the caller wrapped as
     * `RollbackException → PersistenceException → OptimisticLockException` (or
     * `→ StaleObjectStateException`, Hibernate's own unwrapped form), never as a bare
     * `OptimisticLockException`. Same helper/reasoning as
     * `InvoiceOptimisticLockingTest.hasOptimisticLockInChain` and
     * `InvoiceVersionConflictIT.hasOptimisticLockInChain` (test-only duplicates proving the same
     * wrapping) — kept here too since [publish] needs it at runtime, not just in a test assertion.
     */
    private fun isOptimisticLockFailure(ex: Throwable): Boolean {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is jakarta.persistence.OptimisticLockException || cause is org.hibernate.StaleObjectStateException) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * concurrency-fix — production incident: `OptimisticLockException` /
     * `StaleObjectStateException: Row was already updated or deleted by another transaction for
     * entity Invoice`, surfacing either as a raw error toast from `InvoiceExportPanel`'s print
     * button (a genuinely *stale* invoice — see [DocumentJobPublishTransaction.publishTransactional]'s
     * KDoc on why a detached caller-held copy is not just a race but near-guaranteed whenever
     * [GenerationJobService.createJob] itself just bumped the invoice's version) or as the
     * `GenerationJobFastPublishIT` `concurrent poller and fast path publish exactly once` flake
     * (the fast path and the poller each load their own attached copy and race to flush it).
     *
     * This wrapper is the actual public entry point; [DocumentJobPublishTransaction.publishTransactional]
     * does the real work inside its own fresh `@Transactional` scope — reached through that bean's
     * own CDI proxy, a genuine cross-bean call, which is exactly what makes the `@Transactional`
     * there take effect (see [DocumentJobPublishTransaction]'s own KDoc for why this used to be a
     * same-instance `Provider<DocumentJobPublisher>` self-call instead). Two layers of defense,
     * matching the two possible remaining failure modes even after
     * [InvoiceRepository.updateValidationStatus] (the primary fix, which removes the versioned
     * write entirely for the one field this method itself touches):
     *
     * 1. **Primary**: [DocumentJobPublishTransaction.publishTransactional] never calls
     *    [InvoiceRepository.save] on `invoice` anymore for the `validationStatus` flip — see its
     *    own KDoc.
     * 2. **Resilience**: if a lock conflict happens anyway (a write this method doesn't control —
     *    e.g. a concurrent [GenerationJobService.markCompleted]/[GenerationJobService.markFailed]
     *    landing on the exact same row inside the exact same commit window, or a future
     *    regression reintroducing a versioned write here), it is caught — via [publishTransaction], so the
     *    retry's own `@Transactional` actually takes effect — and retried exactly once, against a
     *    freshly reloaded [Invoice] rather than the doomed stale instance. A second failure is
     *    genuinely exceptional and is allowed to propagate — every existing caller
     *    ([GenerationJobFastPublishTransaction.publishOne], [GenerationJobProcessor.pollPendingJobs],
     *    `InvoiceExportPanel`'s print button) already catches broadly around this call and never
     *    lets a raw exception crash the UI or the scheduler.
     *
     * This is also the one method every `GenerationJob` engine (PDF export, the KOSIT/LLM
     * options carried in [job]) dispatches through. The fault-tolerance guard
     * (bulkhead, bounded ack wait — and deliberately no in-process retry) lives on
     * [DocumentJobDispatchGate.dispatch], the bean this method delegates to — see its KDoc for
     * why it cannot sit here. A [org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException]
     * from the gate propagates like any other publish failure: every caller already catches
     * around this call, and the job's PENDING row persisted before the call keeps the work
     * durable ([GenerationJobService.revertToPending] on the fast path, the poller's next tick
     * otherwise). The returned future fails after `invoice.publish.timeout-ms` without an ack —
     * within [GenerationJobFastPublishTransaction]'s own 3s `.get()` bound, so the fast path keeps its
     * timing.
     */
    // See the catch block's own comment below: every exception not identified as
    // the lock-conflict case is rethrown unchanged, nothing swallowed.
    fun publish(
        // The ONE publish path every caller funnels through now reads its
        // jobType/llmOptimize off this persisted [GenerationJob], not from a
        // caller-supplied argument. Previously, the UI's print button passed its own
        // llmOptimize values here while the fast path
        // ([GenerationJobFastPublishTransaction.publishOne]) and the poller republish
        // ([GenerationJobProcessor.pollPendingJobs]) passed neither — since JetStream dedups by
        // message id ("job-$jobId"), whichever publish call reached NATS first silently decided
        // the payload (the 26.08.2026 outage's root cause in this path). Persisting the choice on
        // [GenerationJob] at [GenerationJobService.createJob] time and reading it back here makes
        // that race unconstructable: every caller sends the exact same payload for the same job.
        //
        // [job].[GenerationJob.invoice] (not a caller-supplied `Invoice` parameter) is
        // also the ONLY invoice this method ever maps to the wire. The UI's print button
        // (`InvoiceExportPanel`) used to pass its own reconstructed copy here
        // (`ui/view/invoice/InvoiceDetailView.dtoToInvoice` — a partial, hand-mapped rebuild of
        // `InvoiceDetailDto`, missing fields the mapping simply forgot to carry over, e.g. four
        // PDF footer fields once). Dropping that parameter entirely makes that class of bug
        // unconstructable: every caller (this print button included) gets the same, freshly
        // loaded entity [GenerationJobService.createJob] already fetched via
        // [InvoiceRepository.findByIdWithItems] — one application-layer call, no N+1 — as [job]
        // .[GenerationJob.invoice], and this method reads it back off [job], never off a
        // separately passed-in copy.
        job: GenerationJob,
        skipValidation: Boolean = false,
    ): CompletableFuture<PublishAck> {
        val jobId = job.id ?: -1
        val invoice = requireNotNull(job.invoice) { "GenerationJob $jobId has no associated invoice to publish" }
        return try {
            dispatch(invoice, job, skipValidation).ack
        } catch (ex: Exception) {
            // AP-EXC-1: kept broad on purpose — not a structural handler, but a generic retry
            // wrapper around a @Transactional cross-bean call whose top-level exception type is
            // genuinely unpredictable (see isOptimisticLockFailure's own KDoc: Narayana wraps a
            // commit-time flush failure differently depending on exactly when it happens). Every
            // exception not identified as the lock-conflict case is rethrown unchanged below —
            // nothing is ever silently dropped.
            if (!isOptimisticLockFailure(ex)) throw ex
            log.warnf(
                ex,
                "Optimistic lock conflict publishing GenerationJob %d — reloading the invoice and retrying once",
                jobId,
            )
            // The Nats-Msg-Id dedup ("job-$jobId") makes this safe even in the (unlikely)
            // case the first attempt's jetStream.publishAsync call had already gone out before
            // its own transaction failed to commit: JetStream collapses the retry's identical
            // message id to the same stored message rather than delivering the job twice.
            val refreshed = invoice.id?.let { invoiceRepository?.findByIdWithItems(it) } ?: invoice
            dispatch(refreshed, job, skipValidation).ack
        }
    }

    /**
     * Convenience overload: builds a throwaway [GenerationJob] — carrying the defaults (no LLM
     * check) and the given [invoice] as its own [GenerationJob.invoice] — and
     * delegates.
     */
    fun publish(
        invoice: Invoice,
        jobId: Long,
        jobType: String = "PDF_EXPORT",
        skipValidation: Boolean = false,
    ): CompletableFuture<PublishAck> {
        val job = GenerationJob.messageCopy(jobId, invoice, jobType)
        return publish(job, skipValidation)
    }

    fun currentSentCount(): Long = publishTransaction.currentSentCount()
}
