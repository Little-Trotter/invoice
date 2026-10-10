package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
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
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Object
import software.amazon.awssdk.core.exception.SdkClientException

/**
 * [AbstractS3DocumentStore] against a mocked [S3Client]: a minimal concrete subclass
 * exercises the shared CRUD/listing logic little-trotter-invoice's and kotlin-xpdf's own
 * `S3DocumentStore` used to each carry byte-for-byte. When/how a subclass checks the bucket is
 * its own concern (tested there, in each module); this class only proves the default no-op
 * [AbstractS3DocumentStore.ensureBucket] hook and the `bucketExists`/`createBucketIfMissing`
 * helpers those subclasses call into.
 */
class AbstractS3DocumentStoreTest {

    private class TestStore(override val client: S3Client, override val bucket: String) : AbstractS3DocumentStore() {
        var ensureBucketCalls = 0
        override fun ensureBucket() {
            ensureBucketCalls++
        }

        fun callBucketExists(): Boolean = bucketExists()
        fun callCreateBucketIfMissing() = createBucketIfMissing()
    }

    @Test
    fun `put builds a PutObjectRequest with bucket, key and content type, calling ensureBucket first`() {
        val client: S3Client = mock()
        val store = TestStore(client, "invoices")

        store.put("2026/inv-1.pdf", byteArrayOf(1, 2, 3), "application/pdf")

        assertEquals(1, store.ensureBucketCalls)
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
        val store = TestStore(client, "invoices")

        val result = store.get("2026/inv-1.pdf")

        assertArrayEquals(bytes, result)
        assertEquals(1, store.ensureBucketCalls)
    }

    @Test
    fun `exists returns true when headObject succeeds`() {
        val client: S3Client = mock()
        whenever(client.headObject(any<HeadObjectRequest>())).thenReturn(HeadObjectResponse.builder().build())
        val store = TestStore(client, "invoices")

        assertTrue(store.exists("2026/inv-1.pdf"))
    }

    @Test
    fun `exists returns false when headObject throws`() {
        val client: S3Client = mock()
        whenever(client.headObject(any<HeadObjectRequest>())).thenThrow(NoSuchKeyException.builder().message("not found").build())
        val store = TestStore(client, "invoices")

        assertFalse(store.exists("2026/missing.pdf"))
    }

    @Test
    fun `exists returns false for a plain 404 without NoSuchKey type (HEAD has no body)`() {
        val client: S3Client = mock()
        whenever(client.headObject(any<HeadObjectRequest>())).thenThrow(S3Exception.builder().statusCode(404).message("not found").build())
        val store = TestStore(client, "invoices")

        assertFalse(store.exists("2026/missing.pdf"))
    }

    @Test
    fun `exists rethrows a non-404 S3 error instead of reporting the key as free`() {
        val client: S3Client = mock()
        whenever(client.headObject(any<HeadObjectRequest>())).thenThrow(S3Exception.builder().statusCode(403).message("denied").build())
        val store = TestStore(client, "invoices")

        org.junit.jupiter.api.Assertions.assertThrows(S3Exception::class.java) { store.exists("2026/inv-1.pdf") }
    }

    @Test
    fun `exists rethrows a transport error instead of reporting the key as free`() {
        val client: S3Client = mock()
        whenever(client.headObject(any<HeadObjectRequest>())).thenThrow(SdkClientException.create("connection refused"))
        val store = TestStore(client, "invoices")

        org.junit.jupiter.api.Assertions.assertThrows(SdkClientException::class.java) { store.exists("2026/inv-1.pdf") }
    }

    // ── headLastModified() — E2E Welle C, Punkt 3 ─────────────────────────────────

    @Test
    fun `headLastModified returns the object's LastModified timestamp`() {
        val client: S3Client = mock()
        val lastModified = java.time.Instant.parse("2026-08-01T10:15:30Z")
        whenever(client.headObject(any<HeadObjectRequest>()))
            .thenReturn(HeadObjectResponse.builder().lastModified(lastModified).build())
        val store = TestStore(client, "invoices")

        assertEquals(lastModified, store.headLastModified("receipt-pool/abc.pdf"))
        assertEquals(1, store.ensureBucketCalls)
    }

