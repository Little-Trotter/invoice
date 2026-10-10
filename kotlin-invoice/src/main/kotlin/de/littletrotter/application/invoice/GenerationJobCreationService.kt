package de.littletrotter.application.invoice

import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.GenerationJobCreated
import de.littletrotter.domain.exception.InvoiceGenerationAlreadyRunningException
import de.littletrotter.domain.exception.InvoiceImmutableGenerationBlockedException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException
import de.littletrotter.domain.exception.InvoiceVoidedGenerationBlockedException
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.isIssued
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.receipt.BudgetVerdict
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional

/**
 * GenerationJobService's job-creation responsibility, extracted verbatim (see
 * `createJob`'s original KDoc on `GenerationJobService` for the N13 background
 * this still relies on).
 */
@ApplicationScoped
class GenerationJobCreationService @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
    private val invoiceRepository: InvoiceRepository,
    private val domainEventBus: Event<DomainEvent>,
    private val time: TimeSource,
    private val receiptBudgetService: ReceiptBudgetService,
) {
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.DOCUMENT_GENERATED)
    fun createJob(
        invoice: Invoice,
        jobType: String,
        lane: DocumentJobLane,
        correlationId: String? = null,
        engine: DocumentEngine? = null,
        llmOptimize: Boolean = false,
        trigger: GenerationJobTrigger = GenerationJobTrigger.MANUAL,
    ): GenerationJob {
        val invoiceToPersist = loadAndValidateInvoice(invoice, trigger)
        requireNoRunningPrintJob(invoiceToPersist, jobType, engine ?: invoiceToPersist.documentEngine, llmOptimize)
        if (engine != null) {
            invoiceToPersist.documentEngine = engine
        }

        val job = initGenerationJob(
            invoiceToPersist,
            jobType,
            trigger,
            GenerationJobCreationDetails(lane, correlationId, llmOptimize),
        )
        val saved = saveMappingActiveJobViolation(job, invoiceToPersist)
        val savedId = saved.id
        if (savedId != null) {
            // Synchronous fire (not fireAsync):
            // TransactionPhase.AFTER_SUCCESS observers only apply to a synchronously fired event.
            // See GenerationJobCreated's own KDoc for the full reasoning.
            domainEventBus.fire(GenerationJobCreated(savedId, lane))
        }
        return saved
    }

    /**
     * The id-based overload for callers that only have the invoice's id (e.g.
     * `InvoiceExportPanel`, which holds an `InvoiceDetailDto` read model, never a domain
     * `Invoice`). [createJob] above already discards whatever `Invoice` instance it's handed and
     * reloads fresh by id the moment one is available — this overload is exactly that reload,
     * done once upfront.
     */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.DOCUMENT_GENERATED)
    fun createJob(
        invoiceId: Long,
        jobType: String,
        lane: DocumentJobLane,
        correlationId: String? = null,
        engine: DocumentEngine? = null,
        llmOptimize: Boolean = false,
        trigger: GenerationJobTrigger = GenerationJobTrigger.MANUAL,
    ): GenerationJob {
        val loaded = invoiceRepository.findByIdWithItems(invoiceId) ?: throw InvoiceNotFoundException(invoiceId)
        return createJob(loaded, jobType, lane, correlationId, engine, llmOptimize, trigger)
    }

    private fun loadAndValidateInvoice(invoice: Invoice, trigger: GenerationJobTrigger): Invoice {
        val invoiceId = invoice.id ?: return invoice
        val loaded = invoiceRepository.findByIdWithItems(invoiceId)
            ?: throw InvoiceNotFoundException(invoiceId)
        requireInvoiceEligibleForNewJob(loaded, trigger)
        // Task S: creating a print job must not silently change a finalized/cancelled
        // invoice's stored totals — recalculateTotals() mutates the managed entity, and
        // that mutation was persisted on transaction commit even without an explicit
        // save() call. A read-only invoice's totals are locked; only recompute for
        // invoices that are still open.
        if (!loaded.isReadOnly()) {
            loaded.recalculateTotals()
        }
        loaded.validate()
        return loaded
    }

    /**
     * Split out of [loadAndValidateInvoice] (ThrowsCount): the three business-eligibility
     * guards, each independent of the others.
     *
     * "Guard against concurrent/duplicate sends" (PO decision 2026-09-13) — checked before
     * anything else so a second concurrent "Senden" click is rejected outright.
     *
     * PO decision 2026-08-31 (Annullierung) — a voided invoice never allows a new generation job,
     * except a [GenerationJobTrigger.REPRODUCTION] (see the guard below).
     * This is also the ONLY reason a VOIDED document can never be rendered — see
     * `InvoiceDtoMapper.mapToDto`'s `draft = ... || VOIDED` term.
     *
     * N13 (P4) — the single-generation gate: once a job has actually reached COMPLETED for
     * an issued invoice, a second one would silently overwrite the archived original in the S3
     * store. `hasCompletedIssuedJob` (not "any completed job") means a review-time draft job
     * never locks it once sent.
     *
     * The document-size budget is the fourth, independent guard here (PO decision): linking a
     * receipt no longer refuses an oversized set ([ReceiptBudgetService.requireWithinBudget]), so
     * this is the one place left that must still refuse before an over-budget document is actually
     * produced — checked once per [createJob] call, in front of the other three.
     */
    private fun requireInvoiceEligibleForNewJob(loaded: Invoice, trigger: GenerationJobTrigger) {
        val invoiceId = requireNotNull(loaded.id) { "Loaded invoice must have an id" }
        if (trigger == GenerationJobTrigger.SEND && generationJobRepository.hasNonTerminalTriggerJob(invoiceId, GenerationJobTrigger.SEND)) {
            throw InvoiceSendAlreadyInProgressException(loaded.invoiceNumber.value)
        }
        // PO decision 2026-10-03: the single exception is a reproduction. That trigger is only ever
        // produced by the recovery flow after the stored original, an identical store copy and the
        // mail journal all came up empty; it changes nothing on the invoice, it only renders the
        // frozen state again.
        if (loaded.status == InvoiceStatus.VOIDED && trigger != GenerationJobTrigger.REPRODUCTION) {
            throw InvoiceVoidedGenerationBlockedException(loaded.invoiceNumber.value)
        }
        // A recovery job re-creates the bytes of the already issued document; the single-generation
        // lock exists to stop a second, different issued document, which this is not.
        if (!trigger.isRecovery && loaded.status.isIssued && generationJobRepository.hasCompletedIssuedJob(invoiceId)) {
            throw InvoiceImmutableGenerationBlockedException(loaded.invoiceNumber.value)
        }
        requireDocumentWithinBudget(invoiceId)
    }

    /**
     * Idempotency per invoice and variant: while a print job of the same engine/LLM option is
     * still in flight, a second one is rejected with a defined message instead of racing the
     * first for the invoice's document rows. A different variant (another engine or LLM option)
     * is a different document and stays allowed.
     */
    private fun requireNoRunningPrintJob(invoice: Invoice, jobType: String, engine: DocumentEngine, llmOptimize: Boolean) {
        val invoiceId = invoice.id ?: return
        if (jobType == PRINT_JOB_TYPE && generationJobRepository.hasNonTerminalJob(invoiceId, jobType, engine, llmOptimize)) {
            throw InvoiceGenerationAlreadyRunningException(invoice.invoiceNumber.value)
        }
    }

    /** The unique index `uq_generation_job_active_print` catches the race two concurrent requests
     *  can win past [requireNoRunningPrintJob]; its violation means the same thing and gets the
     *  same defined message. */
    private fun saveMappingActiveJobViolation(job: GenerationJob, invoice: Invoice): GenerationJob =
        try {
            generationJobRepository.save(job)
        } catch (ex: PersistenceException) {
            if (isActivePrintJobViolation(ex)) throw InvoiceGenerationAlreadyRunningException(invoice.invoiceNumber.value)
            throw ex
        }

    /** The hard stop this class owns (see [requireInvoiceEligibleForNewJob]'s own KDoc): the
     *  invoice's *current* receipt set, no candidate — a job is created for what the invoice
     *  already carries, never for a hypothetical write in flight. */
    private fun requireDocumentWithinBudget(invoiceId: Long) {
        val verdict = receiptBudgetService.invoiceDocumentBudget(invoiceId).verdict
        if (verdict is BudgetVerdict.DocumentTooLarge) {
            throw InvoiceDocumentSizeExceededException(verdict.limit, verdict.estimated)
        }
    }

    /** [correlationId]/[llmOptimize] bundled into one value class so
     *  [initGenerationJob] stays under LongParameterList's threshold. */
    private data class GenerationJobCreationDetails(
        val lane: DocumentJobLane,
        val correlationId: String?,
        val llmOptimize: Boolean,
    )

    private fun initGenerationJob(
        invoiceToPersist: Invoice,
        jobType: String,
        trigger: GenerationJobTrigger,
        details: GenerationJobCreationDetails,
    ): GenerationJob = GenerationJob().apply {
        this.invoice = invoiceToPersist
        this.jobType = jobType
        // Construction, not a transition: a brand-new entity has no prior state for
        // GenerationJobStateMachine to check against, so this is a plain field assignment.
        this.status = GenerationJobStatus.PENDING
        this.correlationId = details.correlationId
        this.createdAt = time.nowUtc()
        this.engine = invoiceToPersist.documentEngine
        this.llmOptimize = details.llmOptimize
        this.trigger = trigger
        this.lane = details.lane
        // N13 (P4) — set exactly once, here, from the invoice's status at THIS moment.
        // PO decision 2026-09-13 — Send-triggered jobs are always the issued document.
        this.issued = invoiceToPersist.status.isIssued || trigger == GenerationJobTrigger.SEND || trigger.isRecovery
    }

    internal companion object {
        const val PRINT_JOB_TYPE = "PRINT_JOB"
        /** Name of the partial unique index created in
         *  `src/main/resources/db/migration/ddl/V1__baseline_schema.sql` (keep both in sync). */
        const val ACTIVE_PRINT_JOB_INDEX = "uq_generation_job_active_print"

        /** PostgreSQL SQLState `unique_violation`. */
        private const val SQLSTATE_UNIQUE_VIOLATION = "23505"

        /**
         * Whether [ex] (or any cause) is a PostgreSQL unique violation (SQLState 23505) of
         * [ACTIVE_PRINT_JOB_INDEX]. The constraint is read from the server error message; only
         * when the driver supplies none does the message text decide.
         */
        fun isActivePrintJobViolation(ex: Throwable): Boolean =
            generateSequence(ex) { it.cause }.filterIsInstance<java.sql.SQLException>().any { sql ->
                if (sql.sqlState != SQLSTATE_UNIQUE_VIOLATION) return@any false
                val constraint = (sql as? org.postgresql.util.PSQLException)?.serverErrorMessage?.constraint
                if (constraint != null) constraint == ACTIVE_PRINT_JOB_INDEX
                else sql.message?.contains(ACTIVE_PRINT_JOB_INDEX) == true
            }
    }
}
