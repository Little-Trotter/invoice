package de.littletrotter.shared

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * S3-compatible object store operations (AWS SDK v2, used against RustFS): the CRUD and
 * paginated-listing logic little-trotter-invoice and kotlin-xpdf both implemented byte-for-byte
 * against their own [client]/[bucket]. Client construction and CDI wiring differ per module (one
 * is a plain constructor used outside CDI for the yearly backup feature, the other an
 * `@ApplicationScoped` bean) and stay local to each module's own `S3DocumentStore` — as does when
 * the bucket gets checked/created, via the [ensureBucket] hook: the two existing modules differ
 * here (once at CDI startup vs. lazily-and-memoized on first use) and that difference is
 * deliberately preserved rather than unified, since neither behavior is a bug this move should fix.
 */
abstract class AbstractS3DocumentStore : DocumentStore {

    protected abstract val client: S3Client
    protected abstract val bucket: String

    /** Called before every operation below; the default does nothing. */
    protected open fun ensureBucket() {
        // No-op default: buckets are provisioned out-of-band for the configured environment.
    }

    override fun put(objectKey: String, data: ByteArray, contentType: String) {
        ensureBucket()
        client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(data),
        )
    }

    override fun get(objectKey: String): ByteArray {
        ensureBucket()
        return client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key(objectKey).build()
        ).asByteArray()
    }

    override fun exists(objectKey: String): Boolean {
        ensureBucket()
        return try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build())
            true
        } catch (_: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            // Only a genuine "not found" means absent; auth/network/5xx errors must not be
            // misread as "free key" (document-checksum-mismatch spec, overwrite window).
            if (e.statusCode() != HTTP_NOT_FOUND) throw e
            false
        }
    }

    override fun enableImmutability(objectKey: String) {
        // No object-locking support on either module yet.
    }

    /** `HeadObject`'s `LastModified`, or `null` when the object is gone
     *  (deleted between listing and this call) or the head request otherwise fails. Best-effort
     *  by contract ([DocumentStore.headLastModified]'s own KDoc) — never thrown from here. */
    override fun headLastModified(objectKey: String): java.time.Instant? {
        ensureBucket()
        return try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build()).lastModified()
        } catch (_: Exception) {
            null
        }
    }

    override fun delete(objectKey: String) {
        ensureBucket()
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build())
    }

    /**
     * Pages through `ListObjectsV2` via its continuation token rather than trusting a single
     * response: S3 (and RustFS) caps a single page at 1000 keys, and a year with more documents
     * than that would otherwise silently lose everything past the first page — exactly the kind
     * of incomplete-but-reports-success result a backup must not produce.
     */
    override fun listObjectKeys(prefix: String): List<String> {
        ensureBucket()
        val keys = mutableListOf<String>()
        var continuationToken: String? = null
        do {
            val requestBuilder = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
            continuationToken?.let { requestBuilder.continuationToken(it) }
            val response = client.listObjectsV2(requestBuilder.build())
            response.contents().mapTo(keys) { it.key() }
            continuationToken = if (response.isTruncated == true) response.nextContinuationToken() else null
        } while (continuationToken != null)
        return keys
    }

    /** True when [bucket] already exists on [client]. */
    protected fun bucketExists(): Boolean =
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
            true
        } catch (_: NoSuchBucketException) {
            false
        }

    /** Creates [bucket] on [client] unless [bucketExists] already. */
    protected fun createBucketIfMissing() {
        if (!bucketExists()) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        }
    }
}

private const val HTTP_NOT_FOUND = 404
