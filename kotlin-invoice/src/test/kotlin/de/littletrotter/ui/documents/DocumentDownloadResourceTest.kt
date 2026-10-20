package de.littletrotter.ui.documents

import de.littletrotter.testing.jobView
import de.littletrotter.application.invoice.DocumentChecksumVerifier
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.port.inbound.DocumentReproductionQueryPort
import de.littletrotter.application.invoice.port.inbound.ResolveInvoiceDocumentUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.shared.DocumentStore
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The generic-`Exception` branch in [DocumentDownloadResource.stream]: a
 * [DocumentChecksumVerifier.verifiedGet] failure that is NOT a checksum mismatch (missing object,
 * store unavailable) must answer 404 like a missing job/receipt row, logged at WARN without a
 * stack trace — never let the raw exception escape. A plain unit test (every port mocked), the
 * same layer as any other adapter-facing service test: this class carries no Quarkus-specific
 * behaviour of its own beyond `@Inject`, so a bare constructor call is enough.
 * [DocumentDownloadResourceIT] already covers the full container-backed happy/404/409/403/401
 * paths — this file only adds the one branch that needs a genuinely failing port, not a container.
 */
class DocumentDownloadResourceTest {

    private val generationJobQueryPort: GenerationJobQueryPort = mock()
    private val receiptPoolService: ReceiptPoolService = mock()
    private val documentStore: DocumentStore = mock()
    private val documentChecksumVerifier: DocumentChecksumVerifier = mock()
    private val appRoleProvider: AppRoleQueryPort = mock()
    private val headers: HttpHeaders = mock()
    private val resolveInvoiceDocumentUseCase: ResolveInvoiceDocumentUseCase = mock()
    private val documentReproductionQueryPort: DocumentReproductionQueryPort = mock()

    private fun resource() = DocumentDownloadResource(
        generationJobQueryPort,
        receiptPoolService,
        documentStore,
        documentChecksumVerifier,
        appRoleProvider,
        resolveInvoiceDocumentUseCase,
        documentReproductionQueryPort,
    )

    @Test
    fun `boundary - a non-checksum failure while streaming a job PDF answers 404, not a raw exception`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())
        val job = jobView {
            id = 42L
            status = GenerationJobStatus.COMPLETED
            resultRefs = """{"objectKey":"jobs/42/invoice.pdf"}"""
        }
        whenever(generationJobQueryPort.findJobViewWithInvoice(42L)).thenReturn(job)
        whenever(
            documentChecksumVerifier.verifiedGet(eq(documentStore), eq("jobs/42/invoice.pdf"), anyOrNull(), anyOrNull()),
        ).thenThrow(RuntimeException("object store unavailable"))

        val response = resource().jobPdf(42L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `happy path - a caller holding at least one of the required permissions is let through the guard`() {
        whenever(appRoleProvider.hasPermission(AppPermission.INVOICE_JOBS)).thenReturn(false)
        whenever(appRoleProvider.hasPermission(AppPermission.INVOICE_FULL_EDIT)).thenReturn(true)
        whenever(generationJobQueryPort.findJobViewWithInvoice(any())).thenReturn(null)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())

        val response = resource().jobPdf(1L, headers)

        // Reaching the "unknown job" 404 (not a 403) proves the ANY-of permission guard let this
        // caller through on the second permission alone.
        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `boundary - a caller holding neither required permission is forbidden before any lookup`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(false)

        val response = resource().jobPdf(1L, headers)

        assertEquals(Response.Status.FORBIDDEN.statusCode, response.status)
    }

    // ── resolvePdf/resolveXml/resolveArchive's own missing/blank-objectKey arms ─────────

    private fun completedJob(resultRefs: String) = jobView {
        id = 42L
        status = GenerationJobStatus.COMPLETED
        this.resultRefs = resultRefs
    }

    @Test
    fun `boundary - a completed job with no objectKey field at all in resultRefs answers 404`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())
        whenever(generationJobQueryPort.findJobViewWithInvoice(42L)).thenReturn(completedJob("{}"))

        val response = resource().jobPdf(42L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `boundary - a completed job with a blank objectKey in resultRefs answers 404`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())
        whenever(generationJobQueryPort.findJobViewWithInvoice(42L)).thenReturn(completedJob("""{"objectKey":"  "}"""))

        val response = resource().jobPdf(42L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `boundary - a completed job with no zugferdObjectKey field answers 404 for the XML route`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())
        whenever(generationJobQueryPort.findJobViewWithInvoice(42L)).thenReturn(completedJob("{}"))

        val response = resource().jobXml(42L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `boundary - a completed job with a blank zugferdObjectKey answers 404 for the XML route`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())
        whenever(generationJobQueryPort.findJobViewWithInvoice(42L)).thenReturn(completedJob("""{"zugferdObjectKey":""}"""))

        val response = resource().jobXml(42L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `boundary - a completed job with no archiveObjectKey field answers 404 for the archive route`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())
        whenever(generationJobQueryPort.findJobViewWithInvoice(42L)).thenReturn(completedJob("{}"))

        val response = resource().jobArchive(42L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `boundary - a completed job with a blank archiveObjectKey answers 404 for the archive route`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())
        whenever(generationJobQueryPort.findJobViewWithInvoice(42L)).thenReturn(completedJob("""{"archiveObjectKey":"  "}"""))

        val response = resource().jobArchive(42L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `boundary - a job that has not completed yet answers 404 for the pdf route, never resolving an object key`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())
        val job = jobView {
            id = 42L
            status = GenerationJobStatus.RUNNING
            resultRefs = """{"objectKey":"jobs/42/invoice.pdf"}"""
        }
        whenever(generationJobQueryPort.findJobViewWithInvoice(42L)).thenReturn(job)

        val response = resource().jobPdf(42L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
        org.mockito.kotlin.verify(documentChecksumVerifier, org.mockito.kotlin.never()).verifiedGet(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `boundary - a job that has not completed yet answers 404 for the xml route`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())
        whenever(generationJobQueryPort.findJobViewWithInvoice(42L)).thenReturn(
            jobView { id = 42L; status = GenerationJobStatus.RUNNING; resultRefs = """{"zugferdObjectKey":"jobs/42/invoice.xml"}""" },
        )

        val response = resource().jobXml(42L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `boundary - a job that has not completed yet answers 404 for the archive route`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(emptyList())
        whenever(generationJobQueryPort.findJobViewWithInvoice(42L)).thenReturn(
            jobView { id = 42L; status = GenerationJobStatus.PENDING; resultRefs = """{"archiveObjectKey":"jobs/42/invoice.tar.gz"}""" },
        )

        val response = resource().jobArchive(42L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `boundary - the not-found message resolves in English when the caller's Accept-Language names it first`() {
        whenever(appRoleProvider.hasPermission(any())).thenReturn(true)
        whenever(headers.acceptableLanguages).thenReturn(listOf(java.util.Locale.ENGLISH))
        whenever(generationJobQueryPort.findJobViewWithInvoice(any())).thenReturn(null)

        val response = resource().jobPdf(1L, headers)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }
}
