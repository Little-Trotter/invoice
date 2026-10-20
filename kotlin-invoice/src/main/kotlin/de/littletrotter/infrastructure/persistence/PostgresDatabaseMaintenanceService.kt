package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService
import de.littletrotter.application.system.MaintenanceJobQueued
import de.littletrotter.application.system.port.outbound.MaintenanceExecutionFence
import de.littletrotter.application.system.port.outbound.MaintenanceExecutionFencedException
import de.littletrotter.application.system.port.outbound.MaintenanceOperationOutcome
import de.littletrotter.application.system.port.outbound.MaintenanceOperations
import de.littletrotter.domain.exception.InvoiceGenerationAlreadyRunningException
import de.littletrotter.domain.exception.InvoiceImmutableGenerationBlockedException
import de.littletrotter.domain.exception.InvoiceVoidedGenerationBlockedException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.isReadOnly
import de.littletrotter.application.invoice.port.outbound.InvoiceListingRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.shared.DocumentKeyRules
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.time.TimeSource
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** The invoice reads of the recalculation and queueing sweeps: loading one invoice and listing the ids
 *  to sweep. Bundled into one CDI bean for the same reason as [GenerationJobDependencies]. */
@ApplicationScoped
class MaintenanceInvoiceAccess @Inject constructor(
    val repository: InvoiceRepository,
    val listing: InvoiceListingRepository,
)

/** The `QUEUE_ISSUED_GENERATION_JOBS` handler's own two dependencies — bundled into one
 *  CDI bean (a plain data class has no bean-defining annotation, so ARC cannot resolve it as a
 *  constructor injection point, same idiom as [de.littletrotter.application.invoice
 *  .InvoiceLifecycleSupport]) purely to keep [PostgresDatabaseMaintenanceService] under detekt's
 *  `LongParameterList` threshold. Both are already CDI-managed beans elsewhere in this module
 *  (InvoiceExportPanel's print button), wired in here for the exact same reason
 *  `invoiceLifecycleService` is: `performQueueIssuedGenerationJobs` must go through the real
 *  `createJob`/`DocumentStore.exists`, never a raw insert or a re-derived assumption. */
@ApplicationScoped
class GenerationJobDependencies @Inject constructor(
    val queryService: GenerationJobQueryService,
    val creationService: GenerationJobCreationService,
)

