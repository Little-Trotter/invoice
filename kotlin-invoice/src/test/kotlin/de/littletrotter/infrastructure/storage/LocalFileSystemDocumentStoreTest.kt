package de.littletrotter.infrastructure.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [LocalFileSystemDocumentStore] is the filesystem-backed [de.littletrotter.shared.DocumentStore]
 * used outside the S3/RustFS backend. Path traversal defence (`resolve`'s `require(startsWith)`) is
 * the highest-risk surface here: an objectKey/prefix that escapes [storageDir] must never reach
 * `Files`.
 */
class LocalFileSystemDocumentStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun store(): LocalFileSystemDocumentStore = LocalFileSystemDocumentStore(tempDir.resolve("documents"))

    @Test
    fun `happy path - init creates the storage dir and put get exists delete round trip`() {
        val target = store()
        target.init()
        val key = "receipt-pool/sample.pdf"
        val payload = "pdf-bytes".toByteArray()

        assertFalse(target.exists(key))

        target.put(key, payload, "application/pdf")

        assertTrue(target.exists(key))
        assertArrayEquals(payload, target.get(key))

        target.delete(key)

        assertFalse(target.exists(key))
    }

    @Test
    fun `boundary - path traversal in an objectKey is rejected rather than escaping the storage dir`() {
        val target = store()
        target.init()

        assertThrows(IllegalArgumentException::class.java) {
            target.put("../../../etc/passwd", "evil".toByteArray(), "text/plain")
        }
        assertThrows(IllegalArgumentException::class.java) {
            target.get("../../../etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            target.exists("../outside.pdf")
        }
    }

    @Test
    fun `boundary - reading a missing document fails loudly instead of returning a phantom empty payload`() {
        val target = store()
        target.init()

        assertThrows(NoSuchElementException::class.java) {
            target.get("receipt-pool/never-written.pdf")
        }
    }

    @Test
    fun `boundary - delete of a non-existent key and immutability enable are both no-ops`() {
        val target = store()
        target.init()

        target.delete("receipt-pool/never-written.pdf")
        target.enableImmutability("receipt-pool/never-written.pdf")

        assertFalse(target.exists("receipt-pool/never-written.pdf"))
    }

    @Test
    fun `boundary - listObjectKeys on a prefix with no matching directory returns empty rather than throwing`() {
        val target = store()
        target.init()

        val keys = target.listObjectKeys("documents/2099/")

        assertTrue(keys.isEmpty())
    }

    @Test
    fun `boundary - listObjectKeys rejects a traversal prefix and otherwise returns only keys under that prefix`() {
        val target = store()
        target.init()
        target.put("documents/2026/a.pdf", "a".toByteArray(), "application/pdf")
        target.put("documents/2026/b.pdf", "b".toByteArray(), "application/pdf")
        target.put("documents/2025/c.pdf", "c".toByteArray(), "application/pdf")

        val keys = target.listObjectKeys("documents/2026")

        assertEquals(setOf("documents/2026/a.pdf", "documents/2026/b.pdf"), keys.toSet())
        assertThrows(IllegalArgumentException::class.java) {
            target.listObjectKeys("../../outside")
        }
    }

    @Test
    fun `boundary - put creates intermediate sub-directories that do not exist yet`() {
        val target = store()
        target.init()

        target.put("a/b/c/deep.pdf", "deep".toByteArray(), "application/pdf")

        assertTrue(Files.exists(tempDir.resolve("documents/a/b/c/deep.pdf")))
    }
}
