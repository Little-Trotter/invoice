package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Object

/**
 * [S3DocumentStore] against a mocked [S3Client] — the internal `(client, bucket)` constructor
 * exists exactly for this. No live S3/RustFS endpoint is needed for any of the branch logic
 * this class carries (exists()'s try/catch, listObjectKeys()'s pagination loop, init()'s
 * bucket-exists check).
 */
class S3DocumentStoreTest {

    @Test
    fun `put builds a PutObjectRequest with bucket, key and content type`() {
        val client: S3Client = mock()
        val store = S3DocumentStore(client, bucket = "invoices")

        store.put("2026/inv-1.pdf", byteArrayOf(1, 2, 3), "application/pdf")

        verify(client).putObject(
            argThat<PutObjectRequest> { req -> req.bucket() == "invoices" && req.key() == "2026/inv-1.pdf" && req.contentType() == "application/pdf" },
            any<RequestBody>(),
        )
    }

    @Test
    fun `get returns the object bytes unchanged`() {
        val client: S3Client = mock()
        val bytes = byteArrayOf(9, 8, 7, 6)
        whenever(client.getObjectAsBytes(any<GetObjectRequest>()))
            .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes))
        val store = S3DocumentStore(client, bucket = "invoices")

        val result = store.get("2026/inv-1.pdf")

        assertArrayEquals(bytes, result)
    }

    @Test
    fun `exists returns true when headObject succeeds`() {
        val client: S3Client = mock()
        whenever(client.headObject(any<HeadObjectRequest>())).thenReturn(HeadObjectResponse.builder().build())
        val store = S3DocumentStore(client, bucket = "invoices")

        assertTrue(store.exists("2026/inv-1.pdf"))
    }

    @Test
    fun `exists returns false when headObject throws`() {
        val client: S3Client = mock()
        whenever(client.headObject(any<HeadObjectRequest>())).thenThrow(NoSuchKeyException.builder().message("not found").build())
        val store = S3DocumentStore(client, bucket = "invoices")

        assertFalse(store.exists("2026/missing.pdf"))
    }

    @Test
    fun `delete builds a DeleteObjectRequest with bucket and key`() {
        val client: S3Client = mock()
        val store = S3DocumentStore(client, bucket = "invoices")

        store.delete("2026/inv-1.pdf")

        verify(client).deleteObject(argThat<DeleteObjectRequest> { req -> req.bucket() == "invoices" && req.key() == "2026/inv-1.pdf" })
    }

    @Test
    fun `enableImmutability is a no-op and never touches the client`() {
        val client: S3Client = mock()
        val store = S3DocumentStore(client, bucket = "invoices")

        store.enableImmutability("2026/inv-1.pdf")

        verify(client, never()).headObject(any<HeadObjectRequest>())
    }

    // ── listObjectKeys() — ListObjectsV2 pagination via continuationToken ───────────────────

    @Test
    fun `listObjectKeys returns all keys from a single, non-truncated page`() {
        val client: S3Client = mock()
        val response = ListObjectsV2Response.builder()
            .isTruncated(false)
            .contents(S3Object.builder().key("a.pdf").build(), S3Object.builder().key("b.pdf").build())
            .build()
        whenever(client.listObjectsV2(any<ListObjectsV2Request>())).thenReturn(response)
        val store = S3DocumentStore(client, bucket = "invoices")

        val keys = store.listObjectKeys("2026/")

        assertEquals(listOf("a.pdf", "b.pdf"), keys)
        verify(client, org.mockito.kotlin.times(1)).listObjectsV2(any<ListObjectsV2Request>())
    }

    @Test
    fun `listObjectKeys follows the continuation token across a truncated response`() {
        val client: S3Client = mock()
        val firstPage = ListObjectsV2Response.builder()
            .isTruncated(true)
            .nextContinuationToken("token-1")
            .contents(S3Object.builder().key("a.pdf").build())
            .build()
        val secondPage = ListObjectsV2Response.builder()
            .isTruncated(false)
            .contents(S3Object.builder().key("b.pdf").build())
            .build()
        whenever(client.listObjectsV2(any<ListObjectsV2Request>())).thenReturn(firstPage, secondPage)
        val store = S3DocumentStore(client, bucket = "invoices")

        val keys = store.listObjectKeys("2026/")

        assertEquals(listOf("a.pdf", "b.pdf"), keys)
        val captor = org.mockito.kotlin.argumentCaptor<ListObjectsV2Request>()
        verify(client, org.mockito.kotlin.times(2)).listObjectsV2(captor.capture())
        assertEquals(null, captor.firstValue.continuationToken())
        assertEquals("token-1", captor.secondValue.continuationToken())
    }

    @Test
    fun `listObjectKeys returns an empty list when the prefix has no objects`() {
        val client: S3Client = mock()
        val response = ListObjectsV2Response.builder().isTruncated(false).build()
        whenever(client.listObjectsV2(any<ListObjectsV2Request>())).thenReturn(response)
        val store = S3DocumentStore(client, bucket = "invoices")

        assertTrue(store.listObjectKeys("2026/").isEmpty())
    }

    // ── init()/ensureBucket() — bucket already exists vs. must be created ───────────────────

    @Test
    fun `init does not create the bucket when headBucket succeeds`() {
        val client: S3Client = mock()
        whenever(client.headBucket(any<HeadBucketRequest>())).thenReturn(HeadBucketResponse.builder().build())
        val store = S3DocumentStore(client, bucket = "invoices")

        store.init()

        verify(client, never()).createBucket(any<CreateBucketRequest>())
    }

    @Test
    fun `init creates the bucket when headBucket reports it missing`() {
        val client: S3Client = mock()
        whenever(client.headBucket(any<HeadBucketRequest>())).thenThrow(NoSuchBucketException.builder().message("no bucket").build())
        val store = S3DocumentStore(client, bucket = "invoices")

        store.init()

        verify(client).createBucket(argThat<CreateBucketRequest> { req -> req.bucket() == "invoices" })
    }

    @Test
    fun `boundary - init builds a real S3Client from config when none was injected`() {
        val store = S3DocumentStore("http://127.0.0.1:1", "key", "secret", "invoices")

        assertThrows(Exception::class.java) { store.init() }

        // `client` is a protected override of AbstractS3DocumentStore's abstract property — not
        // visible via a plain dot access from this test file, only reflectively; the backing
        // field lives on this concrete class, since the abstract base declares no field of its own.
        val clientField = store.javaClass.getDeclaredField("client").apply { isAccessible = true }
        assertNotNull(clientField.get(store))
    }

    @Test
    fun `the store is an eager bean so the bucket exists before any other component writes into it`() {
        assertNotNull(S3DocumentStore::class.java.getAnnotation(io.quarkus.runtime.Startup::class.java))
    }
}
