package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.FacturXEngine
import de.littletrotter.facturx.HttpFacturXEngineClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * CDI producer for [FacturXEngine]/[HttpFacturXEngineClient]. Before the module split,
 * [HttpFacturXEngineClient] was itself an `@ApplicationScoped` bean, `@Inject`-resolved
 * straight into [PdfExportServiceImpl]. Verified live: Quarkus's build-time ArC
 * bean discovery does not find an `@ApplicationScoped` bean declared in a *dependency* module
 * (`kotlin-facturx`) the way it finds one in this module's own Jandex index — `mvn verify` failed
 * with `UnsatisfiedResolutionException` when the annotations stayed on the class itself over
 * there.
 *
 * The fallback this project's own KDoc already documents for exactly this situation
 * ([de.littletrotter.shared.AbstractS3DocumentStore]'s class doc, mirrored by this module's own
 * [S3DocumentStore]): the shared class carries no CDI annotations and takes its config as plain
 * constructor parameters; a small `@ApplicationScoped` wrapper local to the *consuming* module
 * reads the real config and constructs it. [S3DocumentStore] does that by subclassing an
 * `abstract` shared base; [HttpFacturXEngineClient] is not designed as an extension point (a
 * concrete class implementing the [FacturXEngine] port directly), so this uses a CDI `@Produces`
 * factory method instead — the standard alternative for exactly this shape, and no less "the same
 * pattern": both put config resolution and CDI wiring in the consuming module, leaving the shared
 * class itself CDI-unaware.
 */
@ApplicationScoped
class FacturXEngineProducer @Inject constructor(
    // Same `@param:` reasoning as this project's other CDI-config classes (see
    // [S3DocumentStore]'s own KDoc): pins the annotation to the constructor parameter only, which
    // is all CDI ever reads to resolve this value.
    @param:ConfigProperty(name = "facturx.engine.base-url", defaultValue = "http://localhost:8000")
    private val baseUrl: String,
    @param:ConfigProperty(name = "facturx.engine.timeout.connect", defaultValue = "5000")
    private val connectTimeoutMs: Long,
    @param:ConfigProperty(name = "facturx.engine.timeout.request", defaultValue = "30000")
    private val requestTimeoutMs: Long,
    // One truth, 25 MB, cross-referenced with docker/compose/facturx.yml's facturx-engine-api
    // MAX_UPLOAD_SIZE_MB; the two must not drift independently. This annotation default only
    // applies when application.properties itself doesn't set the property (it does, at the same
    // value) — kept in sync as belt-and-braces.
    @param:ConfigProperty(name = "facturx.engine.max-upload-mb", defaultValue = "25")
    private val maxUploadMb: Long,
) {

    /** The single real [FacturXEngine] bean the container hands to [PdfExportServiceImpl]'s
     *  `facturXEngine` constructor parameter — `@ApplicationScoped` on the producer method itself
     *  (not just this producer class) so the produced [HttpFacturXEngineClient] is a genuine
     *  container-wide singleton, the same lifecycle it had before the module split. */
    @Produces
    @ApplicationScoped
    fun produce(): FacturXEngine = HttpFacturXEngineClient(
        baseUrl = baseUrl,
        connectTimeoutMs = connectTimeoutMs,
        requestTimeoutMs = requestTimeoutMs,
        maxUploadMb = maxUploadMb,
    )
}
