package de.littletrotter.application

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer

/**
 * Quarkus test resource that starts a Valkey Testcontainer and wires the
 * connection details into the Quarkus application properties.
 *
 * Usage:
 * ```kotlin
 * @QuarkusTest
 * @QuarkusTestResource(ValkeyTestResource::class)
 * class MyCachingIT { ... }
 * ```
 */
class ValkeyTestResource : QuarkusTestResourceLifecycleManager {

    // `--requirepass test` — same non-blank password the `%test.VALKEY_PASSWORD=test` default in
    // application.properties already establishes as this codebase's test-profile convention.
    // A literal "" here (as an earlier revision of this class returned) makes Quarkus's own
    // eager `@ConfigProperty` startup validation fail: an explicit-but-blank env var is spec-
    // treated as "not set" (MP Config), which falls through to the `defaultValue = ""` on
    // PayloadExchangeProducer/ValkeyInvoiceCacheClient — ALSO blank — and a non-Optional `String`
    // property that resolves to a final blank value fails Quarkus's mandatory-value check with a
    // ConfigurationException before the test container even boots.
    private val valkey = GenericContainer("valkey/valkey:9.1.1-alpine")
        .withExposedPorts(6379)
        .withCommand("valkey-server", "--save", "", "--appendonly", "no", "--requirepass", "test")

    override fun start(): MutableMap<String, String> {
        valkey.start()
        return mutableMapOf(
            "VALKEY_HOST"       to valkey.host,
            "VALKEY_PORT"       to valkey.getMappedPort(6379).toString(),
            "VALKEY_PASSWORD"   to "test",
            "VALKEY_INVOICE_DB" to "1",
            "VALKEY_QUERY_CACHE_DB" to "2",
        )
    }

    override fun stop() {
        valkey.stop()
    }
}
