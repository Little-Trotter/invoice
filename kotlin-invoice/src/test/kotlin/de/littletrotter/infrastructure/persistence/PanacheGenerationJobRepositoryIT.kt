package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Recipient
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import de.littletrotter.domain.value.CustomerNumber

/**
 * [PanacheGenerationJobRepository] against a real Testcontainers Postgres: the guarded
 * bulk `UPDATE` state transitions (rejected edges change nothing and never add a row), the
 * stale-DELEGATED and attempt-budget recovery reads/writes, read-only fetch hints, paging and the
 * pipeline aggregate, plus two committed-data races (two claimers of one job, `SKIP LOCKED`).
 *
 * The table is shared with other `@QuarkusTest` classes that commit rows, so every assertion is
 * scoped to this class's own fixture ids, own recipient or a before/after delta. Rolled-back
 * tests use `@TestTransaction`; the two race tests must commit and remove their own rows again.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheGenerationJobRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: PanacheGenerationJobRepository

    private val committedInvoiceIds = mutableListOf<Long>()

    private companion object {
        /** Daily-pattern invoice numbers; millisecond seed keeps committed race fixtures unique. */
        val invoiceSequence = AtomicLong(System.currentTimeMillis())
    }

    @AfterEach
    fun removeCommittedRows() {
        if (committedInvoiceIds.isEmpty()) return
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM generation_job WHERE invoice_id IN (:ids)")
                .setParameter("ids", committedInvoiceIds)
                .executeUpdate()
            em.createNativeQuery("DELETE FROM invoice WHERE id IN (:ids)")
                .setParameter("ids", committedInvoiceIds)
                .executeUpdate()
        }
        committedInvoiceIds.clear()
    }

    private fun invoice(recipient: Recipient? = null): Invoice {
        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("COV05-2031-01-01-${invoiceSequence.incrementAndGet()}")
            this.recipient = recipient
        }
        em.persist(invoice)
        return invoice
    }

    private fun recipient(): Recipient {
        val recipient = Recipient().apply {
            name = "COV05 Testkunde"
            customerNumber = CustomerNumber("COV05-${UUID.randomUUID().toString().take(8)}")
        }
        em.persist(recipient)
        return recipient
    }

    /** Groups [job]'s optional fixture fields — a parameter object instead of a ten-parameter
     *  function, since every field but [invoice] itself is optional and call sites only ever
     *  set the handful they care about for that test. */
    private data class JobParams(
        val status: GenerationJobStatus = GenerationJobStatus.PENDING,
        val createdAt: OffsetDateTime = OffsetDateTime.now(),
        val delegatedAt: OffsetDateTime? = null,
        val startedAt: OffsetDateTime? = null,
        val finishedAt: OffsetDateTime? = null,
        val lastError: String? = null,
        val engine: DocumentEngine = DocumentEngine.MUSTANG,
        val issued: Boolean = false,
        val trigger: GenerationJobTrigger = GenerationJobTrigger.MANUAL,
        val attempts: Int = 0,
        val lane: DocumentJobLane = DocumentJobLane.BULK,
    )

    private fun job(invoice: Invoice, params: JobParams = JobParams()): GenerationJob {
        val job = GenerationJob().apply {
            this.invoice = invoice
            this.jobType = "PDF_EXPORT"
            this.status = params.status
            this.createdAt = params.createdAt
            this.delegatedAt = params.delegatedAt
            this.startedAt = params.startedAt
            this.finishedAt = params.finishedAt
            this.lastError = params.lastError
            this.engine = params.engine
            this.issued = params.issued
            this.trigger = params.trigger
            this.attempts = params.attempts
            this.lane = params.lane
        }
        em.persist(job)
        return job
    }

    private fun flushAndClear() {
        em.flush()
        em.clear()
    }

    private fun reload(id: Long?): GenerationJob {
        em.clear()
        return em.find(GenerationJob::class.java, id)
    }

    private fun rowCount(invoiceId: Long?): Long =
        em.createQuery("SELECT COUNT(j) FROM GenerationJob j WHERE j.invoice.id = :id", Long::class.javaObjectType)
            .setParameter("id", invoiceId)
            .singleResult

    // ── state transitions (guarded bulk UPDATEs) ────────────────────────────

    @Test
    @TestTransaction
    fun `happy path a job walks PENDING-DELEGATED-PENDING-DELEGATED-FAILED in place via guarded updates`() {
        val invoice = invoice()
        val job = job(invoice)
        flushAndClear()

        assertTrue(repository.tryMarkDelegated(job.id!!))
        assertTrue(repository.recordDelegatedStreamSeq(job.id!!, 41L))
        reload(job.id).let {
            assertEquals(GenerationJobStatus.DELEGATED, it.status)
            assertNotNull(it.delegatedAt)
            assertEquals(41L, it.delegatedStreamSeq)
        }

        assertTrue(repository.revertToPending(job.id!!))
        reload(job.id).let {
            assertEquals(GenerationJobStatus.PENDING, it.status)
            assertNull(it.delegatedAt)
            assertNull(it.delegatedStreamSeq)
        }

        assertTrue(repository.tryMarkDelegated(job.id!!))
        assertTrue(repository.failDlq(job.id!!, "result processing gave up", OffsetDateTime.now()))
        reload(job.id).let {
            assertEquals(GenerationJobStatus.FAILED, it.status)
            assertEquals("result processing gave up", it.lastError)
            assertNotNull(it.finishedAt)
        }
        assertEquals(1L, rowCount(invoice.id), "every transition is an UPDATE of the same row, never a re-insert")
    }

    @Test
    @TestTransaction
    fun `boundary rejected state-machine edges leave the row untouched and add no row`() {
        val invoice = invoice()
        val pending = job(invoice, JobParams(status = GenerationJobStatus.PENDING))
        val delegated = job(invoice, JobParams(status = GenerationJobStatus.DELEGATED))
        val completed = job(invoice, JobParams(status = GenerationJobStatus.COMPLETED, finishedAt = OffsetDateTime.now()))
        flushAndClear()

        assertFalse(repository.tryMarkDelegated(delegated.id!!), "DELEGATED cannot be claimed again")
        assertFalse(repository.revertToPending(pending.id!!), "only DELEGATED reverts")
        assertFalse(repository.recordDelegatedStreamSeq(pending.id!!, 7L), "sequence only on DELEGATED")
        assertFalse(repository.failExhausted(delegated.id!!, "budget", OffsetDateTime.now()), "only PENDING exhausts")
        assertFalse(repository.failDlq(pending.id!!, "dlq", OffsetDateTime.now()), "never-delegated job is not DLQ-failed")
        assertFalse(repository.failDlq(completed.id!!, "dlq", OffsetDateTime.now()), "terminal job stays terminal")
        assertFalse(repository.reopenExhausted(completed.id!!), "only FAILED reopens")
        assertFalse(repository.tryMarkDelegated(Long.MAX_VALUE), "unknown id")
        assertFalse(repository.recordLastError(Long.MAX_VALUE, "diagnostic"), "unknown id")

        em.clear()
        assertEquals(GenerationJobStatus.PENDING, em.find(GenerationJob::class.java, pending.id).status)
        assertNull(em.find(GenerationJob::class.java, pending.id).delegatedStreamSeq)
        assertEquals(GenerationJobStatus.DELEGATED, em.find(GenerationJob::class.java, delegated.id).status)
        assertEquals(GenerationJobStatus.COMPLETED, em.find(GenerationJob::class.java, completed.id).status)
        assertNull(em.find(GenerationJob::class.java, completed.id).lastError)
        assertEquals(3L, rowCount(invoice.id))
    }

    @Test
    @TestTransaction
    fun `boundary attempt budget exhaustion and reopen keep the previous reason and reset the budget`() {
        val invoice = invoice()
        val job = job(invoice, JobParams(attempts = 5))
        flushAndClear()
        val reason = "${GenerationJob.ATTEMPT_BUDGET_EXHAUSTED_REASON_PREFIX} after 5 attempts"

        assertTrue(repository.failExhausted(job.id!!, reason, OffsetDateTime.now()))
        assertFalse(repository.failExhausted(job.id!!, reason, OffsetDateTime.now()), "second exhaustion is a no-op")
        assertTrue(repository.findExhaustedJobsWithoutDocument().any { it.id == job.id })

        assertTrue(repository.reopenExhausted(job.id!!))
        reload(job.id).let {
            assertEquals(GenerationJobStatus.PENDING, it.status)
            assertEquals(0, it.attempts)
            assertNull(it.finishedAt)
            assertEquals(reason, it.lastError)
        }
        assertTrue(repository.recordLastError(job.id!!, "diagnostic only"))
        assertEquals(GenerationJobStatus.PENDING, reload(job.id).status)
        assertEquals("diagnostic only", reload(job.id).lastError)
    }

    @Test
    @TestTransaction
    fun `boundary exhausted-job scan ignores other failure reasons and invoices that already have a document`() {
        val reason = "${GenerationJob.ATTEMPT_BUDGET_EXHAUSTED_REASON_PREFIX} after 3 attempts"
        val withoutDocument = job(invoice(), JobParams(status = GenerationJobStatus.FAILED, lastError = reason))
        val otherReason = job(invoice(), JobParams(status = GenerationJobStatus.FAILED, lastError = "KoSIT rejected"))
        val documented = invoice()
        val exhaustedButDocumented = job(documented, JobParams(status = GenerationJobStatus.FAILED, lastError = reason))
        job(documented, JobParams(status = GenerationJobStatus.COMPLETED, finishedAt = OffsetDateTime.now()))
        flushAndClear()

        val ids = repository.findExhaustedJobsWithoutDocument().map { it.id }

        assertTrue(withoutDocument.id in ids)
        assertFalse(otherReason.id in ids)
        assertFalse(exhaustedButDocumented.id in ids)
    }

    // ── stale-DELEGATED recovery after restart ──────────────────────────────

    @Test
    @TestTransaction
    fun `boundary stale-delegated scan picks only old, never-started DELEGATED rows and reset bumps attempts`() {
        val invoice = invoice()
        val now = OffsetDateTime.now()
        val old = now.minusMinutes(30)
        val stale = job(invoice, JobParams(status = GenerationJobStatus.DELEGATED, delegatedAt = old, attempts = 1))
        val fresh = job(invoice, JobParams(status = GenerationJobStatus.DELEGATED, delegatedAt = now))
        val started = job(invoice, JobParams(status = GenerationJobStatus.RUNNING, delegatedAt = old, startedAt = old.plusMinutes(1)))
        val pending = job(invoice)
        flushAndClear()
        assertTrue(repository.recordDelegatedStreamSeq(stale.id!!, 99L))

        val candidates = repository.findStaleDelegatedCandidates(2).associateBy { it.id }

        assertEquals(99L, candidates[stale.id]?.delegatedStreamSeq)
        assertFalse(fresh.id in candidates)
        assertFalse(started.id in candidates)
        assertFalse(pending.id in candidates)

        assertEquals(0, repository.resetStaleDelegatedByIds(emptyList()))
        assertEquals(1, repository.resetStaleDelegatedByIds(listOf(stale.id!!, pending.id!!, started.id!!)))
        reload(stale.id).let {
            assertEquals(GenerationJobStatus.PENDING, it.status)
            assertEquals(2, it.attempts)
            assertNull(it.delegatedAt)
            assertNull(it.delegatedStreamSeq)
        }
        assertEquals(GenerationJobStatus.RUNNING, reload(started.id).status)
        assertEquals(4L, rowCount(invoice.id))
    }

    // ── reads, read-only hints ──────────────────────────────────────────────

    @Test
    @TestTransaction
    fun `pending reads list interactive jobs first and keep creation order within a lane`() {
        val invoice = invoice()
        val base = OffsetDateTime.now().minusHours(1)
        val bulkOld = job(invoice, JobParams(createdAt = base))
        val bulkNew = job(invoice, JobParams(createdAt = base.plusMinutes(1)))
        val interactiveLate = job(invoice, JobParams(createdAt = base.plusMinutes(2), lane = DocumentJobLane.INTERACTIVE))
        val interactiveFirst = job(invoice, JobParams(createdAt = base.plusMinutes(1), lane = DocumentJobLane.INTERACTIVE))
        flushAndClear()

        val expected = listOf(interactiveFirst.id, interactiveLate.id, bulkOld.id, bulkNew.id)
        val ids = setOf(bulkOld.id, bulkNew.id, interactiveLate.id, interactiveFirst.id)
        assertEquals(expected, repository.findPending().map { it.id }.filter { it in ids })
        assertEquals(expected, repository.findPendingWithInvoice().map { it.id }.filter { it in ids })
        assertEquals(DocumentJobLane.INTERACTIVE, reload(interactiveFirst.id).lane)
        assertEquals(DocumentJobLane.BULK, reload(bulkOld.id).lane)
    }

    @Test
    @TestTransaction
    fun `happy path pending reads fetch the invoice and read-only results are never flushed back`() {
        val invoice = invoice()
        val pending = job(invoice)
        val delegated = job(invoice, JobParams(status = GenerationJobStatus.DELEGATED))
        flushAndClear()

        assertTrue(repository.findPending().any { it.id == pending.id })
        assertFalse(repository.findPending().any { it.id == delegated.id })
        val loaded = repository.findPendingWithInvoice().single { it.id == pending.id }
        assertEquals(invoice.id, loaded.invoice?.id)
        loaded.lastError = "must not be written by dirty checking"
        val byId = repository.findByIdWithInvoice(pending.id!!)!!
        byId.attempts = 9
        em.flush()

        reload(pending.id).let {
            assertNull(it.lastError)
            assertEquals(0, it.attempts)
        }
        assertNull(repository.findByIdWithInvoice(Long.MAX_VALUE))
        assertEquals(setOf(pending.id, delegated.id), repository.findByInvoiceId(invoice.id!!).map { it.id }.toSet())
        assertTrue(
            repository.findByInvoiceIdWithTaxRates(invoice.id!!).isEmpty(),
            "inner JOIN FETCH on items: an item-less invoice yields no rows",
        )
        assertEquals(pending.id, repository.findById(pending.id!!)?.id)
    }

    @Test
    @TestTransaction
    fun `boundary issued-lock, duplicate-send guard and engine recency count only matching rows`() {
        val invoice = invoice()
        job(invoice, JobParams(status = GenerationJobStatus.COMPLETED, issued = false, finishedAt = OffsetDateTime.now()))
        job(invoice, JobParams(status = GenerationJobStatus.COMPLETED, trigger = GenerationJobTrigger.SEND, finishedAt = OffsetDateTime.now()))
        val future = OffsetDateTime.now().plusYears(20)
        job(invoice(), JobParams(engine = DocumentEngine.FACTURX, createdAt = future))
        flushAndClear()

        assertFalse(repository.hasCompletedIssuedJob(invoice.id!!), "a completed draft job does not lock")
        assertFalse(
            repository.hasNonTerminalTriggerJob(invoice.id!!, GenerationJobTrigger.SEND),
            "terminal SEND does not block",
        )

        val issuedInvoice = invoice()
        job(issuedInvoice, JobParams(status = GenerationJobStatus.COMPLETED, issued = true, finishedAt = OffsetDateTime.now()))
        job(issuedInvoice, JobParams(status = GenerationJobStatus.RUNNING, trigger = GenerationJobTrigger.SEND))
        flushAndClear()

        assertTrue(repository.hasCompletedIssuedJob(issuedInvoice.id!!))
        assertTrue(repository.hasNonTerminalTriggerJob(issuedInvoice.id!!, GenerationJobTrigger.SEND))
        assertFalse(repository.hasNonTerminalTriggerJob(issuedInvoice.id!!, GenerationJobTrigger.MANUAL))
        assertTrue(repository.existsRecentByEngine(DocumentEngine.FACTURX, future.minusMinutes(1)))
        assertFalse(repository.existsRecentByEngine(DocumentEngine.FACTURX, future.plusYears(1)))
    }

    @Test
    @TestTransaction
    fun `boundary completed window is half-open`() {
        val finished = OffsetDateTime.of(2031, 3, 10, 12, 0, 0, 0, ZoneOffset.UTC)
        val job = job(invoice(), JobParams(status = GenerationJobStatus.COMPLETED, finishedAt = finished))
        flushAndClear()

        assertTrue(repository.findCompletedBetween(finished, finished.plusHours(1)).any { it.id == job.id })
        assertFalse(repository.findCompletedBetween(finished.minusHours(1), finished).any { it.id == job.id })
    }

    // ── pagination, day summaries, pipeline aggregate ───────────────────────

    @Test
    @TestTransaction
    fun `boundary paging splits a filtered set without overlap and count matches`() {
        val recipient = recipient()
        val invoice = invoice(recipient)
        val t0 = OffsetDateTime.of(2031, 3, 10, 8, 0, 0, 0, ZoneOffset.UTC)
        val oldest = job(invoice, JobParams(createdAt = t0, status = GenerationJobStatus.FAILED))
        val middle = job(invoice, JobParams(createdAt = t0.plusHours(1), status = GenerationJobStatus.COMPLETED))
        val newest = job(invoice, JobParams(createdAt = t0.plusHours(2), status = GenerationJobStatus.FAILED))
        flushAndClear()

        val first = repository.findPaged(recipientId = recipient.id, offset = 0, limit = 2)
        val second = repository.findPaged(recipientId = recipient.id, offset = 2, limit = 2)
        val beyond = repository.findPaged(recipientId = recipient.id, offset = 3, limit = 2)

        assertEquals(listOf(newest.id, middle.id), first.map { it.id })
        assertEquals(listOf(oldest.id), second.map { it.id })
        assertTrue(beyond.isEmpty())
        assertEquals(recipient.id, first.first().invoice?.recipient?.id)
        assertEquals(3L, repository.countFiltered(recipientId = recipient.id))
        assertEquals(
            2L,
            repository.countFiltered(
                statuses = setOf(GenerationJobStatus.FAILED),
                recipientId = recipient.id,
                createdFrom = t0,
                createdTo = t0.plusHours(2),
            ),
            "createdFrom/createdTo are inclusive",
        )
    }

    @Test
    @TestTransaction
    fun `boundary day summaries bucket by local day of the requested zone`() {
        val recipient = recipient()
        val invoice = invoice(recipient)
        // 22:30Z = 23:30 Berlin (10 March), 23:30Z = 00:30 Berlin (11 March)
        val lateEvening = OffsetDateTime.of(2031, 3, 10, 22, 30, 0, 0, ZoneOffset.UTC)
        job(invoice, JobParams(createdAt = lateEvening, status = GenerationJobStatus.FAILED))
        job(invoice, JobParams(createdAt = lateEvening.plusHours(1), status = GenerationJobStatus.COMPLETED))
        flushAndClear()

        val berlin = repository.findDaySummaries(recipientId = recipient.id, zone = ZoneId.of("Europe/Berlin"))
        val utc = repository.findDaySummaries(recipientId = recipient.id, zone = ZoneOffset.UTC)

        assertEquals(listOf(LocalDate.of(2031, 3, 11), LocalDate.of(2031, 3, 10)), berlin.map { it.day })
        assertEquals(listOf(0L, 1L), berlin.map { it.failedCount })
        assertEquals(1, utc.size)
        assertEquals(2L, utc.single().jobCount)
        assertEquals(1L, utc.single().failedCount)
    }

    @Test
    @TestTransaction
    fun `boundary pipeline counts over an empty table are zero, never null-unwrapped`() {
        // SUM(CASE ...) over zero rows is SQL NULL, not 0; deleted here (rolled back by
        // @TestTransaction, never committed) to prove the aggregate against a table with no rows
        // at all, the case that a lone/fresh-database run of this test previously hit as an NPE.
        em.createQuery("DELETE FROM GenerationJob").executeUpdate()
        flushAndClear()

        val counts = repository.pipelineCounts(OffsetDateTime.now().minusHours(24), OffsetDateTime.now().minusMinutes(5))

        assertEquals(0L, counts.openCount)
        assertNull(counts.oldestOpenCreatedAt)
        assertEquals(0L, counts.completedLast24h)
        assertEquals(0L, counts.completedLast5m)
        assertEquals(0L, counts.failedCount)
    }

    @Test
    @TestTransaction
    fun `happy path pipeline counts reflect open, recently completed and failed rows`() {
        val now = OffsetDateTime.now()
        val since24h = now.minusHours(24)
        val since5m = now.minusMinutes(5)
        val before = repository.pipelineCounts(since24h, since5m)
        val invoice = invoice()
        val ancient = OffsetDateTime.of(1999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        job(invoice, JobParams(createdAt = ancient))
        job(invoice, JobParams(status = GenerationJobStatus.RUNNING))
        job(invoice, JobParams(status = GenerationJobStatus.COMPLETED, finishedAt = now.minusHours(1)))
        job(invoice, JobParams(status = GenerationJobStatus.COMPLETED, finishedAt = now.minusMinutes(1)))
        job(invoice, JobParams(status = GenerationJobStatus.FAILED, finishedAt = now))
        flushAndClear()

        val after = repository.pipelineCounts(since24h, since5m)

        assertEquals(2L, after.openCount - before.openCount)
        assertEquals(2L, after.completedLast24h - before.completedLast24h)
        assertEquals(1L, after.completedLast5m - before.completedLast5m)
        assertEquals(1L, after.failedCount - before.failedCount)
        val oldestOpen = requireNotNull(after.oldestOpenCreatedAt) { "MIN over open rows must map to OffsetDateTime" }
        assertTrue(ancient.isEqual(oldestOpen), "oldest open row drives the age: $oldestOpen")
    }

    // ── committed races ─────────────────────────────────────────────────────

    private fun commitJobs(vararg statuses: GenerationJobStatus, delegatedAt: OffsetDateTime? = null): List<Long> =
        QuarkusTransaction.requiringNew().call<List<Long>> {
            val invoice = invoice()
            val ids = statuses.map { job(invoice, JobParams(status = it, delegatedAt = delegatedAt)).id!! }
            em.flush()
            committedInvoiceIds += invoice.id!!
            ids
        }

    @Test
    fun `boundary two concurrent claimers of the same job produce exactly one winner`() {
        val id = commitJobs(GenerationJobStatus.PENDING).single()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                pool.submit<Boolean> {
                    barrier.await(10, TimeUnit.SECONDS)
                    QuarkusTransaction.requiringNew().call<Boolean> { repository.tryMarkDelegated(id) }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it }, "exactly one claimer wins: $results")
        } finally {
            pool.shutdownNow()
        }
        QuarkusTransaction.requiringNew().run {
            val job = em.find(GenerationJob::class.java, id)
            assertEquals(GenerationJobStatus.DELEGATED, job.status)
            assertEquals(0, job.attempts)
        }
    }

    @Test
    fun `boundary stale reset skips a row another transaction holds locked`() {
        val (locked, free) = commitJobs(
            GenerationJobStatus.DELEGATED,
            GenerationJobStatus.DELEGATED,
            delegatedAt = OffsetDateTime.now().minusMinutes(30),
        )
        val lockHeld = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val holder = pool.submit(
                Runnable {
                    QuarkusTransaction.requiringNew().run {
                        em.find(GenerationJob::class.java, locked, LockModeType.PESSIMISTIC_WRITE)
                        lockHeld.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                },
            )
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS))

            val reset = QuarkusTransaction.requiringNew()
                .call<Int> { repository.resetStaleDelegatedByIds(listOf(locked, free)) }

            release.countDown()
            holder.get(30, TimeUnit.SECONDS)
            assertEquals(1, reset)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        QuarkusTransaction.requiringNew().run {
            assertEquals(GenerationJobStatus.DELEGATED, em.find(GenerationJob::class.java, locked).status)
            assertEquals(GenerationJobStatus.PENDING, em.find(GenerationJob::class.java, free).status)
            assertEquals(1, em.find(GenerationJob::class.java, free).attempts)
        }
    }
}
