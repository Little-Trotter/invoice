package de.littletrotter.infrastructure.storage

import de.littletrotter.shared.DocumentStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Simple local-filesystem DocumentStore.
 *
 * Stores receipt PDFs under [storageDir]. Sub-directories in the objectKey
 * (e.g. "receipt-pool/abc.pdf") are created automatically. Path traversal
 * attacks are blocked by normalising the resolved path and checking it is
 * still under [storageDir].
 */
class LocalFileSystemDocumentStore(private val storageDir: Path) : DocumentStore {

    fun init() {
        Files.createDirectories(storageDir)
    }

    override fun put(objectKey: String, data: ByteArray, contentType: String) {
        val file = resolve(objectKey)
        Files.createDirectories(file.parent)
        Files.write(file, data)
    }

    override fun get(objectKey: String): ByteArray {
        val file = resolve(objectKey)
        if (!Files.exists(file)) throw NoSuchElementException("Receipt not found in store: $objectKey")
        return Files.readAllBytes(file)
    }

    override fun exists(objectKey: String): Boolean = Files.exists(resolve(objectKey))

    override fun enableImmutability(objectKey: String) {
        // no-op on local filesystem
    }

    override fun delete(objectKey: String) {
        Files.deleteIfExists(resolve(objectKey))
    }

    /**
     * Walks the [prefix] sub-directory and returns every regular file under it as an
     * objectKey, i.e. relative to [storageDir] and joined with `/` regardless of the host
     * platform's path separator, so keys read back the same way [put]/[get] built them.
     *
     * A missing sub-directory (e.g. a year with no receipts yet) is not an error: it just
     * means there is nothing to list.
     */
    override fun listObjectKeys(prefix: String): List<String> {
        val dir = storageDir.resolve(prefix).normalize()
        require(dir.startsWith(storageDir)) { "Invalid prefix: $prefix" }
        if (!Files.isDirectory(dir)) return emptyList()

        Files.walk(dir).use { paths ->
            return paths
                .filter { Files.isRegularFile(it) }
                .map { storageDir.relativize(it).joinToString("/") }
                .toList()
        }
    }

    private fun resolve(objectKey: String): Path {
        val resolved = storageDir.resolve(objectKey).normalize()
        require(resolved.startsWith(storageDir)) { "Invalid objectKey: $objectKey" }
        return resolved
    }
}
