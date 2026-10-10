package de.littletrotter.e2e

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * S3-compatible object store (RustFS, the same pinned image the module ITs use) for E2E tests
 * requiring real document store round trips. Wires the S3-related Quarkus config properties for
 * both the xpdf side ([de.littletrotter.infrastructure.storage.S3DocumentStore], `s3.*`) and the
 * invoice side ([de.littletrotter.infrastructure.storage.DocumentStoreProducer], `invoice.s3.*`,
 * `invoice.storage.backend`) so neither falls back to the invoice module's filesystem default
 * and writes generated documents under the real `~/.little-trotter`.
 */
class S3TestResource : QuarkusTestResourceLifecycleManager {

    private val rustfs = GenericContainer("rustfs/rustfs:1.0.1")
        .withExposedPorts(9000)
        .withEnv("RUSTFS_ACCESS_KEY", "rustfsadmin")
        .withEnv("RUSTFS_SECRET_KEY", "rustfsadmin")
        .withCommand("/data")
        .waitingFor(Wait.forHttp("/health").forPort(9000).forStatusCode(200))

    override fun start(): MutableMap<String, String> {
        rustfs.start()
        val endpoint = "http://${rustfs.host}:${rustfs.getMappedPort(9000)}"
        val props = mutableMapOf(
            "s3.endpoint" to endpoint,
            "s3.access-key" to "rustfsadmin",
            "s3.secret-key" to "rustfsadmin",
            "s3.bucket" to "test-invoices",
            "invoice.storage.backend" to "s3",
            "invoice.s3.endpoint" to endpoint,
            "invoice.s3.access-key" to "rustfsadmin",
            "invoice.s3.secret-key" to "rustfsadmin",
            "invoice.s3.bucket" to "test-invoices",
        )
        // Same reason as PostgresTestResource/ValkeyTestResource: System properties carry
        // a strictly higher SmallRye Config ordinal than any other source in this cross-module
        // index-dependency boot path.
        props.forEach { (key, value) -> System.setProperty(key, value) }
        return props
    }

    override fun stop() {
        rustfs.stop()
    }
}
