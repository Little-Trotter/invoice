package de.littletrotter.application.system

import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceType

/**
 * Port for querying PostgreSQL maintenance state (vacuum/analyze/reindex/backup metadata).
 *
 * Read-only as far as PostgreSQL-level maintenance goes: running VACUUM/ANALYZE/REINDEX is not
 * offered here. Those statements take heavy locks, can run for a long time, and cannot execute
 * inside a transaction block at all — so they belong in a maintenance window at a shell
 * (ADM-1: admin-tools/scripts/db/db-maintenance.sh), not behind a button in the web UI.
 *
 * This is intentionally separated from runtime dependency checks (SystemStatusService).
 */
interface DatabaseMaintenanceService {

    fun currentPostgresMaintenance(): PostgresMaintenanceStatus

    fun recordMaintenanceEvent(event: DatabaseMaintenanceEvent)

    fun latestMaintenanceEvent(type: DatabaseMaintenanceType): DatabaseMaintenanceEvent?

    /**
     * Recalculates and persists totals for every invoice with [de.littletrotter.domain.invoice.Invoice.needsRecalculation]
     * set, delegating to [de.littletrotter.application.invoice.InvoiceLifecycleService.recalculate] per
     * invoice — the one place a stored invoice's totals get recomputed (task S). Invoices whose
     * totals are already current (the normal case — every item/header write recalculates in the
     * same transaction) are skipped by the repository query itself, not just counted as
     * "skipped" here; this is what keeps repeated runs of this job a no-op instead of a full
     * table sweep.
     *
     * Issued invoices ([de.littletrotter.domain.invoice.Invoice.isReadOnly]) are never
     * recalculated — a correctly-issued invoice's totals never need a post-issue rewrite, since
     * [de.littletrotter.domain.invoice.Invoice.recalculateTotals] already ran before the
     * freeze applied. Such an invoice is skipped and counted, exactly like before.
     *
     * N15 — abort on first data error: the sweep processes invoices in ascending id order and
     * stops at the FIRST one whose recalculation fails (a validation error, a freeze-guard
     * rejection, a missing tax reference, or any other exception) rather than counting it and
     * continuing. The recorded [de.littletrotter.domain.system.DatabaseMaintenanceEvent] is
     * FAILED, naming the offending invoice (number and id), the cause (exception class and the
     * first line of its message), how many invoices were recalculated successfully before it, and
     * how many are left unprocessed. Analysis is a developer task — there is no automatic repair.
     * A DRAFT/IN_REVIEW invoice's offending position can be deleted by the user
     * ([de.littletrotter.application.invoice.InvoiceLifecycleService.deleteItemByPosition]), after
     * which a re-queued sweep succeeds; an issued invoice with such a defect is a developer case,
     * since it cannot arise from valid data.
     */
    fun performRecalculateTotals(): String

    /**
     * N12 — sweeps every invoice with [de.littletrotter.domain.invoice.Invoice.status]`.`
     * [de.littletrotter.domain.invoice.isIssued] (SENT, CANCELLED — includes the storno document,
     * type code 384) that has neither a non-terminal [de.littletrotter.domain.invoice
     * .GenerationJobStatus] job (PENDING/DELEGATED/RUNNING) nor a COMPLETED job with
     * [de.littletrotter.domain.invoice.GenerationJob.issued] `true`, and queues a real generation
     * job for each one via [de.littletrotter.application.invoice.GenerationJobService.createJob]
     * (not a raw insert — the VOIDED guard, the fast-path publish and the journal
     * all apply). A FAILED-only invoice qualifies (a failed job never locks, a retry stays
     * possible) — same "the sweep repairs what it can, reports what it can't" shape as
     * [performRecalculateTotals]: never aborts on one bad row, counts created/skipped/failed, and
     * records its own terminal [de.littletrotter.domain.system.DatabaseMaintenanceEvent].
     *
     * P5 (restore case) — an invoice with NO job row at all whose export PDF key already exists in
     * the object store is skipped and named in the report rather than regenerated: a database
     * restored from a backup taken before the export (object store not, or the reverse) is a
     * decision for an operator (RustFS restore vs. explicit regeneration), never an automatism.
     *
     * Idempotent: a second run with nothing left to do ends SUCCESS with 0 created.
     */
    fun performQueueIssuedGenerationJobs(): String

    /**
     * Atomically claims the oldest still-PENDING maintenance-queue request —
     * [DatabaseMaintenanceType.RECALCULATE_TOTALS] or
     * [DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS] combined, oldest `createdAt` first
     * regardless of type —
     * for [de.littletrotter.application.invoice.MaintenanceQueueProcessor]'s single sequential
     * worker; same claim-then-process shape as [de.littletrotter.domain.repository
     * .GenerationJobRepository.tryMarkDelegated]. Returns null when there is nothing queued.
     */
    fun claimOldestPendingMaintenanceJob(): ClaimedMaintenanceJob?

    /** Records that the claimed request in [claimOldestPendingMaintenanceJob] crashed. */
    fun markMaintenanceJobFailed(eventId: Long, reason: String)

    /**
     * P1 — queues a [DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS] event with [details],
     * for [de.littletrotter.application.invoice.MaintenanceQueueProcessor] to pick up next poll.
     * The System-tab button's only action (an admin-only, [de.littletrotter.application.security
     * .AppPermission.DATABASE_MAINTENANCE]-gated operation, same as [performRecalculateTotals]) —
     * useful after a restore that lost the object store, or any other time an operator wants the
     * sweep to run sooner than its next scheduled trigger.
     */
    fun queueIssuedGenerationJobsSweep(details: String): DatabaseMaintenanceEvent

    /**
     * N15 — queues a [DatabaseMaintenanceType.RECALCULATE_TOTALS] event with [details], for
     * [de.littletrotter.application.invoice.MaintenanceQueueProcessor] to pick up next poll. The
     * System-tab button's action (an admin-only,
     * [de.littletrotter.application.security.AppPermission.DATABASE_MAINTENANCE]-gated operation,
     * same as [performRecalculateTotals] itself) — useful whenever an operator wants the sweep to
     * run sooner than its next scheduled trigger, e.g. after fixing a data error the previous run
     * aborted on.
     */
    fun queueRecalculation(details: String): DatabaseMaintenanceEvent

    data class ClaimedMaintenanceJob(val eventId: Long, val type: DatabaseMaintenanceType)

    /**
     * `lastBackup` reflects Postgres' own WAL archiver status (`pg_stat_archiver`) or a recorded
     * `DatabaseMaintenanceType.BACKUP` event — independent of the app-level backup feature
     * (ADM-1: admin-tools/scripts/backup/backup.sh/restore.sh), which does not go through this
     * service at all.
     */
    data class PostgresMaintenanceStatus(
        val lastVacuum: String?,
        val lastAutoVacuum: String?,
        val lastAnalyze: String?,
        val lastAutoAnalyze: String?,
        val lastReindex: String?,
        val lastBackup: String?,
    )
}
