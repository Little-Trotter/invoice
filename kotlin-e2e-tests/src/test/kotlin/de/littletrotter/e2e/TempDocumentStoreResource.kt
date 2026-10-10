package de.littletrotter.e2e

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the invoice module's default filesystem
 * [de.littletrotter.infrastructure.storage.DocumentStore] inside a fresh per-run temp directory
 * instead of the real `~/.little-trotter`, for E2E classes that do not otherwise need a real S3
 * round trip (see [S3TestResource] for those that do).
 */
class TempDocumentStoreResource : QuarkusTestResourceLifecycleManager {

    private var directory: Path? = null

    override fun start(): MutableMap<String, String> {
        val dir = Files.createTempDirectory("little-trotter-e2e-documents-")
        directory = dir
        val props = mutableMapOf("invoice.storage.directory" to dir.toString())
        // Same reason as PostgresTestResource/ValkeyTestResource/S3TestResource: System
        // properties carry a strictly higher SmallRye Config ordinal than any other source in
        // this cross-module index-dependency boot path.
        props.forEach { (key, value) -> System.setProperty(key, value) }
        return props
    }

    override fun stop() {
        directory?.toFile()?.deleteRecursively()
    }
}
