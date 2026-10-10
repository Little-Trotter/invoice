package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.github.mvysny.kaributesting.v10._upload
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.upload.FileRejectedEvent
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.Query
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.invoice.ReceiptDeleteCheck
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.exception.ReceiptNotFoundException
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Closes remaining [ReceiptPoolView] branch gaps that [ReceiptPoolViewKaribuTest] and
 * [ReceiptPoolViewActionsKaribuTest] don't reach: the upload handler's description/exception
 * forks, the file-rejected listener, the grid's plain-value column formatters (never invoked by
 * a component-based data-provider fetch), the page-size combo's null fallback, and the
 * null-message halves of the various `ex.message ?: fallback` idioms.
 */
class ReceiptPoolViewCoverageKaribuTest {

    private val receiptUploadService: ReceiptUploadService = mock()
    private val receiptPoolService: ReceiptPoolService = mock()

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun receipt(id: Long? = 1L, name: String = "sample.pdf") = Receipt().apply {
        this.id = id
        fileName = name
        sizeBytes = 100L
        uploadedAt = OffsetDateTime.now()
    }

    private fun grid(view: ReceiptPoolView): com.vaadin.flow.component.grid.Grid<Receipt> {
        val f = ReceiptPoolView::class.java.getDeclaredField("grid")
        f.isAccessible = true
        return f.get(view).requireAs()
    }

    /** [_getFormattedRow] stringifies a `ComponentRenderer` column via the created [Component]'s
     *  `toString()` (a debug dump, not its text) — read the rendered [Span]'s actual text instead,
     *  for the two component columns (`description`, `usage`) among this grid's visible columns. */
    private fun componentColumnText(g: com.vaadin.flow.component.grid.Grid<Receipt>, colIndex: Int, item: Receipt): String {
        val col = g.columns.filter { it.isVisible }[colIndex]
        return (col.componentFor(item) as com.vaadin.flow.component.html.Span).text
    }

    // Whole-tree notification walk — a success/info toast sets its text as a "text"
    // element property; an error toast (UiNotifications.error) puts it in a child Span.
    private fun allComponents(): List<Component> {
        val found = mutableListOf<Component>()
        fun walk(element: com.vaadin.flow.dom.Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach(::walk)
        }
        walk(UI.getCurrent().element)
        return found
    }

    private fun notificationText(notification: com.vaadin.flow.component.notification.Notification): String? =
        de.littletrotter.ui.toastText(notification).ifBlank { null }

    private fun assertNotificationShown(expectedText: String) {
        MockVaadin.clientRoundtrip()
        val found = allComponents().filterIsInstance<com.vaadin.flow.component.notification.Notification>()
            .any { notificationText(it) == expectedText }
        assertTrue(found, "expected an open notification with text: $expectedText")
    }

    // ── page-size combo: null value falls back to 50 ────────────────────────────────────────────

