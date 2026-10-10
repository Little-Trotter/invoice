package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.invoice.MaintenanceQueueProcessor
import de.littletrotter.shared.MaintenanceWakeup
import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.Message
import io.nats.client.MessageHandler
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verifyNoInteractions

class MaintenanceWakeupNatsAdapterTest {
    @Test
    fun `subject and envelope are versioned and instance scoped`() {
        assertEquals("maintenance.v1.workstation.requested", MaintenanceWakeup.subject("workstation"))
        assertEquals(1, MaintenanceWakeup(1, 7).version)
    }

    @Test
    fun `invalid instance tokens and request ids are rejected`() {
        assertThrows<IllegalArgumentException> { MaintenanceWakeup.subject("bad.token") }
        assertThrows<IllegalArgumentException> { MaintenanceWakeup.subject("bad token") }
        assertThrows<IllegalArgumentException> { MaintenanceWakeup.subject("ä") }
        assertThrows<IllegalArgumentException> { MaintenanceWakeup.subject("*") }
        assertThrows<IllegalArgumentException> { MaintenanceWakeup(1, 0) }
    }

    @Test
    fun `disabled subscriber does not resolve the NATS connection`() {
        val connections = mock<Instance<Connection>>()
        val adapter = MaintenanceWakeupNatsAdapter(mock<MaintenanceQueueProcessor>(), connections, "default", false)

        adapter.subscribe()

        verifyNoInteractions(connections)
        assertDoesNotThrow { adapter.close() }
    }

    @Test
    fun `enabled subscriber closes only its dispatcher on shutdown`() {
        val connections = mock<Instance<Connection>>()
        val connection = mock<Connection>()
        val dispatcher = mock<Dispatcher>()
        whenever(connections.get()).thenReturn(connection)
        whenever(connection.createDispatcher(any())).thenReturn(dispatcher)
        val adapter = MaintenanceWakeupNatsAdapter(mock(), connections, "default", true)

        adapter.subscribe()
        adapter.close()

        verify(dispatcher).subscribe("maintenance.v1.default.requested")
        verify(connection).closeDispatcher(dispatcher)
        org.mockito.kotlin.verify(connection, org.mockito.kotlin.never()).close()
    }

    @Test
    fun `failed subscription still closes a dispatcher created before subscribe failed`() {
        val connections = mock<Instance<Connection>>()
        val connection = mock<Connection>()
        val dispatcher = mock<Dispatcher>()
        whenever(connections.get()).thenReturn(connection)
        whenever(connection.createDispatcher(any())).thenReturn(dispatcher)
        whenever(dispatcher.subscribe(any<String>())).thenThrow(IllegalStateException("broker unavailable"))
        val adapter = MaintenanceWakeupNatsAdapter(mock(), connections, "default", true)

        adapter.subscribe()
        adapter.close()

        verify(connection).closeDispatcher(dispatcher)
    }

    @Test
    fun `valid hint requests an asynchronous drain through the processor`() {
        val connections = mock<Instance<Connection>>()
        val connection = mock<Connection>()
        val dispatcher = mock<Dispatcher>()
        val processor = mock<MaintenanceQueueProcessor>()
        val handler = argumentCaptor<MessageHandler>()
        whenever(connections.get()).thenReturn(connection)
        whenever(connection.createDispatcher(handler.capture())).thenReturn(dispatcher)
        val adapter = MaintenanceWakeupNatsAdapter(processor, connections, "default", true)
        adapter.subscribe()

        val message = mock<Message>()
        whenever(message.data).thenReturn("{\"version\":1,\"requestId\":17}".toByteArray())
        handler.firstValue.onMessage(message)

        verify(processor).requestDrain()
    }

    @Test
    fun `invalid hints do not request a drain`() {
        val connections = mock<Instance<Connection>>()
        val connection = mock<Connection>()
        val dispatcher = mock<Dispatcher>()
        val processor = mock<MaintenanceQueueProcessor>()
        val handler = argumentCaptor<MessageHandler>()
        whenever(connections.get()).thenReturn(connection)
        whenever(connection.createDispatcher(handler.capture())).thenReturn(dispatcher)
        val adapter = MaintenanceWakeupNatsAdapter(processor, connections, "default", true)
        adapter.subscribe()
        listOf(
            "{}",
            "{\"version\":2,\"requestId\":17}",
            "{\"version\":1,\"requestId\":0}",
            "not-json",
        ).forEach { raw ->
            val message = mock<Message>()
            whenever(message.data).thenReturn(raw.toByteArray())
            handler.firstValue.onMessage(message)
        }

        org.mockito.kotlin.verifyNoInteractions(processor)
    }

    @Test
    fun `an unrelated runtime exception from the drain request is not silently eaten`() {
        val connections = mock<Instance<Connection>>()
        val connection = mock<Connection>()
        val dispatcher = mock<Dispatcher>()
        val processor = mock<MaintenanceQueueProcessor>()
        val handler = argumentCaptor<MessageHandler>()
        whenever(connections.get()).thenReturn(connection)
        whenever(connection.createDispatcher(handler.capture())).thenReturn(dispatcher)
        whenever(processor.requestDrain()).thenThrow(IllegalArgumentException("programming error"))
        val adapter = MaintenanceWakeupNatsAdapter(processor, connections, "default", true)
        adapter.subscribe()

        val message = mock<Message>()
        whenever(message.data).thenReturn("{\"version\":1,\"requestId\":17}".toByteArray())

        assertThrows<IllegalArgumentException> { handler.firstValue.onMessage(message) }
    }

    @Test
    fun `publish serializes versioned request on instance subject and contains broker failure`() {
        val connections = mock<Instance<Connection>>()
        val connection = mock<Connection>()
        whenever(connections.get()).thenReturn(connection)
        val adapter = MaintenanceWakeupNatsAdapter(mock(), connections, "worker_1", false)

        assertDoesNotThrow { adapter.publish(23L) }
        val payload = argumentCaptor<ByteArray>()
        verify(connection).publish(org.mockito.kotlin.eq("maintenance.v1.worker_1.requested"), payload.capture())
        val json = String(payload.firstValue)
        assertEquals(true, json.contains("\"version\":1"))
        assertEquals(true, json.contains("\"requestId\":23"))

        doThrow(IllegalStateException("offline")).whenever(connection).publish(any<String>(), any<ByteArray>())
        assertDoesNotThrow { adapter.publish(24L) }
        org.mockito.kotlin.verify(connection, org.mockito.kotlin.never()).close()
    }
}
