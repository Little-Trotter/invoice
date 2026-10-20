package de.littletrotter.infrastructure.storage

import de.littletrotter.shared.DocumentStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.file.Paths
import java.util.Optional

/**
 * CDI producer for [DocumentStore].
 *
 * Defaults to a local-filesystem store under `invoice.storage.directory`
 * (default: `~/.little-trotter/receipts`).  Override the property to point
 * to any writable directory.  To swap in RustFS/S3 (via kotlin-xpdf), replace
 * this producer with a delegating implementation.
 */
@ApplicationScoped
class DocumentStoreProducer @Inject constructor(
    @param:ConfigProperty(name = "invoice.storage.backend")
    private val storageBackend: Optional<String>,
    @param:ConfigProperty(name = "invoice.storage.directory")
    private val storageDirectory: Optional<String>,
    @param:ConfigProperty(name = "invoice.s3.endpoint")
    private val s3Endpoint: Optional<String>,
    @param:ConfigProperty(name = "invoice.s3.access-key")
    private val s3AccessKey: Optional<String>,
    @param:ConfigProperty(name = "invoice.s3.secret-key")
    private val s3SecretKey: Optional<String>,
    @param:ConfigProperty(name = "invoice.s3.bucket")
    private val s3Bucket: Optional<String>,
) {

    // No `?.`/`?:` around the Optional#orElse() results below: `Optional<String>` (Java's,
    // populated by MicroProfile Config) is either empty (then `orElse` returns the given non-null
    // literal default) or holds the non-null configured value; it can never hold a null reference,
    // so a defensive null-chain here was provably dead code (unreachable branches, never exercised
    // by any config value) rather than a real edge case worth testing.
    //
    // `backend in LOCAL_BACKEND_NAMES`/plain `==`, not a
    // `when(backend) { "filesystem", "local" -> ...}`: for a fixed String set, Kotlin compiles a
    // multi-arm `when` into a hashCode-bucketed switch with its own per-bucket collision-guard
    // branches (a synthetic "hash matched but the string didn't" branch per arm) that no real
    // config value can ever reach — only a deliberately hash-colliding string could, which is not
    // a scenario worth engineering a test around.
    @Produces
    fun produce(): DocumentStore {
        val backend = storageBackend.orElse("filesystem").trim().lowercase()
        require(backend in LOCAL_BACKEND_NAMES || backend == "s3") { "Unsupported invoice.storage.backend: $backend" }
        return if (backend in LOCAL_BACKEND_NAMES) createLocalStore() else createS3Store()
    }

    private fun createLocalStore(): DocumentStore {
        val dir = storageDirectory.filter { it.isNotBlank() }.orElse("").trim()
        val path = if (dir.isEmpty()) {
            Paths.get(System.getProperty("user.home"), ".little-trotter", "receipts")
        } else {
            Paths.get(dir)
        }
        return LocalFileSystemDocumentStore(path).also { it.init() }
    }

    private fun createS3Store(): DocumentStore {
        val endpoint = s3Endpoint.orElse("http://localhost:9000").trim()
        val accessKey = s3AccessKey.orElse("rustfsadmin").trim()
        val secretKey = s3SecretKey.orElse("rustfsadmin").trim()
        val bucket = s3Bucket.orElse("invoice-documents").trim()
        return S3DocumentStore(endpoint, accessKey, secretKey, bucket)
    }
}

private val LOCAL_BACKEND_NAMES = setOf("filesystem", "local")