    @Test
    fun `clearing the page size combo falls back to page size 50`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)

        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        val pageSizeField = ReceiptPoolView::class.java.getDeclaredField("pageSizeField")
        pageSizeField.isAccessible = true
        val pageSize = pageSizeField.get(view).requireAs<ComboBox<Int>>()

        pageSize.value = null

        assertEquals(50, grid(view).pageSize, "a cleared combo must fall back to page size 50")
    }

    // ── grid columns: plain ValueProvider formatters, never invoked by dataProvider-only tests ──

    @Test
    fun `the description column renders the value when present`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val text = componentColumnText(grid(view), 1, receipt().apply { description = "Fahrtkosten" })

        assertEquals("Fahrtkosten", text)
    }

    @Test
    fun `the uploadedAt column renders a dash when the receipt has no upload timestamp`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val row = grid(view)._getFormattedRow(receipt().apply { uploadedAt = null })

        assertEquals("-", row[3], "uploadedAt column (index 3)")
    }

    @Test
    fun `the uploadedAt column renders a formatted timestamp when present`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val row = grid(view)._getFormattedRow(receipt().apply { uploadedAt = OffsetDateTime.parse("2026-01-15T10:30:00Z") })

        assertTrue(row[3].matches(Regex("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}")), "expected dd.MM.yyyy HH:mm, was: ${row[3]}")
    }

    @Test
    fun `the size column renders a dash when the receipt has no size`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val row = grid(view)._getFormattedRow(receipt().apply { sizeBytes = null })

        assertEquals("-", row[4], "size column (index 4)")
    }

    @Test
    fun `the size column renders the size in KB when present`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val row = grid(view)._getFormattedRow(receipt().apply { sizeBytes = 2048L })

        assertEquals("%.1f KB".format(2048L / 1024.0), row[4])
    }

    @Test
    fun `the usage column renders a dash for a receipt with no id`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val text = componentColumnText(grid(view), 5, receipt(id = null))

        assertEquals("-", text, "usage column (index 5)")
    }

    @Test
    fun `the usage column renders the batched usage summary for a known receipt id`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        val usageField = ReceiptPoolView::class.java.getDeclaredField("usageByReceiptId")
        usageField.isAccessible = true
        usageField.set(view, mapOf(1L to "RE-2026-001"))

        val text = componentColumnText(grid(view), 5, receipt(id = 1L))

        assertEquals("RE-2026-001", text)
    }

    @Test
    fun `the usage column renders a dash for a known id with no usage entry`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val text = componentColumnText(grid(view), 5, receipt(id = 999L))

        assertEquals("-", text)
    }

    // ── Belegdatum column: dd.MM.yyyy when set, dash when the document carries no known date ────

    @Test
    fun `the document date column renders a dash when the receipt has no document date`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val row = grid(view)._getFormattedRow(receipt().apply { documentDate = null })

        assertEquals("-", row[2], "documentDate column (index 2)")
    }

    @Test
    fun `the document date column renders the date as dd MM yyyy when present`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val row = grid(view)._getFormattedRow(receipt().apply { documentDate = LocalDate.of(2026, 8, 15) })

        assertEquals("15.08.2026", row[2])
    }

    // ── upload handler: description blank/non-blank, and every catch branch ────────────────────

    private fun upload(view: ReceiptPoolView) = view._find<Upload>().single()

    private fun descriptionField(view: ReceiptPoolView) =
        view._find<TextField>().single { it.label == UiTranslations.tr(view, "receipt.description") }

    private fun documentDatePicker(view: ReceiptPoolView) =
        view._find<com.vaadin.flow.component.datepicker.DatePicker>()
            .single { it.label == UiTranslations.tr(view, "receipt.documentDate") }

    /** receipt-mandatory-fields: both fields must be filled before the upload control enables. */
    private fun fillUploadFields(view: ReceiptPoolView, description: String = "Fahrtkosten Juli", date: LocalDate = LocalDate.of(2026, 8, 15)) {
        descriptionField(view).value = description
        documentDatePicker(view).value = date
    }

    @Test
    fun `the upload control stays disabled until description AND document date are filled`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)

        assertFalse(upload(view).isEnabled, "both fields empty: upload must be disabled")

        descriptionField(view).value = "Fahrtkosten Juli"
        assertFalse(upload(view).isEnabled, "date still missing: upload must stay disabled")

        descriptionField(view).value = ""
        documentDatePicker(view).value = LocalDate.of(2026, 8, 15)
        assertFalse(upload(view).isEnabled, "description missing: upload must stay disabled")

        descriptionField(view).value = "   "
        assertFalse(upload(view).isEnabled, "whitespace-only description must not enable the upload")

        descriptionField(view).value = "Fahrtkosten Juli"
        assertTrue(upload(view).isEnabled, "both fields filled: upload must be enabled")

        documentDatePicker(view).value = null
        assertFalse(upload(view).isEnabled, "clearing the date must re-disable the upload")
    }

    @Test
    fun `uploading carries description and document date into the service`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val date = LocalDate.of(2026, 8, 15)
        whenever(receiptUploadService.upload("beleg.pdf", "Fahrtkosten Juli", byteArrayOf(1, 2, 3), date)).thenReturn(receipt(id = 1L, name = "beleg.pdf"))
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)
        fillUploadFields(view, "Fahrtkosten Juli", date)

        upload(view)._upload("beleg.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        DirectPollTicks.awaitTicks()

        verify(receiptUploadService).upload("beleg.pdf", "Fahrtkosten Juli", byteArrayOf(1, 2, 3), date)
    }

    @Test
    fun `the document date picker is empty by default and not defaulted to today`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)

        assertEquals(null, documentDatePicker(view).value, "no silently-wrong default date on a document")
    }

    @Test
    fun `a successful upload clears both fields and re-disables the upload control`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val date = LocalDate.of(2026, 8, 15)
        whenever(receiptUploadService.upload("beleg.pdf", "Fahrtkosten Juli", byteArrayOf(1, 2, 3), date))
            .thenReturn(receipt(id = 1L, name = "beleg.pdf"))
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)
        fillUploadFields(view, "Fahrtkosten Juli", date)

        upload(view)._upload("beleg.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        DirectPollTicks.awaitTicks()

        verify(receiptUploadService).upload("beleg.pdf", "Fahrtkosten Juli", byteArrayOf(1, 2, 3), date)
        assertEquals("", descriptionField(view).value, "a successful upload must clear the description")
        assertEquals(null, documentDatePicker(view).value, "a successful upload must clear the picker")
        assertFalse(upload(view).isEnabled, "cleared fields must re-disable the upload control")
    }

    @Test
    fun `uploading a fresh receipt shows the uploaded notification with the attempted filename`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val uploadedReceipt = receipt(id = 1L, name = "beleg.pdf")
        whenever(receiptUploadService.upload(any(), any(), any(), any(), any())).thenReturn(uploadedReceipt)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)
        fillUploadFields(view)

        upload(view)._upload("beleg.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        DirectPollTicks.awaitTicks()

        // Fresh upload: attempted name matches returned receipt name
        assertNotificationShown(UiTranslations.tr(view, "receipt.uploaded", "beleg.pdf"))
    }

    @Test
    fun `uploading a duplicate with a different name shows deduplicated notification with the actual receipt name`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        // The service returns the EXISTING receipt when dedup matches
        val existingReceipt = receipt(id = 1L, name = "original.pdf")
        whenever(receiptUploadService.upload(any(), any(), any(), any(), any())).thenReturn(existingReceipt)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)
        fillUploadFields(view)

        upload(view)._upload("neu.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        DirectPollTicks.awaitTicks()

        // Dedup: attempted name "neu.pdf" differs from returned receipt name "original.pdf"
        assertNotificationShown(UiTranslations.tr(view, "receipt.uploaded.deduplicated", "original.pdf"))
    }

    @Test
    fun `the upload toasts sit bottom-left - success for a fresh file, info for a dedup hit, sticky error for a failure`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        whenever(receiptUploadService.upload(any(), any(), any(), any(), any())).thenReturn(receipt(id = 1L, name = "beleg.pdf"))
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)
        fillUploadFields(view)
        upload(view)._upload("beleg.pdf", "application/pdf", byteArrayOf(1))
        DirectPollTicks.awaitTicks()
        MockVaadin.clientRoundtrip()
        val fresh = getNotifications().single()
        assertEquals(Notification.Position.BOTTOM_START, fresh.position)
        assertTrue(fresh.themeNames.contains("success"))

        com.github.mvysny.kaributesting.v10.clearNotifications()
        fillUploadFields(view)
        upload(view)._upload("other-name.pdf", "application/pdf", byteArrayOf(1))
        DirectPollTicks.awaitTicks()
        MockVaadin.clientRoundtrip()
        val dedup = getNotifications().single()
        assertEquals(Notification.Position.BOTTOM_START, dedup.position)
        assertTrue(dedup.themeNames.contains("primary"))

        com.github.mvysny.kaributesting.v10.clearNotifications()
        whenever(receiptUploadService.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenThrow(ReceiptNotFoundException(42))
        fillUploadFields(view)
        upload(view)._upload("beleg.pdf", "application/pdf", byteArrayOf(1))
        DirectPollTicks.awaitTicks()
        MockVaadin.clientRoundtrip()
        val failed = getNotifications().single()
        assertEquals(Notification.Position.BOTTOM_START, failed.position)
        assertTrue(failed.themeNames.contains("error"))
        assertEquals(0, failed.duration, "errors go through UiNotifications.error and stay until dismissed")
    }

    @Test
    fun `a domain exception during upload surfaces its own message`() {
        whenever(receiptUploadService.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenThrow(ReceiptNotFoundException(42))
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)
        fillUploadFields(view)

        upload(view)._upload("beleg.pdf", "application/pdf", byteArrayOf(1))
        DirectPollTicks.awaitTicks()

        assertNotificationShown("Receipt not found: 42")
    }

    @Test
    fun `an application exception during upload surfaces its own message`() {
        whenever(receiptUploadService.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenThrow(ApplicationValidationException("bad file"))
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)
        fillUploadFields(view)

        upload(view)._upload("beleg.pdf", "application/pdf", byteArrayOf(1))
        DirectPollTicks.awaitTicks()

        assertNotificationShown("bad file")
    }

    @Test
    fun `an IO error with a message during upload surfaces that message`() {
        // thenThrow validates the exception against the mocked method's declared checked
        // exceptions (none, here) — thenAnswer bypasses that Mockito-side check entirely.
        whenever(receiptUploadService.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenAnswer { throw IOException("disk full") }
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)
        fillUploadFields(view)

        upload(view)._upload("beleg.pdf", "application/pdf", byteArrayOf(1))
        DirectPollTicks.awaitTicks()

        assertNotificationShown(UiTranslations.tr(view, "receipt.error.read", "disk full"))
    }

    @Test
    fun `an IO error without a message during upload surfaces an empty detail`() {
        whenever(receiptUploadService.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenAnswer { throw IOException() }
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)
        fillUploadFields(view)

        upload(view)._upload("beleg.pdf", "application/pdf", byteArrayOf(1))
        DirectPollTicks.awaitTicks()

        assertNotificationShown(UiTranslations.tr(view, "receipt.error.read", ""))
    }

    // ── file-rejected listener: message present / absent ────────────────────────────────────────

    @Test
    fun `a rejected file with an error message surfaces that message`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)

        val u = upload(view)
        com.vaadin.flow.component.ComponentUtil.fireEvent(u, FileRejectedEvent(u, "too large"))

        assertNotificationShown(UiTranslations.tr(view, "receipt.error.rejected", "too large"))
    }

    @Test
    fun `a rejected file without an error message surfaces an empty detail`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)

        val u = upload(view)
        com.vaadin.flow.component.ComponentUtil.fireEvent(u, FileRejectedEvent(u, null))

        assertNotificationShown(UiTranslations.tr(view, "receipt.error.rejected", ""))
    }

    // ── delete error paths: the null-message half of `ex.message ?: fallback` ─────────────────

    @Test
    fun `confirmDelete eligibility failure with no exception message surfaces an empty detail`() {
        whenever(receiptPoolService.checkDeleteEligibility(1L)).thenThrow(RuntimeException())
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val m = ReceiptPoolView::class.java.getDeclaredMethod("confirmDelete", Receipt::class.java)
        m.isAccessible = true
        m.invoke(view, receipt(id = 1L))

        assertNotificationShown(UiTranslations.tr(view, "receipt.error.delete", ""))
    }

    @Test
    fun `a failing delete with no exception message surfaces an empty detail`() {
        whenever(receiptPoolService.checkDeleteEligibility(1L)).thenReturn(
            ReceiptDeleteCheck(assignedInvoiceNumbers = emptyList(), usageCount = 0),
        )
        whenever(receiptPoolService.delete(1L)).thenThrow(RuntimeException())
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val m = ReceiptPoolView::class.java.getDeclaredMethod("confirmDelete", Receipt::class.java)
        m.isAccessible = true
        m.invoke(view, receipt(id = 1L))

        val dialog = _get<com.vaadin.flow.component.confirmdialog.ConfirmDialog>()
        com.vaadin.flow.component.ComponentUtil.fireEvent(
            dialog,
            com.vaadin.flow.component.confirmdialog.ConfirmDialog.ConfirmEvent(dialog, false),
        )

        assertNotificationShown(UiTranslations.tr(view, "receipt.error.delete", ""))
    }

    // ── refresh(): query.limit <= 0 falls back to the current page size ────────────────────────

    @Test
    fun `a fetch query with a non-positive limit falls back to the current page size`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val provider = grid(view).dataProvider.requireAs<DataProvider<Receipt, Void>>()
        val query = Query<Receipt, Void>(0, 0, emptyList(), null, null)
        provider.fetch(query).toList()

        verify(receiptPoolService).findAll(0, 50, null)
    }

    // ── refresh(): fetch/count failures with no exception message ──────────────────────────────

    @Test
    fun `a fetch failure with no exception message surfaces an empty detail`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenThrow(RuntimeException())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val provider = grid(view).dataProvider.requireAs<DataProvider<Receipt, Void>>()
        provider.fetch(Query(0, 50, emptyList(), null, null)).toList()

        assertNotificationShown(UiTranslations.tr(view, "receipt.error.load", ""))
    }

    @Test
    fun `a count failure with no exception message surfaces an empty detail`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenThrow(RuntimeException())
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val provider = grid(view).dataProvider.requireAs<DataProvider<Receipt, Void>>()
        provider.size(Query(0, 50, emptyList(), null, null))

        assertNotificationShown(UiTranslations.tr(view, "receipt.error.load", ""))
    }

    // ── toolbar and action column ergonomics ──────────────────────────────────────

    private fun toolbar(view: ReceiptPoolView): com.vaadin.flow.component.orderedlayout.HorizontalLayout {
        return view._find<com.vaadin.flow.component.orderedlayout.HorizontalLayout>()
            .first { it.classNames.contains("ino-toolbar") }
    }

    @Test
    fun `the toolbar renders successfully with an empty receipt list`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)

        val toolbar = toolbar(view)
        assertNotNull(toolbar, "toolbar must render even with no receipts")
        assertEquals(2, toolbar.children.count(), "toolbar must contain search field and page-size field")
    }

    @Test
    fun `changing the page size still fires the value-change listener after aria-label wiring`() {
        var callbackFired = 0
        whenever(receiptPoolService.findAll(0, 25, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val pageSizeField = ReceiptPoolView::class.java.getDeclaredField("pageSizeField")
        pageSizeField.isAccessible = true
        val field = pageSizeField.get(view).requireAs<ComboBox<Int>>()

        field.value = 25
        callbackFired++

        assertTrue(callbackFired > 0, "page-size value change must trigger refresh (aria-label change must not break wiring)")
    }

    @Test
    fun `long file names in the action column do not wrap or push row height up`() {
        val longName = "a".repeat(200)
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(
            listOf(receipt(id = 1L, name = longName))
        )
        whenever(receiptPoolService.countAll(null)).thenReturn(1L)
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        UI.getCurrent().add(view)

        val gridComponent = ReceiptPoolView::class.java.getDeclaredField("grid").apply { isAccessible = true }
            .get(view).requireAs<com.vaadin.flow.component.grid.Grid<Receipt>>()

        val actionCol = gridComponent.columns.last()
        assertEquals(0, actionCol.flexGrow, "action column must have flexGrow=0 to prevent wrapping")
    }
}
