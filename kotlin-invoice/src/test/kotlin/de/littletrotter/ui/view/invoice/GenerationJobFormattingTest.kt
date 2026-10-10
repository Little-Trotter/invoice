package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.dto.PrintJobOverviewRow
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

/**
 * §2.5.2/§2.5.3 — [formatDescriptorLabel] maps the worker-extracted guideline
 * URN to the exact human label per §2.3's table, and [resolveFormatLabel] falls back to the
 * KoSIT payload's own `profile` for a legacy job that predates this format label.
 */
class GenerationJobFormattingTest {

    // buildDownloadAction(...) builds plain Anchor(String) instances against the document resource's stable
    // /documents/... route (no Vaadin dynamic resource, no live UI needed); MockVaadin stays wired
    // only because a couple of helpers below build Button/Anchor components that still read
    // Vaadin's translation/session plumbing indirectly.
    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    // ── §2.3 table rows ──────────────────────────────────────────────────────

    @Test
    fun `factur-x MINIMUM`() {
        assertEquals("Factur-X 1.0 / ZUGFeRD 2 · MINIMUM", formatDescriptorLabel("urn:factur-x.eu:1p0:minimum"))
    }

    @Test
    fun `factur-x BASIC WL`() {
        assertEquals("Factur-X 1.0 / ZUGFeRD 2 · BASIC WL", formatDescriptorLabel("urn:factur-x.eu:1p0:basicwl"))
    }

    @Test
    fun `factur-x BASIC`() {
        assertEquals("Factur-X 1.0 / ZUGFeRD 2 · BASIC", formatDescriptorLabel("urn:factur-x.eu:1p0:basic"))
    }

    @Test
    fun `factur-x EN16931`() {
        assertEquals("Factur-X 1.0 / ZUGFeRD 2 · EN 16931", formatDescriptorLabel("urn:factur-x.eu:1p0:en16931"))
    }

    @Test
    fun `factur-x EXTENDED`() {
        assertEquals("Factur-X 1.0 / ZUGFeRD 2 · EXTENDED", formatDescriptorLabel("urn:factur-x.eu:1p0:extended"))
    }

    @Test
    fun `zugferd 2p0 profile is uppercased generically`() {
        assertEquals("ZUGFeRD 2.0 · BASIC", formatDescriptorLabel("urn:zugferd.de:2p0:basic"))
    }

    @Test
    fun `xeinkauf xrechnung short URN`() {
        assertEquals("XRechnung 3.0", formatDescriptorLabel("urn:xeinkauf.de:kosit:xrechnung_3.0"))
    }

    @Test
    fun `xoev-de kosit standard xrechnung URN, other version`() {
        assertEquals("XRechnung 2.3", formatDescriptorLabel("urn:xoev-de:kosit:standard:xrechnung_2.3"))
    }

    @Test
    fun `bare EN16931 core URN`() {
        assertEquals("EN 16931", formatDescriptorLabel("urn:cen.eu:en16931:2017"))
    }

    // ── composed URNs (compliant#/conformant#) ──────────────────────────────

    @Test
    fun `composed URN matches on the compliant suffix`() {
        assertEquals(
            "XRechnung 3.0",
            formatDescriptorLabel("urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0"),
        )
    }

    @Test
    fun `composed URN matches on the conformant suffix for EXTENDED`() {
        assertEquals(
            "Factur-X 1.0 / ZUGFeRD 2 · EXTENDED",
            formatDescriptorLabel("urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:extended"),
        )
    }

    // ── generic version derivation ──────────────────────────────────────────

    @Test
    fun `version segment is derived generically, not hardcoded per version`() {
        assertEquals("Factur-X 2.1 / ZUGFeRD 2 · EN 16931", formatDescriptorLabel("urn:factur-x.eu:2p1:en16931"))
    }

    // ── unknown / null ───────────────────────────────────────────────────────

    @Test
    fun `unknown URN is returned verbatim, never labeled unbekannt`() {
        val unknown = "urn:some-future-standard.eu:9p9:whatever"
        assertEquals(unknown, formatDescriptorLabel(unknown))
    }

    @Test
    fun `null input yields null`() {
        assertNull(formatDescriptorLabel(null))
    }

