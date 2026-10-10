package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.system.MaintenanceDrainLease
import de.littletrotter.application.system.MaintenanceDrainLockLostException
import de.littletrotter.application.system.MaintenanceOperationOutcome
import de.littletrotter.application.system.MaintenanceExecutionFencedException
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.test.FIXED_TIME_SOURCE
import io.agroal.api.AgroalDataSource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException
import java.sql.Connection

/**
 * Metadata-only integration proof for the durable maintenance lease. No invoice or business
 * fixture is needed: queue ordering, fencing metadata, and recovery are all control-plane state.
 */
@QuarkusTest
@TestProfile(MaintenanceQueueIsolationProfile::class)
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(NatsTestResource::class, restrictToAnnotatedClass = true)
class PostgresMaintenanceQueueAdapterIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var queue: PostgresMaintenanceQueueAdapter

    @Inject
    lateinit var dataSource: AgroalDataSource

    @BeforeEach
    fun clearMetadata() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM maintenance_queue_execution").executeUpdate()
            em.createNativeQuery("DELETE FROM database_maintenance_event").executeUpdate()
        }
    }

    private fun insert(
        type: DatabaseMaintenanceType,
        status: DatabaseMaintenanceStatus,
        details: String,
        offsetSeconds: Long,
    ): Long = QuarkusTransaction.requiringNew().call {
        val event = DatabaseMaintenanceEvent(
            type = type,
            status = status,
            details = details,
            createdAt = java.time.OffsetDateTime.now().plusSeconds(offsetSeconds),
        )
        em.persist(event)
        em.flush()
        event.id!!
    }

    @Test
    fun `claims oldest request across maintenance types and completes it durably`() {
        val older = insert(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, DatabaseMaintenanceStatus.PENDING, "older", -2)
        insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.PENDING, "newer", 0)

        val result = queue.withExclusiveDrainLease {
            val claim = assertInstanceOf(MaintenanceDrainLease.ClaimResult.Claimed::class.java, claimNext())
            complete(claim.job, MaintenanceOperationOutcome.Succeeded("done"))
            claim.job.eventId
        }

        val claimedId = assertInstanceOf(
            de.littletrotter.application.system.MaintenanceQueuePort.LeaseAttempt.Acquired::class.java,
            result,
        ).result
        assertEquals(older, claimedId)
        val status = QuarkusTransaction.requiringNew().call {
            em.createQuery("select e.status from DatabaseMaintenanceEvent e where e.id = :id", DatabaseMaintenanceStatus::class.java)
                .setParameter("id", older)
                .singleResult
        }
        assertEquals(DatabaseMaintenanceStatus.SUCCESS, status)
        val ledgerRows = QuarkusTransaction.requiringNew().call<Long> {
            (em.createNativeQuery("select count(*) from maintenance_queue_execution where event_id = :id")
                .setParameter("id", older).singleResult as Number).toLong()
        }
        assertEquals(0L, ledgerRows)
    }

    @Test
    fun `a competing lease reports busy while the first session holds the lock`() {
        val firstEntered = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val first = Thread {
            queue.withExclusiveDrainLease {
                firstEntered.countDown()
                check(releaseFirst.await(15, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        first.start()
        try {
            assertTrue(firstEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val second = queue.withExclusiveDrainLease { "unexpected" }
            assertEquals(de.littletrotter.application.system.MaintenanceQueuePort.LeaseAttempt.Busy, second)
        } finally {
            releaseFirst.countDown()
            first.join(10_000)
        }
    }

    @Test
    fun `an earlier failed recalculation blocks generation until a later recalculation succeeds`() {
        val failed = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.FAILED, "failed", -3)
        val generation = insert(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, DatabaseMaintenanceStatus.PENDING, "generation", -2)

        queue.withExclusiveDrainLease {
            assertInstanceOf(MaintenanceDrainLease.ClaimResult.Blocked::class.java, claimNext())
        }
        val recovery = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.PENDING, "operator retry", 0)
        queue.withExclusiveDrainLease {
            val claim = assertInstanceOf(MaintenanceDrainLease.ClaimResult.Claimed::class.java, claimNext())
            assertEquals(recovery, claim.job.eventId)
            complete(claim.job, MaintenanceOperationOutcome.Succeeded("repaired"))
            val generationClaim = assertInstanceOf(MaintenanceDrainLease.ClaimResult.Claimed::class.java, claimNext())
            assertEquals(generation, generationClaim.job.eventId)
            complete(generationClaim.job, MaintenanceOperationOutcome.Succeeded("generated"))
        }
        val statuses = QuarkusTransaction.requiringNew().call {
            em.createQuery("select e.status from DatabaseMaintenanceEvent e where e.id in (:a, :b)", DatabaseMaintenanceStatus::class.java)
                .setParameter("a", failed).setParameter("b", generation).resultList
        }
        assertNotNull(statuses)
        assertTrue(statuses.contains(DatabaseMaintenanceStatus.SUCCESS))
    }

    @Test
    fun `recovery terminalizes unknown historical and versioned running rows`() {
        val historical = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.RUNNING, "historical", -2)
        val retryable = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.RUNNING, "retryable", -1)
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("insert into maintenance_queue_execution(event_id, execution_version, claim_token, claimed_at) values (:id, 1, nextval('maintenance_queue_claim_token_seq'), '2026-06-15T10:00:00Z')")
                .setParameter("id", retryable).executeUpdate()
        }

        queue.withExclusiveDrainLease {
            val result = recoverInterruptedClaims()
            assertEquals(1, result.terminalizedVersionedClaims)
            assertEquals(1, result.terminalizedHistoricalClaims)
        }
        val statuses = QuarkusTransaction.requiringNew().call {
            em.createQuery("select e.status from DatabaseMaintenanceEvent e where e.id in (:a, :b)", DatabaseMaintenanceStatus::class.java)
                .setParameter("a", historical).setParameter("b", retryable).resultList
        }
        assertTrue(statuses.contains(DatabaseMaintenanceStatus.UNKNOWN))
        assertEquals(2L, statuses.count { it == DatabaseMaintenanceStatus.UNKNOWN }.toLong())
    }

    @Test
    fun `a business exception from the block propagates unchanged and still releases the lock`() {
        val event = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.PENDING, "boom", 0)

        assertThrows<IllegalStateException> {
            queue.withExclusiveDrainLease { throw IllegalStateException("business failure") }
        }

        // The advisory lock was released despite the failure: a fresh lease can still be
        // acquired and still sees the untouched PENDING row.
        val result = queue.withExclusiveDrainLease {
            assertInstanceOf(MaintenanceDrainLease.ClaimResult.Claimed::class.java, claimNext()).job.eventId
        }
        val claimedId = assertInstanceOf(
            de.littletrotter.application.system.MaintenanceQueuePort.LeaseAttempt.Acquired::class.java,
            result,
        ).result
        assertEquals(event, claimedId)
    }

    @Test
    fun `boundary - claimNext never reclaims a row it already marked RUNNING within the same lease`() {
        val first = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.PENDING, "first", -2)
        val second = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.PENDING, "second", -1)

        queue.withExclusiveDrainLease {
            val firstClaim = assertInstanceOf(MaintenanceDrainLease.ClaimResult.Claimed::class.java, claimNext()).job
            assertEquals(first, firstClaim.eventId)

            val secondClaim = assertInstanceOf(MaintenanceDrainLease.ClaimResult.Claimed::class.java, claimNext()).job
            assertEquals(second, secondClaim.eventId, "the already-RUNNING first row must never be claimed again")

            assertInstanceOf(MaintenanceDrainLease.ClaimResult.Empty::class.java, claimNext())
        }
    }

    @Test
    fun `boundary - completing an already-completed claim is rejected instead of silently accepted`() {
        val event = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.PENDING, "double-complete", 0)

        val claim = assertInstanceOf(
            de.littletrotter.application.system.MaintenanceQueuePort.LeaseAttempt.Acquired::class.java,
            queue.withExclusiveDrainLease {
                val claimed = assertInstanceOf(MaintenanceDrainLease.ClaimResult.Claimed::class.java, claimNext()).job
                // A Failed first completion here also proves the FAILED branch of complete()'s own
                // status mapping -- every other completion in this file uses Succeeded.
                complete(claimed, MaintenanceOperationOutcome.Failed("first completion failed"))
                claimed
            },
        ).result as MaintenanceDrainLease.ClaimedMaintenanceJob

        assertThrows<IllegalStateException> {
            queue.withExclusiveDrainLease { complete(claim, MaintenanceOperationOutcome.Succeeded("second completion")) }
        }
    }

    @Test
    fun `boundary - verifying the fence before any claim has happened reports no active claim`() {
        queue.withExclusiveDrainLease {
            assertThrows<MaintenanceExecutionFencedException> { verifyInCurrentTransaction() }
        }
    }

    // ── -- verifyOwnership's own lock-lost branches (owned=false, and a genuine SQLException ──
    // ── reading pg_locks) can never be reached through this adapter's public API: only one session can ──
    // ── ever be inside withExclusiveDrainLease's block at a time (serialized by the advisory lock ──
    // ── itself), so nothing in-process can ever race verifyOwnership's own SELECT. Both are reached ──
    // ── here the same way a real lost lock would look from the JDBC layer: a second, genuinely ──
    // ── independent connection from the same pool that never acquired the lock (owned=false), and ──
    // ── that same connection closed before use (a real SQLException) -- never a mock, and never the ──
    // ── production code's own lease connection.

    @Test
    fun `boundary - verifying ownership on a connection that never held the lock reports it lost`() {
        val leaseClass = jdbcMaintenanceDrainLeaseClass()
        val constructor = leaseClass.getDeclaredConstructor(Connection::class.java, EntityManager::class.java, TimeSource::class.java)
            .apply { isAccessible = true }
        val verifyOwnership = leaseClass.getDeclaredMethod("verifyOwnership").apply { isAccessible = true }

        dataSource.connection.use { neverAcquired ->
            val lease = constructor.newInstance(neverAcquired, em, FIXED_TIME_SOURCE)
            val invocationFailure = assertThrows<InvocationTargetException> { verifyOwnership.invoke(lease) }
            val cause = assertInstanceOf(MaintenanceDrainLockLostException::class.java, invocationFailure.targetException)
            assertTrue(cause.message?.contains("no longer owned") == true, cause.message)
        }
    }

    @Test
    fun `boundary - verifying ownership on a closed connection wraps the SQLException as a lock-lost failure`() {
        val leaseClass = jdbcMaintenanceDrainLeaseClass()
        val constructor = leaseClass.getDeclaredConstructor(Connection::class.java, EntityManager::class.java, TimeSource::class.java)
            .apply { isAccessible = true }
        val verifyOwnership = leaseClass.getDeclaredMethod("verifyOwnership").apply { isAccessible = true }

        val closedConnection = dataSource.connection.also { it.close() }
        val lease = constructor.newInstance(closedConnection, em, FIXED_TIME_SOURCE)
        val invocationFailure = assertThrows<InvocationTargetException> { verifyOwnership.invoke(lease) }
        val cause = assertInstanceOf(MaintenanceDrainLockLostException::class.java, invocationFailure.targetException)
        assertInstanceOf(java.sql.SQLException::class.java, cause.cause)
        assertTrue(cause.message?.contains("Could not verify") == true, cause.message)
    }

    private fun jdbcMaintenanceDrainLeaseClass(): Class<*> =
        Class.forName("de.littletrotter.infrastructure.persistence.PostgresMaintenanceQueueAdapter\$JdbcMaintenanceDrainLease")

    // ── -- releaseLease's own failure path (advisory lock no longer owned at release time) ──
    // ── is reached through the exact same "a genuine second, independent connection that never ──
    // ── acquired the lock" technique the verifyOwnership tests above already use for the inner ──
    // ── lease class -- here against the OUTER adapter's own private release()/releaseLease(), never ──
    // ── the production lease connection itself (that one really does hold the lock and must stay ──
    // ── able to release it for every other test in this file).

    @Test
    fun `boundary - releaseLease with no block failure throws the lock-lost failure once release reports the lock already gone`() {
        val releaseLease = PostgresMaintenanceQueueAdapter::class.java
            .getDeclaredMethod("releaseLease", Connection::class.java, Throwable::class.java)
            .apply { isAccessible = true }

        dataSource.connection.use { neverAcquired ->
            val invocationFailure = assertThrows<InvocationTargetException> {
                releaseLease.invoke(queue, neverAcquired, null)
            }
            val cause = assertInstanceOf(MaintenanceDrainLockLostException::class.java, invocationFailure.targetException)
            assertTrue(cause.message?.contains("no longer owned at release") == true, cause.message)
        }
    }

    @Test
    fun `boundary - releaseLease suppresses the lock-lost failure onto an existing block failure instead of throwing a second one`() {
        val releaseLease = PostgresMaintenanceQueueAdapter::class.java
            .getDeclaredMethod("releaseLease", Connection::class.java, Throwable::class.java)
            .apply { isAccessible = true }
        val blockFailure = IllegalStateException("business failure that happened first")

        dataSource.connection.use { neverAcquired ->
            // Must not throw at all -- the release failure is recorded as suppressed on the
            // caller's own failure instead of escaping as a second, competing exception.
            releaseLease.invoke(queue, neverAcquired, blockFailure)
        }

        assertEquals(1, blockFailure.suppressed.size)
        assertInstanceOf(MaintenanceDrainLockLostException::class.java, blockFailure.suppressed[0])
    }

    @Test
    fun `boundary - tryAcquire reports false while a second real session already holds the advisory lock`() {
        val tryAcquire = PostgresMaintenanceQueueAdapter::class.java
            .getDeclaredMethod("tryAcquire", Connection::class.java)
            .apply { isAccessible = true }
        val holderEntered = java.util.concurrent.CountDownLatch(1)
        val releaseHolder = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            queue.withExclusiveDrainLease {
                holderEntered.countDown()
                check(releaseHolder.await(15, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        holder.start()
        try {
            assertTrue(holderEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            dataSource.connection.use { contender ->
                val acquired = tryAcquire.invoke(queue, contender) as Boolean
                assertTrue(!acquired, "a second session must never acquire the lock while the first holds it")
            }
        } finally {
            releaseHolder.countDown()
            holder.join(10_000)
        }
    }

    @Test
    fun `happy path - verifyInCurrentTransaction returns normally while the claim token still matches`() {
        val event = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.PENDING, "matching-token", 0)

        queue.withExclusiveDrainLease {
            val claim = assertInstanceOf(MaintenanceDrainLease.ClaimResult.Claimed::class.java, claimNext()).job
            assertEquals(event, claim.eventId)
            // Must not throw -- the ledger's claim_token still matches what claimNext() just wrote.
            verifyInCurrentTransaction()
        }
    }

    // -- verifyInCurrentTransaction's `(rows.firstOrNull() as? Number)?.toLong()`: every
    // other fence test in this file UPDATEs the ledger row's own claim_token (a mismatch against a
    // still-present row), never removes the row outright. A genuinely missing ledger row (e.g. an
    // operator manually clearing a stuck maintenance_queue_execution row, or recoverInterruptedClaims
    // running concurrently) makes `rows.firstOrNull()` itself null, a distinct arm from "wrong token".
    @Test
    fun `verifyInCurrentTransaction is fenced when its own ledger row is missing entirely, not just mismatched`() {
        val event = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.PENDING, "ledger-row-gone", 0)

        queue.withExclusiveDrainLease {
            val claim = assertInstanceOf(MaintenanceDrainLease.ClaimResult.Claimed::class.java, claimNext()).job
            assertEquals(event, claim.eventId)
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("DELETE FROM maintenance_queue_execution WHERE event_id = :id")
                    .setParameter("id", event).executeUpdate()
            }
            assertThrows<MaintenanceExecutionFencedException> {
                QuarkusTransaction.requiringNew().run { verifyInCurrentTransaction() }
            }
        }
    }

    @Test
    fun `a stale claim token is fenced before completion`() {
        val event = insert(DatabaseMaintenanceType.RECALCULATE_TOTALS, DatabaseMaintenanceStatus.PENDING, "fence", 0)

        queue.withExclusiveDrainLease {
            val claim = assertInstanceOf(MaintenanceDrainLease.ClaimResult.Claimed::class.java, claimNext()).job
            assertEquals(event, claim.eventId)
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("update maintenance_queue_execution set claim_token = claim_token + 1 where event_id = :id")
                    .setParameter("id", event).executeUpdate()
            }
            assertThrows<MaintenanceExecutionFencedException> {
                QuarkusTransaction.requiringNew().run { verifyInCurrentTransaction() }
            }
        }
    }
}

/** Only the explicitly provisioned PostgreSQL and NATS containers may serve this test. */
class MaintenanceQueueIsolationProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.scheduler.enabled" to "false",
        "auth.enabled" to "false",
        "mail.enabled" to "false",
        "invoice.storage.backend" to "filesystem",
        "invoice.s3.endpoint" to "http://127.0.0.1:1",
        "VALKEY_HOST" to "127.0.0.1",
        "VALKEY_PORT" to "1",
    )
}
