package de.littletrotter.application.invoice

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
 * Reproduces the concurrent-edit lost update through the ACTUAL production
 * save path, not the repository's `forceVersionIncrement` called directly (that only proved the
 * mechanism works, not that the real save flow uses it — [de.littletrotter.infrastructure
 * .persistence.RecipientOptimisticLockingIT] stays for that, narrower, mechanical proof).
 *
 * [de.littletrotter.ui.view.masterdata.RecipientView.persistEntity] calls
 * [de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
 * .saveRecipientWithAddresses] on every save, unconditionally re-submitting the
 * billing street the form currently holds (see [de.littletrotter.ui.view.masterdata
 * .RecipientUpdateSaveIT]'s KDoc for the same "always resubmitted" note). Two sessions loading the
 * same recipient and both changing the billing street — the finding's literal wording — must
 * result in exactly one winner; the loser must fail loudly, never silently overwrite.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class RecipientAddressSaveConcurrencyIT {

    @Inject
    lateinit var helper: RecipientAddressSaveConcurrencyTestHelper

    @Test
    fun `two concurrent saves editing the same recipient's billing street cause exactly one optimistic lock failure`() {
        val recipientId = helper.createRecipientWithBillingAddress()

        val loadedLatch = CountDownLatch(2)
        val startSignal = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val f1 = executor.submit(Callable {
            runCatching {
                helper.loadEditBillingStreetAndSave(recipientId, "Session-A-Straße 1", loadedLatch, startSignal)
            }.exceptionOrNull()
        })
        val f2 = executor.submit(Callable {
            runCatching {
                helper.loadEditBillingStreetAndSave(recipientId, "Session-B-Straße 2", loadedLatch, startSignal)
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
        assertEquals(
            1,
            failures.size,
            "Exactly one of the two concurrent saves must fail; before the ST-A1 fix both silently " +
                "succeeded and the second write overwrote the first (the exact 'both saves acknowledged' " +
                "finding). Failures seen: $results",
        )

        val failure = failures.single()
        assertTrue(
            hasOptimisticLockInChain(failure),
            "Expected OptimisticLockException (or StaleObjectStateException) anywhere in cause chain, " +
                "but got: ${failure.javaClass.name}: ${failure.message}",
        )

        // The survivor's street must actually be persisted (the winning writer's value, not
        // silently lost, and not a blend of both).
        val (persistedStreet, persistedVersion) = helper.currentBillingStreetAndVersion(recipientId)
        assertTrue(
            persistedStreet == "Session-A-Straße 1" || persistedStreet == "Session-B-Straße 2",
            "the surviving write's street must be persisted exactly, got: $persistedStreet",
        )
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
