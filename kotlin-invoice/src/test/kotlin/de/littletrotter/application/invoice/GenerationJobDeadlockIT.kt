package de.littletrotter.application.invoice

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.RepeatedTest
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Fix U — the lock-ordering deadlock the PO observed live on the demo stack
 * (`deadlock detected … while updating tuple … in relation "generation_job"`), reproduced
 * deterministically against a real Postgres ([PostgresTestResource]).
 *
 * Mechanism: the stale-DELEGATED reset used to be one bulk `UPDATE` over every stale DELEGATED row.
 * A bulk statement locks rows in scan order and keeps every lock it already holds while it
 * waits for the next one. Any other transaction that holds one `generation_job` row and then
 * touches a second one (a publish transaction joined by its caller, the demo re-generation
 * path, the maintenance sweep) closes the cycle. Here:
 *  - ten stale DELEGATED rows; a "holder" transaction locks the middle row and waits;
 *  - the sweep runs: before the fix it locks the rows before (or after, depending on scan
 *    direction) the middle one and then blocks on it while holding them;
 *  - the holder then updates the first and the last row — one of them is held by the
 *    blocked sweep, Postgres aborts one side with `40P01`.
 * With the fix the sweep selects `ORDER BY id FOR UPDATE SKIP LOCKED` and updates one row per
 * statement: it skips the held row, commits, and the holder's later updates only wait for that
 * short commit. Evidence is Postgres' own `pg_stat_database.deadlocks` counter plus the SQL
 * state of whatever either side throws — three repetitions, per the package's definition of done.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
