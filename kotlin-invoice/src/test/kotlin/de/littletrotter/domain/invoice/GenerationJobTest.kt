package de.littletrotter.domain.invoice

import de.littletrotter.fixture.aGenerationJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import de.littletrotter.fixture.anInvoice
import java.time.OffsetDateTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Date invariants (PO 2026-09-02) — [GenerationJob.createdAt] is the print/generation job's own
 * date invariant (invariant 3): the DB column (`V1__baseline_schema.sql`) is
 * `NOT NULL DEFAULT NOW()`, and the domain field itself is non-nullable. A domain class may not
 * read the clock itself (TimeSourceArchitectureTest), so a plain `GenerationJob()` no-arg
 * construction carries a fixed placeholder, never "now" — [de.littletrotter.application.invoice
 * .GenerationJobCreationService] is the one production creation path, and it always overwrites
 * this field explicitly from the injected TimeSource before the job is ever saved
 * ([de.littletrotter.application.invoice.GenerationJobCreationServiceTest] pins that write).
 */
class GenerationJobTest {

    @Test
    fun `a freshly constructed job always carries the same non-null placeholder createdAt`() {
        val first = GenerationJob()
        val second = GenerationJob()

        assertNotNull(first.createdAt)
        assertEquals(first.createdAt, second.createdAt, "the no-arg placeholder is fixed, not read from a clock")
    }

    // ── reclaimAndTransitionTo: the safety net for a late-but-genuine worker result
    // arriving for a job the app's own stale-DELEGATED reset already put back to PENDING. ────────

    @Test
    fun `reclaimAndTransitionTo bridges PENDING through DELEGATED to COMPLETED without touching the state machine's edge table`() {
        val job = aGenerationJob { status = GenerationJobStatus.PENDING }

        job.reclaimAndTransitionTo(GenerationJobStatus.COMPLETED)

        assertEquals(GenerationJobStatus.COMPLETED, job.status)
    }

    @Test
    fun `reclaimAndTransitionTo bridges PENDING through DELEGATED to FAILED`() {
        val job = aGenerationJob { status = GenerationJobStatus.PENDING }

        job.reclaimAndTransitionTo(GenerationJobStatus.FAILED)

        assertEquals(GenerationJobStatus.FAILED, job.status)
    }

    @Test
    fun `reclaimAndTransitionTo behaves exactly like transitionTo for a job already DELEGATED`() {
        val job = aGenerationJob { status = GenerationJobStatus.DELEGATED }

        job.reclaimAndTransitionTo(GenerationJobStatus.COMPLETED)

        assertEquals(GenerationJobStatus.COMPLETED, job.status)
    }

    @Test
    fun `reclaimAndTransitionTo still rejects a genuinely illegal edge — it is not a blanket bypass`() {
        // COMPLETED is terminal: reclaimAndTransitionTo must not resurrect it just because the
        // PENDING branch above exists for a different, well-understood race.
        val job = aGenerationJob { status = GenerationJobStatus.COMPLETED }

        assertThrows<IllegalStateException> { job.reclaimAndTransitionTo(GenerationJobStatus.FAILED) }
        assertEquals(GenerationJobStatus.COMPLETED, job.status, "a rejected reclaim must not leave the job half-transitioned")
    }

    private val now = OffsetDateTime.parse("2026-10-01T10:00:00+00:00")
    private val invoice = anInvoice()

    private fun pendingJob(): GenerationJob = GenerationJob.create(
        invoice,
        GenerationJobDetails(
            jobType = "PRINT_JOB",
            trigger = GenerationJobTrigger.MANUAL,
            lane = DocumentJobLane.BULK,
            llmOptimize = true,
            issued = true,
            correlationId = "corr-1",
        ),
        now,
    )

    @Test
    fun `create yields a PENDING job carrying all creation details`() {
        val job = pendingJob()

        assertSame(invoice, job.invoice)
        assertEquals("PRINT_JOB", job.jobType)
        assertEquals(GenerationJobStatus.PENDING, job.status)
        assertEquals(GenerationJobTrigger.MANUAL, job.trigger)
        assertEquals(DocumentJobLane.BULK, job.lane)
        assertTrue(job.llmOptimize)
        assertTrue(job.issued)
        assertEquals("corr-1", job.correlationId)
        assertEquals(now, job.createdAt)
        assertEquals(0, job.attempts)
    }

