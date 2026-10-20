package de.littletrotter.infrastructure.storage

import de.littletrotter.shared.AbstractS3DocumentStore
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

/**
 * S3-compatible object store backed by the AWS SDK v2, used against RustFS for this app's own
 * yearly document backup.
 *
 * `endpointOverride` + path-style access are what make the AWS SDK talk to a self-hosted
 * server; the region is only SigV4 signature filler and has no meaning here. CRUD and listing
 * logic live in [AbstractS3DocumentStore], shared with kotlin-xpdf's own
 * `S3DocumentStore`; this class only supplies client construction and this app's own
 * bucket-ensure strategy: lazily on first use, memoized so concurrent callers only check/create
 * the bucket once.
 */
class S3DocumentStore(
    endpoint: String,
    accessKey: String,
    secretKey: String,
    override val bucket: String,
) : AbstractS3DocumentStore() {

    override val client: S3Client by lazy {
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
            )
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .build()
    }

    @Volatile
    private var bucketEnsured: Boolean = false

    @Synchronized
    override fun ensureBucket() {
        if (bucketEnsured) return
        createBucketIfMissing()
        bucketEnsured = true
    }

    // Year-based document storage (J.1): put/get/exists/delete(invoiceDate, ...) are inherited
    // from DocumentStore's default implementations, which delegate to the single-key overrides
    // in AbstractS3DocumentStore. Do not redeclare them here without `override` — that hides the
    // interface default instead of using it and fails to compile (see AGENT-BOARD.md logbook,
    // Task F entry).
}
