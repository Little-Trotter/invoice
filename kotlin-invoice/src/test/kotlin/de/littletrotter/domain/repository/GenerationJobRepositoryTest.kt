package de.littletrotter.domain.repository

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import de.littletrotter.fixture.anInvoice

/**
 * COV — [GenerationJobRepository.revertToPending]/[GenerationJobRepository.failExhausted] carry
 * default bodies (compatibility fallbacks for in-memory test doubles without a real guarded
 * `UPDATE`, see their own KDoc on the interface) that
 * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository] always overrides
 * in production. Same idiom as [ArticleRepositoryDefaultsTest]/[SenderProfileRepositoryDefaultsTest] — new code
 * gets its own direct test instead of relying on incidental exercise through some service's fake.
 */
class GenerationJobRepositoryTest {

    private fun job(id: Long, status: GenerationJobStatus) = aGenerationJob { this.id = id; this.status = status }

    private fun jobForInvoice(
        id: Long,
        invoiceId: Long,
        status: GenerationJobStatus,
        issued: Boolean = false,
        trigger: GenerationJobTrigger = GenerationJobTrigger.MANUAL,
    ) =
        job(id, status).edit {
            invoice = anInvoice(id = invoiceId)
            this.issued = issued
            this.trigger = trigger
        }

    /** Minimal [GenerationJobRepository] implementing only [findById]/[save] — every other
     *  default-bodied method under test here runs the interface's own implementation. */
    private class Repo(vararg jobs: GenerationJob) : GenerationJobRepository {
        private val data = jobs.associateBy { it.id }.toMutableMap()
        val saved = mutableListOf<GenerationJob>()
        // N13/ (P4) — filters by GenerationJob.invoice, not hardcoded emptyList():
        // hasCompletedIssuedJob's default implementation (interface KDoc) delegates to exactly
        // this method, so its own unit tests below need it to behave like a real repository would.
        override fun findByInvoiceId(invoiceId: Long) = data.values.filter { it.invoice?.id == invoiceId }
        override fun findByInvoiceIdWithTaxRates(invoiceId: Long) = emptyList<GenerationJob>()
        override fun findPending() = emptyList<GenerationJob>()
        override fun findPendingWithInvoice() = emptyList<GenerationJob>()
        override fun findById(id: Long) = data[id]
        override fun save(job: GenerationJob): GenerationJob {
            saved += job
            data[job.id] = job
            return job
        }
        override fun tryMarkDelegated(id: Long) = false
    }

    // ── revertToPending ──────────────────────────────────────────────────────

