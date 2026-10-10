package de.littletrotter.infrastructure.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import java.net.URI
import java.util.UUID

@Testcontainers
class S3DocumentStoreIT {

    @Test
    fun `store roundtrip put get exists delete works`() {
        val store = S3DocumentStore(endpoint(), ACCESS_KEY, SECRET_KEY, randomBucket())
        val key = "receipt-pool/sample.pdf"
        val payload = "pdf-bytes".toByteArray()

        assertFalse(store.exists(key))

        store.put(key, payload, "application/pdf")

        assertTrue(store.exists(key))
        assertArrayEquals(payload, store.get(key))

        store.delete(key)

        assertFalse(store.exists(key))
    }

    @Test
    fun `listObjectKeys returns exactly the keys under the given prefix against a real RustFS`() {
        val store = S3DocumentStore(endpoint(), ACCESS_KEY, SECRET_KEY, randomBucket())
        store.put("documents/2026/a.pdf", "a".toByteArray(), "application/pdf")
        store.put("documents/2026/b.pdf", "b".toByteArray(), "application/pdf")
        store.put("documents/2025/c.pdf", "c".toByteArray(), "application/pdf")

        val keys = store.listObjectKeys("documents/2026/")

        assertEquals(setOf("documents/2026/a.pdf", "documents/2026/b.pdf"), keys.toSet())
    }

    @Test
    fun `listObjectKeys pages past RustFS's 1000-key response cap`() {
        val store = S3DocumentStore(endpoint(), ACCESS_KEY, SECRET_KEY, randomBucket())
        val expectedKeys = (1..1005).map { "documents/2026/receipt-%04d.pdf".format(it) }
        expectedKeys.forEach { store.put(it, byteArrayOf(1), "application/pdf") }

        val keys = store.listObjectKeys("documents/2026/")

        assertEquals(expectedKeys.toSet(), keys.toSet(), "a single ListObjectsV2 page caps at 1000 keys; pagination must not lose the rest")
    }

    @Test
    fun `bucket is created lazily on first operation`() {
        val bucket = randomBucket()
        val store = S3DocumentStore(endpoint(), ACCESS_KEY, SECRET_KEY, bucket)
        val client = S3Client.builder()
            .endpointOverride(URI.create(endpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .build()

        assertFalse(bucketExists(client, bucket))

        assertFalse(store.exists("missing.pdf"))

        assertTrue(bucketExists(client, bucket))
    }

    private fun bucketExists(client: S3Client, bucket: String): Boolean =
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
            true
        } catch (_: NoSuchBucketException) {
            false
        }

    private fun endpoint(): String = "http://${rustfs.host}:${rustfs.getMappedPort(9000)}"

    private fun randomBucket(): String =
        "receipts-${UUID.randomUUID().toString().replace("-", "").lowercase().take(16)}"

    companion object {
        private const val ACCESS_KEY = "rustfs"
        private const val SECRET_KEY = "rustfs123"

        /**
         * Pinned, never `latest`: on 05.09.2026 `latest` moved to a build whose `/health` answers
         * 401, so the wait condition below was never met and the build went red without a line of
         * code changing. A container version fetched from the network makes this build depend on
         * someone else's release schedule. The pin tracks the version the stack runs
         * (`docker-compose.yml`) so tests and product see the same object store.
         */
        @Container
        @JvmStatic
        val rustfs: GenericContainer<*> = GenericContainer(DockerImageName.parse("rustfs/rustfs:1.0.1"))
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
            .withCommand("/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
    }
}
