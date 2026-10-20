package de.littletrotter.application.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.RepeatedTest
import java.sql.SQLException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The audit-journal lock-ordering deadlock the 2026-09-18 stress night observed
 * (`deadlock detected` on `pg_advisory_xact_lock(1308)` taken by
 * `capture_invoice_audit_event()` during `invoice.create`/`invoice.addItem`), reproduced
 * deterministically against a real Postgres ([PostgresTestResource]) rather than relying on
 * ST-1's own probabilistic 100-thread run.
 *
 * Mechanism: `capture_invoice_audit_event()` takes the global `pg_advisory_xact_lock(1308)` from
 * inside the AFTER trigger — i.e. AFTER the statement that fired it already holds its own row
 * lock. Here an "external" transaction takes 1308 first (standing in for one journaled
 * transaction already mid-trigger for some other row), then, once the "app" thread is confirmed
 * waiting, updates the SAME invoice row [InvoiceLifecycleService.recalculate] is about to touch:
 *  - **before the fix**, `recalculate`'s own `UPDATE invoice` runs first (no pre-lock), takes the
 *    row, and only then the trigger's `PERFORM pg_advisory_xact_lock(1308)` blocks on the
 *    external transaction; the external transaction's own `UPDATE` on that same row then blocks
 *    on the app thread — a classic AB-BA cycle, Postgres aborts one side with `40P01`;
 *  - **after the fix**, `AuditGucInterceptor` acquires 1308 as the very first statement of the
 *    transaction, before `recalculate` touches any row, so the app thread blocks on 1308 while
 *    holding nothing; the external transaction's `UPDATE` then succeeds immediately and commits,
 *    releasing 1308, and the app thread proceeds without ever contending on the row — no cycle.
 * Evidence is Postgres' own `pg_stat_database.deadlocks` counter plus the SQL state of whatever
 * either side throws — three repetitions, per this package's definition of done.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AuditJournalLockOrderingDeadlockIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceLifecycleService: InvoiceLifecycleService

    companion object {
        private const val AUDIT_JOURNAL_LOCK_KEY = 1308L
        private const val POSTGRES_DEADLOCK_SQLSTATE = "40P01"
        private val runCounter = AtomicInteger(0)
    }

    /**
     * Own invoice-number/prefix range (`STA2-2026-09-18-…`/`STA2n`) — disjoint from every other
     * suite's own fixtures in this module, per this project's fixture-collision convention. [run]
     * (not a timestamp salt) keeps every generated value comfortably inside [InvoiceNumber]'s
     * `DAILY_PATTERN` and [SenderProfile.invoicePrefix]'s 10-char column limit across all 3
     * [RepeatedTest] repetitions.
     */
    private fun persistInvoice(run: Int): Long = QuarkusTransaction.requiringNew().call<Long> {
        val sender = aSenderProfile {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "STA2$run"
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-STA2-$run")
            name = "Kunde AG"
            email = Email("kunde@kunde-ag.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        em.persist(sender)
        em.persist(recipient)
        val invoice = anInvoice(
            invoiceNumber = InvoiceNumber("STA2-2026-09-18-${run.toString().padStart(5, '0')}"),
            invoiceDate = LocalDate.now(),
            senderProfile = sender,
            recipient = recipient,
        )
        em.persist(invoice)
        em.flush()
        invoice.id!!
    }

    private fun deadlockCountInDb(): Long = QuarkusTransaction.requiringNew().call<Long> {
        (
            em.createNativeQuery("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")
                .singleResult as Number
            ).toLong()
    }

    private fun Throwable.isPostgresDeadlock(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is SQLException && cause.sqlState == POSTGRES_DEADLOCK_SQLSTATE) return true
            cause = cause.cause
        }
        return false
    }

    @RepeatedTest(3)
    fun `a journaled write never deadlocks racing another transaction for the audit lock and the same row`() {
        val run = runCounter.incrementAndGet()
        val invoiceId = persistInvoice(run)

        val deadlocksBefore = deadlockCountInDb()
        val externalHolds1308 = CountDownLatch(1)
        val appStarted = CountDownLatch(1)
        val externalError = AtomicReference<Throwable?>()
        val appError = AtomicReference<Throwable?>()

        val external = Thread {
            try {
                QuarkusTransaction.requiringNew().run {
                    em.createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                        .setParameter("key", AUDIT_JOURNAL_LOCK_KEY)
                        .singleResult
                    externalHolds1308.countDown()
                    appStarted.await(10, TimeUnit.SECONDS)
                    // Give the app thread time to either (pre-fix) take the row and start
                    // waiting on 1308, or (post-fix) start waiting on 1308 before touching any
                    // row at all.
                    Thread.sleep(800)
                    em.createNativeQuery("UPDATE invoice SET free_text = :v WHERE id = :id")
                        .setParameter("v", "external-touch-$run")
                        .setParameter("id", invoiceId)
                        .executeUpdate()
                }
            } catch (ex: Throwable) {
                externalError.set(ex)
            }
        }
        val app = Thread {
            try {
                externalHolds1308.await(10, TimeUnit.SECONDS)
                appStarted.countDown()
                invoiceLifecycleService.recalculate(invoiceId)
            } catch (ex: Throwable) {
                appError.set(ex)
            }
        }
        external.start()
        app.start()
        external.join(30_000)
        app.join(30_000)
        assertFalse(
            external.isAlive || app.isAlive,
            "external or app thread did not finish — a lock wait without deadlock detection",
        )

        val deadlocksAfter = deadlockCountInDb()
        listOf(externalError.get(), appError.get()).filterNotNull().forEach { ex ->
            assertFalse(ex.isPostgresDeadlock(), "Postgres reported a deadlock (40P01): $ex")
            throw AssertionError("unexpected failure racing the audit lock against the invoice row", ex)
        }
        assertEquals(deadlocksBefore, deadlocksAfter, "pg_stat_database.deadlocks must not increase")
    }
}
