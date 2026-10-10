package de.littletrotter.infrastructure.storage

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

class DocumentStoreProducerTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `producer returns local filesystem store for default backend`() {
        val tempDir = Files.createTempDirectory(tmp, "receipt-store-")
        val producer = DocumentStoreProducer(
            Optional.empty(),
            Optional.of(tempDir.toString()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        val store = producer.produce()

        assertTrue(store is LocalFileSystemDocumentStore)
    }

    @Test
    fun `producer returns local filesystem store for explicit filesystem backend`() {
        val tempDir = Files.createTempDirectory(tmp, "receipt-store-explicit-")
        val producer = DocumentStoreProducer(
            Optional.of("filesystem"),
            Optional.of(tempDir.toString()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        val store = producer.produce()

        assertTrue(store is LocalFileSystemDocumentStore)
    }

    @Test
    fun `producer returns s3 store for s3 backend`() {
        val producer = DocumentStoreProducer(
            Optional.of("s3"),
            Optional.empty(),
            Optional.of("http://localhost:9000"),
            Optional.of("rustfsadmin"),
            Optional.of("rustfsadmin"),
            Optional.of("invoice-documents")
        )

        val store = producer.produce()

        assertTrue(store is S3DocumentStore)
    }

    @Test
    fun `producer rejects unknown backend`() {
        val producer = DocumentStoreProducer(
            Optional.of("unknown"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        assertThrows(IllegalArgumentException::class.java) {
            producer.produce()
        }
    }

    @Test
    fun `producer returns local filesystem store for explicit local backend`() {
        val tempDir = Files.createTempDirectory(tmp, "receipt-store-local-")
        val producer = DocumentStoreProducer(
            Optional.of("local"),
            Optional.of(tempDir.toString()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        val store = producer.produce()

        assertTrue(store is LocalFileSystemDocumentStore)
    }

    @Test
    fun `producer normalizes backend value with surrounding whitespace and mixed case`() {
        val producer = DocumentStoreProducer(
            Optional.of("  S3  "),
            Optional.empty(),
            Optional.of("http://localhost:9000"),
            Optional.of("rustfsadmin"),
            Optional.of("rustfsadmin"),
            Optional.of("invoice-documents")
        )

        val store = producer.produce()

        assertTrue(store is S3DocumentStore)
    }

    @Test
    fun `producer rejects blank backend rather than silently defaulting`() {
        val producer = DocumentStoreProducer(
            Optional.of("   "),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        assertThrows(IllegalArgumentException::class.java) {
            producer.produce()
        }
    }

    @Test
    fun `producer falls back to default receipts directory when storage directory property is absent`() {
        val fakeHome = Files.createTempDirectory(tmp, "fake-home-")
        val originalHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", fakeHome.toString())
            val producer = DocumentStoreProducer(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            )

            val store = producer.produce() as LocalFileSystemDocumentStore
            store.put("probe.txt", "x".toByteArray(), "text/plain")

            val expected = fakeHome.resolve(".little-trotter").resolve("receipts").resolve("probe.txt")
            assertTrue(Files.exists(expected))
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun `producer falls back to default receipts directory when storage directory is blank`() {
        val fakeHome = Files.createTempDirectory(tmp, "fake-home-")
        val originalHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", fakeHome.toString())
            val producer = DocumentStoreProducer(
                Optional.empty(),
                Optional.of("   "),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            )

            val store = producer.produce() as LocalFileSystemDocumentStore
            store.put("probe.txt", "x".toByteArray(), "text/plain")

            val expected = fakeHome.resolve(".little-trotter").resolve("receipts").resolve("probe.txt")
            assertTrue(Files.exists(expected))
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun `producer builds s3 store without throwing when s3 properties are absent (defaults applied)`() {
        // S3DocumentStore.bucket is `protected` (declared in kotlin-shared's AbstractS3DocumentStore,
        // shared with kotlin-xpdf), so the resulting default value isn't observable from this module's
        // test without widening that visibility across a module boundary purely for this assertion.
        // What's a real, developer-relevant assertion here is that every Optional-defaulting branch in
        // createS3Store() runs to completion (endpoint/access-key/secret-key/bucket all absent) and
        // still produces a usable S3DocumentStore rather than throwing.
        val producer = DocumentStoreProducer(
            Optional.of("s3"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        val store = producer.produce()

        assertTrue(store is S3DocumentStore)
    }

    @Test
    fun `producer builds s3 store without throwing when s3 properties are explicit`() {
        val producer = DocumentStoreProducer(
            Optional.of("s3"),
            Optional.empty(),
            Optional.of("http://localhost:9000"),
            Optional.of("rustfsadmin"),
            Optional.of("rustfsadmin"),
            Optional.of("custom-invoice-bucket")
        )

        val store = producer.produce()

        assertTrue(store is S3DocumentStore)
    }
}
