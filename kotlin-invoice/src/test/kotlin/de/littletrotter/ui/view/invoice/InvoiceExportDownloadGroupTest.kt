package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.server.StreamResourceRegistry
import com.vaadin.flow.server.streams.AbstractDownloadHandler
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadResponse
import com.vaadin.flow.server.streams.InputStreamDownloadCallback
import com.vaadin.flow.server.streams.InputStreamDownloadHandler
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.export.InvoiceHeaderExportRow
import de.littletrotter.application.export.InvoiceJournalExportRow
import de.littletrotter.application.export.InvoicePositionExportRow
import de.littletrotter.application.export.port.inbound.InvoiceWorkbookExportQueryPort
import de.littletrotter.application.export.port.outbound.TripExportNotFoundException
import de.littletrotter.application.export.port.inbound.TripWorkbookExportQueryPort
import de.littletrotter.application.export.port.outbound.InvoiceExportReadRepository
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.infrastructure.export.workbook.InvoiceWorkbookExportService
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.jboss.logging.Logger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import de.littletrotter.application.export.InvoiceWorkbookExportQueries

/**
 * The invoice-detail export button: one icon-only [Button], fixed to the four-sheet
 * workbook, inside the download [Anchor] [buildInvoiceExportButton] returns. Mode A's earlier
 * format control and its CSV alternative are gone (see that function's own KDoc for why); mode B's
 * format switch (workbook / bulk CSV) is covered by [InvoiceListExportDialogTest] instead.
 *
 * Unwraps the [Anchor]'s registered handler by reflection the same way
 * `PdfViewerTabE2EIT`/`GenerationJobFormattingTest` already do — [Anchor.getHref]'s backing field
 * down to the actual [InputStreamDownloadHandler], then its own private `callback` field, so the
 * produced [DownloadResponse] (file name, content type, bytes) can be pinned without driving a
 * real HTTP download request.
 */
class InvoiceExportDownloadGroupTest {

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

