package de.littletrotter.infrastructure.persistence

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
 * Reproduces the "lost update on recipient addresses" finding (DB-04 violation) against
 * real PostgreSQL: two concurrent transactions load the SAME [de.littletrotter.domain.masterdata
 * .Recipient] (identical `version` captured in each JPA session), each then modifies a DIFFERENT
 * child [de.littletrotter.domain.masterdata.Address] row (billing vs. delivery — the exact shape
 * of the finding, not two edits of the same row) and force-increments the recipient before
 * committing. Before this fix, [PanacheRecipientRepository.save] never touched the
 * recipient's own version for a child-only change, so both transactions committed silently — this
 * test proves that with [PanacheRecipientRepository.forceVersionIncrement] wired in, exactly one
 * of the two now fails with an optimistic-lock conflict.
 *
 * Same structure as [de.littletrotter.domain.invoice.InvoiceOptimisticLockingIT], including the
 * cause-chain walk: that test's own KDoc documents Narayana/JTA wrapping the real cause
 * (`RollbackException -> PersistenceException -> OptimisticLockException`) on a losing commit.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class RecipientOptimisticLockingIT {

    @Inject
    lateinit var helper: RecipientOptimisticLockTestHelper

    @Test
    fun `concurrent edits of different address rows on the same recipient cause exactly one optimistic lock failure`() {
        val ids = helper.createRecipientWithTwoAddresses()

        val loadedLatch = CountDownLatch(2)
        val startSignal = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val f1 = executor.submit(Callable {
            runCatching {
                helper.loadModifyAddressAndSave(ids.recipientId, ids.billingAddressId, "Neue Rechnungsstraße 1", loadedLatch, startSignal)
            }.exceptionOrNull()
        })
        val f2 = executor.submit(Callable {
            runCatching {
                helper.loadModifyAddressAndSave(ids.recipientId, ids.deliveryAddressId, "Neue Lieferstraße 2", loadedLatch, startSignal)
            }.exceptionOrNull()
        })

        assertTrue(loadedLatch.await(10, TimeUnit.SECONDS), "Both threads must load the entity within timeout")
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

        val persistedVersion = helper.currentVersion(ids.recipientId)
        assertEquals(1L, persistedVersion, "the aggregate's own version must have moved for the surviving write")
    }

    /** See [de.littletrotter.domain.invoice.InvoiceOptimisticLockingIT.hasOptimisticLockInChain]. */
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
