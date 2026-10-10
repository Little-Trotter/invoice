package de.littletrotter.application.system

import java.time.Duration

/** The state a pipeline card is in, driving both its colour token and its text/icon (a
 *  card's state is never colour-only). */
enum class PipelineCardState { OK, WARNING, ERROR }

/**
 * One pipeline row, left to right: created, in progress, completed, failed, plus the raw
 * JetStream transport line beneath it. [created]/[completed]/[failed] are DB-authoritative
 * ([de.littletrotter.domain.repository.GenerationJobRepository]); [inProgress] and [transport] are
 * JetStream-only. A DB job count and a transport count are never folded into one number.
 */
data class JobPipelineStatus(
    val created: CreatedStage,
    val inProgress: InProgressStage,
    val completed: CompletedStage,
    val failed: FailedStage,
    val transport: TransportLine,
) {

    /** "Angelegt" — every open job (`PENDING`, `DELEGATED`, `RUNNING`) that has not produced a
     *  result yet. */
    data class CreatedStage(
        val count: Long,
        val oldestAge: Duration?,
        val state: PipelineCardState,
    )

    /** "In Bearbeitung" — the worker consumer's own `numAckPending`: messages currently claimed
     *  by a worker. `null` when the broker is unreachable. */
    data class InProgressStage(
        val ackPending: Long?,
        val workerConnected: Boolean,
        val state: PipelineCardState,
    )

    /** "Fertig" — `COMPLETED` jobs; never turns warning/error. */
    data class CompletedStage(
        val last24h: Long,
        val last5m: Long,
    )

    /** "Fehlgeschlagen" — the DB-authoritative `FAILED` count is the card's big number; the DLQ
     *  figures are transport (JetStream) and stay in the sub-line, never summed into
     *  [failedCount]. */
    data class FailedStage(
        val failedCount: Long,
        val dlqMessages: Long,
        val dlqBytes: Long,
        val state: PipelineCardState,
    )

    /** The raw JetStream transport line below the pipeline row. All fields default to `0` when
     *  [reachable] is `false` — the caller must check [reachable] before showing them. */
    data class TransportLine(
        val reachable: Boolean,
        val queueDepth: Long = 0,
        val ackPending: Long = 0,
        val unprocessedResults: Long = 0,
        val redelivered: Long = 0,
        val duplicates: Long = 0,
    )
}