    @Test
    fun `headLastModified returns null when the object is gone`() {
        val client: S3Client = mock()
        whenever(client.headObject(any<HeadObjectRequest>())).thenThrow(NoSuchKeyException.builder().message("not found").build())
        val store = TestStore(client, "invoices")

        assertEquals(null, store.headLastModified("receipt-pool/missing.pdf"))
    }

    @Test
    fun `delete builds a DeleteObjectRequest with bucket and key`() {
        val client: S3Client = mock()
        val store = TestStore(client, "invoices")

        store.delete("2026/inv-1.pdf")

        verify(client).deleteObject(argThat<DeleteObjectRequest> { req -> req.bucket() == "invoices" && req.key() == "2026/inv-1.pdf" })
    }

    @Test
    fun `enableImmutability is a no-op and never touches the client`() {
        val client: S3Client = mock()
        val store = TestStore(client, "invoices")

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
        val store = TestStore(client, "invoices")

        val keys = store.listObjectKeys("2026/")

        assertEquals(listOf("a.pdf", "b.pdf"), keys)
        verify(client, times(1)).listObjectsV2(any<ListObjectsV2Request>())
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
        val store = TestStore(client, "invoices")

        val keys = store.listObjectKeys("2026/")

        assertEquals(listOf("a.pdf", "b.pdf"), keys)
        val captor = argumentCaptor<ListObjectsV2Request>()
        verify(client, times(2)).listObjectsV2(captor.capture())
        assertEquals(null, captor.firstValue.continuationToken())
        assertEquals("token-1", captor.secondValue.continuationToken())
    }

    @Test
    fun `listObjectKeys returns an empty list when the prefix has no objects`() {
        val client: S3Client = mock()
        val response = ListObjectsV2Response.builder().isTruncated(false).build()
        whenever(client.listObjectsV2(any<ListObjectsV2Request>())).thenReturn(response)
        val store = TestStore(client, "invoices")

        assertTrue(store.listObjectKeys("2026/").isEmpty())
    }

    // ── default ensureBucket() hook (no override) ────────────────────────────────

    @Test
    fun `default ensureBucket hook is a no-op`() {
        val fakeClient: S3Client = mock()
        whenever(fakeClient.headObject(any<HeadObjectRequest>())).thenReturn(HeadObjectResponse.builder().build())
        val store = object : AbstractS3DocumentStore() {
            override val client: S3Client = fakeClient
            override val bucket: String = "invoices"
        }

        assertTrue(store.exists("2026/inv-1.pdf"))
        verify(fakeClient, never()).headBucket(any<HeadBucketRequest>())
    }

    // ── bucketExists()/createBucketIfMissing() helpers ───────────────────────────

    @Test
    fun `bucketExists returns true when headBucket succeeds`() {
        val client: S3Client = mock()
        whenever(client.headBucket(any<HeadBucketRequest>())).thenReturn(HeadBucketResponse.builder().build())
        val store = TestStore(client, "invoices")

        assertTrue(store.callBucketExists())
    }

    @Test
    fun `bucketExists returns false when headBucket throws NoSuchBucketException`() {
        val client: S3Client = mock()
        whenever(client.headBucket(any<HeadBucketRequest>())).thenThrow(NoSuchBucketException.builder().message("no bucket").build())
        val store = TestStore(client, "invoices")

        assertFalse(store.callBucketExists())
    }

    @Test
    fun `createBucketIfMissing does not create the bucket when it already exists`() {
        val client: S3Client = mock()
        whenever(client.headBucket(any<HeadBucketRequest>())).thenReturn(HeadBucketResponse.builder().build())
        val store = TestStore(client, "invoices")

        store.callCreateBucketIfMissing()

        verify(client, never()).createBucket(any<CreateBucketRequest>())
    }

    @Test
    fun `createBucketIfMissing creates the bucket when headBucket reports it missing`() {
        val client: S3Client = mock()
        whenever(client.headBucket(any<HeadBucketRequest>())).thenThrow(NoSuchBucketException.builder().message("no bucket").build())
        val store = TestStore(client, "invoices")

        store.callCreateBucketIfMissing()

        verify(client).createBucket(argThat<CreateBucketRequest> { req -> req.bucket() == "invoices" })
    }
}
