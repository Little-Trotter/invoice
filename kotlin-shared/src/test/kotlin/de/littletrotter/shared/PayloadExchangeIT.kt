package de.littletrotter.shared

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer

/**
 * AP-M6 — [PayloadExchange] is on the
 * `check-messaging-core` 100 % line+branch gate (kotlin-shared/pom.xml), so both the happy path
 * (a real Valkey round-trip) and the degrade-on-outage path (an unreachable host) are covered
 * here, same split as `de.littletrotter.infrastructure.cache.ValkeyInvoiceCacheClientIT`/`Test`
 * in kotlin-invoice.
 *
 * Runs as a failsafe integration test (not surefire): it starts a real Valkey Testcontainer,
 * which needs a Docker daemon that isn't available in the plain unit-test environment. The
 * `check-messaging-core` gate is skipped in that same environment (`-DskipITs`) and only
 * enforced where this IT actually runs — see kotlin-shared/pom.xml.
 */
class PayloadExchangeIT {

    companion object {
        private val container = GenericContainer("valkey/valkey:9.1.1-alpine")
            .withExposedPorts(6379)
            .withCommand("valkey-server", "--save", "", "--appendonly", "no")

        @JvmStatic
        @BeforeAll
        fun startContainer() {
            container.start()
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() {
            container.stop()
        }
    }

    private fun realExchange(database: Int = 0): PayloadExchange =
        PayloadExchange(container.host, container.getMappedPort(6379), "", database)

    // ── Happy path — a real Valkey round-trip ────────────────────────────────────────────

    @Test
    fun `put then get round-trips the stored value`() {
        val exchange = realExchange()
        exchange.put("lt:test:roundtrip", "hello valkey", 60L)

        assertEquals("hello valkey", exchange.get("lt:test:roundtrip"))
    }

    @Test
    fun `get on an unknown key returns null`() {
        val exchange = realExchange()

        assertNull(exchange.get("lt:test:never-written"))
    }

    @Test
    fun `a non-blank password still builds a working client config against an unauthenticated server`() {
        // The container never sets requirepass, so a client that unconditionally sends AUTH would
        // get an error reply on the very first real command -- this only proves the
        // password(...)-branch in the client-config builder is exercised, not that auth actually
        // succeeds against a real password-protected server (out of scope here; docker-compose
        // wiring is what carries the real VALKEY_PASSWORD).
        val exchange = PayloadExchange(container.host, container.getMappedPort(6379), "dummy-password", 0)

        exchange.close()
    }

    @Test
    fun `close does not throw`() {
        val exchange = realExchange()

        exchange.close()
    }

    @Test
    fun `distinct databases keep keys apart`() {
        val db1 = realExchange(database = 1)
        val db3 = realExchange(database = 3)
        db1.put("lt:test:db-isolation", "db1-value", 60L)

        assertEquals("db1-value", db1.get("lt:test:db-isolation"))
        assertNull(db3.get("lt:test:db-isolation"), "the same key in a different database must not be visible")
    }

    // ── Degrade-on-outage path ────────────────────────────────────────────────────────────

    @Test
    fun `put against an unreachable host is swallowed, never throws`() {
        val exchange = unreachableExchange()

        exchange.put("lt:test:unreachable", "value", 60L)
        // No assertion beyond "did not throw" -- the write is silently dropped by design.
    }

    @Test
    fun `get against an unreachable host returns null, never throws`() {
        val exchange = unreachableExchange()

        assertNull(exchange.get("lt:test:unreachable"))
    }

    /** `localhost` with nothing listening on the port -- connection refused fires immediately, no
     *  need to wait out a real timeout. */
    private fun unreachableExchange(): PayloadExchange = PayloadExchange("localhost", 1, "", 0)

    // ── sha256Hex ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `sha256Hex is deterministic and matches the known SHA-256 of an empty string`() {
        // Well-known constant: SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PayloadExchange.sha256Hex(""),
        )
    }

    @Test
    fun `sha256Hex produces the same hash for the same input and a different hash for different input`() {
        val a = PayloadExchange.sha256Hex("template-content-a")
        val b = PayloadExchange.sha256Hex("template-content-a")
        val c = PayloadExchange.sha256Hex("template-content-b")

        assertEquals(a, b)
        assert(a != c)
    }
}
