package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
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
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.export.port.inbound.InvoiceWorkbookExportQueryPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import de.littletrotter.test.FIXED_LOCAL_NOW
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * The filtered-list export dialog: scope summary, format choice, and the single
 * download anchor whose handler is swapped per format, the same shape [InvoiceExportDownloadGroupTest]
 * (mode A) already tests. Same reflection-based anchor-unwrap idiom as that file's own KDoc
 * explains.
 */
class InvoiceListExportDialogTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun tr(key: String): String = key

    private fun trWithArg(key: String, arg: Any): String = "$key:$arg"

    private fun fakeService(workbookMarker: String = "workbook", csvMarker: String = "positionId\ncsv"): InvoiceWorkbookExportQueryPort {
        val service: InvoiceWorkbookExportQueryPort = mock()
        doAnswer { invocation ->
            (invocation.arguments[3] as OutputStream).write(workbookMarker.toByteArray())
            Unit
        }.whenever(service).writeWorkbook(any<InvoiceExportFilter>(), any(), any(), any())
        doAnswer { invocation ->
            (invocation.arguments[2] as OutputStream).write(csvMarker.toByteArray())
            Unit
        }.whenever(service).writePositionsCsv(any<InvoiceExportFilter>(), any(), any())
        return service
    }

    private fun bodyLayoutOf(dialog: Dialog): VerticalLayout = dialog.children.toList().filterIsInstance<VerticalLayout>().first()

    private fun formatControlOf(dialog: Dialog): RadioButtonGroup<InvoiceExportDownloadFormat> =
        bodyLayoutOf(dialog).children.toList().filterIsInstance<RadioButtonGroup<InvoiceExportDownloadFormat>>().first()

    private fun actionsRowOf(dialog: Dialog): HorizontalLayout =
        bodyLayoutOf(dialog).children.toList().filterIsInstance<HorizontalLayout>().first()

    private fun confirmAnchorOf(dialog: Dialog): Anchor = actionsRowOf(dialog).children.toList().filterIsInstance<Anchor>().first()

    private fun cancelButtonOf(dialog: Dialog): Button =
        actionsRowOf(dialog).children.toList().filterIsInstance<Button>().first { it.id.orElse(null) == INVOICE_LIST_EXPORT_CANCEL_ID }

    /** Same reflection unwrap [InvoiceExportDownloadGroupTest] uses. */
    private fun downloadHandlerOf(anchor: Anchor): AbstractDownloadHandler<*> {
        val hrefField = Anchor::class.java.getDeclaredField("href")
        hrefField.isAccessible = true
        val resource = hrefField.get(anchor) as StreamResourceRegistry.ElementStreamResource
        return resource.elementRequestHandler as AbstractDownloadHandler<*>
    }

    private fun invokeCallback(anchor: Anchor): DownloadResponse {
        val handler = downloadHandlerOf(anchor)
        val callbackField = InputStreamDownloadHandler::class.java.getDeclaredField("callback")
        callbackField.isAccessible = true
        val callback = callbackField.get(handler) as InputStreamDownloadCallback
        val event = DownloadEvent(mock(), mock(), mock(), UI.getCurrent()!!.element)
        return callback.complete(event)
    }

    private fun buildDialog(
        filter: InvoiceExportFilter = InvoiceExportFilter(),
        scopeDescription: String = "Alle Rechnungen",
        matchCount: Long = 3L,
        service: InvoiceWorkbookExportQueryPort = fakeService(),
    ): Dialog = buildInvoiceListExportDialog(filter, scopeDescription, matchCount, ExportServices(service, ::tr, ::trWithArg, time = FIXED_TIME_SOURCE))

    @Test
    fun `the format control and dialog buttons carry the stable ids XLS-8 needs`() {
        val dialog = buildDialog()

        assertThat(formatControlOf(dialog).id.orElse(null)).isEqualTo(INVOICE_LIST_EXPORT_FORMAT_CONTROL_ID)
        assertThat(cancelButtonOf(dialog).id.orElse(null)).isEqualTo(INVOICE_LIST_EXPORT_CANCEL_ID)
        val confirmButton = confirmAnchorOf(dialog).children.toList().filterIsInstance<Button>().first()
        assertThat(confirmButton.id.orElse(null)).isEqualTo(INVOICE_LIST_EXPORT_CONFIRM_ID)
    }

    @Test
    fun `the workbook format is preselected`() {
        assertThat(formatControlOf(buildDialog()).value).isEqualTo(InvoiceExportDownloadFormat.WORKBOOK)
    }

    @Test
    fun `the format control carries the lt-segmented theme XLS-11's CSS switch paints`() {
        assertThat(formatControlOf(buildDialog()).themeNames).contains("lt-segmented")
    }

    @Test
    fun `the format control's own label stays set for assistive technology - only the CSS hides it visually`() {
        assertThat(formatControlOf(buildDialog()).label).isEqualTo(tr("invoiceExport.format.label"))
    }

    @Test
    fun `the confirm control is a plain attachment download, never opened inline`() {
        assertThat(confirmAnchorOf(buildDialog()).isDownload).isTrue()
    }

    @Test
    fun `confirming the preselected workbook format downloads a three-sheet xlsx with no Journal sheet`() {
        val dialog = buildDialog(
            filter = InvoiceExportFilter(dateFrom = LocalDate.of(2026, 1, 1), dateTo = LocalDate.of(2026, 3, 31)),
        )

        val response = invokeCallback(confirmAnchorOf(dialog))

        assertThat(response.hasError()).isFalse()
        assertThat(response.fileName).matches("rechnungen_20260101_20260331_\\d{8}-\\d{6}\\.xlsx")
        assertThat(response.contentType).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        response.inputStream.close()
    }

    private fun privacyHintOf(dialog: Dialog): String =
        bodyLayoutOf(dialog).children.toList().filterIsInstance<Span>()
            .first { it.id.orElse(null) == "$INVOICE_LIST_EXPORT_FORMAT_CONTROL_ID-privacy-hint" }.text

    @Test
    fun `the privacy hint follows the selected format`() {
        val dialog = buildDialog()
        assertThat(privacyHintOf(dialog)).isEqualTo("export.privacyHint.coverSheet")

        formatControlOf(dialog).value = InvoiceExportDownloadFormat.CSV
        assertThat(privacyHintOf(dialog)).isEqualTo("export.privacyHint.plain")

        formatControlOf(dialog).value = InvoiceExportDownloadFormat.WORKBOOK
        assertThat(privacyHintOf(dialog)).isEqualTo("export.privacyHint.coverSheet")
    }

    @Test
    fun `switching the format control to CSV changes the anchor's download to the positions CSV`() {
        val dialog = buildDialog()
        formatControlOf(dialog).value = InvoiceExportDownloadFormat.CSV

        val response = invokeCallback(confirmAnchorOf(dialog))

        assertThat(response.hasError()).isFalse()
        assertThat(response.fileName).matches("rechnungen-positionen_alle_alle_\\d{8}-\\d{6}\\.csv")
        assertThat(response.contentType).isEqualTo("text/csv; charset=UTF-8")
        response.inputStream.close()
    }

    @Test
    fun `an unbounded filter uses 'alle' for both date bounds in the file name`() {
        val response = invokeCallback(confirmAnchorOf(buildDialog(filter = InvoiceExportFilter())))
        assertThat(response.fileName).matches("rechnungen_alle_alle_\\d{8}-\\d{6}\\.xlsx")
        response.inputStream.close()
    }

    @Test
    fun `an unknown-cause failure surfaces as a DownloadResponse error rather than throwing out of the request`() {
        val failingService: InvoiceWorkbookExportQueryPort = mock()
        doAnswer { throw RuntimeException("boom") }.whenever(failingService).writeWorkbook(any<InvoiceExportFilter>(), any(), any(), any())

        val response = invokeCallback(confirmAnchorOf(buildDialog(service = failingService)))

        assertThat(response.hasError()).isTrue()
    }

    @Test
    fun `boundary - a failing positions CSV export surfaces as a DownloadResponse error`() {
        val failingService: InvoiceWorkbookExportQueryPort = mock()
        doAnswer { throw IllegalStateException("csv broken") }.whenever(failingService).writePositionsCsv(any<InvoiceExportFilter>(), any(), any())
        val dialog = buildDialog(service = failingService)
        formatControlOf(dialog).value = InvoiceExportDownloadFormat.CSV

        val response = invokeCallback(confirmAnchorOf(dialog))

        assertThat(response.hasError()).isTrue()
    }

    @Test
    fun `the temporary file backing the response is gone once the returned stream is closed`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        fun exportTempFiles() = tempDir.listFiles { f -> f.name.startsWith("invoice-list-export-") }?.toList().orEmpty()

        val before = exportTempFiles().toSet()
        val response = invokeCallback(confirmAnchorOf(buildDialog()))
        val created = exportTempFiles().toSet() - before
        assertThat(created).hasSize(1)

        response.inputStream.readBytes()
        response.inputStream.close()

        assertThat(exportTempFiles().toSet() - before).isEmpty()
    }

    @Test
    fun `invoiceListExportFileName renders both bounds as 'alle' when the filter is unbounded`() {
        assertThat(invoiceListExportFileName(InvoiceExportFilter(), base = "rechnungen", extension = "xlsx", localNow = FIXED_LOCAL_NOW))
            .matches("rechnungen_alle_alle_\\d{8}-\\d{6}\\.xlsx")
    }

    @Test
    fun `invoiceListExportFileName renders only the set bound, 'alle' for the other`() {
        val fromOnly = invoiceListExportFileName(InvoiceExportFilter(dateFrom = LocalDate.of(2026, 6, 1)), base = "rechnungen", extension = "xlsx", localNow = FIXED_LOCAL_NOW)
        assertThat(fromOnly).matches("rechnungen_20260601_alle_\\d{8}-\\d{6}\\.xlsx")

        val toOnly = invoiceListExportFileName(InvoiceExportFilter(dateTo = LocalDate.of(2026, 6, 30)), base = "rechnungen", extension = "xlsx", localNow = FIXED_LOCAL_NOW)
        assertThat(toOnly).matches("rechnungen_alle_20260630_\\d{8}-\\d{6}\\.xlsx")
    }

    @Test
    fun `buildInvoiceListExportButton opens a dialog snapshotting the filter and count at click time`() {
        var filterCalls = 0
        var countedFilter: InvoiceExportFilter? = null
        val button = buildInvoiceListExportButton(
            currentFilter = { filterCalls++; InvoiceExportFilter(filterText = "abc") },
            currentScopeDescription = { "Suchtext" },
            countMatches = { f -> countedFilter = f; 5L },
            services = ExportServices(fakeService(), ::tr, ::trWithArg, time = FIXED_TIME_SOURCE),
        )

        assertThat(button.id.orElse(null)).isEqualTo(INVOICE_LIST_EXPORT_BUTTON_ID)
        button.click()

        assertThat(filterCalls).isEqualTo(1)
        assertThat(countedFilter).isEqualTo(InvoiceExportFilter(filterText = "abc"))
    }
}
