package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.html.ListItem
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteDocument
import de.littletrotter.application.quote.port.inbound.QuoteDocumentContent
import de.littletrotter.application.quote.port.inbound.QuoteDocumentFileQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteValidationReport
import de.littletrotter.application.quote.port.inbound.QuoteValidationReportQueryPort
import de.littletrotter.application.quote.port.inbound.RequestQuoteDocumentUseCase
import de.littletrotter.ui.i18n.TranslationProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

class QuoteDocumentsPanelKaribuTest {

    private val request: RequestQuoteDocumentUseCase = mock()
    private val documents: QuoteDocumentQueryPort = mock()
    private val files: QuoteDocumentFileQueryPort = mock()
    private val reports: QuoteValidationReportQueryPort = mock()
    private val host = object : TranslationProvider {
        private val anchor = Div()
        override fun getComponent() = anchor
    }
    private val at = Instant.parse("2026-10-02T08:00:00Z")

    private val ready = QuoteDocument(
        "d-1", QuoteDocumentMark.REVIEW, QuoteDocumentStatus.COMPLETED, at, at, "h", hasValidationReport = true, hasXml = true,
    )
    private val pending = QuoteDocument("d-2", QuoteDocumentMark.DRAFT, QuoteDocumentStatus.REQUESTED, at)
    private val failed = QuoteDocument(
        "d-3", QuoteDocumentMark.DISPATCH, QuoteDocumentStatus.FAILED, at, at, error = "Renderer nicht erreichbar",
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun panel(mayRequest: Boolean = true, vararg items: QuoteDocument = arrayOf(ready, pending, failed)): QuoteDocumentsPanel {
        whenever(documents.documents("q-1")).thenReturn(items.toList())
        return QuoteDocumentsPanel(host, QuoteDocumentPorts(request, documents, files, reports, mock(), mock())).also {
            UI.getCurrent().add(it)
            it.bind("q-1", QuoteStatus.REVIEW, mayRequest)
        }
    }

    private fun rows(panel: QuoteDocumentsPanel) = shown(panel)

    private fun cell(panel: QuoteDocumentsPanel, row: Int, key: String) = panel.grid._getCellComponent(row, key)

    private fun anchorIds(panel: QuoteDocumentsPanel) =
        rows(panel).indices.flatMap { cell(panel, it, "download")._find<Anchor>() }.mapNotNull { it.id.orElse(null) }

    private fun shown(panel: QuoteDocumentsPanel) = (panel.grid.dataProvider as ListDataProvider<QuoteDocument>).items.toList()

    @Test
    fun `an empty list shows the empty state and no pending hint`() {
        val panel = panel(true)
        assertEquals(3, shown(panel).size)
        whenever(documents.documents("q-1")).thenReturn(emptyList())
        panel.reload()

        assertTrue(panel.emptyState.isVisible)
        assertFalse(panel.pendingHint.isVisible)
    }

    @Test
    fun `jobs show as pending ready or failed with the pending hint`() {
        val panel = panel()
        assertTrue(panel.pendingHint.isVisible)
        assertFalse(panel.emptyState.isVisible)
        val glyphs = rows(panel).indices.flatMap { cell(panel, it, "status")._find<Span>() }
            .filter { it.classNames.contains("status-glyph") }.map { it.element.getAttribute("aria-label") }
        assertTrue("Bereit" in glyphs && "In Arbeit" in glyphs && glyphs.any { it.startsWith("Fehlgeschlagen") }, glyphs.toString())
    }

    @Test
    fun `the columns match the e-invoice job table`() {
        val panel = panel()
        val headers = panel.grid.columns.map { it.key to it.headerText }
        val invoice = listOf("type", "status", "createdAt", "finishedAt", "download", "validation")
            .map { host.tr("invoiceDetail.jobs.column.$it") }

        assertEquals(listOf("position", "type", "status", "requestedAt", "completedAt", "download", "validation"), headers.map { it.first })
        assertEquals(listOf("Nr.", "Typ", "Status", "Erstellt am", "Beendet am", "Download", "Validierung"), headers.map { it.second })
        assertEquals(invoice, headers.map { it.second }.drop(1))
    }

    @Test
    fun `the type column tells pdf from pdf with xml`() {
        val panel = panel(true, ready, pending)

        assertEquals(listOf("PDF", "PDF + XML"), shown(panel).indices.map { panel.grid._getFormattedRow(it)[1] })
    }

    @Test
    fun `the stand shows as a symbol - draft and review yes, dispatch no`() {
        val dispatch = ready.copy(id = "d-5", mark = QuoteDocumentMark.DISPATCH)
        val panel = panel(true, ready, pending, dispatch)
        val chipsOf = { id: String ->
            val row = shown(panel).indexOfFirst { it.id == id }
            cell(panel, row, "status")._find<Span>().filter { it.classNames.contains("status-glyph") }.map { it.element.getAttribute("aria-label") }
        }

        assertEquals(listOf("Bereit", host.tr("quote.documents.reviewChip.tooltip")), chipsOf("d-1"))
        assertEquals(listOf("In Arbeit", host.tr("invoiceDetail.jobs.draftChip.tooltip")), chipsOf("d-2"))
        assertEquals(listOf("Bereit"), chipsOf("d-5"))
    }

    @Test
    fun `only a finished copy offers the pdf and the xml only when it has one`() {
        val panel = panel(true, ready, pending, failed, ready.copy(id = "d-4", hasXml = false, hasValidationReport = false))
        assertEquals(
            listOf("quote.documents.pdf-d-4", "quote.documents.pdf-d-1", "quote.documents.xml-d-1"),
            anchorIds(panel).filter { it.endsWith("d-1") || it.endsWith("d-4") },
        )
    }

    @Test
    fun `requesting asks for the stand matching the status and reloads`() {
        whenever(request.request("q-1", QuoteDocumentMark.REVIEW)).thenReturn("d-9")
        val panel = panel()
        panel.requestButton._click()

        verify(request).request("q-1", QuoteDocumentMark.REVIEW)
        verify(documents, times(2)).documents("q-1")
        assertEquals(de.littletrotter.ui.i18n.UiTranslations.tr("message.saved"), panel.requestButton.text)
        assertTrue(notificationTexts().isEmpty())
    }

    @Test
    fun `a draft offer requests a draft copy`() {
        whenever(request.request("q-1", QuoteDocumentMark.DRAFT)).thenReturn("d-8")
        val panel = QuoteDocumentsPanel(host, QuoteDocumentPorts(request, documents, files, reports, mock(), mock()))
        UI.getCurrent().add(panel)
        panel.bind("q-1", QuoteStatus.DRAFT, true)
        panel.requestButton._click()

        verify(request).request("q-1", QuoteDocumentMark.DRAFT)
    }

    @Test
    fun `a refused request is explained and nothing reloads`() {
        whenever(request.request(any(), any())).thenThrow(QuoteServiceException("Nur im Status Prüfung möglich"))
        val panel = panel()
        panel.requestButton._click()

        assertTrue(notificationTexts().any { it.contains("Nur im Status Prüfung möglich") })
        verify(documents).documents("q-1")
    }

    @Test
    fun `there is no copy select and only draft and review offers can request`() {
        val expected = mapOf(
            QuoteStatus.DRAFT to true,
            QuoteStatus.REVIEW to true,
            QuoteStatus.SENT to false,
            QuoteStatus.ACCEPTED to false,
            QuoteStatus.CANCELLED to false,
        )
        expected.forEach { (status, visible) ->
            val panel = QuoteDocumentsPanel(host, QuoteDocumentPorts(request, documents, files, reports, mock(), mock()))
            UI.getCurrent().add(panel)
            panel.bind("q-1", status, true)

            assertEquals(visible, panel.requestButton.isVisible, status.name)
            assertTrue(panel._find<com.vaadin.flow.component.select.Select<*>>().isEmpty(), status.name)
        }
    }

    @Test
    fun `readers cannot request`() {
        val panel = panel(mayRequest = false)

        assertFalse(panel.requestButton.isVisible)
        verify(request, never()).request(any(), any())
    }

    @Test
    fun `there is no refresh button`() {
        val panel = panel()

        assertTrue(panel._find<Button>().none { it.text == "Aktualisieren" })
    }

    @Test
    fun `the latest job is one row above the table, which lists every job`() {
        val panel = panel(true, ready)

        assertTrue(panel.currentRow.isVisible)
        assertTrue(visibleText(panel.currentRow).contains("Letzter E-Angebots-Auftrag: Nr. 1"))
        assertTrue(panel.currentRow._find<Span>().any { it.element.getAttribute("aria-label") == "Bereit" })
        assertEquals(listOf("d-1"), shown(panel).map { it.id })
        assertTrue(panel.grid.isVisible)
    }

    @Test
    fun `requesting is the primary action only while the stand has no current job`() {
        val panel = panel(true, ready)
        assertFalse(panel.requestButton.themeNames.contains("primary"))
        assertTrue(panel.requestButton.themeNames.contains("tertiary"))

        whenever(documents.documents("q-1")).thenReturn(listOf(ready.copy(mark = QuoteDocumentMark.DRAFT), failed))
        panel.reload()
        assertTrue(panel.requestButton.themeNames.contains("primary"))

        whenever(documents.documents("q-1")).thenReturn(listOf(ready.copy(mark = QuoteDocumentMark.DISPATCH)))
        panel.reload()
        assertTrue(panel.requestButton.themeNames.contains("primary"))

        val empty = panel(true)
        whenever(documents.documents("q-1")).thenReturn(emptyList())
        empty.reload()
        assertTrue(empty.requestButton.themeNames.contains("primary"))
    }

    @Test
    fun `the newest job is the first row and the first column counts the jobs in request order without showing an id`() {
        val newest = ready.copy(id = "d-new", requestedAt = at.plusSeconds(20))
        val oldest = ready.copy(id = "d-old", requestedAt = at.minusSeconds(60))
        val middle = ready.copy(id = "d-mid", requestedAt = at)
        val panel = panel(true, newest, oldest, middle)

        assertEquals(listOf("3", "2", "1"), shown(panel).indices.map { panel.grid._getFormattedRow(it)[0] })
        assertTrue(shown(panel).indices.none { row -> panel.grid._getFormattedRow(row).any { it.startsWith("d-") } })
        assertTrue(visibleText(panel.currentRow).contains("Nr. 3"))
    }

    @Test
    fun `the id of a job is only the tooltip of its download cell`() {
        val panel = panel(true, ready, pending)

        assertEquals(listOf("d-2", "d-1"), shown(panel).indices.map { cell(panel, it, "download").element.getAttribute("title") })
    }

    @Test
    fun `older and failed jobs stay in the table`() {
        val panel = panel(true, ready, ready.copy(id = "d-old", requestedAt = at.minusSeconds(60)), failed)

        assertEquals(listOf("d-3", "d-1", "d-old"), shown(panel).map { it.id })
        assertTrue(panel.grid.isVisible)
    }

    @Test
    fun `a pending copy makes the panel poll by itself and a finished one stops it`() {
        val ui = UI.getCurrent()
        val before = ui.pollInterval
        val panel = panel(true, pending)

        assertEquals(de.littletrotter.ui.view.invoice.DETAIL_POLL_INTERVAL_MS, ui.pollInterval)

        whenever(documents.documents("q-1")).thenReturn(listOf(ready))
        panel.reload()

        assertEquals(before, ui.pollInterval)
        assertFalse(panel.pendingHint.isVisible)
    }

    @Test
    fun `a poll result tells the owner so it can redraw, a failed poll does not`() {
        var told = 0
        val panel = QuoteDocumentsPanel(host, QuoteDocumentPorts(request, documents, files, reports, mock(), mock())) { told++ }
        whenever(documents.documents("q-1")).thenReturn(listOf(pending))
        UI.getCurrent().add(panel)
        panel.bind("q-1", QuoteStatus.REVIEW, true)

        panel.applyPolled(null)
        assertEquals(0, told)

        panel.applyPolled(listOf(ready))
        assertEquals(1, told)
        assertFalse(panel.pendingHint.isVisible)
    }

    @Test
    fun `a panel without a pending copy never polls and detaching stops a running poll`() {
        val ui = UI.getCurrent()
        val before = ui.pollInterval
        panel(true, ready)
        assertEquals(before, ui.pollInterval)

        val polling = panel(true, pending)
        assertEquals(de.littletrotter.ui.view.invoice.DETAIL_POLL_INTERVAL_MS, ui.pollInterval)
        ui.remove(polling)
        assertEquals(before, ui.pollInterval)
    }

    @Test
    fun `a panel bound before it is attached starts polling on attach`() {
        whenever(documents.documents("q-1")).thenReturn(listOf(pending))
        val panel = QuoteDocumentsPanel(host, QuoteDocumentPorts(request, documents, files, reports, mock(), mock()))
        panel.bind("q-1", QuoteStatus.REVIEW, true)
        val before = UI.getCurrent().pollInterval

        UI.getCurrent().add(panel)

        assertEquals(de.littletrotter.ui.view.invoice.DETAIL_POLL_INTERVAL_MS, UI.getCurrent().pollInterval)
        UI.getCurrent().remove(panel)
        assertEquals(before, UI.getCurrent().pollInterval)
    }

    @Test
    fun `an unreachable component leaves the list empty and says why`() {
        whenever(documents.documents("q-1")).thenThrow(QuoteServiceException("Angebots-Dienst nicht erreichbar"))
        val panel = QuoteDocumentsPanel(host, QuoteDocumentPorts(request, documents, files, reports, mock(), mock()))
        UI.getCurrent().add(panel)
        panel.bind("q-1", QuoteStatus.REVIEW, true)

        assertTrue(panel.emptyState.isVisible)
        assertTrue(notificationTexts().any { it.contains("Angebots-Dienst nicht erreichbar") })
    }

    @Test
    fun `the pdf is fetched on demand and named by the component`() {
        whenever(files.pdf("q-1", "d-1")).thenReturn(QuoteDocumentContent("AN-1.pdf", byteArrayOf(1, 2)))
        val panel = panel()

        val response = panel.pdfResponse(ready)

        verify(files).pdf("q-1", "d-1")
        assertEquals("AN-1.pdf", response.fileName)
        assertEquals("application/pdf", response.contentType)
        assertArrayEquals(byteArrayOf(1, 2), response.inputStream.readAllBytes())
    }

    @Test
    fun `the pdf opens inline in a new tab and the xml downloads`() {
        val panel = panel(true, ready)
        val anchors = cell(panel, 0, "download")._find<Anchor>()
        val pdf = anchors.first { it.id.orElse("").startsWith("quote.documents.pdf") }
        val xml = anchors.first { it.id.orElse("").startsWith("quote.documents.xml") }

        assertEquals(listOf(pdf, xml), anchors)
        assertEquals("_blank", pdf.element.getAttribute("target"))
        assertFalse(pdf.element.hasAttribute("download"))
        assertTrue(pdf.href.isNotBlank())
        assertEquals("PDF ansehen", pdf._get<Button>().element.getAttribute("title"))
        assertTrue(xml.element.hasAttribute("download"))
        assertFalse(xml.element.hasAttribute("target"))
    }

    @Test
    fun `the xml is offered as xml and a missing one or a failure becomes an error answer`() {
        whenever(files.xml("q-1", "d-1")).thenReturn(QuoteDocumentContent("quotation.ubl.xml", byteArrayOf(3)))
        whenever(files.xml("q-1", "d-2")).thenReturn(null)
        whenever(files.pdf("q-1", "d-3")).thenThrow(QuoteNotFoundException("nicht verfügbar"))
        val panel = panel()

        assertEquals("text/xml", panel.xmlResponse(ready).contentType)
        assertEquals(404, panel.xmlResponse(pending).error)
        val blank = panel.pdfResponse(failed)
        assertEquals(-1, blank.error)
        assertEquals(0, blank.inputStream.readAllBytes().size)
    }

    @Test
    fun `the report opens in a dialog with its findings`() {
        whenever(reports.report("q-1", "d-1")).thenReturn(QuoteValidationReport(false, listOf("BR-Q-01: Gültigkeit fehlt", "XSD: Feld fehlt")))
        val panel = panel()

        panel.showReport(ready)

        val dialog = UI.getCurrent()._get<QuoteValidationReportDialog>()
        assertEquals(listOf("BR-Q-01: Gültigkeit fehlt", "XSD: Feld fehlt"), dialog.findings._find<ListItem>().map { it.text })
        assertEquals("Validierung nicht erfolgreich", dialog.verdict.text)
        dialog.closeButton._click()
        assertFalse(dialog.isOpened)
    }

    @Test
    fun `a passed report and a missing one are told apart`() {
        whenever(reports.report("q-1", "d-1")).thenReturn(QuoteValidationReport(true))
        whenever(reports.report("q-1", "d-2")).thenReturn(null)
        val panel = panel()

        panel.showReport(ready)
        assertEquals("Validierung erfolgreich", UI.getCurrent()._get<QuoteValidationReportDialog>().verdict.text)
        panel.showReport(pending)
        assertTrue(notificationTexts().any { it.contains("keine Validierungsbefunde") })
    }

    @Test
    fun `a passed report shows no empty findings list`() {
        whenever(reports.report("q-1", "d-1")).thenReturn(QuoteValidationReport(true))
        val panel = panel()

        panel.showReport(ready)

        assertFalse(UI.getCurrent()._get<QuoteValidationReportDialog>().findings.parent.isPresent)
    }

    @Test
    fun `the position column sizes to its content and the grid spans the panel`() {
        val panel = panel()

        assertTrue(panel.grid.getColumnByKey("position").isAutoWidth)
        assertEquals("100%", panel.grid.width)
    }

    @Test
    fun `a failed job carries its error in the status tooltip`() {
        val panel = panel()
        val row = shown(panel).indexOfFirst { it.id == "d-3" }

        val chip = cell(panel, row, "status")._find<Span>().first { it.classNames.contains("status-glyph") }
        assertTrue(chip.element.getAttribute("title").contains("Renderer nicht erreichbar"))
    }

    @Test
    fun `a failed report without details says so`() {
        whenever(reports.report("q-1", "d-1")).thenReturn(QuoteValidationReport(false))
        val panel = panel()

        panel.showReport(ready)

        assertTrue(visibleText(UI.getCurrent()._get<QuoteValidationReportDialog>()).contains("keine Einzelbefunde"))
    }

    @Test
    fun `the report button sits on documents that have one`() {
        val panel = panel()
        val ids = rows(panel).indices.flatMap { cell(panel, it, "validation")._find<Button>() }
            .mapNotNull { it.id.orElse(null) }
            .filter { it.startsWith("quote-document-report") }
        assertEquals(listOf("quote-document-report-d-1"), ids)
    }
}
