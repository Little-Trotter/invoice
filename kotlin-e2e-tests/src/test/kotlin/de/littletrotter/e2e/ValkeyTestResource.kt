package de.littletrotter.e2e

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer

/**
 * Bug production 2026-08-30 — the AP-M6 KoSIT-report-ref path
 * ([de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer.resolveKositReport])
 * resolves via a real [de.littletrotter.shared.PayloadExchange] (Valkey-backed); this module had
 * no Valkey [io.quarkus.test.common.QuarkusTestResource] of its own yet (only kotlin-invoice's own
 * test sources have one, and those are not on this module's classpath — no test-jar dependency).
 * Same container/config shape as kotlin-invoice's own `ValkeyTestResource`
 * (`de.littletrotter.application.ValkeyTestResource`, not visible here), stack this alongside
 * [PostgresTestResource] on any `@QuarkusTest` that needs a real KoSIT-report round trip through
 * [de.littletrotter.shared.PayloadExchange] rather than the graceful "Valkey unreachable" `null`
 * degrade path every other call site here already exercises.
 */
class ValkeyTestResource : QuarkusTestResourceLifecycleManager {

    // `--requirepass test` matches the `%test.VALKEY_PASSWORD=test` default already declared in
    // kotlin-invoice's own application.properties (on this module's classpath via
    // quarkus.index-dependency) — an explicit-but-blank password fails Quarkus's own mandatory-
    // value config check before the container even boots, same reasoning kotlin-invoice's
    // ValkeyTestResource documents.
    private val valkey = GenericContainer("valkey/valkey:9.1.1-alpine")
        .withExposedPorts(6379)
        .withCommand("valkey-server", "--save", "", "--appendonly", "no", "--requirepass", "test")

    override fun start(): MutableMap<String, String> {
        valkey.start()
        val props = mutableMapOf(
            "VALKEY_HOST" to valkey.host,
            "VALKEY_PORT" to valkey.getMappedPort(6379).toString(),
            "VALKEY_PASSWORD" to "test",
        )
        // Bug production 2026-08-30 — empirically, the returned map above did NOT reliably reach
        // PayloadExchangeProducer's @ConfigProperty-injected constructor in this cross-module
        // "index-dependency" boot path (same "second application.properties on the classpath"
        // ambiguity PostgresTestResource's own KDoc documents for the datasource URL): every read
        // through the injected PayloadExchange bean failed with "Failed to create socket." against
        // the literal default host "valkey", never this container's actual mapped port. System
        // properties carry a strictly higher SmallRye Config ordinal than any other source here,
        // so setting them directly — before Quarkus's config is assembled — removes the ambiguity
        // outright, the same fix already applied there.
        props.forEach { (key, value) -> System.setProperty(key, value) }
        return props
    }

    override fun stop() {
        valkey.stop()
    }
}