    @Test
    fun `blank input yields null`() {
        assertNull(formatDescriptorLabel("   "))
    }

    // ── §2.5.3 — resolveFormatLabel fallback chain ──────────────────────────

    private fun jobWithResultRefs(resultRefs: String?, kositReport: String? = null): GenerationJob =
        GenerationJob().apply {
            this.resultRefs = resultRefs
            this.kositReport = kositReport
        }

    @Test
    fun `resolveFormatLabel prefers formatGuideline when present`() {
        val job = jobWithResultRefs(
            resultRefs = """{"formatGuideline":"urn:xeinkauf.de:kosit:xrechnung_3.0"}""",
            kositReport = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID, profile = "EN16931")),
        )
        assertEquals("XRechnung 3.0", resolveFormatLabel(job))
    }

    @Test
    fun `resolveFormatLabel falls back to the KoSIT payload profile when formatGuideline is absent`() {
        val job = jobWithResultRefs(
            resultRefs = """{"objectKey":"invoices/x.pdf"}""",
            kositReport = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID, profile = "XRechnung")),
        )
        assertEquals("XRechnung", resolveFormatLabel(job))
    }

    @Test
    fun `resolveFormatLabel renders nothing when neither formatGuideline nor profile exist`() {
        val job = jobWithResultRefs(resultRefs = null, kositReport = null)
        assertNull(resolveFormatLabel(job))
    }

    // ── formatJobTimeRange ────────────────────────────────────────────────────

    @Test
    fun `formatJobTimeRange renders a same-day pair without a date prefix`() {
        val created = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        val finished = OffsetDateTime.parse("2026-08-20T10:05:00Z")
        assertEquals("12:00 → 12:05", formatJobTimeRange(created, finished))
    }

    @Test
    fun `formatJobTimeRange renders a dash when the job has not finished yet`() {
        val created = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        assertEquals("12:00 → —", formatJobTimeRange(created, null))
    }

    @Test
    fun `formatJobTimeRange prefixes the finished time with the date when it lands on the next calendar day`() {
        val created = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        val finished = OffsetDateTime.parse("2026-08-21T09:00:00Z")
        assertEquals("12:00 → 21.08. 11:00", formatJobTimeRange(created, finished))
    }

    @Test
    fun `formatJobTimeRange uses the date prefix when the calendar day only differs in Berlin time`() {
        // Both instants fall on 2026-08-20 in UTC, but 21:30Z is 23:30 Berlin (Aug 20) while
        // 22:30Z is already 00:30 Berlin the next day (Aug 21) — the UTC day never changes.
        val created = OffsetDateTime.parse("2026-08-20T21:30:00Z")
        val finished = OffsetDateTime.parse("2026-08-20T22:30:00Z")
        assertEquals("23:30 → 21.08. 00:30", formatJobTimeRange(created, finished))
    }

    // ── formatJobDuration ─────────────────────────────────────────────────────

    @Test
    fun `formatJobDuration is null for an unfinished job`() {
        val created = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        assertNull(formatJobDuration(created, null))
    }

    @Test
    fun `formatJobDuration is null for a negative span`() {
        val created = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        val finished = OffsetDateTime.parse("2026-08-20T09:59:00Z")
        assertNull(formatJobDuration(created, finished))
    }

    @Test
    fun `formatJobDuration renders whole seconds under a minute`() {
        val created = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        val finished = OffsetDateTime.parse("2026-08-20T10:00:42Z")
        assertEquals("42 s", formatJobDuration(created, finished))
    }

    @Test
    fun `formatJobDuration renders an exact number of minutes without seconds`() {
        val created = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        val finished = OffsetDateTime.parse("2026-08-20T10:05:00Z")
        assertEquals("5 min", formatJobDuration(created, finished))
    }

    @Test
    fun `formatJobDuration renders minutes and seconds`() {
        val created = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        val finished = OffsetDateTime.parse("2026-08-20T10:05:30Z")
        assertEquals("5 min 30 s", formatJobDuration(created, finished))
    }

    @Test
    fun `formatJobDuration renders hours and minutes`() {
        val created = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        val finished = OffsetDateTime.parse("2026-08-20T12:14:00Z")
        assertEquals("2 h 14 min", formatJobDuration(created, finished))
    }

    @Test
    fun `formatJobDuration renders an exact number of hours without minutes`() {
        val created = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        val finished = OffsetDateTime.parse("2026-08-20T12:00:00Z")
        assertEquals("2 h", formatJobDuration(created, finished))
    }

    // ── buildDownloadAction(job) — the entity overload, previously entirely untested ───────────

    private fun jobWith(
        status: GenerationJobStatus,
        resultRefs: String?,
        invoiceNumber: String = "RE-2026-0001",
        jobId: Long = 42L,
    ): GenerationJob =
        GenerationJob().apply {
            this.status = status
            this.resultRefs = resultRefs
            this.id = jobId
            this.invoice = Invoice().apply { this.invoiceNumber = InvoiceNumber(invoiceNumber) }
        }

    private fun tr(key: String): String = key

    @Test
    fun `buildDownloadAction for a completed job renders both PDF and XML buttons when both keys exist`() {
        val job = jobWith(
            GenerationJobStatus.COMPLETED,
            """{"objectKey":"invoices/re-2026-001.pdf","zugferdObjectKey":"invoices/re-2026-001.xml"}""",
        )
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("invoices/re-2026-001.xml")).thenReturn(true)

        val component = buildDownloadAction(job, documentStore, tr = ::tr)

        val row = component as HorizontalLayout
        val buttons = row.children.toList().filterIsInstance<Anchor>().mapNotNull { it.children.toList().filterIsInstance<Button>().firstOrNull() }
        assertEquals(2, buttons.size, "both PDF and XML must render when both object keys resolve")
    }

    @Test
    fun `buildDownloadAction hides the XML button when documentStore reports it missing`() {
        val job = jobWith(
            GenerationJobStatus.COMPLETED,
            """{"objectKey":"invoices/re-2026-001.pdf","zugferdObjectKey":"invoices/re-2026-001.xml"}""",
        )
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("invoices/re-2026-001.xml")).thenReturn(false)

        val component = buildDownloadAction(job, documentStore, tr = ::tr)

        val row = component as HorizontalLayout
        assertEquals(1, row.componentCount, "only the PDF button renders when the XML key does not resolve in the store")
    }

    @Test
    fun `buildDownloadAction treats a documentStore exists failure as XML-not-available, not a crash`() {
        val job = jobWith(
            GenerationJobStatus.COMPLETED,
            """{"objectKey":"invoices/re-2026-001.pdf","zugferdObjectKey":"invoices/re-2026-001.xml"}""",
        )
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("invoices/re-2026-001.xml")).thenThrow(RuntimeException("S3 down"))

        val component = buildDownloadAction(job, documentStore, tr = ::tr)

        val row = component as HorizontalLayout
        assertEquals(1, row.componentCount, "a store failure while probing the XML key must degrade to PDF-only, not throw")
    }

    @Test
    fun `buildDownloadAction renders the dash when the job has no PDF object key yet`() {
        val job = jobWith(GenerationJobStatus.PENDING, resultRefs = null)
        val documentStore: DocumentStore = mock()

        val component = buildDownloadAction(job, documentStore, tr = ::tr)

        assertTrue(component is Span)
        assertEquals("—", (component as Span).text)
    }

    @Test
    fun `buildDownloadAction wraps the row with the format label line when a format is known`() {
        val job = jobWith(
            GenerationJobStatus.COMPLETED,
            """{"objectKey":"invoices/re-2026-001.pdf","formatGuideline":"urn:xeinkauf.de:kosit:xrechnung_3.0"}""",
        )
        val documentStore: DocumentStore = mock()

        val component = buildDownloadAction(job, documentStore, tr = ::tr)

        assertTrue(component is VerticalLayout, "a known format label wraps the button row in an extra caption line")
        val caption = (component as VerticalLayout).children.toList().filterIsInstance<Span>().first { it.text != "—" }
        assertEquals("XRechnung 3.0", caption.text)
    }

    // ── PDF-tab-fix — the PDF anchor must open a viewer tab in both Chrome and Firefox ──────────
    // Root cause (reproduced in Chromium against the dev stack): the PDF anchor carried an
    // attachment-mode DownloadHandler AND an explicit `download` attribute, so Chrome saved the
    // file silently (no tab, no visible action) while only Firefox happened to still open its PDF
    // viewer. The fix mirrors [ReceiptDialog]/[ReceiptPoolView]'s existing "view" anchor:
    // `target="_blank"` with NO `download` attribute — DocumentDownloadResource answers
    // with `Content-Disposition: inline` for the PDF route, so that combination still opens a
    // viewer tab in both browsers even though the anchor is now a plain `href`, not a
    // DownloadHandler.

    private fun pdfAnchorOf(component: com.vaadin.flow.component.Component): Anchor {
        val row = component as HorizontalLayout
        return row.children.toList().filterIsInstance<Anchor>()
            .first { it.children.toList().filterIsInstance<Button>().first().text == "PDF" }
    }

    private fun xmlAnchorOf(component: com.vaadin.flow.component.Component): Anchor {
        val row = component as HorizontalLayout
        return row.children.toList().filterIsInstance<Anchor>()
            .first { it.children.toList().filterIsInstance<Button>().first().text != "PDF" }
    }

    @Test
    fun `buildDownloadAction for a job opens the PDF anchor in a new tab with no download attribute`() {
        val job = jobWith(
            GenerationJobStatus.COMPLETED,
            """{"objectKey":"invoices/re-2026-001.pdf","zugferdObjectKey":"invoices/re-2026-001.xml"}""",
        )
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("invoices/re-2026-001.xml")).thenReturn(true)

        val pdfAnchor = pdfAnchorOf(buildDownloadAction(job, documentStore, tr = ::tr))

        assertEquals("_blank", pdfAnchor.target.orElse(null), "PDF anchor must open a new tab, like Firefox already does")
        assertFalse(pdfAnchor.isDownload, "PDF anchor must not force a silent download (that is Chrome's current bug)")
        assertEquals("documents/jobs/42/pdf", pdfAnchor.href, "ST-A5 — the stable route, not a Vaadin dynamic resource")
    }

    @Test
    fun `buildDownloadAction for a job keeps the XML anchor as a plain attachment download`() {
        val job = jobWith(
            GenerationJobStatus.COMPLETED,
            """{"objectKey":"invoices/re-2026-001.pdf","zugferdObjectKey":"invoices/re-2026-001.xml"}""",
        )
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("invoices/re-2026-001.xml")).thenReturn(true)

        val xmlAnchor = xmlAnchorOf(buildDownloadAction(job, documentStore, tr = ::tr))

        assertTrue(xmlAnchor.isDownload, "XML stays a download artifact, not a viewer-tab affordance")
        assertEquals("documents/jobs/42/xml", xmlAnchor.href)
    }

    @Test
    fun `buildDownloadAction for a row also opens the PDF anchor in a new tab with no download attribute`() {
        val row = rowWith(GenerationJobStatus.COMPLETED, pdfObjectKey = "invoices/re-2026-002.pdf", xmlObjectKey = "invoices/re-2026-002.xml")
        val documentStore: DocumentStore = mock()

        val pdfAnchor = pdfAnchorOf(buildDownloadAction(row, documentStore, tr = ::tr))

        assertEquals("_blank", pdfAnchor.target.orElse(null))
        assertFalse(pdfAnchor.isDownload)
        assertEquals("documents/jobs/7/pdf", pdfAnchor.href, "the row overload links by jobId alone (see rowWith's default)")
    }

    @Test
    fun `ST-A5 the download anchor's URL is byte-identical across independent renders and is never a Vaadin dynamic resource`() {
        // A DownloadHandler-backed Anchor minted a fresh /VAADIN/dynamic/resource/<uiId>/<uuid>/
        // URL on every call — the root cause a live-update refreshAll() exploited (see this
        // module's own KDoc). A plain Anchor(String) is stateless: calling the exact same
        // builder twice, simulating "before a refresh" and "after a refresh", must yield the
        // identical href both times.
        val job = jobWith(GenerationJobStatus.COMPLETED, """{"objectKey":"invoices/re-2026-001.pdf"}""")
        val documentStore: DocumentStore = mock()

        val hrefBeforeRefresh = pdfAnchorOf(buildDownloadAction(job, documentStore, tr = ::tr)).href
        val hrefAfterRefresh = pdfAnchorOf(buildDownloadAction(job, documentStore, tr = ::tr)).href

        assertEquals(hrefBeforeRefresh, hrefAfterRefresh)
        assertEquals("documents/jobs/42/pdf", hrefBeforeRefresh)
        assertFalse(hrefBeforeRefresh.startsWith("VAADIN/dynamic/resource"), "must never be a per-attach Vaadin dynamic resource")
    }

    @Test
    fun `N12 - buildDownloadAction renders only the PDF button when zugferdObjectKey is absent -- no pdf-key derivation`() {
        // Coordinator review (2026-09-11): deriving an XML key from the PDF key was removed. An
        // absent zugferdObjectKey must mean no XML button, never a guessed one.
        val job = jobWith(GenerationJobStatus.COMPLETED, """{"objectKey":"invoices/re-2026-001.pdf"}""")
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("invoices/re-2026-001.xml")).thenReturn(true)

        val component = buildDownloadAction(job, documentStore, tr = ::tr)

        val row = component as HorizontalLayout
        assertEquals(1, row.componentCount, "only the PDF button -- no derived XML button even though a same-named object exists in the store")
    }

    @Test
    fun `N12 - buildDownloadAction renders only the PDF button when zugferdObjectKey is an empty string`() {
        // The rejected-Factur-X-engine outcome: the worker writes zugferdObjectKey="" (no XML at
        // all). This must never fall back to deriving one from the PDF key.
        val job = jobWith(GenerationJobStatus.COMPLETED, """{"objectKey":"invoices/re-2026-001.pdf","zugferdObjectKey":""}""")
        val documentStore: DocumentStore = mock()

        val component = buildDownloadAction(job, documentStore, tr = ::tr)

        val row = component as HorizontalLayout
        assertEquals(1, row.componentCount, "only the PDF button -- an empty zugferdObjectKey must not derive anything")
    }

    // ── buildDownloadAction(row) — the flat PrintJobOverviewRow overload ────────────────────────

    private fun rowWith(status: GenerationJobStatus, pdfObjectKey: String?, xmlObjectKey: String?): PrintJobOverviewRow =
        PrintJobOverviewRow(
            jobId = 7L,
            invoiceId = 1L,
            invoiceNumber = "RE-2026-002",
            recipientName = null,
            status = status,
            createdAt = OffsetDateTime.parse("2026-08-20T10:00:00Z"),
            finishedAt = null,
            attempts = 0,
            lastError = null,
            llmStatus = null,
            pdfObjectKey = pdfObjectKey,
            xmlObjectKey = xmlObjectKey,
        )

    @Test
    fun `buildDownloadAction for a row never probes the store - presence of the xml key alone is enough`() {
        val row = rowWith(GenerationJobStatus.COMPLETED, pdfObjectKey = "invoices/re-2026-002.pdf", xmlObjectKey = "invoices/re-2026-002.xml")
        val documentStore: DocumentStore = mock()

        val component = buildDownloadAction(row, documentStore, tr = ::tr)

        val hLayout = component as HorizontalLayout
        assertEquals(2, hLayout.componentCount, "PQ-4: the row overload must not call DocumentStore.exists at render time")
    }

    @Test
    fun `buildDownloadAction for a row with no xml key renders only PDF`() {
        val row = rowWith(GenerationJobStatus.COMPLETED, pdfObjectKey = "invoices/re-2026-002.pdf", xmlObjectKey = null)
        val documentStore: DocumentStore = mock()

        val component = buildDownloadAction(row, documentStore, tr = ::tr)

        val hLayout = component as HorizontalLayout
        assertEquals(1, hLayout.componentCount)
    }

    @Test
    fun `buildDownloadAction for an unfinished row's job renders the dash`() {
        val row = rowWith(GenerationJobStatus.PENDING, pdfObjectKey = null, xmlObjectKey = null)
        val documentStore: DocumentStore = mock()

        val component = buildDownloadAction(row, documentStore, tr = ::tr)

        assertTrue(component is Span)
    }

    // ── The archive package download button ───────────────────────────────────────

    private fun archiveAnchorOf(component: com.vaadin.flow.component.Component): Anchor? {
        val row = component as HorizontalLayout
        return row.children.toList().filterIsInstance<Anchor>()
            .firstOrNull { it.children.toList().filterIsInstance<Button>().first().text == "TAR.GZ" }
    }

    @Test
    fun `RS-9 - buildDownloadAction renders a third TAR-GZ button when archiveObjectKey is present`() {
        val job = jobWith(
            GenerationJobStatus.COMPLETED,
            """{"objectKey":"invoices/re-2026-001.pdf","zugferdObjectKey":"invoices/re-2026-001.xml","archiveObjectKey":"invoices/re-2026-001.tar.gz"}""",
        )
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("invoices/re-2026-001.xml")).thenReturn(true)

        val component = buildDownloadAction(job, documentStore, tr = ::tr)

        val row = component as HorizontalLayout
        assertEquals(3, row.componentCount, "PDF, XML and TAR.GZ must all render when all three keys resolve")
        assertNotNull(archiveAnchorOf(component))
    }

    @Test
    fun `RS-9 - buildDownloadAction renders no third button when archiveObjectKey is absent (draft or legacy job)`() {
        val job = jobWith(
            GenerationJobStatus.COMPLETED,
            """{"objectKey":"invoices/re-2026-001.pdf","zugferdObjectKey":"invoices/re-2026-001.xml"}""",
        )
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("invoices/re-2026-001.xml")).thenReturn(true)

        val component = buildDownloadAction(job, documentStore, tr = ::tr)

        val row = component as HorizontalLayout
        assertEquals(2, row.componentCount, "no archive package exists for this job — only PDF and XML render")
        assertNull(archiveAnchorOf(component))
    }

    @Test
    fun `RS-9 - buildDownloadAction renders no archive button at all for a job with no completed export`() {
        val job = jobWith(GenerationJobStatus.PENDING, resultRefs = null)
        val documentStore: DocumentStore = mock()

        val component = buildDownloadAction(job, documentStore, tr = ::tr)

        assertTrue(component is Span, "a not-yet-completed job renders the dash, no buttons at all")
    }

    @Test
    fun `RS-9 - the archive anchor is a plain attachment download, not opened inline like the PDF`() {
        val job = jobWith(
            GenerationJobStatus.COMPLETED,
            """{"objectKey":"invoices/re-2026-001.pdf","zugferdObjectKey":"invoices/re-2026-001.xml","archiveObjectKey":"invoices/re-2026-001.tar.gz"}""",
        )
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("invoices/re-2026-001.xml")).thenReturn(true)

        val archiveAnchor = archiveAnchorOf(buildDownloadAction(job, documentStore, tr = ::tr))

        assertNotNull(archiveAnchor)
        assertTrue(archiveAnchor!!.isDownload, "the archive package must download as a plain attachment, like the XML button")
    }

    @Test
    fun `RS-9 - buildDownloadAction for a row overload never renders a third button (resultRefs is not carried by the flat row)`() {
        val row = rowWith(GenerationJobStatus.COMPLETED, pdfObjectKey = "invoices/re-2026-002.pdf", xmlObjectKey = "invoices/re-2026-002.xml")
        val documentStore: DocumentStore = mock()

        val component = buildDownloadAction(row, documentStore, tr = ::tr)

        val hLayout = component as HorizontalLayout
        assertEquals(2, hLayout.componentCount, "PQ-4's flat row never carries an archiveObjectKey — no third button")
    }

    @Test
    fun `RS-9 - archiveFileName derives the invoice-number-based filename with a tar gz extension`() {
        val job = jobWith(GenerationJobStatus.COMPLETED, resultRefs = null, invoiceNumber = "RE-2026-0042", jobId = 99L)

        assertEquals("RE-2026-0042.tar.gz", archiveFileName(job))
    }

    @Test
    fun `RS-9 - archiveFileName falls back to job-id when the invoice number is unassigned`() {
        val job = jobWith(GenerationJobStatus.COMPLETED, resultRefs = null, invoiceNumber = "", jobId = 77L)

        assertEquals("job-77.tar.gz", archiveFileName(job))
    }
}