    private fun invoice(id: Long = 1L, invoiceNumber: String = "RE/2026/001") = InvoiceDetailDto(
        id = id, invoiceNumber = invoiceNumber, status = 0, invoiceDate = LocalDate.of(2026, 1, 15),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun headerRow(invoiceId: Long, invoiceNumber: String) =
        InvoiceHeaderExportRow(invoiceId = invoiceId, invoiceNumber = invoiceNumber, invoiceDate = LocalDate.of(2026, 1, 15))

    private fun buildService(
        headerRow: InvoiceHeaderExportRow?,
        positions: List<InvoicePositionExportRow> = emptyList(),
        journal: List<InvoiceJournalExportRow> = emptyList(),
    ): InvoiceWorkbookExportQueryPort {
        val repository: InvoiceExportReadRepository = mock()
        whenever(repository.findHeaderRow(any())).thenReturn(headerRow)
        whenever(repository.findPositionRows(any<Long>())).thenReturn(positions)
        whenever(repository.findJournalRows(any())).thenReturn(journal)
        return InvoiceWorkbookExportQueries(
            InvoiceWorkbookExportService(
                repository, SystemAuditActorProvider(), ApplicationIdentity("9.9.9-test"),
                de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
            ),
        )
    }

    private fun buildAnchor(
        invoice: InvoiceDetailDto = invoice(),
        service: InvoiceWorkbookExportQueryPort = buildService(headerRow(invoice.id, invoice.invoiceNumber)),
    ): Anchor = buildInvoiceExportButton(invoice, service, ::tr, ::trWithArg) as Anchor

    private fun buttonOf(anchor: Anchor): Button = anchor.children.toList().filterIsInstance<Button>().first()

    /** Unwraps [Anchor.getHref]'s backing field down to the actually registered handler — same
     *  reflection PdfViewerTabE2EIT already uses to cross this same private-field boundary. */
    private fun downloadHandlerOf(anchor: Anchor): AbstractDownloadHandler<*> {
        val hrefField = Anchor::class.java.getDeclaredField("href")
        hrefField.isAccessible = true
        val resource = hrefField.get(anchor) as StreamResourceRegistry.ElementStreamResource
        return resource.elementRequestHandler as AbstractDownloadHandler<*>
    }

    /** Invokes the handler's own callback directly (bypassing Vaadin's HTTP request machinery
     *  entirely) — the callback here never reads the [DownloadEvent] it is given (see
     *  [buildInvoiceExportButton]'s own handler), so a minimal event with a real owning element
     *  (from the mocked UI) is enough. */
    private fun invokeCallback(anchor: Anchor): DownloadResponse {
        val handler = downloadHandlerOf(anchor)
        val callbackField = InputStreamDownloadHandler::class.java.getDeclaredField("callback")
        callbackField.isAccessible = true
        val callback = callbackField.get(handler) as InputStreamDownloadCallback
        val event = DownloadEvent(mock(), mock(), mock(), UI.getCurrent()!!.element)
        return callback.complete(event)
    }

    @Test
    fun `the export button carries the stable id XLS-8 needs`() {
        assertThat(buttonOf(buildAnchor()).id.orElse(null)).isEqualTo(INVOICE_EXPORT_BUTTON_ID)
    }

    @Test
    fun `the button carries no text, only the download icon`() {
        assertThat(buttonOf(buildAnchor()).text).isNullOrEmpty()
    }

    @Test
    fun `the button exposes an aria-label and a title naming the export format`() {
        val button = buttonOf(buildAnchor())
        assertThat(button.element.getAttribute("aria-label")).isEqualTo("Als Excel-Tabelle herunterladen")
        assertThat(com.vaadin.flow.component.shared.Tooltip.forComponent(button).text).isEqualTo("Als Excel-Tabelle herunterladen")
    }

    @Test
    fun `the button carries the icon and tertiary theme variants, not the small one`() {
        val themeNames = buttonOf(buildAnchor()).themeNames
        assertThat(themeNames).contains(
            ButtonVariant.LUMO_ICON.variantName,
            ButtonVariant.LUMO_TERTIARY.variantName,
        )
        assertThat(themeNames).doesNotContain(ButtonVariant.LUMO_SMALL.variantName)
    }

    @Test
    fun `the anchor is a plain attachment download, never opened inline`() {
        assertThat(buildAnchor().isDownload).isTrue()
    }

    @Test
    fun `clicking the button downloads a valid four-sheet xlsx with the expected name and content type`() {
        val anchor = buildAnchor(
            invoice(id = 7L, invoiceNumber = "RE/2026/001"),
            buildService(headerRow(7L, "RE/2026/001"), positions = listOf(InvoicePositionExportRow(invoiceId = 7L, positionId = "1"))),
        )

        val response = invokeCallback(anchor)

        assertThat(response.hasError()).isFalse()
        assertThat(response.fileName).isEqualTo("rechnung_re-2026-001.xlsx")
        assertThat(response.contentType).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

        val bytes = response.inputStream.readBytes()
        response.inputStream.close()
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            assertThat(listOf(0, 1, 2, 3).map { wb.getSheetName(it) })
                .containsExactly("Export", "Rechnungen", "Positionen", "Journal")
        }
    }

