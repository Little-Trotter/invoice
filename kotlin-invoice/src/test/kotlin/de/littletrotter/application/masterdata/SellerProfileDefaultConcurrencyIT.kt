package de.littletrotter.application.masterdata

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.exception.DefaultSenderProfileAlreadySetException
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ST-1 invariant 1 finding: two concurrent `sender.create` calls raced on
 * `SellerProfileService.save`'s `ensureAtLeastOneDefault` — both read `findDefault() == null` and
 * both then tried to claim the default, racing on `uq_sender_profile_single_default`. Baseline
 * reproduction was a raw `ConstraintViolationException` from an `UPDATE sender_profile SET
 * is_default = ...`.
 *
 * An advisory lock spanning the whole `save()`/`setDefaultSeller` transaction was tried first and
 * reverted: `save()` nests a `REQUIRES_NEW` sub-transaction (the number-collision retry loop), so
 * the lock ended up held for the entire method body on one connection while every concurrent
 * writer queued up behind it holding ITS OWN connection — under ST-1's 100 threads this starved
 * the Agroal pool (`ContextNotActiveException`, JDBC "pool acquisition timeout" across unrelated
 * ops, 21s -> 137s runtime). See `ensureAtLeastOneDefault`'s own KDoc.
 *
 * The actual fix is `PanacheSenderProfileRepository.save` translating the partial unique index's
 * own violation into [DefaultSenderProfileAlreadySetException] — an `InvoiceDomainException`, so
 * ST-1's `isExpected` classifies it EXPECTED rather than an unexpected crash. Postgres itself
 * still guarantees at most one winner (the loser's conflicting `UPDATE`/`INSERT` blocks on the
 * pending index entry, then raises the violation once the winner commits) — no lock needed, and
 * no thread here can block longer than one ordinary transaction.
 *
 * Each test future runs with a bounded `get(timeout)` and captures its outcome via `runCatching`
 * (never lets an exception propagate out of the worker thread unbounded), so a regression back to
 * a real deadlock/hang fails the test loudly instead of hanging the whole build.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SellerProfileDefaultConcurrencyIT {

    @Inject
    lateinit var sellerService: SellerProfileService

    @Inject
    lateinit var addressManagementService: AddressManagementService

    @Inject
    lateinit var em: EntityManager

    private fun newCandidate(name: String, invoicePrefix: String): SenderProfile = SenderProfile().apply {
        this.name = name
        this.invoicePrefix = invoicePrefix
        email = Email("$invoicePrefix@example.invalid".lowercase())
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
    }

    private fun currentDefaultCount(): Long =
        (em.createNativeQuery("SELECT COUNT(*) FROM sender_profile WHERE is_default = TRUE").singleResult as Number).toLong()

    /** Acceptable failure outcomes: [DefaultSenderProfileAlreadySetException] (the translated
     *  unique-index race) or an [OptimisticLockException] (the winner revoked the loser's row's
     *  default flag, bumping its version — first committed writer wins, ADR-028). Anything else
     *  (in particular a raw `ConstraintViolationException`, or any timeout/hang) is the regression
     *  this test guards against. */
    private fun assertOnlyExpectedFailure(failures: List<Throwable>) {
        failures.forEach {
            assertTrue(
                it is DefaultSenderProfileAlreadySetException || it is OptimisticLockException,
                "only DefaultSenderProfileAlreadySetException/OptimisticLockException are acceptable race outcomes, got: ${it.javaClass.name}: ${it.message}",
            )
        }
    }

    @Test
    fun `two concurrent sender creations racing the bootstrap default both settle with exactly one winner`() {
        // Deterministic precondition (ensureAtLeastOneDefault only fires when NO default exists
        // anywhere): clear any default left over from other tests/seed data before the race, so
        // this test does not depend on suite execution order.
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE sender_profile SET is_default = FALSE WHERE is_default = TRUE").executeUpdate()
        }

        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val profiles = listOf(
                newCandidate("Nebenlaeufig Bootstrap A GmbH", "NBA"),
                newCandidate("Nebenlaeufig Bootstrap B GmbH", "NBB"),
            )
            val futures = profiles.map { profile ->
                pool.submit(
                    Callable {
                        ready.countDown()
                        go.await(5, TimeUnit.SECONDS)
                        runCatching {
                            QuarkusTransaction.requiringNew().call {
                                sellerService.save(profile, defaults = SellerDefaultSelections())
                            }
                        }
                    },
                )
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads must reach the start line within timeout")
            go.countDown()

            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            pool.shutdown()

            val successes = results.mapNotNull { it.getOrNull() }
            val failures = results.mapNotNull { it.exceptionOrNull() }
            assertOnlyExpectedFailure(failures)
            assertTrue(successes.isNotEmpty(), "at least one of the two concurrent creations must succeed")

            val defaultCount = QuarkusTransaction.requiringNew().call<Long> { currentDefaultCount() }
            assertEquals(1L, defaultCount, "exactly one sender profile may be the default once both attempts settle")

            val defaultAmongSuccesses = successes.count { saved ->
                QuarkusTransaction.requiringNew().call { sellerService.findById(saved.id!!) }?.isDefault == true
            }
            assertEquals(
                1, defaultAmongSuccesses,
                "the surviving default must be one of the profiles that actually got persisted, exactly once",
            )
        } finally {
            if (!pool.isShutdown) pool.shutdown()
        }
    }

    /**
     * Same race, the other entry point: [AddressManagementService.setDefaultSeller] does the
     * identical unguarded `findAll().filter { isDefault }` read, revoke, then
     * `markAsDefault`/save write — unlike the bootstrap case above, this one ALWAYS attempts to
     * claim the default regardless of current state, so two already-persisted, non-default
     * profiles both explicitly asked to become the default concurrently are guaranteed to collide.
     */
    @Test
    fun `two concurrent setDefaultSeller calls settle with exactly one winner`() {
        val idA = QuarkusTransaction.requiringNew().call {
            sellerService.save(newCandidate("Nebenlaeufig SetDefault A GmbH", "NSA"), defaults = SellerDefaultSelections())
        }.id!!
        val idB = QuarkusTransaction.requiringNew().call {
            sellerService.save(newCandidate("Nebenlaeufig SetDefault B GmbH", "NSB"), defaults = SellerDefaultSelections())
        }.id!!

        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(idA, idB).map { id ->
                pool.submit(
                    Callable {
                        ready.countDown()
                        go.await(5, TimeUnit.SECONDS)
                        runCatching {
                            QuarkusTransaction.requiringNew().call {
                                addressManagementService.setDefaultSeller(sellerService.findById(id)!!)
                            }
                        }
                    },
                )
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads must reach the start line within timeout")
            go.countDown()

            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            pool.shutdown()

            assertOnlyExpectedFailure(results.mapNotNull { it.exceptionOrNull() })

            val defaultCount = QuarkusTransaction.requiringNew().call<Long> { currentDefaultCount() }
            assertEquals(1L, defaultCount, "exactly one sender profile may be the default after both concurrent setDefaultSeller calls settle")

            val reloadedA = QuarkusTransaction.requiringNew().call { sellerService.findById(idA) }
            val reloadedB = QuarkusTransaction.requiringNew().call { sellerService.findById(idB) }
            assertTrue(
                (reloadedA?.isDefault == true) != (reloadedB?.isDefault == true),
                "exactly one of the two profiles must be the surviving default, not both/neither: " +
                    "A.isDefault=${reloadedA?.isDefault}, B.isDefault=${reloadedB?.isDefault}",
            )
        } finally {
            if (!pool.isShutdown) pool.shutdown()
        }
    }
}
