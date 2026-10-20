package de.littletrotter.application

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * A real RustFS (S3-compatible) backend for `@QuarkusTest` ITs that need
 * [de.littletrotter.ui.documents.DocumentDownloadResource] to actually stream bytes through the
 * production [de.littletrotter.infrastructure.storage.S3DocumentStore] (via
 * [de.littletrotter.infrastructure.storage.DocumentStoreProducer]'s `s3` backend), unlike
 * [TempDocumentStoreResource]'s local-filesystem fake. Same image/credentials pin as
 * [de.littletrotter.test.CombinedTestContainersExtension] (rustfs/rustfs:1.0.1,
 * rustfs/rustfs123 — never `minio/minio`, see this project's own "no MinIO" decision), so both
 * paths never drift against two different fake S3 backends.
 *
 * Usage:
 * ```kotlin
 * @QuarkusTest
 * @QuarkusTestResource(PostgresTestResource::class)
 * @QuarkusTestResource(RustFsTestResource::class)
 * class MyRealDocumentStoreIT { ... }
 * ```
 */
class RustFsTestResource : QuarkusTestResourceLifecycleManager {

    private val rustfs = GenericContainer(DockerImageName.parse("rustfs/rustfs:1.0.1")).apply {
        withExposedPorts(9000)
        withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
        withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
        withCommand("/data")
        waitingFor(Wait.forHttp("/health").forStatusCode(200))
    }

    override fun start(): MutableMap<String, String> {
        rustfs.start()
        return mutableMapOf(
            "invoice.storage.backend" to "s3",
            "invoice.s3.endpoint" to "http://${rustfs.host}:${rustfs.getMappedPort(9000)}",
            "invoice.s3.access-key" to ACCESS_KEY,
            "invoice.s3.secret-key" to SECRET_KEY,
            // A fresh, unique bucket per test run — S3DocumentStore/AbstractS3DocumentStore
            // create it lazily on first use, so no manual provisioning is needed here.
            "invoice.s3.bucket" to "st-a5-it-${UUID.randomUUID()}",
        )
    }

    override fun stop() {
        rustfs.stop()
    }

    private companion object {
        private const val ACCESS_KEY = "rustfs"
        private const val SECRET_KEY = "rustfs123"
    }
}