    @Test
    fun `an invoice with no positions and no journal entries still downloads a valid workbook`() {
        val anchor = buildAnchor(invoice(id = 3L, invoiceNumber = "RE/2026/003"), buildService(headerRow(3L, "RE/2026/003")))

        val response = invokeCallback(anchor)

        assertThat(response.hasError()).isFalse()
        val bytes = response.inputStream.readBytes()
        response.inputStream.close()
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            assertThat(wb.getSheet("Positionen").physicalNumberOfRows).isEqualTo(1) // header only
        }
    }

    @Test
    fun `a blank invoice number (draft) falls back to the invoice id in the file name`() {
        val anchor = buildAnchor(invoice(id = 42L, invoiceNumber = ""), buildService(headerRow(42L, "")))

        val response = invokeCallback(anchor)

        assertThat(response.fileName).isEqualTo("rechnung_id-42.xlsx")
        response.inputStream.close()
    }

    @Test
    fun `a missing invoice surfaces as a DownloadResponse error rather than throwing out of the request`() {
        val anchor = buildAnchor(invoice(id = 404L), buildService(headerRow = null))

        val response = invokeCallback(anchor)

        assertThat(response.hasError()).isTrue()
    }

    @Test
    fun `the trip handler downloads the port's bytes under the port's file name`() {
        val port: TripWorkbookExportQueryPort = mock()
        whenever(port.fileName("RE/2026/001", 7L, "3")).thenReturn("dienstreise_re-2026-001_3.xlsx")
        whenever(port.writeTripWorkbook(any(), any(), any(), any())).thenAnswer { inv ->
            (inv.getArgument(3) as java.io.OutputStream).write("xlsx-bytes".toByteArray())
        }
        val anchor = Anchor(tripWorkbookDownloadHandler(invoice(id = 7L, invoiceNumber = "RE/2026/001"), "3", port, ::tr), "")

        val response = invokeCallback(anchor)

        assertThat(response.hasError()).isFalse()
        assertThat(response.fileName).isEqualTo("dienstreise_re-2026-001_3.xlsx")
        assertThat(response.contentType).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        assertThat(response.inputStream.use { it.readBytes() }).isEqualTo("xlsx-bytes".toByteArray())
        verify(port).writeTripWorkbook(eq(7L), eq("3"), any(), any())
    }

    @Test
    fun `a trip export failure surfaces as a DownloadResponse error`() {
        val port: TripWorkbookExportQueryPort = mock()
        whenever(port.fileName(any(), any(), any())).thenReturn("dienstreise_id-1_9.xlsx")
        whenever(port.writeTripWorkbook(any(), any(), any(), any())).thenThrow(TripExportNotFoundException(1L, "9"))
        val anchor = Anchor(tripWorkbookDownloadHandler(invoice(id = 1L), "9", port, ::tr), "")

        val response = invokeCallback(anchor)

        assertThat(response.hasError()).isTrue()
    }

    @Test
    fun `the temporary file backing the response is gone once the returned stream is closed`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        fun exportTempFiles() = tempDir.listFiles { f -> f.name.startsWith("invoice-export-") }?.toList().orEmpty()

        val before = exportTempFiles().toSet()
        val anchor = buildAnchor(invoice(id = 7L, invoiceNumber = "RE/2026/001"), buildService(headerRow(7L, "RE/2026/001")))
        val response = invokeCallback(anchor)

        val created = (exportTempFiles().toSet() - before)
        assertThat(created).hasSize(1)

        response.inputStream.readBytes()
        response.inputStream.close()

        assertThat(exportTempFiles().toSet() - before).isEmpty()
    }

    @Test
    fun `TempFileCleanupInputStream deletes its backing file on close`() {
        val file = File.createTempFile("invoice-export-unit-", ".tmp")
        assertThat(file).exists()
        TempFileCleanupInputStream(file).use { stream -> stream.readBytes() }
        assertThat(file).doesNotExist()
    }

    @Test
    fun `deleteQuietly does not throw when the file is already gone (kotlin S899 - the delete result is now checked)`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "invoice-export-already-gone-${System.nanoTime()}.tmp")
        assertThat(missing).doesNotExist()

        missing.deleteQuietly(Logger.getLogger("test"), "unit test")
    }

    @Test
    fun `invoiceExportFileName sanitises slashes and lower-cases the whole name`() {
        assertThat(invoiceExportFileName("RE/2026/001", 1L, suffix = "", extension = "xlsx"))
            .isEqualTo("rechnung_re-2026-001.xlsx")
    }

    @Test
    fun `invoiceExportFileName appends the given suffix before the extension`() {
        assertThat(invoiceExportFileName("RE-2026-001", 1L, suffix = "_positionen", extension = "csv"))
            .isEqualTo("rechnung_re-2026-001_positionen.csv")
    }

    @Test
    fun `invoiceExportFileName falls back to the invoice id when the number is blank`() {
        assertThat(invoiceExportFileName("", 42L, suffix = "", extension = "xlsx")).isEqualTo("rechnung_id-42.xlsx")
        assertThat(invoiceExportFileName("   ", 42L, suffix = "", extension = "xlsx")).isEqualTo("rechnung_id-42.xlsx")
    }
}
