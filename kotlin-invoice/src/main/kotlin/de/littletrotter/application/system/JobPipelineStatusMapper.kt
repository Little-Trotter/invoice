package de.littletrotter.application.system

import de.littletrotter.domain.repository.GenerationJobPipelineCounts
import java.time.Duration
import java.time.OffsetDateTime

/**
 * The pure mapping from raw counts to [JobPipelineStatus]. `DELEGATED` means a job is published
 * to JetStream, not claimed by a worker — the database cannot tell queued from in-progress, so
 * "In Bearbeitung" is the worker consumer's own `numAckPending` (transport), never a DB count.
 * `FAILED` and DLQ counts are never summed into one number.
 */
object JobPipelineStatusMapper {

    val OLDEST_AGE_WARNING_THRESHOLD: Duration = Duration.ofMinutes(10)

    fun map(counts: GenerationJobPipelineCounts, transport: TransportSnapshot?, now: OffsetDateTime): JobPipelineStatus {
        val oldestAge = counts.oldestOpenCreatedAt?.let { Duration.between(it, now) }
        val workerConnected = transport?.workerConnected ?: false
        val openWithoutWorker = counts.openCount > 0 && !workerConnected

        val created = JobPipelineStatus.CreatedStage(
            count = counts.openCount,
            oldestAge = oldestAge,
            state = when {
                openWithoutWorker -> PipelineCardState.ERROR
                oldestAge != null && oldestAge > OLDEST_AGE_WARNING_THRESHOLD -> PipelineCardState.WARNING
                else -> PipelineCardState.OK
            },
        )

        val inProgress = JobPipelineStatus.InProgressStage(
            ackPending = transport?.ackPending,
            workerConnected = workerConnected,
            state = if (openWithoutWorker) PipelineCardState.ERROR else PipelineCardState.OK,
        )

        val completed = JobPipelineStatus.CompletedStage(
            last24h = counts.completedLast24h,
            last5m = counts.completedLast5m,
        )

        val dlqMessages = transport?.dlqMessages ?: 0
        val failed = JobPipelineStatus.FailedStage(
            failedCount = counts.failedCount,
            dlqMessages = dlqMessages,
            dlqBytes = transport?.dlqBytes ?: 0,
            state = if (counts.failedCount > 0 || dlqMessages > 0) PipelineCardState.ERROR else PipelineCardState.OK,
        )

        val transportLine = if (transport == null) {
            JobPipelineStatus.TransportLine(reachable = false)
        } else {
            JobPipelineStatus.TransportLine(
                reachable = true,
                queueDepth = transport.queueDepth,
                ackPending = transport.ackPending,
                unprocessedResults = transport.unprocessedResults,
                redelivered = transport.redelivered,
                duplicates = duplicates(counts, transport),
            )
        }

        return JobPipelineStatus(created, inProgress, completed, failed, transportLine)
    }

    /** "Duplikate" — how far the in-flight transport count (queued + claimed by a worker) exceeds
     *  the DB's own open-job count. Never negative — a transport count temporarily *below* the DB
     *  count (e.g. a result just landed but the DB write has not committed yet) is not a
     *  duplicate. */
    private fun duplicates(counts: GenerationJobPipelineCounts, transport: TransportSnapshot): Long {
        val inTransit = transport.queueDepth + transport.ackPending
        return (inTransit - counts.openCount).coerceAtLeast(0)
    }
}
