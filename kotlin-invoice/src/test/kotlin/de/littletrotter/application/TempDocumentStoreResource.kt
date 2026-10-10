package de.littletrotter.application

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the invoice module's default filesystem
 * [de.littletrotter.infrastructure.storage.DocumentStore] inside a fresh per-run temp directory
 * instead of the real `~/.little-trotter`, for `@QuarkusTest` ITs that inject the CDI-produced
 * store rather than a test double.
 *
 * Usage:
 * ```kotlin
 * @QuarkusTest
 * @QuarkusTestResource(TempDocumentStoreResource::class)
 * class MyDocumentWritingIT { ... }
 * ```
 */
class TempDocumentStoreResource : QuarkusTestResourceLifecycleManager {

    private var directory: Path? = null

    override fun start(): MutableMap<String, String> {
        val dir = Files.createTempDirectory("little-trotter-invoice-it-documents-")
        directory = dir
        return mutableMapOf("invoice.storage.directory" to dir.toString())
    }

    override fun stop() {
        directory?.toFile()?.deleteRecursively()
    }
}
