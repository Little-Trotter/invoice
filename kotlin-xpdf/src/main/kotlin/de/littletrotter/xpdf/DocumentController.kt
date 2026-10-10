package de.littletrotter.xpdf

import de.littletrotter.shared.time.TimeSource
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration

/**
 * REST endpoint that generates (or returns cached) presigned download URLs for the
 * S3-compatible object store (RustFS).
 *
 * Presigned URL lifetime: configurable `ttl` seconds (default 900 = 15 min).
 * Cache TTL: [PresignCache.DEFAULT_CACHE_TTL_MS] (14 min) — conservative margin
 * so a cached URL is never served after actual expiry.
 */
@Path("/documents")
class DocumentController {

    private val presigner: S3Presigner
    private val cache: PresignCache
    private val time: TimeSource

    /** CDI entry point: an `@Inject`-annotated constructor is required here since ArC would
     *  otherwise not know which of this class's constructors to call. */
    @Inject
    constructor(time: TimeSource) {
        presigner = S3Clients.presigner(s3Endpoint, getS3AccessKey(), getS3SecretKey())
        cache = PresignCache
        this.time = time
    }

    /** Test constructor — allows injecting a mock presigner and/or a no-op cache. */
    internal constructor(presigner: S3Presigner, time: TimeSource, cache: PresignCache = PresignCache) {
        this.presigner = presigner
        this.cache = cache
        this.time = time
    }

    internal val s3Endpoint: String get() = configValue("s3.endpoint", "S3_ENDPOINT", "http://rustfs:9000")
    internal fun getS3AccessKey(): String = configValue("s3.access-key", "S3_ACCESS_KEY", "rustfsadmin")
    internal fun getS3SecretKey(): String = configValue("s3.secret-key", "S3_SECRET_KEY", "rustfsadmin")

    private fun validateRequest(bucket: String?, objectKey: String?, ttlSeconds: Int?): Triple<String, String, Int> {
        require(!bucket.isNullOrBlank() && !objectKey.isNullOrBlank()) { "bucket and objectKey are required" }
        require(bucket.matches(BUCKET_PATTERN)) { "invalid bucket" }
        require(objectKey.length <= MAX_OBJECT_KEY_LENGTH && !objectKey.startsWith('/') &&
            !objectKey.split('/').any { it == ".." || it.isBlank() }) { "invalid objectKey" }
        val ttl = ttlSeconds ?: DEFAULT_TTL_SECONDS
        require(ttl in 1..MAX_TTL_SECONDS) { "ttl must be between 1 and $MAX_TTL_SECONDS seconds" }
        return Triple(bucket, objectKey, ttl)
    }

    private fun configValue(prop: String, env: String, default: String): String =
        System.getProperty(prop).takeUnless { it.isNullOrBlank() }
            ?: readEnv(env).takeUnless { it.isNullOrBlank() }
            ?: default

    @GET
    @Path("/presign")
    @Produces(MediaType.APPLICATION_JSON)
    fun presign(
        @QueryParam("bucket") bucket: String?,
        @QueryParam("objectKey") objectKey: String?,
        @QueryParam("ttl") ttlSeconds: Int?,
    ): Map<String, String> {
        val (safeBucket, safeObjectKey, ttl) = validateRequest(bucket, objectKey, ttlSeconds)

        // Cache read — skip the signing round-trip on hit
        val cached = cache.get(safeBucket, safeObjectKey, time.instant())
        if (cached != null) {
            return mapOf("url" to cached, "bucket" to safeBucket, "objectKey" to safeObjectKey)
        }

        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofSeconds(ttl.toLong()))
            .getObjectRequest(GetObjectRequest.builder().bucket(safeBucket).key(safeObjectKey).build())
            .build()

        val url = presigner.presignGetObject(presignRequest).url().toString()

        // Cache write — use 14-min TTL regardless of requested ttl to stay safely under expiry
        cache.put(safeBucket, safeObjectKey, url, time.instant())

        return mapOf("url" to url, "bucket" to safeBucket, "objectKey" to safeObjectKey)
    }

    private companion object {
        const val DEFAULT_TTL_SECONDS = 900
        const val MAX_TTL_SECONDS = 900
        const val MAX_OBJECT_KEY_LENGTH = 512
        val BUCKET_PATTERN = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
    }
}