// staleDelegatedResetCoordinator pulls in the real NatsDelegatedMessageProbe, which needs
// a live Connection; declared explicitly rather than relying on another test class in the same
// JVM run having already set nats.url first.
@QuarkusTestResource(NatsTestResource::class)
class GenerationJobDeadlockIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var staleDelegatedResetCoordinator: StaleDelegatedResetCoordinator

    companion object {
        private const val JOB_COUNT = 10
        private const val POSTGRES_DEADLOCK_SQLSTATE = "40P01"
        private val runCounter = AtomicInteger(0)
    }

    /**
     * Own invoice-number/prefix range (`PKGU-2026-09-18-…`/`PU…`) — disjoint from every other
     * suite's own fixtures in this module, per this project's own fixture-collision convention.
     * [run]/[idx] (not a timestamp salt) keep every generated value comfortably inside
     * [InvoiceNumber]'s `DAILY_PATTERN` and [SenderProfile.invoicePrefix]'s 10-char column limit
     * across all 3 [RepeatedTest] repetitions.
     */
    private fun persistStaleDelegatedJob(run: Int, idx: Int): Long = QuarkusTransaction.requiringNew().call<Long> {
        val fixtureCode = run * 1000 + idx
        val sender = aSenderProfile {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "PU$fixtureCode"
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-PKGU-$fixtureCode")
            name = "Kunde AG"
            email = Email("kunde@kunde-ag.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        val taxRate = TaxRate().edit {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "PKGU_TEST_19_$fixtureCode"
            category = "S"
            isSelectable = false
        }
        em.persist(sender)
        em.persist(recipient)
        em.persist(taxRate)
        val item = anInvoiceItem(
            positionId = "1",
            description = "Beratung",
            quantity = BigDecimal("1.00"),
            unitPriceNet = Money(BigDecimal("100.00")),
            taxRateRef = taxRate,
        )
        val invoice = anInvoice(
            invoiceNumber = InvoiceNumber("PKGU-2026-09-18-${fixtureCode.toString().padStart(5, '0')}"),
            invoiceDate = LocalDate.now(),
            senderProfile = sender,
            recipient = recipient,
            items = listOf(item),
            recalculate = true,
        )
        em.persist(invoice)
        // Inserted directly as an already stale DELEGATED row (never started, delegated in the
        // past, no delegatedStreamSeq recorded) so resetStaleDelegated(0) selects it as a
        // MISSING_SEQ candidate (reset outright, no NATS probe call needed); no
        // createJob()/real publish, so no fast path fires and there is no real stream message to
        // check the existence of anyway.
        val job = aGenerationJob {
            this.invoice = invoice
            this.jobType = "PDF_EXPORT"
            this.status = GenerationJobStatus.DELEGATED
            this.createdAt = OffsetDateTime.now().minusMinutes(10)
            this.delegatedAt = OffsetDateTime.now().minusMinutes(10)
        }
        em.persist(job)
        em.flush()
        job.id!!
    }

    private fun deadlockCountInDb(): Long = QuarkusTransaction.requiringNew().call<Long> {
        (em.createNativeQuery("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")
            .singleResult as Number).toLong()
    }

    private fun Throwable.isPostgresDeadlock(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is java.sql.SQLException && cause.sqlState == POSTGRES_DEADLOCK_SQLSTATE) return true
            cause = cause.cause
        }
        return false
    }

    private fun touch(id: Long) {
        em.createQuery("UPDATE GenerationJob j SET j.lastError = :sha WHERE j.id = :id")
            .setParameter("sha", "fix-u-$id")
            .setParameter("id", id)
            .executeUpdate()
    }

    private fun statusOf(id: Long): GenerationJobStatus = QuarkusTransaction.requiringNew().call<GenerationJobStatus> {
        em.find(GenerationJob::class.java, id).status
    }

    @RepeatedTest(3)
    fun `stale-delegated sweep never deadlocks against a transaction holding a job row`() {
        val run = runCounter.incrementAndGet()
        val ids = (1..JOB_COUNT).map { persistStaleDelegatedJob(run, it) }.sorted()
        val first = ids.first()
        val middle = ids[JOB_COUNT / 2]
        val last = ids.last()

        try {
            runSweepRace(first, middle, last)
        } finally {
            // The module's ITs share one Postgres: a PENDING row left behind would be picked up and
            // published by every later suite that runs the poller (GenerationJobFastPublishIT).
            retireFixtureJobs(ids)
        }
    }

    private fun retireFixtureJobs(ids: List<Long>) = QuarkusTransaction.requiringNew().run {
        em.createQuery("UPDATE GenerationJob j SET j.status = :failed, j.lastError = :reason WHERE j.id IN :ids")
            .setParameter("failed", GenerationJobStatus.FAILED)
            .setParameter("reason", "fix-u fixture retired")
            .setParameter("ids", ids)
            .executeUpdate()
    }

    private fun runSweepRace(first: Long, middle: Long, last: Long) {
        val deadlocksBefore = deadlockCountInDb()
        val holderHoldsMiddle = CountDownLatch(1)
        val sweepStarted = CountDownLatch(1)
        val holderError = AtomicReference<Throwable?>()
        val sweepError = AtomicReference<Throwable?>()
        val sweepResult = AtomicReference<Int?>()

        val holder = Thread {
            try {
                QuarkusTransaction.requiringNew().run {
                    touch(middle)
                    holderHoldsMiddle.countDown()
                    // Give the sweep time to lock what it can and block on the middle row
                    // (before the fix) or to finish (after the fix).
                    sweepStarted.await(10, TimeUnit.SECONDS)
                    Thread.sleep(1500)
                    touch(first)
                    touch(last)
                }
            } catch (ex: Throwable) {
                holderError.set(ex)
            }
        }
        val sweep = Thread {
            try {
                holderHoldsMiddle.await(10, TimeUnit.SECONDS)
                sweepStarted.countDown()
                sweepResult.set(staleDelegatedResetCoordinator.resetStale(0))
            } catch (ex: Throwable) {
                sweepError.set(ex)
            }
        }
        holder.start()
        sweep.start()
        holder.join(30_000)
        sweep.join(30_000)
        assertFalse(holder.isAlive || sweep.isAlive, "holder or sweep did not finish — lock wait without deadlock detection")

        val deadlocksAfter = deadlockCountInDb()
        listOf(holderError.get(), sweepError.get()).filterNotNull().forEach { ex ->
            assertFalse(ex.isPostgresDeadlock(), "Postgres reported a deadlock (40P01): $ex")
            throw AssertionError("unexpected failure during the sweep race", ex)
        }
        assertEquals(deadlocksBefore, deadlocksAfter, "pg_stat_database.deadlocks must not increase")

        // The sweep skipped the held row and reset every other stale row; a second pass picks
        // up the one it skipped once the holder has committed — nothing is lost, only deferred.
        assertEquals(JOB_COUNT - 1, sweepResult.get(), "the sweep resets every stale row it could lock")
        assertEquals(GenerationJobStatus.DELEGATED, statusOf(middle))
        assertEquals(GenerationJobStatus.PENDING, statusOf(first))
        assertEquals(1, staleDelegatedResetCoordinator.resetStale(0), "the skipped row is reset on the next pass")
        assertEquals(GenerationJobStatus.PENDING, statusOf(middle))
    }
}
