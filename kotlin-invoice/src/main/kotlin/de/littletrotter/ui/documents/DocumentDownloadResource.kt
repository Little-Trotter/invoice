package de.littletrotter.ui.documents

import de.littletrotter.application.invoice.DocumentChecksumMismatchException
import de.littletrotter.application.invoice.DocumentChecksumVerifier
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.port.inbound.DocumentReproductionQueryPort
import de.littletrotter.application.invoice.port.inbound.DocumentResolution
import de.littletrotter.application.invoice.port.inbound.ResolveInvoiceDocumentUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.i18n.I18nBundleMerger
import de.littletrotter.ui.view.invoice.archiveFileName
import de.littletrotter.ui.view.invoice.getDownloadResourceName
import de.littletrotter.ui.view.invoice.parseGenerationJobObjectKey
import de.littletrotter.ui.view.invoice.parseGenerationJobResultRef
import de.littletrotter.ui.view.invoice.zugferdXmlFileName
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger

/**
 * Stable, authenticated download routes for generation-job artifacts and receipts,
 * replacing the per-cell Vaadin `DownloadHandler` anchors that used to back
 * de.littletrotter.ui.view.invoice.GenerationJobFormatting.buildDownloadAction (used by
 * [de.littletrotter.ui.view.invoice.InvoiceExportPanel] and
 * [de.littletrotter.ui.view.invoice.PrintJobListView]). A `DownloadHandler`'s
 * `/VAADIN/dynamic/resource/<uiId>/<uuid>/` URL only lives while the owning grid cell stays
 * attached; a live-update `refreshAll()` (push or the 2 s poll fallback) rebuilds the cell and
 * kills any URL the browser already holds — the root cause of the reported
 * "Resource is not found" errors. `Anchor(String)` against this resource's own stable,
 * jobId-/receiptId-scoped URL has no such lifetime at all.
 *
 * Same posture as [de.littletrotter.ui.i18n.I18nBundleResource]/
 * [de.littletrotter.ui.settings.SettingsAvailabilityResource]: no `@PermitAll` of its own — every
 * request already passes the deployment's global `authenticated` HTTP policy
 * (application.properties, TS-2) before it ever reaches this class, so an anonymous caller never
 * gets past that layer. [requirePermission] then re-applies the same [AppPermission] the Vaadin
 * routes that render these links already gate on (see [de.littletrotter.ui.security.RouteAccess]'s
 * own KDoc for the added note on these paths), via [AppRoleProvider] directly — the plain-CDI
 * equivalent of what [de.littletrotter.ui.security.NavigationAccessGuard] does for Vaadin
 * navigation, which never runs for a bare JAX-RS request.
 *
 * Bytes are always fetched fresh from [DocumentStore] through [DocumentChecksumVerifier]:
 * a checksum mismatch is recorded in the message centre by the verifier itself and this request
 * answers 409, exactly as the earlier in-app download path already did. There is deliberately no
 * caching layer here (the finding's own "robust over fast" decision, PO 2026-09-18) — every
 * request is a real object-store round trip.
 */
