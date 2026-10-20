package de.littletrotter.test

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.GenericContainer

/**
 * JUnit 5 extension that spins up a Valkey Testcontainer for integration tests
 * that need a real Valkey connection (no CDI / Quarkus context required).
 *
 * Usage:
 * ```kotlin
 * @ExtendWith(ValkeyTestContainersExtension::class)
 * class MyValkeyIT {
 *     // use ValkeyTestContainersExtension.host() / port()
 * }
 * ```
 *
 * Sets system properties:
 *  - `valkey.test.host`
 *  - `valkey.test.port`
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
                // Disable AOF persistence – not needed for tests
                .withCommand("valkey-server", "--save", "", "--appendonly", "no")
            container!!.start()
        }
        System.setProperty("valkey.test.host", host())
        System.setProperty("valkey.test.port", port().toString())
    }

    override fun afterAll(context: ExtensionContext) {
        container?.stop()
        container = null
    }
}
