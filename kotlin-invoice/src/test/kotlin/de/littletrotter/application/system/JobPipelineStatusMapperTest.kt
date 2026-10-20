package de.littletrotter.application.system

import de.littletrotter.domain.repository.GenerationJobPipelineCounts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import de.littletrotter.application.dto.PipelineCardState

/**
 * Pure unit tests for [JobPipelineStatusMapper]: no NATS, no DB, no Quarkus boot. Every input is
 * a plain value, matching the class's own KDoc.
 */
class JobPipelineStatusMapperTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-09-18T12:00:00Z")

    private fun transport(
        queueDepth: Long = 0,
        ackPending: Long = 0,
        unprocessedResults: Long = 0,
        redelivered: Long = 0,
        workerConnected: Boolean = false,
        dlqMessages: Long = 0,
        dlqBytes: Long = 0,
    ) = TransportSnapshot(queueDepth, ackPending, unprocessedResults, redelivered, workerConnected, dlqMessages, dlqBytes)

    // ── created stage ────────────────────────────────────────────────────────

    @Test
    fun `created stage reports the open count and a null oldest age when nothing is open`() {
        val status = JobPipelineStatusMapper.map(GenerationJobPipelineCounts(openCount = 0), transport(workerConnected = true), now)

        assertEquals(0, status.created.count)
        assertEquals(null, status.created.oldestAge)
        assertEquals(PipelineCardState.OK, status.created.state)
    }

    @Test
    fun `created stage counts DELEGATED and RUNNING jobs too, not only PENDING`() {
        // openCount is whatever the repository's grouped query already summed across PENDING,
        // DELEGATED and RUNNING -- the mapper itself has no per-status knowledge left to check.
        val status = JobPipelineStatusMapper.map(GenerationJobPipelineCounts(openCount = 9), transport(workerConnected = true), now)

        assertEquals(9, status.created.count)
    }

    @Test
    fun `created stage stays OK when the oldest open job is exactly at the warning threshold`() {
        val oldest = now.minus(JobPipelineStatusMapper.OLDEST_AGE_WARNING_THRESHOLD)
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(openCount = 3, oldestOpenCreatedAt = oldest),
            transport(workerConnected = true),
            now,
        )

        assertEquals(3, status.created.count)
        assertEquals(Duration.ofMinutes(10), status.created.oldestAge)
        assertEquals(PipelineCardState.OK, status.created.state, "exactly at the threshold must not warn yet")
    }

    @Test
    fun `created stage turns WARNING once the oldest open job exceeds the warning threshold`() {
        val oldest = now.minus(JobPipelineStatusMapper.OLDEST_AGE_WARNING_THRESHOLD).minusSeconds(1)
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(openCount = 3, oldestOpenCreatedAt = oldest),
            transport(workerConnected = true),
            now,
        )

        assertEquals(PipelineCardState.WARNING, status.created.state)
    }

    @Test
    fun `created stage turns ERROR (not WARNING) when open jobs are old AND no worker is connected`() {
        val oldest = now.minus(JobPipelineStatusMapper.OLDEST_AGE_WARNING_THRESHOLD).minusHours(1)
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(openCount = 3, oldestOpenCreatedAt = oldest),
            transport(workerConnected = false),
            now,
        )

        assertEquals(PipelineCardState.ERROR, status.created.state, "no worker connected outranks the age warning")
    }

    // ── in-progress stage ────────────────────────────────────────────────────

    @Test
    fun `in-progress stage's big number is the worker consumer's own ackPending, not a DB count`() {
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(openCount = 0),
            transport(workerConnected = true, ackPending = 7),
            now,
        )

        assertEquals(7, status.inProgress.ackPending)
        assertEquals(PipelineCardState.OK, status.inProgress.state)
    }

    @Test
    fun `in-progress stage's ackPending is null when the broker is unreachable, not defaulted to zero`() {
        val status = JobPipelineStatusMapper.map(GenerationJobPipelineCounts(openCount = 0), null, now)

        assertNull(status.inProgress.ackPending)
        assertFalse(status.inProgress.workerConnected)
    }

    @Test
    fun `in-progress stage turns ERROR when jobs are open and no worker is connected`() {
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(openCount = 5),
            transport(workerConnected = false),
            now,
        )

        assertEquals(PipelineCardState.ERROR, status.inProgress.state)
        assertFalse(status.inProgress.workerConnected)
    }

    @Test
    fun `in-progress stage stays OK when jobs are open but a worker is connected`() {
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(openCount = 5),
            transport(workerConnected = true, ackPending = 3),
            now,
        )

        assertEquals(PipelineCardState.OK, status.inProgress.state)
        assertTrue(status.inProgress.workerConnected)
        assertEquals(3, status.inProgress.ackPending)
    }

    // ── completed stage ──────────────────────────────────────────────────────

    @Test
    fun `completed stage passes the 24h and 5min counts through unchanged`() {
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(completedLast24h = 40, completedLast5m = 2),
            transport(workerConnected = true),
            now,
        )

        assertEquals(40, status.completed.last24h)
        assertEquals(2, status.completed.last5m)
    }

    // ── failed stage — never mixes the DB count and the DLQ transport count ────

    @Test
    fun `failed stage's big number is the DB FAILED count alone, never summed with DLQ messages`() {
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(failedCount = 3),
            transport(workerConnected = true, dlqMessages = 4, dlqBytes = 2048),
            now,
        )

        assertEquals(3, status.failed.failedCount, "must stay the DB count, not 3 + 4")
        assertEquals(4, status.failed.dlqMessages)
        assertEquals(2048, status.failed.dlqBytes)
    }

    @Test
    fun `failed stage turns ERROR from a non-empty DLQ even when no job itself is FAILED`() {
        val status = JobPipelineStatusMapper.map(GenerationJobPipelineCounts(failedCount = 0), transport(workerConnected = true, dlqMessages = 1), now)

        assertEquals(PipelineCardState.ERROR, status.failed.state)
    }

    @Test
    fun `failed stage stays OK when there are no failed jobs and no DLQ messages`() {
        val status = JobPipelineStatusMapper.map(GenerationJobPipelineCounts(failedCount = 0), transport(workerConnected = true, dlqMessages = 0), now)

        assertEquals(PipelineCardState.OK, status.failed.state)
    }

    // ── duplicates ────────────────────────────────────────────────────────────

    @Test
    fun `duplicates is the surplus of in-transit messages over the DB's own open job count`() {
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(openCount = 631),
            transport(workerConnected = true, queueDepth = 700, ackPending = 5),
            now,
        )

        assertEquals(74, status.transport.duplicates, "(700 + 5) - 631 = 74")
    }

    @Test
    fun `duplicates never goes negative when transport is temporarily behind the DB`() {
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(openCount = 15),
            transport(workerConnected = true, queueDepth = 1, ackPending = 1),
            now,
        )

        assertEquals(0, status.transport.duplicates)
    }

    /** The incident this task documents, replayed exactly: 631 jobs published to JetStream (all
     *  `DELEGATED`, none `PENDING`), a worker throttled to 0 messages currently claimed but still
     *  connected, and a producer that kept re-publishing — the queue grew to 4601 messages for
     *  only 631 real open jobs. */
    @Test
    fun `incident replay -- 631 open jobs, a connected-but-idle worker and a 4601-message queue`() {
        val oldest = now.minusMinutes(30)
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(openCount = 631, oldestOpenCreatedAt = oldest),
            transport(workerConnected = true, queueDepth = 4601, ackPending = 0),
            now,
        )

        assertEquals(631, status.created.count)
        assertEquals(PipelineCardState.WARNING, status.created.state, "30 min oldest age must warn")
        assertEquals(0, status.inProgress.ackPending)
        assertEquals(3970, status.transport.duplicates, "(4601 + 0) - 631 = 3970")
    }

    // ── broker-down degradation ──────────────────────────────────────────────

    @Test
    fun `a null transport snapshot degrades the transport line to unreachable and zero everywhere, but keeps the DB stages`() {
        val status = JobPipelineStatusMapper.map(
            GenerationJobPipelineCounts(openCount = 4, completedLast24h = 9, failedCount = 1),
            null,
            now,
        )

        assertEquals(4, status.created.count, "job stages must still render from the DB")
        assertEquals(PipelineCardState.ERROR, status.created.state, "open jobs with no confirmable worker is an error")
        assertNull(status.inProgress.ackPending)
        assertFalse(status.inProgress.workerConnected)
        assertEquals(9, status.completed.last24h)
        assertEquals(1, status.failed.failedCount)
        assertEquals(0, status.failed.dlqMessages)

        assertFalse(status.transport.reachable)
        assertEquals(0, status.transport.queueDepth)
        assertEquals(0, status.transport.ackPending)
        assertEquals(0, status.transport.duplicates)
    }
}