@ApplicationScoped
class PostgresDatabaseMaintenanceService
@Inject
constructor(
    private val em: EntityManager,
    private val invoices: MaintenanceInvoiceAccess,
    private val invoiceLifecycleService: InvoiceLifecycleService,
    private val generationJob: GenerationJobDependencies,
    private val documentStore: DocumentStore,
    private val maintenanceJobQueued: Event<MaintenanceJobQueued>,
    private val time: TimeSource,
) : DatabaseMaintenanceService, MaintenanceOperations {
    private val queryService get() = generationJob.queryService
    private val creationService get() = generationJob.creationService

    private val log = Logger.getLogger(PostgresDatabaseMaintenanceService::class.java)
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override fun currentPostgresMaintenance(): DatabaseMaintenanceService.PostgresMaintenanceStatus {
        val rawResult = em.createNativeQuery(
            """
            SELECT
                MAX(last_vacuum) as last_vacuum,
                MAX(last_autovacuum) as last_autovacuum,
                MAX(last_analyze) as last_analyze,
                MAX(last_autoanalyze) as last_autoanalyze
            FROM pg_stat_user_tables
            """.trimIndent()
        ).singleResult
        val result = when (rawResult) {
            is Array<*> -> rawResult
            else -> null
        }

        val vac = toText(result?.getOrNull(0))
        val autoVac = toText(result?.getOrNull(1))
        val analyze = toText(result?.getOrNull(2))
        val autoAnalyze = toText(result?.getOrNull(AUTOANALYZE_COLUMN))

        val reindexEvent = latestMaintenanceEvent(DatabaseMaintenanceType.REINDEX)
        val reindex = reindexEvent?.let { "${it.createdAt.format(formatter)} (${it.status})" }

        val backupFromArchive = em.createNativeQuery(
            "SELECT last_archived_time FROM pg_stat_archiver LIMIT 1"
        ).singleResult?.let { toText(it) }

        val backupEvent = latestMaintenanceEvent(DatabaseMaintenanceType.BACKUP)
        val backup = backupEvent?.let { "${it.createdAt.format(formatter)} (${it.status})" } ?: backupFromArchive

        return DatabaseMaintenanceService.PostgresMaintenanceStatus(
            lastVacuum = vac,
            lastAutoVacuum = autoVac,
            lastAnalyze = analyze,
            lastAutoAnalyze = autoAnalyze,
            lastReindex = reindex,
            lastBackup = backup,
        )
    }

    @Transactional
    override fun recordMaintenanceEvent(event: DatabaseMaintenanceEvent) {
        em.persist(event)
        if (
            event.status == DatabaseMaintenanceStatus.PENDING &&
            event.type in QUEUED_MAINTENANCE_TYPES
        ) {
            maintenanceJobQueued.fire(
                MaintenanceJobQueued(event.id ?: error("Persisted maintenance request has no id")),
            )
        }
    }

    // TS-8 — also called internally by InvoiceLifecycleService.recalculate's own caller chain in
    // reverse (this method drives per-invoice recalculation, not the other way round), so no
    // conflicting-permission concern here, unlike recalculate() itself (see its KDoc).
    /**
     * Deliberately **not** `@Transactional`: each invoice gets its own transaction below.
     *
     * With one shared transaction the sweep looked like it tolerated a bad row but silently threw
     * its work away. A malformed `invoice_number` makes the converter throw a
     * [jakarta.persistence.PersistenceException] while the row is being materialised, and that
     * marks the ambient JTA transaction rollback-only. Catching the exception does not clear that
     * flag, so every successful recalculation before and after it was rolled back at commit while
     * the method still reported "Recalculated N invoices successfully" — a report that was simply
     * untrue.
     *
     * One transaction per invoice means a poisoned row can only doom itself.
     *
     * N15 (PO decision 2026-09-11 evening) — abort on the first data error instead of counting and
     * continuing: ids are processed in ascending order ([InvoiceListingRepository.findAllIdsForRecalculation]
     * is `ORDER BY i.id`), and the moment one invoice's recalculation throws, the sweep stops right
     * there. Read-only (issued) invoices are still just skipped and counted, same as before — they
     * are never recalculated at all (see this method's own KDoc on the interface).
     */
    @RequiresPermission(AppPermission.DATABASE_MAINTENANCE)
    override fun performRecalculateTotals(): String {
        val outcome = executeRecalculateTotals(null)
        recordStandaloneOutcome(DatabaseMaintenanceType.RECALCULATE_TOTALS, outcome)
        return outcome.summary
    }

    private fun executeRecalculateTotals(fence: MaintenanceExecutionFence?): MaintenanceOperationOutcome {
        // Ids, not entities: one row with a malformed invoice_number would otherwise make the
        // SELECT itself throw and abort a sweep over every other invoice — see
        // InvoiceListingRepository.findAllIdsForRecalculation. Loading each invoice in its own
        // transaction below turns such a row into one identifiable failure instead of a dead job.
        val ids = QuarkusTransaction.requiringNew().call { invoices.listing.findAllIdsForRecalculation() }
        var successCount = 0
        var skippedCount = 0

        for (index in ids.indices) {
            val id = ids[index]
            // Captured from inside the transaction below so a failure that happens AFTER the
            // invoice loaded successfully (e.g. the freeze-guard rejecting the header UPDATE, or a
            // missing tax reference discovered while computing amounts) can still name the invoice
            // by its real number, not just its id.
            var loadedInvoiceNumber: String? = null
            try {
                // Own transaction per invoice: a row that cannot even be read must not poison the
                // transaction the other invoices are recalculated in (see this method's KDoc).
                val recalculated = QuarkusTransaction.requiringNew().call {
                    fence?.verifyInCurrentTransaction()
                    val inv = invoices.repository.findById(id) ?: return@call false
                    loadedInvoiceNumber = inv.invoiceNumber.value
                    if (inv.isReadOnly()) return@call false
                    // Task S: the one recalc-and-save operation, not a second implementation —
                    // see InvoiceLifecycleService.recalculate's KDoc.
                    invoiceLifecycleService.recalculate(id)
                    true
                }
                if (recalculated) successCount++ else skippedCount++
            } catch (ex: MaintenanceExecutionFencedException) {
                throw ex
            } catch (ex: Exception) {
                // AP-EXC-1: kept broad on purpose — recalculate() can fail for any number of
                // per-invoice reasons (validation, a freeze-guard rejection, a missing tax
                // reference, malformed data, ...); whichever it is, the sweep must abort here and
                // report exactly what happened rather than silently skipping it.
                val unprocessed = ids.size - index - 1
                val invoiceLabel = describeInvoiceForFailure(id, loadedInvoiceNumber)
                val cause = describeCause(ex)
                val message = "Recalculation aborted at invoice $invoiceLabel: $cause. " +
                    "$successCount invoice(s) recalculated successfully before it, $skippedCount skipped (read-only), " +
                    "$unprocessed invoice(s) left unprocessed."
                log.warnf("Recalculation sweep aborted at invoice %s: %s", invoiceLabel, cause)
                return MaintenanceOperationOutcome.Failed(message)
            }
        }

        val message = "Recalculated $successCount invoices successfully, $skippedCount skipped (read-only), 0 failed."
        return MaintenanceOperationOutcome.Succeeded(message)
    }

    /** Names the invoice a recalculation failure is reported against: its real number once one
     *  was resolved before the failure, its (still empty, sentinel) number spelled out for a DRAFT
     *  that never had one assigned, or just the id when even the load itself is what failed. */
    private fun describeInvoiceForFailure(id: Long, loadedInvoiceNumber: String?): String = when {
        loadedInvoiceNumber == null -> "id $id (invoice number unavailable -- the row itself could not be read)"
        loadedInvoiceNumber.isBlank() -> "id $id (no invoice number assigned yet)"
        else -> "$loadedInvoiceNumber (id $id)"
    }

    /** Exception class plus the first line of its message, per the N15 PO decision ("the cause"). */
    private fun describeCause(ex: Exception): String {
        val firstLine = ex.message?.lineSequence()?.firstOrNull()
        return if (firstLine.isNullOrBlank()) ex.javaClass.simpleName else "${ex.javaClass.simpleName}: $firstLine"
    }

    // Generalized from claimOldestPendingRecalculation to claim across BOTH queue types
    // (RECALCULATE_TOTALS, QUEUE_ISSUED_GENERATION_JOBS), oldest createdAt first regardless of
    // type: the loaders queue the recalculation before the generation sweep for exactly this
    // reason (a PDF rendered before the recalculation rewrote totals could never be regenerated),
    // and this single ORDER BY is what guarantees that ordering is honored end to end.
    @Transactional
    override fun claimOldestPendingMaintenanceJob(): DatabaseMaintenanceService.ClaimedMaintenanceJob? {
        val candidate = em.createQuery(
            "SELECT e FROM DatabaseMaintenanceEvent e " +
                "WHERE e.type IN (:recalculate, :queueIssued) AND e.status = :pending ORDER BY e.createdAt ASC",
            DatabaseMaintenanceEvent::class.java,
        )
            .setParameter("recalculate", DatabaseMaintenanceType.RECALCULATE_TOTALS)
            .setParameter("queueIssued", DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS)
            .setParameter(PENDING_PARAM, DatabaseMaintenanceStatus.PENDING)
            .setMaxResults(1)
            .resultList
            .firstOrNull() ?: return null

        val id = candidate.id ?: return null

        val updated = em.createQuery(
            "UPDATE DatabaseMaintenanceEvent e SET e.status = :running " +
                "WHERE e.id = :id AND e.status = :pending",
        )
            .setParameter("running", DatabaseMaintenanceStatus.RUNNING)
            .setParameter("id", id)
            .setParameter(PENDING_PARAM, DatabaseMaintenanceStatus.PENDING)
            .executeUpdate()

        if (updated == 0) return null // lost the race to another worker instance
        return DatabaseMaintenanceService.ClaimedMaintenanceJob(id, candidate.type)
    }

    @Transactional
    override fun markMaintenanceJobFailed(eventId: Long, reason: String) {
        em.createQuery("UPDATE DatabaseMaintenanceEvent e SET e.status = :failed, e.details = :details WHERE e.id = :id")
            .setParameter("failed", DatabaseMaintenanceStatus.FAILED)
            .setParameter("details", "Maintenance job crashed: $reason")
            .setParameter("id", eventId)
            .executeUpdate()
    }

    @RequiresPermission(AppPermission.DATABASE_MAINTENANCE)
    @Transactional
    override fun queueIssuedGenerationJobsSweep(details: String): DatabaseMaintenanceEvent {
        val event = DatabaseMaintenanceEvent(
            type = DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS,
            status = DatabaseMaintenanceStatus.PENDING,
            details = details,
            createdAt = time.nowUtc(),
        )
        recordMaintenanceEvent(event)
        return event
    }

    @RequiresPermission(AppPermission.DATABASE_MAINTENANCE)
    @Transactional
    override fun queueRecalculation(details: String): DatabaseMaintenanceEvent {
        val event = DatabaseMaintenanceEvent(
            type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
            status = DatabaseMaintenanceStatus.PENDING,
            details = details,
            createdAt = time.nowUtc(),
        )
        recordMaintenanceEvent(event)
        return event
    }

    /**
     * N12 — the ids of every issued invoice (ISSUED/CANCELLED) that has neither a non-terminal job
     * nor a COMPLETED issued job, oldest invoice id first. A scalar `i.id` projection, not `SELECT
     * i` — same reason as [InvoiceListingRepository.findAllIdsForRecalculation]: materialising a whole
     * [de.littletrotter.domain.invoice.Invoice] runs every `AttributeConverter` on the row, and one
     * malformed `invoice_number` anywhere in this shared table would abort the bulk `SELECT` before
     * the per-invoice guard below ever gets a chance to see it.
     */
    private fun findInvoiceIdsNeedingIssuedGenerationJob(): List<Long> =
        em.createQuery(
            """
            SELECT i.id FROM Invoice i
            WHERE i.status IN (:sent, :cancelled)
              AND NOT EXISTS (
                  SELECT 1 FROM GenerationJob j
                  WHERE j.invoice = i AND j.status IN (:pending, :delegated, :running)
              )
              AND NOT EXISTS (
                  SELECT 1 FROM GenerationJob j
                  WHERE j.invoice = i AND j.status = :completed AND j.issued = true
              )
            ORDER BY i.id
            """.trimIndent(),
            Long::class.javaObjectType,
        )
            .setParameter("sent", InvoiceStatus.ISSUED)
            .setParameter("cancelled", InvoiceStatus.CANCELLED)
            .setParameter(PENDING_PARAM, GenerationJobStatus.PENDING)
            .setParameter("delegated", GenerationJobStatus.DELEGATED)
            .setParameter("running", GenerationJobStatus.RUNNING)
            .setParameter("completed", GenerationJobStatus.COMPLETED)
            .resultList
            .map { it.toLong() }

    /**
     * N12 — handler for [DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS], same shape as
     * [performRecalculateTotals]: one transaction per invoice (a poisoned row can only doom
     * itself), never aborts the sweep on one bad row, records its own terminal event.
     *
     * P5 — an invoice with NO job row at all whose export PDF key already exists in the object
     * store (a restore where the database is older than the object store) is skipped and named in
     * the details rather than regenerated; created/skipped(P5 or gate)/failed are all counted and
     * reported separately, same "name the counts and the first failure reasons" contract as
     * [performRecalculateTotals].
     */
    @RequiresPermission(AppPermission.DATABASE_MAINTENANCE)
    override fun performQueueIssuedGenerationJobs(): String {
        val outcome = executeQueueIssuedGenerationJobs(null)
        recordStandaloneOutcome(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, outcome)
        return outcome.summary
    }

    private fun executeQueueIssuedGenerationJobs(fence: MaintenanceExecutionFence?): MaintenanceOperationOutcome {
        val ids = QuarkusTransaction.requiringNew().call { findInvoiceIdsNeedingIssuedGenerationJob() }
        var createdCount = 0
        var skippedCount = 0
        var failCount = 0
        val details = StringBuilder()

        ids.forEach { id ->
            try {
                when (processIssuedGenerationJobForInvoice(id, fence, details)) {
                    QueueOutcome.CREATED -> createdCount++
                    QueueOutcome.SKIPPED -> skippedCount++
                }
            } catch (ex: InvoiceImmutableGenerationBlockedException) {
                // Gate rejection — e.g. a concurrent UI-triggered print job won the race and
                // completed between this sweep's own SELECT and this invoice's transaction. Not a
                // failure: the invoice already has (or is getting) its generation, exactly what
                // this sweep wanted for it.
                skippedCount++
                log.infof("Invoice #%d already generation-locked by the time this sweep reached it, skipping: %s", id, ex.message)
                details.append("Invoice #$id skipped -- gate rejected (likely a concurrent generation): ${ex.message}\n")
            } catch (ex: InvoiceGenerationAlreadyRunningException) {
                // A print job for this invoice is already in flight — the sweep's goal is met.
                skippedCount++
                details.append("Invoice #$id skipped -- generation already running: ${ex.message}\n")
            } catch (ex: InvoiceVoidedGenerationBlockedException) {
                // The invoice was voided concurrently between this sweep's own SELECT and this
                // invoice's transaction (VOIDED is never itself selected — isIssued excludes it).
                // Also a legitimate gate rejection, not a failure.
                skippedCount++
                details.append("Invoice #$id skipped -- voided concurrently: ${ex.message}\n")
            } catch (ex: MaintenanceExecutionFencedException) {
                throw ex
            } catch (ex: Exception) {
                // AP-EXC-1: kept broad on purpose — same reasoning as performRecalculateTotals's
                // own per-invoice catch: createJob can fail for any number of per-invoice reasons
                // (a validation defect, an unreadable row) and this is a sweep over potentially
                // hundreds of invoices, so one bad row must be counted and skipped rather than
                // aborting the whole maintenance run.
                failCount++
                log.warnf("Queueing an issued-generation job failed for invoice #%d: %s", id, ex.message)
                details.append("Invoice #$id failed: ${ex.message}\n")
            }
        }

        val message = "Queued $createdCount issued-generation job(s), $skippedCount skipped, $failCount failed."
        val outcomeDetails = "$message\n$details"
        return if (failCount == 0) {
            MaintenanceOperationOutcome.Succeeded(message, outcomeDetails)
        } else {
            MaintenanceOperationOutcome.Failed(message, outcomeDetails)
        }
    }

    private fun processIssuedGenerationJobForInvoice(
        id: Long,
        fence: MaintenanceExecutionFence?,
        details: StringBuilder,
    ): QueueOutcome = QuarkusTransaction.requiringNew().call {
        fence?.verifyInCurrentTransaction()
        val invoice = invoices.repository.findById(id) ?: return@call QueueOutcome.SKIPPED
        if (queryService.findByInvoiceId(id).isEmpty()) {
            // Every export writes per-attempt keys (DocumentKeyRules.deriveJobExportKeys), so the
            // restored PDF is found by listing this invoice's export prefix, not by one fixed key.
            val number = invoice.invoiceNumber.value
            val pdfKey = documentStore.listObjectKeys(DocumentKeyRules.issuedJobExportPrefix(number, id))
                .firstOrNull { DocumentKeyRules.isIssuedJobPdfKey(it, number, id) }
            if (pdfKey != null) {
                details.append(
                    "Invoice #$id skipped -- restore case: export PDF already exists in the " +
                        "object store ($pdfKey) with no job row; regenerate explicitly or restore RustFS.\n",
                )
                return@call QueueOutcome.SKIPPED
            }
        }
        creationService.createJob(
            invoiceId = id,
            jobType = "PRINT_JOB",
            lane = DocumentJobLane.BULK,
            llmOptimize = false,
        )
        QueueOutcome.CREATED
    }

    @RequiresPermission(AppPermission.DATABASE_MAINTENANCE)
    override fun execute(
        type: DatabaseMaintenanceType,
        fence: MaintenanceExecutionFence,
    ): MaintenanceOperationOutcome = when (type) {
        DatabaseMaintenanceType.RECALCULATE_TOTALS -> executeRecalculateTotals(fence)
        DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS -> executeQueueIssuedGenerationJobs(fence)
        else -> MaintenanceOperationOutcome.Failed("No maintenance queue handler for type $type")
    }

    /** Preserves the historical direct-call API while queue drains complete their original row. */
    private fun recordStandaloneOutcome(
        type: DatabaseMaintenanceType,
        outcome: MaintenanceOperationOutcome,
    ) {
        QuarkusTransaction.requiringNew().run {
            recordMaintenanceEvent(
                DatabaseMaintenanceEvent(
                    type = type,
                    status = when (outcome) {
                        is MaintenanceOperationOutcome.Succeeded -> DatabaseMaintenanceStatus.SUCCESS
                        is MaintenanceOperationOutcome.Failed -> DatabaseMaintenanceStatus.FAILED
                    },
                    details = outcome.details,
                    createdAt = time.nowUtc(),
                ),
            )
        }
    }

    /** Outcome of a single invoice's per-transaction attempt in [performQueueIssuedGenerationJobs]
     *  — a gate rejection (e.g. lost a race to a concurrent UI-triggered job) and a P5 restore-case
     *  skip are deliberately not distinguished here (both are counted as "skipped" and named in the
     *  details text); only [performQueueIssuedGenerationJobs]'s own `catch` counts as a failure. */
    private enum class QueueOutcome { CREATED, SKIPPED }

    override fun latestMaintenanceEvent(type: DatabaseMaintenanceType): DatabaseMaintenanceEvent? {
        val query = em.createQuery(
            "SELECT e FROM DatabaseMaintenanceEvent e WHERE e.type = :type ORDER BY e.createdAt DESC",
            DatabaseMaintenanceEvent::class.java
        )
        query.setParameter("type", type)
        query.maxResults = 1
        return query.resultList.firstOrNull()
    }

    private fun toText(value: Any?): String? {
        return when (value) {
            null -> null
            is Timestamp -> value.toLocalDateTime().format(formatter)
            is LocalDateTime -> value.format(formatter)
            is OffsetDateTime -> value.toLocalDateTime().format(formatter)
            is String -> value
            else -> value.toString()
        }
    }

    companion object {
        private val QUEUED_MAINTENANCE_TYPES = setOf(
            DatabaseMaintenanceType.RECALCULATE_TOTALS,
            DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS,
        )

        /** Column index of `last_autoanalyze` in the [currentPostgresMaintenance] result row. */
        private const val AUTOANALYZE_COLUMN = 3

        private const val PENDING_PARAM = "pending"
    }
}
