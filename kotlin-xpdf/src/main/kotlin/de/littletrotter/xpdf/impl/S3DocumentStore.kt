package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.S3Clients
import de.littletrotter.shared.AbstractS3DocumentStore
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import software.amazon.awssdk.services.s3.S3Client

/**
 * S3-compatible object store backed by the AWS SDK v2.
 *
 * The endpoint is overridden and path-style access forced (built via [S3Clients]), so this works
 * against RustFS (and any other S3-compatible server) rather than only AWS itself. CRUD and
 * listing logic live in [AbstractS3DocumentStore], shared with little-trotter-invoice's
 * own `S3DocumentStore`; this class only supplies CDI wiring/config and this worker's own
 * bucket-ensure strategy: once, at `@PostConstruct`.
 *
 * The bean is eager (`@Startup`): the bucket belongs to this worker and other components write
 * into it before their first job reaches the worker (the offer service stages its XML and the
 * customer files there). Were the bucket created lazily on the first job, a fresh deployment
 * would refuse that first offer document with "bucket does not exist". The check is one HEAD
 * request against an object store the deployment already waits for.
 */
@Startup
@ApplicationScoped
class S3DocumentStore @Inject constructor(
    // `@param:` pins each of these to the constructor parameter only; Kotlin 2.4's
    // default annotation-use-site-target policy would otherwise also copy `@ConfigProperty` onto
    // the backing final field (or, for [bucket], the overriding property), which Qodana's CDI
    // inspector misreads as an illegal final-field injection even though CDI never reads the
    // field/property (no `@Inject` there).
    @param:ConfigProperty(name = "s3.endpoint") private val endpoint: String,
    @param:ConfigProperty(name = "s3.access-key") private val accessKey: String,
    @param:ConfigProperty(name = "s3.secret-key") private val secretKey: String,
    @param:ConfigProperty(name = "s3.bucket") override val bucket: String,
) : AbstractS3DocumentStore() {

    override lateinit var client: S3Client

    /** For testing: inject a pre-built S3Client, skip PostConstruct wiring. */
    internal constructor(client: S3Client, bucket: String) : this("http://rustfs:9000", "key", "secret", bucket) {
        this.client = client
    }

    @PostConstruct
    fun init() {
        if (!this::client.isInitialized) {
            client = S3Clients.build(endpoint, accessKey, secretKey)
        }
        ensureBucket()
    }

    override fun ensureBucket() {
        createBucketIfMissing()
    }

    // Year-based document storage: put/get/exists/delete(invoiceDate, ...) are inherited
    // from DocumentStore's default implementations, which build the year-based key through
    // DocumentKeyRules — the single owner of the key layout. They delegate to the single-key
    // overrides in AbstractS3DocumentStore. Do not redeclare them here; a private buildDocumentKey
    // copy would drift from the shared contract.
}
