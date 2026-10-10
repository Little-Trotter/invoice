package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.infrastructure.messaging.MaintenanceWakeupNatsAdapter
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.reset
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Proves queue wake-up publication follows the durable transaction boundary. */
@QuarkusTest
@TestProfile(MaintenanceQueueIsolationProfile::class)
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(NatsTestResource::class, restrictToAnnotatedClass = true)
class MaintenanceCommitNotificationIT {

    @Inject
    lateinit var service: DatabaseMaintenanceService

    @Inject
    lateinit var em: EntityManager

    private val publisher = mock<MaintenanceWakeupNatsAdapter>()

    @BeforeEach
    fun clearPublisherCalls() {
        reset(publisher)
        QuarkusMock.installMockForType(publisher, MaintenanceWakeupNatsAdapter::class.java)
    }

    @Test
    fun `rolled back queue event does not publish a wake-up`() {
        assertThrows<IllegalStateException> {
            QuarkusTransaction.requiringNew().run {
                service.recordMaintenanceEvent(pendingEvent("rollback"))
                error("force rollback")
            }
        }

        verifyNoInteractions(publisher)
    }

    @Test
    fun `committed queue event publishes only after its row is visible in a fresh transaction`() {
        val published = CountDownLatch(1)
        val rowWasCommitted = AtomicBoolean(false)
        val callbackFailure = AtomicReference<Throwable?>()
        doAnswer {
            try {
                rowWasCommitted.set(QuarkusTransaction.requiringNew().call {
                    em.createQuery(
                        "select count(e) from DatabaseMaintenanceEvent e where e.details = :details",
                        Long::class.java,
                    ).setParameter("details", "commit-notification").singleResult > 0
                })
            } catch (failure: Throwable) {
                callbackFailure.set(failure)
            } finally {
                published.countDown()
            }
            null
        }.whenever(publisher).publish(org.mockito.kotlin.any())

        QuarkusTransaction.requiringNew().run {
            service.recordMaintenanceEvent(pendingEvent("commit-notification"))
        }

        check(published.await(10, TimeUnit.SECONDS)) { "AFTER_SUCCESS publication did not occur" }
        verify(publisher).publish(org.mockito.kotlin.any())
        check(callbackFailure.get() == null) { "fresh transaction failed: ${callbackFailure.get()}" }
        assertTrue(rowWasCommitted.get(), "publisher observed an uncommitted maintenance row")
    }

    private fun pendingEvent(details: String) = DatabaseMaintenanceEvent(
        type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
        status = DatabaseMaintenanceStatus.PENDING,
        details = details,
    )
}
