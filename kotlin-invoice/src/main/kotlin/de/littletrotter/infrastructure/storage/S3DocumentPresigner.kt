package de.littletrotter.infrastructure.storage

import de.littletrotter.application.mail.port.outbound.DocumentPresignPort
import de.littletrotter.application.mail.port.outbound.PresignedDocument
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.net.URI
import java.time.Duration
import java.util.Optional

/**
 * Mints presigned GET URLs for objects in this app's own document bucket, so the
 * mail component can pull an invoice PDF into its own bucket without ever holding credentials for
 * this one (see [DocumentPresignPort]'s KDoc for why that matters).
 *
 * The client construction is the same three settings [S3DocumentStore] already needs against a
 * self-hosted, S3-compatible server (RustFS): `endpointOverride`, path-style addressing, and a
 * region that is only SigV4 signature filler. `kotlin-xpdf`'s `S3Clients.presigner` builds exactly
 * this shape for its own `/documents/presign` endpoint; that object lives in the other deployable
 * and is not on this module's classpath, so the builder is repeated here rather than the two
 * modules being coupled for eight lines.
 *
 * The HEAD before the signature is what makes [PresignedDocument.sizeBytes] a statement about the
 * object *now* rather than about whatever was written into `invoice_document.size_bytes` (a
 * nullable column no code path fills). A missing object returns `null` — the caller then fails the
 * whole handover rather than sending a mail with one attachment fewer than the user approved.
 */
class S3DocumentPresigner(
    private val client: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
) : DocumentPresignPort {

    private val log = Logger.getLogger(S3DocumentPresigner::class.java)

    override fun presignGet(objectKey: String, ttl: Duration): PresignedDocument? {
        val size = try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build()).contentLength()
        } catch (e: S3Exception) {
            log.warnf(e, "Cannot presign '%s' in bucket '%s' — HEAD failed", objectKey, bucket)
            return null
        }
        val url = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
                .build(),
        ).url().toString()
        return PresignedDocument(url = url, sizeBytes = size)
    }
}

/**
 * The port's answer on a deployment whose document store cannot sign anything — the local
 * filesystem backend.
 *
 * A separate type rather than a `null` bean on purpose: a `@Produces` method returning `null`
 * still makes the injection point *resolvable*, so the caller would receive `null` where its type
 * says non-null. Returning `null` from [presignGet] instead keeps the failure where the caller
 * already handles it, with a message that names the real cause.
 */
class UnsupportedDocumentPresigner : DocumentPresignPort {
    override fun presignGet(objectKey: String, ttl: Duration): PresignedDocument? = null
}

/**
 * CDI producer for [DocumentPresignPort], mirroring [DocumentStoreProducer]'s backend switch:
 * the S3/RustFS backend gets a real presigner, every other backend gets
 * [UnsupportedDocumentPresigner]. With the latter,
 * [de.littletrotter.application.mail.InvoiceMailDispatchService] reports "attachments cannot be
 * handed over" at the first click instead of quietly composing a mail without its invoice.
 */
@ApplicationScoped
class DocumentPresignProducer @Inject constructor(
    @param:ConfigProperty(name = "invoice.storage.backend")
    private val storageBackend: Optional<String>,
    @param:ConfigProperty(name = "invoice.s3.endpoint")
    private val s3Endpoint: Optional<String>,
    @param:ConfigProperty(name = "invoice.s3.access-key")
    private val s3AccessKey: Optional<String>,
    @param:ConfigProperty(name = "invoice.s3.secret-key")
    private val s3SecretKey: Optional<String>,
    @param:ConfigProperty(name = "invoice.s3.bucket")
    private val s3Bucket: Optional<String>,
) {

    // See DocumentStoreProducer's own note: Optional#orElse() here is never null, so the
    // former `?.`/`?:` chain around each value was provably dead (unreachable branches).
    @Produces
    fun produce(): DocumentPresignPort {
        val backend = storageBackend.orElse("filesystem").trim().lowercase()
        if (backend != "s3") return UnsupportedDocumentPresigner()
        val endpoint = s3Endpoint.orElse(DEFAULT_ENDPOINT).trim()
        val accessKey = s3AccessKey.orElse(DEFAULT_KEY).trim()
        val secretKey = s3SecretKey.orElse(DEFAULT_KEY).trim()
        val bucket = s3Bucket.orElse(DEFAULT_BUCKET).trim()
        val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
        val client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(credentials)
            .region(REGION)
            .forcePathStyle(true)
            .build()
        val presigner = S3Presigner.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(credentials)
            .region(REGION)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
        return S3DocumentPresigner(client, presigner, bucket)
    }
}

/** Signature filler for an S3-compatible server, not a real AWS placement — see [S3DocumentStore]. */
private val REGION: Region = Region.US_EAST_1
private const val DEFAULT_ENDPOINT = "http://localhost:9000"
private const val DEFAULT_KEY = "rustfsadmin"
private const val DEFAULT_BUCKET = "invoice-documents"
