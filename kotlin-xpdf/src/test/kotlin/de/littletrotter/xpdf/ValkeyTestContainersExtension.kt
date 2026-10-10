package de.littletrotter.xpdf

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.GenericContainer

/**
 * AP-M6 — JUnit 5 extension that spins up a Valkey Testcontainer for `kotlin-xpdf` integration
 * tests that need a real Valkey connection, backing [PayloadExchange][de.littletrotter.shared.PayloadExchange]
 * (no CDI/Quarkus context required). Same pattern as kotlin-invoice's own
 * `de.littletrotter.test.ValkeyTestContainersExtension` — kept as a separate copy here rather
 * than shared, since kotlin-xpdf's test sources have no dependency on kotlin-invoice's.
 *
 * Usage: `@ExtendWith(ValkeyTestContainersExtension::class)`, then
 * `ValkeyTestContainersExtension.host()`/`.port()`.
 */
class ValkeyTestContainersExtension : BeforeAllCallback, AfterAllCallback {

    companion object {
        private var container: GenericContainer<*>? = null

        fun host(): String = container?.host ?: "localhost"
        fun port(): Int = container?.getMappedPort(6379) ?: 6379
    }

    override fun beforeAll(context: ExtensionContext) {
        if (container == null || !container!!.isRunning) {
            container = GenericContainer("valkey/valkey:9.1.1-alpine")
                .withExposedPorts(6379)
                // Disable AOF persistence -- not needed for tests
                .withCommand("valkey-server", "--save", "", "--appendonly", "no")
            container!!.start()
        }
    }

    override fun afterAll(context: ExtensionContext) {
        container?.stop()
        container = null
    }
}