    @Test
    fun `revertToPending liefert false wenn die Aufgabe nicht existiert`() {
        val repo = Repo()

        assertFalse(repo.revertToPending(99L))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `revertToPending liefert false wenn die Aufgabe nicht DELEGATED ist`() {
        val repo = Repo(job(1L, GenerationJobStatus.PENDING))

        assertFalse(repo.revertToPending(1L))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `revertToPending setzt eine DELEGATED-Aufgabe auf PENDING zurueck`() {
        val repo = Repo(job(1L, GenerationJobStatus.DELEGATED))

        assertTrue(repo.revertToPending(1L))
        assertEquals(GenerationJobStatus.PENDING, repo.findById(1L)?.status)
    }

    // ── recordDelegatedStreamSeq ─────────────────────────────────────────────────

    @Test
    fun `recordDelegatedStreamSeq returns false when the job does not exist`() {
        val repo = Repo()

        assertFalse(repo.recordDelegatedStreamSeq(99L, 42L))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `recordDelegatedStreamSeq returns false when the job is no longer DELEGATED`() {
        val repo = Repo(job(1L, GenerationJobStatus.PENDING))

        assertFalse(repo.recordDelegatedStreamSeq(1L, 42L))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `recordDelegatedStreamSeq records the sequence on a DELEGATED job`() {
        val repo = Repo(job(1L, GenerationJobStatus.DELEGATED))

        assertTrue(repo.recordDelegatedStreamSeq(1L, 42L))
        assertEquals(42L, repo.findById(1L)?.delegatedStreamSeq)
    }

    // ── failExhausted ────────────────────────────────────────────────────────

    @Test
    fun `failExhausted liefert false wenn die Aufgabe nicht existiert`() {
        val repo = Repo()

        assertFalse(repo.failExhausted(99L, "boom", OffsetDateTime.parse("2026-06-15T10:00:00Z")))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `failExhausted liefert false wenn die Aufgabe nicht PENDING ist`() {
        val repo = Repo(job(1L, GenerationJobStatus.DELEGATED))

        assertFalse(repo.failExhausted(1L, "boom", OffsetDateTime.parse("2026-06-15T10:00:00Z")))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `failExhausted markiert eine PENDING-Aufgabe als FAILED mit Fehlermeldung`() {
        val repo = Repo(job(1L, GenerationJobStatus.PENDING))

        assertTrue(repo.failExhausted(1L, "attempt budget exhausted", OffsetDateTime.parse("2026-06-15T10:00:00Z")))
        val updated = repo.findById(1L)!!
        assertEquals(GenerationJobStatus.FAILED, updated.status)
        assertEquals("attempt budget exhausted", updated.lastError)
    }

    // ── hasCompletedIssuedJob (N13/, P4) ─────────────────────────────────

    @Test
    fun `RS-1 - hasCompletedIssuedJob is false when the invoice has no jobs at all`() {
        val repo = Repo()

        assertFalse(repo.hasCompletedIssuedJob(42L))
    }

    @Test
    fun `RS-1 - hasCompletedIssuedJob is false when only non-completed jobs exist for the invoice`() {
        val repo = Repo(
            jobForInvoice(1L, 42L, GenerationJobStatus.PENDING, issued = true),
            jobForInvoice(2L, 42L, GenerationJobStatus.FAILED, issued = true),
        )

        assertFalse(repo.hasCompletedIssuedJob(42L))
    }

    @Test
    fun `RS-1 - hasCompletedIssuedJob is true once a COMPLETED issued job exists for the invoice`() {
        val repo = Repo(
            jobForInvoice(1L, 42L, GenerationJobStatus.FAILED, issued = true),
            jobForInvoice(2L, 42L, GenerationJobStatus.COMPLETED, issued = true),
        )

        assertTrue(repo.hasCompletedIssuedJob(42L))
    }

    @Test
    fun `RS-1 - hasCompletedIssuedJob ignores a COMPLETED job that belongs to a different invoice`() {
        val repo = Repo(jobForInvoice(1L, 99L, GenerationJobStatus.COMPLETED, issued = true))

        assertFalse(repo.hasCompletedIssuedJob(42L))
    }

    @Test
    fun `N13 - hasCompletedIssuedJob is false for a COMPLETED job whose issued flag is false -- a review-time draft job`() {
        val repo = Repo(jobForInvoice(1L, 42L, GenerationJobStatus.COMPLETED, issued = false))

        assertFalse(repo.hasCompletedIssuedJob(42L), "a completed draft job (issued=false) must never lock generation")
    }

    // ── hasNonTerminalTriggerJob ("Guard against concurrent/duplicate sends", PO 2026-09-13) ──

    @Test
    fun `hasNonTerminalTriggerJob is false when the invoice has no jobs at all`() {
        val repo = Repo()

        assertFalse(repo.hasNonTerminalTriggerJob(42L, GenerationJobTrigger.SEND))
    }

    @Test
    fun `hasNonTerminalTriggerJob is true for a PENDING SEND job`() {
        val repo = Repo(jobForInvoice(1L, 42L, GenerationJobStatus.PENDING, trigger = GenerationJobTrigger.SEND))

        assertTrue(repo.hasNonTerminalTriggerJob(42L, GenerationJobTrigger.SEND))
    }

    @Test
    fun `hasNonTerminalTriggerJob is true for a DELEGATED or RUNNING SEND job`() {
        assertTrue(Repo(jobForInvoice(1L, 42L, GenerationJobStatus.DELEGATED, trigger = GenerationJobTrigger.SEND)).hasNonTerminalTriggerJob(42L, GenerationJobTrigger.SEND))
        assertTrue(Repo(jobForInvoice(1L, 42L, GenerationJobStatus.RUNNING, trigger = GenerationJobTrigger.SEND)).hasNonTerminalTriggerJob(42L, GenerationJobTrigger.SEND))
    }

    @Test
    fun `hasNonTerminalTriggerJob is false once the SEND job is terminal (COMPLETED or FAILED)`() {
        assertFalse(Repo(jobForInvoice(1L, 42L, GenerationJobStatus.COMPLETED, trigger = GenerationJobTrigger.SEND)).hasNonTerminalTriggerJob(42L, GenerationJobTrigger.SEND))
        assertFalse(Repo(jobForInvoice(1L, 42L, GenerationJobStatus.FAILED, trigger = GenerationJobTrigger.SEND)).hasNonTerminalTriggerJob(42L, GenerationJobTrigger.SEND))
    }

    @Test
    fun `hasNonTerminalTriggerJob ignores a non-terminal job of a different trigger`() {
        val repo = Repo(jobForInvoice(1L, 42L, GenerationJobStatus.PENDING, trigger = GenerationJobTrigger.MANUAL))

        assertFalse(repo.hasNonTerminalTriggerJob(42L, GenerationJobTrigger.SEND))
    }

    @Test
    fun `hasNonTerminalTriggerJob ignores a non-terminal SEND job that belongs to a different invoice`() {
        val repo = Repo(jobForInvoice(1L, 99L, GenerationJobStatus.PENDING, trigger = GenerationJobTrigger.SEND))

        assertFalse(repo.hasNonTerminalTriggerJob(42L, GenerationJobTrigger.SEND))
    }

    // ── remaining default bodies ────────────────────────────────────

    @Test
    fun `happy path resetStaleDelegatedByIds resets every DELEGATED id, bumps attempts and clears the delegation stamps`() {
        val stale = job(1L, GenerationJobStatus.DELEGATED).edit {
            attempts = 2
            delegatedAt = OffsetDateTime.now().minusMinutes(10)
            delegatedStreamSeq = 7L
        }
        val repo = Repo(stale, job(2L, GenerationJobStatus.DELEGATED))

        assertEquals(2, repo.resetStaleDelegatedByIds(listOf(1L, 2L)))

        val reset = repo.findById(1L)!!
        assertEquals(GenerationJobStatus.PENDING, reset.status)
        assertEquals(3, reset.attempts)
        assertNull(reset.delegatedAt)
        assertNull(reset.delegatedStreamSeq)
        assertEquals(GenerationJobStatus.PENDING, repo.findById(2L)?.status)
    }

    @Test
    fun `boundary resetStaleDelegatedByIds skips unknown ids and jobs that already moved on`() {
        val repo = Repo(
            job(1L, GenerationJobStatus.RUNNING),
            job(2L, GenerationJobStatus.COMPLETED),
            job(3L, GenerationJobStatus.DELEGATED),
        )

        assertEquals(1, repo.resetStaleDelegatedByIds(listOf(99L, 1L, 2L, 3L)))
        assertEquals(GenerationJobStatus.RUNNING, repo.findById(1L)?.status)
        assertEquals(GenerationJobStatus.COMPLETED, repo.findById(2L)?.status)
        assertEquals(listOf(3L), repo.saved.map { it.id })
    }

    @Test
    fun `boundary resetStaleDelegatedByIds with no ids touches nothing`() {
        val repo = Repo(job(1L, GenerationJobStatus.DELEGATED))

        assertEquals(0, repo.resetStaleDelegatedByIds(emptyList()))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `happy path reopenExhausted reopens a FAILED job with a fresh attempt budget`() {
        val failed = job(1L, GenerationJobStatus.FAILED).edit {
            attempts = 5
            finishedAt = OffsetDateTime.now()
            lastError = "${GenerationJob.ATTEMPT_BUDGET_EXHAUSTED_REASON_PREFIX} after 5 attempts"
        }
        val repo = Repo(failed)

        assertTrue(repo.reopenExhausted(1L))

        val reopened = repo.findById(1L)!!
        assertEquals(GenerationJobStatus.PENDING, reopened.status)
        assertEquals(0, reopened.attempts)
        assertNull(reopened.finishedAt)
        assertNotNull(reopened.lastError, "the previous cycle's reason stays visible")
    }

    @Test
    fun `boundary reopenExhausted rejects unknown and non-FAILED jobs without saving`() {
        val repo = Repo(job(1L, GenerationJobStatus.COMPLETED), job(2L, GenerationJobStatus.PENDING))

        assertFalse(repo.reopenExhausted(99L))
        assertFalse(repo.reopenExhausted(1L))
        assertFalse(repo.reopenExhausted(2L))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `happy path failDlq fails a DELEGATED or RUNNING job with message and finish time`() {
        val repo = Repo(job(1L, GenerationJobStatus.DELEGATED), job(2L, GenerationJobStatus.RUNNING))

        assertTrue(repo.failDlq(1L, "dlq", OffsetDateTime.parse("2026-06-15T10:00:00Z")))
        assertTrue(repo.failDlq(2L, "dlq", OffsetDateTime.parse("2026-06-15T10:00:00Z")))

        listOf(1L, 2L).forEach { id ->
            val failed = repo.findById(id)!!
            assertEquals(GenerationJobStatus.FAILED, failed.status)
            assertEquals("dlq", failed.lastError)
            assertNotNull(failed.finishedAt)
        }
    }

    @Test
    fun `boundary failDlq refuses unknown, PENDING and terminal jobs`() {
        val repo = Repo(
            job(1L, GenerationJobStatus.PENDING),
            job(2L, GenerationJobStatus.COMPLETED),
            job(3L, GenerationJobStatus.FAILED),
        )

        assertFalse(repo.failDlq(99L, "dlq", OffsetDateTime.parse("2026-06-15T10:00:00Z")))
        assertFalse(repo.failDlq(1L, "dlq", OffsetDateTime.parse("2026-06-15T10:00:00Z")))
        assertFalse(repo.failDlq(2L, "dlq", OffsetDateTime.parse("2026-06-15T10:00:00Z")))
        assertFalse(repo.failDlq(3L, "dlq", OffsetDateTime.parse("2026-06-15T10:00:00Z")))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `happy path recordLastError writes the message without touching the status`() {
        val repo = Repo(job(1L, GenerationJobStatus.COMPLETED))

        assertTrue(repo.recordLastError(1L, "diagnostic"))

        assertEquals("diagnostic", repo.findById(1L)?.lastError)
        assertEquals(GenerationJobStatus.COMPLETED, repo.findById(1L)?.status)
    }

    @Test
    fun `boundary recordLastError returns false for an unknown job`() {
        val repo = Repo()

        assertFalse(repo.recordLastError(99L, "diagnostic"))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `boundary findByIdWithInvoice falls back to findById, null for an unknown id`() {
        val existing = job(1L, GenerationJobStatus.PENDING)
        val repo = Repo(existing)

        assertSame(existing, repo.findByIdWithInvoice(1L))
        assertNull(repo.findByIdWithInvoice(99L))
    }

    @Test
    fun `boundary read-model defaults report nothing to do for doubles that do not override them`() {
        val repo = Repo(job(1L, GenerationJobStatus.DELEGATED))
        val now = OffsetDateTime.now()

        assertTrue(repo.findStaleDelegatedCandidates().isEmpty())
        assertTrue(repo.findCompletedBetween(now.minusDays(1), now).isEmpty())
        assertTrue(repo.findExhaustedJobsWithoutDocument().isEmpty())
        assertTrue(repo.findPaged().isEmpty())
        assertEquals(0L, repo.countFiltered())
        assertTrue(repo.findDaySummaries().isEmpty())
        assertEquals(GenerationJobPipelineCounts(), repo.pipelineCounts(now.minusDays(1), now.minusMinutes(5)))
    }
}
