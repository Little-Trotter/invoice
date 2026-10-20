package de.littletrotter.application.invoice

import de.littletrotter.application.shared.AuditActionOverride
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.DocumentLossReason
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.RestoreSource
import de.littletrotter.domain.repository.GenerationJobRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

/** What the lost-original request found out about the document, for the audit row. */
class LossFacts(val reason: DocumentLossReason, val hashOld: String?, val hashFound: String?)

/** Result of asking for a regeneration: a job of its own, or the one that is already running. */
sealed interface RegenerationOutcome {
    data class Created(val job: GenerationJob) : RegenerationOutcome

    data class AlreadyRunning(val jobId: Long, val reproduction: Boolean) : RegenerationOutcome
}

/**
 * The transactional pieces of the document recovery. They live in their own bean so that the
 * recovery service, which talks to the document store and the mail component between them, never
 * holds a database transaction across those calls and still goes through the CDI interceptors.
 */
@ApplicationScoped
class InvoiceDocumentRecoveryTransactions @Inject constructor(
    private val em: EntityManager,
    private val generationJobRepository: GenerationJobRepository,
    private val generationJobCreationService: GenerationJobCreationService,
    private val auditActionOverride: AuditActionOverride,
    private val auditTouch: InvoiceDocumentAuditTouch,
) {

    /** The newest PRINT job of [invoiceId] that has not reached a terminal state, if any. */
    fun runningPrintJob(invoiceId: Long): GenerationJob? =
        generationJobRepository.findByInvoiceId(invoiceId)
            .filter { it.jobType == GenerationJobCreationService.PRINT_JOB_TYPE && it.status in NON_TERMINAL }
            .maxByOrNull { it.id ?: 0L }

    /**
     * A voided invoice never had an issued document, so no stored row can be marked lost: the
     * reproduction job is created on its own. A running print job is answered instead of rejected.
     */
    @Transactional
    fun requestReproductionWithoutRow(invoiceId: Long): RegenerationOutcome {
        runningPrintJob(invoiceId)?.let {
            return RegenerationOutcome.AlreadyRunning(requireNotNull(it.id), it.trigger == GenerationJobTrigger.REPRODUCTION)
        }
        val job = generationJobCreationService.createJob(
            invoiceId,
            jobType = GenerationJobCreationService.PRINT_JOB_TYPE,
            lane = DocumentJobLane.BULK,
            trigger = GenerationJobTrigger.REPRODUCTION,
        )
        return RegenerationOutcome.Created(job)
    }

    /**
     * Creates the regeneration job and moves the lost row to LOST in one transaction. The row is
     * locked first, so two concurrent accesses serialize: the second sees the LOST row and the
     * job of the first instead of creating another one. When a print job is already running,
     * [GenerationJobCreationService.createJob] rejects and the whole transaction rolls back — the
     * row then stays COMPLETED and the caller answers with the running job.
     *
     * The audit journal lock is taken by [SetsAuditGuc] before the row lock: without it the lock
     * would only be requested later, inside the job creation, while this transaction already holds
     * the row, which can form a cycle with a transaction holding the journal lock and waiting for
     * the same row.
     */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.DOCUMENT_GENERATED)
    fun requestRegeneration(documentId: Long, invoiceId: Long, reproduction: Boolean, facts: LossFacts): RegenerationOutcome {
        val document = requireNotNull(em.find(InvoiceDocument::class.java, documentId, LockModeType.PESSIMISTIC_WRITE)) {
            "invoice_document $documentId disappeared"
        }
        val wasCompleted = document.generationStatus == DocumentGenerationStatus.COMPLETED
        if (!wasCompleted) {
            runningPrintJob(invoiceId)?.let {
                return RegenerationOutcome.AlreadyRunning(requireNotNull(it.id), it.trigger == GenerationJobTrigger.REPRODUCTION)
            }
        }
        val trigger = if (reproduction) GenerationJobTrigger.REPRODUCTION else GenerationJobTrigger.REGENERATION
        val job = generationJobCreationService.createJob(
            invoiceId,
            jobType = GenerationJobCreationService.PRINT_JOB_TYPE,
            lane = DocumentJobLane.BULK,
            trigger = trigger,
        )
        val action = if (reproduction) InvoiceAuditAction.DOCUMENT_REPRODUCED else InvoiceAuditAction.DOCUMENT_REGENERATED
        val context = mapOf(
            "phase" to "REQUESTED",
            "reason" to facts.reason.name,
            "hash_old" to facts.hashOld,
            "hash_found" to facts.hashFound,
            "source" to "RENDERED",
            "job_id" to job.id,
        )
        var journaled = false
        try {
            if (wasCompleted) {
                auditActionOverride.withAction(action, context) { document.markLost() }
            } else {
                // A retry after a failed regeneration: nothing changes on the row, but the new
                // request still belongs in the audit trail.
                auditTouch.touch(documentId, action, context)
            }
            journaled = true
        } finally {
            if (!journaled) discardJob(requireNotNull(job.id))
        }
        return RegenerationOutcome.Created(job)
    }

    /**
     * The job repository commits a new job in its own transaction, so a failure of this
     * transaction would leave it behind and the poller would render a document nobody asked for.
     * Removing it in yet another transaction keeps "no audit row, no job" true.
     */
    private fun discardJob(jobId: Long) {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM generation_job WHERE id = :id").setParameter("id", jobId).executeUpdate()
        }
    }

    /**
     * Journals a restore. When the restored bytes replace a reproduction, the row takes the
     * original's checksum and loses its reproduction tag; otherwise the row is unchanged and only
     * its version moves.
     */
    @Transactional
    fun recordRestore(documentId: Long, reason: String, found: RestoredCopy, clearReproduction: Boolean) {
        val context = mapOf(
            "reason" to reason,
            "source" to found.source.name,
            "journal_id" to found.journalId,
            "file" to found.file,
            "hash_new" to found.sha256,
        )
        if (!clearReproduction) {
            auditTouch.touch(documentId, InvoiceAuditAction.DOCUMENT_RESTORED, context)
            return
        }
        val document = requireNotNull(em.find(InvoiceDocument::class.java, documentId)) { "invoice_document $documentId disappeared" }
        auditActionOverride.withAction(InvoiceAuditAction.DOCUMENT_RESTORED, context) {
            document.restoreOriginal(found.sha256)
        }
    }
}

private val NON_TERMINAL = setOf(GenerationJobStatus.PENDING, GenerationJobStatus.DELEGATED, GenerationJobStatus.RUNNING)

/** Bytes written back to the document store together with where they came from. */
class RestoredCopy(val source: RestoreSource, val sha256: String, val journalId: Long? = null, val file: String? = null)
