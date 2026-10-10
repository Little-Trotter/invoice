package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
        val job = GenerationJob().apply { status = GenerationJobStatus.PENDING }

        job.reclaimAndTransitionTo(GenerationJobStatus.COMPLETED)

        assertEquals(GenerationJobStatus.COMPLETED, job.status)
    }

    @Test
    fun `reclaimAndTransitionTo bridges PENDING through DELEGATED to FAILED`() {
        val job = GenerationJob().apply { status = GenerationJobStatus.PENDING }

        job.reclaimAndTransitionTo(GenerationJobStatus.FAILED)

        assertEquals(GenerationJobStatus.FAILED, job.status)
    }

    @Test
    fun `reclaimAndTransitionTo behaves exactly like transitionTo for a job already DELEGATED`() {
        val job = GenerationJob().apply { status = GenerationJobStatus.DELEGATED }

        job.reclaimAndTransitionTo(GenerationJobStatus.COMPLETED)

        assertEquals(GenerationJobStatus.COMPLETED, job.status)
    }

    @Test
    fun `reclaimAndTransitionTo still rejects a genuinely illegal edge — it is not a blanket bypass`() {
        // COMPLETED is terminal: reclaimAndTransitionTo must not resurrect it just because the
        // PENDING branch above exists for a different, well-understood race.
        val job = GenerationJob().apply { status = GenerationJobStatus.COMPLETED }

        assertThrows<IllegalStateException> { job.reclaimAndTransitionTo(GenerationJobStatus.FAILED) }
        assertEquals(GenerationJobStatus.COMPLETED, job.status, "a rejected reclaim must not leave the job half-transitioned")
    }
}
