package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.server.StreamResourceRegistry
import com.vaadin.flow.server.streams.AbstractDownloadHandler
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadResponse
import com.vaadin.flow.server.streams.InputStreamDownloadCallback
import com.vaadin.flow.server.streams.InputStreamDownloadHandler
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteListCsvExportQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteListWorkbookExportQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteSortDirection
import de.littletrotter.application.quote.port.inbound.QuoteSortField
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteWorkbookExportQueryPort
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.view.invoice.DownloadAnchorTestSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuoteListExportTest {

    private val captured = mutableListOf<QuoteListCriteria>()
    private val workbook = QuoteListWorkbookExportQueryPort { criteria, _, out ->
        captured += criteria
        out.write("xlsx".toByteArray())
    }
    private val csv = QuoteListCsvExportQueryPort { criteria, _, out ->
        captured += criteria
        out.write("csv".toByteArray())
    }
    private val ports = QuoteListExportPorts(workbook, csv, FIXED_TIME_SOURCE)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun host(): TranslationProvider {
        val anchor = Div()
        UI.getCurrent().add(anchor)
        return object : TranslationProvider {
            override fun getComponent() = anchor
        }
    }

    private fun dialog(criteria: QuoteListCriteria = QuoteListCriteria(), count: Long = 3L, exports: QuoteListExportPorts = ports): Dialog =
        buildQuoteListExportDialog(host(), exports, criteria, count)

    private fun body(dialog: Dialog): VerticalLayout = dialog.children.toList().filterIsInstance<VerticalLayout>().first()

    private fun format(dialog: Dialog): RadioButtonGroup<QuoteListExportFormat> =
        body(dialog).children.toList().filterIsInstance<RadioButtonGroup<QuoteListExportFormat>>().first()

    private fun anchor(dialog: Dialog): Anchor =
        body(dialog).children.toList().filterIsInstance<HorizontalLayout>().first().children.toList().filterIsInstance<Anchor>().first()

    private fun run(anchor: Anchor): DownloadResponse {
        val hrefField = Anchor::class.java.getDeclaredField("href")
        hrefField.isAccessible = true
        val resource = hrefField.get(anchor) as StreamResourceRegistry.ElementStreamResource
        val handler = resource.elementRequestHandler as AbstractDownloadHandler<*>
        val callbackField = InputStreamDownloadHandler::class.java.getDeclaredField("callback")
        callbackField.isAccessible = true
        val callback = callbackField.get(handler) as InputStreamDownloadCallback
        return callback.complete(DownloadEvent(mock(), mock(), mock(), UI.getCurrent()!!.element))
    }

    @Test
    fun `the workbook response carries the written bytes as xlsx`() {
        val response = quoteWorkbookResponse("angebote") { out -> out.write("xlsx".toByteArray()) }

        assertEquals("angebote.xlsx", response.fileName)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.contentType)
        assertFalse(response.hasError())
        assertEquals("xlsx", response.inputStream.use { String(it.readAllBytes()) })
    }

    @Test
    fun `a failing workbook export answers with an error status`() {
        val response = quoteWorkbookResponse("x") { throw QuoteServiceException("down") }

        assertEquals(500, response.error)
    }

    @Test
    fun `the detail workbook link is a download anchor with its own id`() {
        val single = buildQuoteWorkbookLink(host(), QuoteWorkbookExportQueryPort { _, _, _ -> }, quoteDetail())

        assertTrue(single.element.hasAttribute("download"))
        assertTrue(single.children.anyMatch { it.id.orElse("") == "quote-export-xlsx" })
    }

    @Test
    fun `the list button is the shared spreadsheet symbol with the stable id`() {
        val button = buildQuoteListExportButton(host(), ports, { QuoteListCriteria() }) { 0L }

        assertEquals(QUOTE_LIST_EXPORT_BUTTON_ID, button.id.orElse(null))
    }

    @Test
    fun `the button counts the matches of the criteria shown at the click and opens the dialog`() {
        var shown = QuoteListCriteria(text = "a")
        val counted = mutableListOf<QuoteListCriteria>()
        val button = buildQuoteListExportButton(host(), ports, { shown }) { counted += it; 7L }
        shown = QuoteListCriteria(text = "b")

        UI.getCurrent().add(button)
        button._click()

        assertEquals(listOf(QuoteListCriteria(text = "b")), counted)
    }

    @Test
    fun `the dialog carries the stable ids and preselects Excel`() {
        val dialog = dialog()

        assertEquals(QuoteListExportFormat.WORKBOOK, format(dialog).value)
        assertEquals(QUOTE_LIST_EXPORT_FORMAT_ID, format(dialog).id.orElse(null))
        assertTrue(format(dialog).themeNames.contains("lt-segmented"))
        val confirm = anchor(dialog).children.toList().filterIsInstance<Button>().first()
        assertEquals(QUOTE_LIST_EXPORT_CONFIRM_ID, confirm.id.orElse(null))
    }

    @Test
    fun `an empty filter reads as all quotes and shows the match count`() {
        val texts = body(dialog(count = 12L)).children.toList().filterIsInstance<Span>().map { it.text }

        assertTrue(texts.any { it.contains("Alle Angebote") || it.contains("All quotes") }, texts.toString())
        assertTrue(texts.any { it.contains("12") }, texts.toString())
    }

    @Test
    fun `the scope names an active status and search text`() {
        val scope = quoteListExportScope(host(), QuoteListCriteria(status = QuoteStatus.SENT, text = "Müller; GmbH"))

        assertTrue(scope.contains("Müller; GmbH"))
        assertTrue(scope.contains(","))
    }

    @Test
    fun `the default download is the Excel file named by the day with the filter and sort of the list`() {
        val criteria = QuoteListCriteria(
            status = QuoteStatus.ACCEPTED,
            text = "x",
            offset = 100,
            limit = 10,
            sortField = QuoteSortField.QUOTE_DATE,
            sortDirection = QuoteSortDirection.ASC,
        )

        val response = run(anchor(dialog(criteria)))

        assertFalse(response.hasError())
        assertTrue(response.fileName.matches(Regex("(angebote|quotes)-\\d{8}\\.xlsx")), response.fileName)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.contentType)
        assertEquals("xlsx", response.inputStream.use { String(it.readAllBytes()) })
        assertEquals(listOf(criteria), captured)
    }

    @Test
    fun `switching the format to CSV downloads the csv file`() {
        val dialog = dialog()
        format(dialog).value = QuoteListExportFormat.CSV

        val response = run(anchor(dialog))

        assertTrue(response.fileName.matches(Regex("(angebote|quotes)-\\d{8}\\.csv")), response.fileName)
        assertEquals("text/csv; charset=UTF-8", response.contentType)
        assertEquals("csv", response.inputStream.use { String(it.readAllBytes()) })
    }

    @Test
    fun `a failing CSV export answers with an error status`() {
        val failing = QuoteListExportPorts(workbook, { _, _, _ -> throw QuoteServiceException("down") }, FIXED_TIME_SOURCE)
        val dialog = dialog(exports = failing)
        format(dialog).value = QuoteListExportFormat.CSV

        assertEquals(500, run(anchor(dialog)).error)
    }

    @Test
    fun `the detail link writes the workbook of its offer when downloaded`() {
        val detail = quoteDetail()
        var seenId: String? = null
        val link = buildQuoteWorkbookLink(host(), QuoteWorkbookExportQueryPort { id, _, out ->
            seenId = id
            out.write("one".toByteArray())
        }, detail)

        val response = DownloadAnchorTestSupport.invoke(link)

        assertEquals(detail.id, seenId)
        assertEquals("one", response.inputStream.use { String(it.readAllBytes()) })
    }

    @Test
    fun `an I-O failure while writing answers with an error status`() {
        val response = quoteWorkbookResponse("x") { throw java.io.IOException("disk full") }

        assertEquals(500, response.error)
    }
}