    @Test
    fun `messageCopy carries id invoice and type only`() {
        val copy = GenerationJob.messageCopy(42L, invoice, "PDF_EXPORT")

        assertEquals(42L, copy.id)
        assertSame(invoice, copy.invoice)
        assertEquals("PDF_EXPORT", copy.jobType)
        assertEquals(GenerationJobStatus.PENDING, copy.status)
    }

    @Test
    fun `markDelegated transitions and stamps the stale clock`() {
        val job = pendingJob()

        job.markDelegated(now)

        assertEquals(GenerationJobStatus.DELEGATED, job.status)
        assertEquals(now, job.delegatedAt)
    }

    @Test
    fun `markDelegated rejects an illegal edge`() {
        val job = pendingJob().also { it.reclaimAndTransitionTo(GenerationJobStatus.COMPLETED) }

        assertThrows<IllegalStateException> { job.markDelegated(now) }
        assertNull(job.delegatedAt)
    }

    @Test
    fun `recordDelegatedStreamSeq stores the sequence`() {
        val job = pendingJob().also { it.markDelegated(now) }

        job.recordDelegatedStreamSeq(77L)

        assertEquals(77L, job.delegatedStreamSeq)
    }

    @Test
    fun `markStarted stamps the start and moves to RUNNING`() {
        val job = pendingJob().also { it.markDelegated(now) }

        job.markStarted(now.plusMinutes(1))

        assertEquals(GenerationJobStatus.RUNNING, job.status)
        assertEquals(now.plusMinutes(1), job.startedAt)
    }

    @Test
    fun `resetStaleDelegated goes back to PENDING spends an attempt and clears queue evidence`() {
        val job = pendingJob().also {
            it.markDelegated(now)
            it.recordDelegatedStreamSeq(5L)
        }

        job.resetStaleDelegated()

        assertEquals(GenerationJobStatus.PENDING, job.status)
        assertEquals(1, job.attempts)
        assertNull(job.delegatedAt)
        assertNull(job.delegatedStreamSeq)
    }

    @Test
    fun `reopenExhausted restores a full attempt budget on a FAILED job`() {
        val job = pendingJob().also {
            it.markDelegated(now)
            it.resetStaleDelegated()
            it.fail("boom", now)
        }

        job.reopenExhausted()

        assertEquals(GenerationJobStatus.PENDING, job.status)
        assertEquals(0, job.attempts)
        assertNull(job.finishedAt)
    }

    @Test
    fun `fail stamps reason and finish time together with the FAILED transition`() {
        val job = pendingJob()

        job.fail("Attempt budget exhausted", now)

        assertEquals(GenerationJobStatus.FAILED, job.status)
        assertEquals("Attempt budget exhausted", job.lastError)
        assertEquals(now, job.finishedAt)
    }

    @Test
    fun `recordLastError leaves the status untouched`() {
        val job = pendingJob()

        job.recordLastError("DLQ")

        assertEquals("DLQ", job.lastError)
        assertEquals(GenerationJobStatus.PENDING, job.status)
    }

    @Test
    fun `recordResult stores outcome and keeps llmStatus when none is given`() {
        val job = pendingJob()
        job.recordResult("{\"a\":\"b\"}", "report", "PENDING", now)

        job.recordResult("{\"c\":\"d\"}", null, null, now.plusMinutes(2))

        assertEquals("{\"c\":\"d\"}", job.resultRefs)
        assertNull(job.kositReport)
        assertEquals("PENDING", job.llmStatus)
        assertEquals(now.plusMinutes(2), job.finishedAt)
    }

    @Test
    fun `updateResultRefs replaces refs and optionally the llmStatus without a transition`() {
        val job = pendingJob()

        job.updateResultRefs("{}")
        assertNull(job.llmStatus)
        job.updateResultRefs("{\"x\":\"y\"}", "COMPLETED")

        assertEquals("{\"x\":\"y\"}", job.resultRefs)
        assertEquals("COMPLETED", job.llmStatus)
        assertEquals(GenerationJobStatus.PENDING, job.status)
    }
}
