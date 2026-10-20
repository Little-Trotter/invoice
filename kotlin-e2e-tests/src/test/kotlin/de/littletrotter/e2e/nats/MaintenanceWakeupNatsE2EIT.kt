package de.littletrotter.e2e.nats

import de.littletrotter.application.invoice.MaintenanceQueueProcessor
import de.littletrotter.infrastructure.messaging.MaintenanceWakeupNatsAdapter
import io.nats.client.Connection
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Proves the demo-load wake path on a real broker: the byte-exact hint that
 * `admin-tools/scripts/db/nudge-maintenance.py` publishes reaches the real
 * [MaintenanceWakeupNatsAdapter] subscription, which calls `requestDrain()` within seconds
 * (the 5-minute recovery poller is not involved). Subject and payload are literals pinned on the
 * tools side by `datagen/tests/test_nudge_maintenance_contract.py`; change both together.
 */
@ExtendWith(NatsE2EExtension::class)
class MaintenanceWakeupNatsE2EIT {

    private var adapter: MaintenanceWakeupNatsAdapter? = null

    @AfterEach
    fun cleanup() {
        adapter?.close()
    }

    private fun startAdapter(processor: MaintenanceQueueProcessor) {
        val connections = mock<Instance<Connection>>()
        whenever(connections.get()).thenReturn(NatsE2EExtension.connection)
        adapter = MaintenanceWakeupNatsAdapter(processor, connections, "default", true).also { it.subscribe() }
        NatsE2EExtension.connection.flush(java.time.Duration.ofSeconds(5))
    }

    @Test
    fun `the nudge-maintenance hint wakes the drain within seconds`() {
        val processor = mock<MaintenanceQueueProcessor>()
        startAdapter(processor)

        // Exactly what nudge-maintenance.py --instance default --request-id 42 puts on the wire.
        NatsE2EExtension.connection.publish("maintenance.v1.default.requested", """{"version":1,"requestId":42}""".toByteArray())

        val woke = NatsE2ESupport.waitUntil(10) { runCatching { verify(processor, atLeastOnce()).requestDrain() }.isSuccess }
        assertTrue(woke, "requestDrain() must be called within 10 s of the hint (recovery poller interval is 5 min)")
    }

    @Test
    fun `a hint for another instance does not wake this one`() {
        val processor = mock<MaintenanceQueueProcessor>()
        startAdapter(processor)

        NatsE2EExtension.connection.publish("maintenance.v1.other.requested", """{"version":1,"requestId":42}""".toByteArray())
        NatsE2EExtension.connection.publish("maintenance.v1.default.requested", """{"version":1,"requestId":43}""".toByteArray())

        assertTrue(
            NatsE2ESupport.waitUntil(10) { runCatching { verify(processor, atLeastOnce()).requestDrain() }.isSuccess },
            "the matching hint must still wake the drain",
        )
        verify(processor, org.mockito.kotlin.times(1)).requestDrain()
    }
}
