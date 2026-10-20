package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NatsUrlsTest {

    @Test
    fun `adds scheme to plain host-port values`() {
        assertEquals("nats://nats:4222", normalizeNatsServers("nats:4222"))
    }

    @Test
    fun `keeps existing nats scheme`() {
        assertEquals("nats://nats:4222", normalizeNatsServers("nats://nats:4222"))
    }

    @Test
    fun `normalizes multi server lists`() {
        assertEquals(
            "nats://nats:4222,nats://localhost:14222",
            normalizeNatsServers("nats://nats:4222, localhost:14222"),
        )
    }

    @Test
    fun `falls back to localhost when blank`() {
        assertEquals("nats://localhost:4222", normalizeNatsServers("   "))
    }
}
