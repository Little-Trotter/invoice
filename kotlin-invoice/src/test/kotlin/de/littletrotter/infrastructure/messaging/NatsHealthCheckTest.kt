package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.system.port.outbound.NatsStatusService
import org.eclipse.microprofile.health.HealthCheckResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.dto.NatsStatus

/**
 * AP-M10 — no test existed for this class
 * at all before this AP.
 */
class NatsHealthCheckTest {

    private fun status(
        brokerUp: Boolean,
        error: String? = null,
        dlqMessages: Long = 0,
    ) = NatsStatus(
        brokerUp = brokerUp,
        serverUrl = "nats://nats:4222",
        sentMessages = 42,
        totalLag = 0,
        subjects = emptyList(),
        dlqMessages = dlqMessages,
        error = error,
    )

    @Test
    fun `reports UP with no error data when the broker is reachable and error is null`() {
        val service: NatsStatusService = mock()
        whenever(service.currentStatus()).thenReturn(status(brokerUp = true, error = null))

        val response = NatsHealthCheck(service).call()

        assertEquals(HealthCheckResponse.Status.UP, response.status)
        assertEquals(false, response.data.get().containsKey("error"))
        assertEquals("nats://nats:4222", response.data.get()["serverUrl"])
        assertEquals(42L, response.data.get()["sentMessages"])
        assertEquals(0L, response.data.get()["dlqMessages"])
    }

    @Test
    fun `reports UP even when the DLQ is non-empty — a filled DLQ is a warning, not a readiness failure`() {
        val service: NatsStatusService = mock()
        whenever(service.currentStatus()).thenReturn(status(brokerUp = true, error = null, dlqMessages = 7))

        val response = NatsHealthCheck(service).call()

        assertEquals(HealthCheckResponse.Status.UP, response.status)
        assertEquals(7L, response.data.get()["dlqMessages"])
    }

    @Test
    fun `reports UP with no error data when error is a blank string`() {
        val service: NatsStatusService = mock()
        whenever(service.currentStatus()).thenReturn(status(brokerUp = true, error = "   "))

        val response = NatsHealthCheck(service).call()

        assertEquals(HealthCheckResponse.Status.UP, response.status)
        assertEquals(false, response.data.get().containsKey("error"), "a blank error must not be surfaced as data")
    }

    @Test
    fun `reports DOWN with the error message when the broker is unreachable`() {
        val service: NatsStatusService = mock()
        whenever(service.currentStatus()).thenReturn(status(brokerUp = false, error = "connection refused"))

        val response = NatsHealthCheck(service).call()

        assertEquals(HealthCheckResponse.Status.DOWN, response.status)
        assertEquals("connection refused", response.data.get()["error"])
    }
}
