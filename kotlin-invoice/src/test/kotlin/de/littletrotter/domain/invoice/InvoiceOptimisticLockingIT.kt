package de.littletrotter.domain.invoice

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Integration test (failsafe: Postgres + NATS test resources, repo convention for a
 * Postgres-backed [QuarkusTest]) that reproduces the ARJUNA016053 / optimistic-locking scenario observed in
 * production:
 *
 * Two concurrent requests both load the same [Invoice] (identical `version` value captured in
 * each JPA session).  The first transaction to commit succeeds and bumps `version` by 1.  The
 * second transaction then tries to flush its stale entity — Hibernate issues an UPDATE with the
 * original version in the WHERE clause and finds 0 rows updated, which triggers an
 * [jakarta.persistence.OptimisticLockException].  Under Narayana/Arjuna this surfaces as the
 * ARJUNA016053 log entry ("Could not commit transaction") and the JTA transaction is rolled back.
 *
 * The test uses [InvoiceOptimisticLockTestHelper] — a top-level [jakarta.enterprise.context.ApplicationScoped]
 * bean — so that CDI manages the bean lifecycle correctly.  Non-static inner classes cannot be
 * CDI beans (they lack an accessible no-arg constructor visible to the container).
 *
 * Both concurrent threads attempt the same valid transition (DRAFT → IN_REVIEW) so the domain
 * invariant is not violated before the optimistic-lock check fires.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class InvoiceOptimisticLockingIT {

    @Inject
    lateinit var helper: InvoiceOptimisticLockTestHelper

    @Test
    fun `concurrent DRAFT to IN_REVIEW transitions on the same invoice cause exactly one optimistic lock failure`() {
        val invoiceId = helper.createDraftInvoice()

        val loadedLatch = CountDownLatch(2)
        val startSignal = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        // Both threads attempt the same valid transition so the domain guard doesn't reject either;
        // the loser is determined solely by the DB-level version check.
        val f1 = executor.submit(Callable {
            runCatching {
                helper.loadAndTransition(invoiceId, InvoiceStatus.IN_REVIEW, loadedLatch, startSignal)
            }.exceptionOrNull()
        })
        val f2 = executor.submit(Callable {
            runCatching {
                helper.loadAndTransition(invoiceId, InvoiceStatus.IN_REVIEW, loadedLatch, startSignal)
            }.exceptionOrNull()
        })

        // Wait until both transactions have loaded the entity (version is now fixed in each tx)
        assertTrue(loadedLatch.await(10, TimeUnit.SECONDS), "Both threads must load the entity within timeout")
        // Release both threads to commit at roughly the same time
        startSignal.countDown()

        val results = listOf(
            f1.get(15, TimeUnit.SECONDS),
            f2.get(15, TimeUnit.SECONDS),
        )
        executor.shutdown()

        val failures = results.filterNotNull()
        assertEquals(1, failures.size, "Exactly one transaction must fail; the other must succeed")

        val failure = failures.single()
        assertTrue(
            hasOptimisticLockInChain(failure),
            "Expected OptimisticLockException (or StaleObjectStateException) anywhere in cause chain, " +
                "but got: ${failure.javaClass.name}: ${failure.message}",
        )
    }

    /**
     * Walks the full [Throwable.cause] chain looking for any exception that indicates a stale
     * version / optimistic-lock conflict.
     *
     * Under Narayana/Arjuna (the JTA provider in Quarkus) the exception is typically wrapped:
     * `RollbackException → PersistenceException → OptimisticLockException`
     * or
     * `RollbackException → PersistenceException → StaleObjectStateException`
     *
     * Checking only `e is OptimisticLockException || e.cause is OptimisticLockException` (the
     * original, single-level check) would miss these wrappings and produce false negatives.
     */
    private fun hasOptimisticLockInChain(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is jakarta.persistence.OptimisticLockException ||
                cause is org.hibernate.StaleObjectStateException
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}