@Path("/documents")
class DocumentDownloadResource @Inject constructor(
    private val generationJobQueryService: GenerationJobQueryService,
    private val receiptPoolService: ReceiptPoolService,
    private val documentStore: DocumentStore,
    private val documentChecksumVerifier: DocumentChecksumVerifier,
    private val appRoleProvider: AppRoleProvider,
    private val resolveInvoiceDocumentUseCase: ResolveInvoiceDocumentUseCase,
    private val documentReproductionQueryPort: DocumentReproductionQueryPort,
) {
    private val log = Logger.getLogger(javaClass)

    @GET
    @Path("/jobs/{jobId}/pdf")
    fun jobPdf(@PathParam("jobId") jobId: Long, @Context headers: HttpHeaders): Response =
        jobDownload(jobId, headers, ::resolvePdf, recoverOriginal = true)

    @GET
    @Path("/jobs/{jobId}/xml")
    fun jobXml(@PathParam("jobId") jobId: Long, @Context headers: HttpHeaders): Response =
        jobDownload(jobId, headers, ::resolveXml)

    @GET
    @Path("/jobs/{jobId}/archive")
    fun jobArchive(@PathParam("jobId") jobId: Long, @Context headers: HttpHeaders): Response =
        jobDownload(jobId, headers, ::resolveArchive)

    @GET
    @Path("/receipts/{receiptId}")
    fun receipt(@PathParam("receiptId") receiptId: Long, @Context headers: HttpHeaders): Response {
        requirePermission(AppPermission.RECEIPTS_AND_STATISTICS, AppPermission.INVOICE_FULL_EDIT)?.let { return it }
        val receiptRow = findReceiptOrNull(receiptId)
        if (receiptRow == null) {
            log.warn("Document download requested for unknown receipt id=$receiptId")
            return notFound(headers)
        }
        // Receipt has no invoice association at all (n:m pool entity, attached to zero or more
        // invoices/items via link tables) — invoiceId/invoiceNumber are genuinely absent here,
        // not merely unthreaded; see DownloadSpec's own KDoc.
        return stream(DownloadSpec(receiptRow.objectKey, receiptRow.fileName, "application/pdf", inline = true), headers)
    }

    // ── generation-job artifacts ─────────────────────────────────────────────

    private fun jobDownload(
        jobId: Long,
        headers: HttpHeaders,
        resolve: (GenerationJob) -> DownloadSpec?,
        recoverOriginal: Boolean = false,
    ): Response {
        requirePermission(AppPermission.INVOICE_JOBS, AppPermission.INVOICE_FULL_EDIT)?.let { return it }
        val spec = resolveDownloadSpec(jobId, resolve) ?: return notFound(headers)
        return if (recoverOriginal) streamCurrentDocument(spec, headers) else stream(spec, headers)
    }

    private fun streamCurrentDocument(spec: DownloadSpec, headers: HttpHeaders): Response {
        // Older jobs keep their own keys and are streamed unchanged; only the invoice's current
        // document is checked and, when its original is gone, restored or re-rendered.
        val invoiceId = spec.invoiceId
        if (invoiceId == null || !isCurrentDocument(spec)) return stream(spec, headers)
        return when (val resolution = resolveInvoiceDocumentUseCase.resolveForInvoice(invoiceId)) {
            is DocumentResolution.Serve -> stream(spec.copy(objectKey = resolution.objectKey), headers)
            is DocumentResolution.Restored -> stream(spec.copy(objectKey = resolution.objectKey), headers)
            is DocumentResolution.RegenerationStarted ->
                textResponse(ACCEPTED, message(headers, "documents.download.regenerationStarted", resolution.jobId))
            is DocumentResolution.StorageUnavailable ->
                textResponse(SERVICE_UNAVAILABLE, message(headers, "documents.download.storageUnavailable"))
            is DocumentResolution.NoDocument -> notFound(headers)
        }
    }

    private fun isCurrentDocument(spec: DownloadSpec): Boolean {
        val invoiceId = spec.invoiceId ?: return false
        return documentReproductionQueryPort.currentDocument(invoiceId)?.objectKey == spec.objectKey
    }

    private fun textResponse(status: Int, text: String): Response =
        Response.status(status).type(MediaType.TEXT_PLAIN).entity(text).build()

    private fun resolveDownloadSpec(jobId: Long, resolve: (GenerationJob) -> DownloadSpec?): DownloadSpec? {
        val job = generationJobQueryService.findByIdWithInvoice(jobId)
        if (job == null) {
            log.warn("Document download requested for unknown generation job id=$jobId")
            return null
        }
        val spec = resolve(job)
        if (spec == null) {
            log.warn("Document download requested for generation job id=$jobId but no matching artifact exists")
        }
        return spec
    }

    private fun resolvePdf(job: GenerationJob): DownloadSpec? {
        if (job.status != GenerationJobStatus.COMPLETED) return null
        val objectKey = parseGenerationJobObjectKey(job.resultRefs)?.takeIf { it.isNotBlank() } ?: return null
        return DownloadSpec(objectKey, getDownloadResourceName(job), "application/pdf", true, job.invoiceId(), job.invoiceNumber())
    }

    private fun resolveXml(job: GenerationJob): DownloadSpec? {
        if (job.status != GenerationJobStatus.COMPLETED) return null
        val objectKey = parseGenerationJobResultRef(job.resultRefs, "zugferdObjectKey")?.takeIf { it.isNotBlank() } ?: return null
        return DownloadSpec(objectKey, zugferdXmlFileName(job), "application/xml", false, job.invoiceId(), job.invoiceNumber())
    }

    private fun resolveArchive(job: GenerationJob): DownloadSpec? {
        if (job.status != GenerationJobStatus.COMPLETED) return null
        val objectKey = parseGenerationJobResultRef(job.resultRefs, "archiveObjectKey")?.takeIf { it.isNotBlank() } ?: return null
        return DownloadSpec(objectKey, archiveFileName(job), "application/gzip", false, job.invoiceId(), job.invoiceNumber())
    }

    /** PO review (2026-09-18) — [DocumentChecksumVerifier.verifiedGet]'s `invoiceId`/
     *  `invoiceNumber` are only ever used to name the invoice in the message-centre ERROR entry a
     *  mismatch records; [jobDownload] already loaded the full [GenerationJob] (via
     *  `findByIdWithInvoice`), so these two small extractors are what actually thread that
     *  traceability through instead of losing it behind a `null, null` call. */
    private fun GenerationJob.invoiceId(): Long? = invoice?.id
    private fun GenerationJob.invoiceNumber(): String? = invoice?.invoiceNumber?.value

    /** [ReceiptPoolService.findById] throws (never returns `null`) for an unknown id — adapted to this
     *  class's own "resolve-or-404" idiom instead of leaking that exception past this boundary. */
    private fun findReceiptOrNull(receiptId: Long): Receipt? =
        try {
            receiptPoolService.findById(receiptId)
        } catch (_: Exception) {
            null
        }

    // ── shared permission/streaming/error plumbing ──────────────────────────

    /** ANY-of semantics, same convention as [de.littletrotter.application.security.RequiresPermission]:
     *  a caller holding at least one of [permissions] is let through. `null` means "proceed". */
    private fun requirePermission(vararg permissions: AppPermission): Response? =
        if (permissions.any(appRoleProvider::hasPermission)) null else Response.status(Response.Status.FORBIDDEN).build()

    /**
     * Fetches [DownloadSpec.objectKey] through [DocumentChecksumVerifier] and streams it
     * with [DownloadSpec.fileName]/[DownloadSpec.contentType]. A checksum mismatch (already
     * recorded in the message centre by the verifier itself, see its own KDoc) answers 409; any
     * other failure to fetch the bytes (missing object, store unavailable) is treated the same as
     * a missing job/receipt row — 404, logged at WARN without a stack trace.
     */
    // Sole catch-all of this flow: DocumentStore is a port with multiple
    // backends (S3 SDK, filesystem) with no shared checked-exception contract -- same precedent
    // as ReceiptUploadService and ReceiptPoolService's own DocumentStore catches.
    private fun stream(spec: DownloadSpec, headers: HttpHeaders): Response {
        val bytes = try {
            documentChecksumVerifier.verifiedGet(documentStore, spec.objectKey, spec.invoiceId, spec.invoiceNumber)
        } catch (_: DocumentChecksumMismatchException) {
            return Response.status(HTTP_CONFLICT)
                .type(MediaType.TEXT_PLAIN)
                .entity(message(headers, "documents.download.checksumMismatch"))
                .build()
        } catch (e: Exception) {
            log.warn("Document download failed for object key '${spec.objectKey}': ${e.message}")
            return notFound(headers)
        }
        val disposition = if (spec.inline) "inline" else "attachment"
        return Response.ok(bytes, spec.contentType)
            .header("Content-Disposition", "$disposition; filename=\"${spec.fileName}\"")
            .build()
    }

    private fun notFound(headers: HttpHeaders): Response =
        Response.status(Response.Status.NOT_FOUND)
            .type(MediaType.TEXT_PLAIN)
            .entity(message(headers, "documents.download.notFound"))
            .build()

    /** Locale DE-default (project-wide convention): English only when the caller's
     *  `Accept-Language` explicitly names it first, German otherwise. */
    private fun message(headers: HttpHeaders, key: String, vararg args: Any): String {
        val requestedLanguage = headers.acceptableLanguages.firstOrNull()?.language?.lowercase()
        val locale = if (requestedLanguage == "en") "en" else "de"
        val pattern = I18nBundleMerger.resolve(locale)[key] ?: return key
        return if (args.isEmpty()) pattern else args.foldIndexed(pattern) { i, text, arg -> text.replace("{$i}", arg.toString()) }
    }

    /** PO review (2026-09-18) — [invoiceId]/[invoiceNumber] are only ever forwarded into
     *  [DocumentChecksumVerifier.verifiedGet], purely so a checksum-mismatch message-centre entry
     *  names the invoice instead of reading "Rechnung ?: ...". Both `null` for the receipts route
     *  (see its own call site's comment) — a [de.littletrotter.domain.masterdata.Receipt] carries
     *  no invoice association to report. */
    private data class DownloadSpec(
        val objectKey: String,
        val fileName: String,
        val contentType: String,
        val inline: Boolean,
        val invoiceId: Long? = null,
        val invoiceNumber: String? = null,
    )

    private companion object {
        private const val HTTP_CONFLICT = 409
        private const val ACCEPTED = 202
        private const val SERVICE_UNAVAILABLE = 503
    }
}
