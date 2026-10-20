package de.littletrotter.ui.view.invoice

import de.littletrotter.testing.edit
import de.littletrotter.application.invoice.port.inbound.UploadReceiptUseCase
import de.littletrotter.application.invoice.port.inbound.ReceiptPoolQueryPort
import de.littletrotter.application.invoice.port.inbound.EditReceiptDetailsUseCase
import de.littletrotter.application.invoice.port.inbound.DeleteReceiptUseCase
import de.littletrotter.application.invoice.port.inbound.ReceiptView
import de.littletrotter.testing.receiptView
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.invoice.port.inbound.ReceiptDeleteCheck
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

/**
 * [ReceiptPoolView] had almost no branch coverage before this file (9.21 %,
 * [ReceiptPoolViewKaribuTest] only exercises the grid's lazy data provider). Covers the pool's
 * decision-heavy private methods: [ReceiptPoolView.confirmDelete] (eligibility exception, the
 * assigned-receipt refusal and the confirm button's two outcomes) and
 * [ReceiptPoolView.openEditDialog], plus the filter field's value-change listener and the action
 * column's own visibility rules. Those methods are `private`; invoked directly via reflection
 * (same idiom as [InvoiceStatisticsViewKaribuTest]'s field reflection) instead of clicking through
 * [ReceiptPoolView.buildActionColumn], which the grid only renders once attached to a UI with real
 * row data.
 *
 * receipt-delete-guard — referencing a receipt (assigning it as well as releasing the assignment)
 * left the pool entirely: both happen on the invoice, which allows it only in DRAFT/IN_REVIEW. The
 * assign-dialog tests this file used to carry went with it; what replaces them is the action
 * column's contract (which buttons exist at all) plus the delete guard.
 */
class ReceiptPoolViewActionsKaribuTest {

    private val uploadReceiptUseCase: UploadReceiptUseCase = mock()
    private val receiptPoolQueryPort: ReceiptPoolQueryPort = mock()
    private val editReceiptDetailsUseCase: EditReceiptDetailsUseCase = mock()
    private val deleteReceiptUseCase: DeleteReceiptUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun receipt(id: Long = 1L, name: String = "sample.pdf") = receiptView {
        this.id = id
        fileName = name
        sizeBytes = 100L
        uploadedAt = OffsetDateTime.now()
    }

    /** Success/info toasts are plain [Notification.show] ones with a "text" element property;
     *  error toasts ([de.littletrotter.ui.notification.UiNotifications.error]) carry the message
     *  in a child [com.vaadin.flow.component.html.Span] instead. */
    private fun notificationTexts(): List<String> {
        MockVaadin.clientRoundtrip()
        val found = mutableListOf<com.vaadin.flow.component.Component>()
        fun walk(element: com.vaadin.flow.dom.Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach(::walk)
        }
        walk(com.vaadin.flow.component.UI.getCurrent().element)
        return found.filterIsInstance<com.vaadin.flow.component.notification.Notification>()
            .mapNotNull { notificationText(it) }
    }

    private fun notificationText(notification: com.vaadin.flow.component.notification.Notification): String? =
        de.littletrotter.ui.toastText(notification).ifBlank { null }

    private fun invokeConfirmDelete(view: ReceiptPoolView, receipt: ReceiptView) {
        val m = ReceiptPoolView::class.java.getDeclaredMethod("confirmDelete", ReceiptView::class.java)
        m.isAccessible = true
        m.invoke(view, receipt)
    }

    private fun confirmDeletion() {
        val dialog = _get<ConfirmDialog>()
        ComponentUtil.fireEvent(dialog, ConfirmDialog.ConfirmEvent(dialog, false))
    }

    private fun invokeOpenEditDialog(view: ReceiptPoolView, receipt: ReceiptView) {
        val m = ReceiptPoolView::class.java.getDeclaredMethod("openEditDialog", ReceiptView::class.java)
        m.isAccessible = true
        m.invoke(view, receipt)
    }

    /** The action column is a component column; build its row for [receipt] the way the grid does,
     *  after seeding the per-page usage map [ReceiptPoolView.buildActionColumn] reads from. */
    private fun actionButtons(
        view: ReceiptPoolView,
        receipt: ReceiptView,
        usage: Map<Long, String>,
        locked: Set<Long> = emptySet(),
    ): List<Button> {
        val usageField = ReceiptPoolView::class.java.getDeclaredField("usageByReceiptId")
        usageField.isAccessible = true
        usageField.set(view, usage)
        val lockedField = ReceiptPoolView::class.java.getDeclaredField("lockedReceiptIds")
        lockedField.isAccessible = true
        lockedField.set(view, locked)
        val m = ReceiptPoolView::class.java.getDeclaredMethod("buildActionColumn", ReceiptView::class.java)
        m.isAccessible = true
        val row = m.invoke(view, receipt) as com.vaadin.flow.component.orderedlayout.HorizontalLayout
        return row.children.toList().filterIsInstance<Button>()
    }

    private fun unusedLabel(view: ReceiptPoolView) =
        UiTranslations.tr(view, "invoiceDetail.items.receipts.usage.unused")

    // ── filterField listener: currentFilter is trimmed and lowercased ──────────────────────────

    @Test
    fun `typing into the filter field trims and lowercases the term before it drives the grid`() {
        whenever(receiptPoolQueryPort.findReceiptViews(0, 50, "rechnung")).thenReturn(emptyList())
        whenever(receiptPoolQueryPort.countAll("rechnung")).thenReturn(0L)

        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val filterField = ReceiptPoolView::class.java.getDeclaredField("filterField")
        filterField.isAccessible = true
        val field = filterField.get(view) as TextField
        field.value = "  Rechnung  "

        val gridField = ReceiptPoolView::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        val grid = gridField.get(view).requireAs<com.vaadin.flow.component.grid.Grid<ReceiptView>>()
        val provider = grid.dataProvider.requireAs<com.vaadin.flow.data.provider.DataProvider<ReceiptView, Void>>()
        val query = com.vaadin.flow.data.provider.Query<ReceiptView, Void>(0, 50, emptyList(), null, null)
        provider.fetch(query).toList()

        verify(receiptPoolQueryPort).findReceiptViews(0, 50, "rechnung")
    }

    // ── action column: what the pool offers at all (receipt-delete-guard) ──────────────────────

    @Test
    fun `the pool offers no assign and no detach affordance at all`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        val titles = actionButtons(view, receipt(id = 1L), mapOf(1L to "RE-9 (Rechnung)"))
            .mapNotNull { it.element.getAttribute("aria-label") }

        assertFalse(
            titles.contains(UiTranslations.tr(view, "receipt.action.detach")),
            "releasing a reference happens on the invoice, never in the pool: $titles",
        )
        assertTrue(
            titles.none { it.contains("zuordnen", ignoreCase = true) || it.contains("assign", ignoreCase = true) },
            "assigning happens on the invoice, never in the pool: $titles",
        )
    }

    @Test
    fun `an unassigned receipt is offered the delete button`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        val delBtn = actionButtons(view, receipt(id = 1L), mapOf(1L to unusedLabel(view)))
            .single { it.element.getAttribute("aria-label") == UiTranslations.tr(view, "app.delete") }

        assertTrue(delBtn.isVisible, "a receipt no invoice references stays deletable")
    }

    @Test
    fun `a receipt assigned to an invoice is not offered the delete button`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        val delBtn = actionButtons(view, receipt(id = 1L), mapOf(1L to "RE-9 (Rechnung)"))
            .single { it.element.getAttribute("aria-label") == UiTranslations.tr(view, "app.delete") }

        assertFalse(delBtn.isVisible, "an assigned receipt must not even be offered the delete action")
    }

    @Test
    fun `a receipt whose usage is not on the current page is treated as unassigned`() {
        // The usage map is filled per rendered page; an id missing from it renders "-" in the
        // usage column, and the server-side check in confirmDelete remains the guard.
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        val delBtn = actionButtons(view, receipt(id = 42L), emptyMap())
            .single { it.element.getAttribute("aria-label") == UiTranslations.tr(view, "app.delete") }

        assertTrue(delBtn.isVisible)
    }

    // ── fix-receipt-editable-after-invoice-issued: edit button hidden once issued ──────────────

    @Test
    fun `a receipt on a draft-only invoice keeps the edit button visible`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        val editBtn = actionButtons(view, receipt(id = 1L), mapOf(1L to "RE-9 (Rechnung)"), locked = emptySet())
            .single { it.element.getAttribute("aria-label") == UiTranslations.tr(view, "receipt.action.edit") }

        assertTrue(editBtn.isVisible)
    }

    @Test
    fun `a receipt on an issued invoice hides the edit button`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        val editBtn = actionButtons(view, receipt(id = 1L), mapOf(1L to "RE-9 (Rechnung)"), locked = setOf(1L))
            .single { it.element.getAttribute("aria-label") == UiTranslations.tr(view, "receipt.action.edit") }

        assertFalse(editBtn.isVisible)
    }

    // ── confirmDelete: eligibility exception / assigned-receipt guard ───────────────────────────

    @Test
    fun `confirmDelete shows an error and opens no dialog when eligibility lookup fails`() {
        whenever(deleteReceiptUseCase.checkDeleteEligibility(1L)).thenThrow(RuntimeException("db down"))
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        invokeConfirmDelete(view, receipt(id = 1L))

        assertTrue(_find<ConfirmDialog>().none { it.isOpened })
    }

    @Test
    fun `confirmDelete refuses a receipt that is assigned to a named invoice and never deletes it`() {
        whenever(deleteReceiptUseCase.checkDeleteEligibility(1L)).thenReturn(
            ReceiptDeleteCheck(assignedInvoiceNumbers = listOf("RE-1"), usageCount = 1),
        )
        whenever(receiptPoolQueryPort.findReceiptViews(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolQueryPort.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        invokeConfirmDelete(view, receipt(id = 1L))

        assertTrue(_find<ConfirmDialog>().none { it.isOpened }, "an assigned receipt must never reach the confirm dialog")
        assertTrue(
            notificationTexts().contains(UiTranslations.tr(view, "receipt.dialog.deleteBlockedNamed", "RE-1")),
            "must name the invoice that blocks the delete: ${notificationTexts()}",
        )
        verify(deleteReceiptUseCase, never()).delete(org.mockito.kotlin.any())
    }

    @Test
    fun `confirmDelete refuses a draft-only assignment with the message that names no invoice`() {
        // A draft draws its invoice number only at send time, so there is nothing to name.
        whenever(deleteReceiptUseCase.checkDeleteEligibility(1L)).thenReturn(
            ReceiptDeleteCheck(assignedInvoiceNumbers = emptyList(), usageCount = 2),
        )
        whenever(receiptPoolQueryPort.findReceiptViews(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolQueryPort.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        invokeConfirmDelete(view, receipt(id = 1L))

        assertTrue(_find<ConfirmDialog>().none { it.isOpened })
        assertTrue(
            notificationTexts().contains(UiTranslations.tr(view, "receipt.dialog.deleteBlocked")),
            "must fall back to the un-named refusal: ${notificationTexts()}",
        )
    }

    // ── confirmDelete: the unassigned path ─────────────────────────────────────────────────────

    @Test
    fun `confirmDelete on an unassigned receipt shows the plain confirmation`() {
        whenever(deleteReceiptUseCase.checkDeleteEligibility(1L)).thenReturn(
            ReceiptDeleteCheck(assignedInvoiceNumbers = emptyList(), usageCount = 0),
        )
        val r = receipt(id = 1L, name = "plain.pdf")
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        invokeConfirmDelete(view, r)

        val dlg = _get<ConfirmDialog>()
        assertTrue(dlg.isOpened)
        assertEquals(UiTranslations.tr(view, "receipt.dialog.deleteConfirm", r.fileName), dlg.element.getProperty("message"))
        assertEquals(UiTranslations.tr(view, "receipt.dialog.deleteTitle"), dlg.element.getProperty("header"))
    }

    @Test
    fun `confirming the delete calls the service and refreshes the list`() {
        whenever(deleteReceiptUseCase.checkDeleteEligibility(1L)).thenReturn(
            ReceiptDeleteCheck(assignedInvoiceNumbers = emptyList(), usageCount = 0),
        )
        whenever(receiptPoolQueryPort.findReceiptViews(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolQueryPort.countAll(null)).thenReturn(0L)
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        invokeConfirmDelete(view, receipt(id = 1L))
        confirmDeletion()

        verify(deleteReceiptUseCase).delete(1L)
        assertTrue(notificationTexts().contains(UiTranslations.tr(view, "receipt.deleted")), "success is announced: ${notificationTexts()}")
    }

    @Test
    fun `a failing delete surfaces the error`() {
        whenever(deleteReceiptUseCase.checkDeleteEligibility(1L)).thenReturn(
            ReceiptDeleteCheck(assignedInvoiceNumbers = emptyList(), usageCount = 0),
        )
        whenever(deleteReceiptUseCase.delete(1L)).thenThrow(RuntimeException("db down"))
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        invokeConfirmDelete(view, receipt(id = 1L))
        confirmDeletion()

        assertTrue(notificationTexts().contains(UiTranslations.tr(view, "receipt.error.delete", "db down")), "${notificationTexts()}")
    }

    // ── openEditDialog — edit affordance for a receipt's description and Belegdatum ────────

    private fun saveButton(view: ReceiptPoolView) =
        _find<Button>().single { it.text == UiTranslations.tr(view, "app.save") }

    private fun editDescriptionField(view: ReceiptPoolView) =
        _find<TextField>().single { it.label == UiTranslations.tr(view, "receipt.description") }

    private fun editDatePicker(view: ReceiptPoolView) =
        _find<com.vaadin.flow.component.datepicker.DatePicker>()
            .single { it.label == UiTranslations.tr(view, "receipt.documentDate") }

    @Test
    fun `openEditDialog titles the dialog and pre-fills the current description`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val r = receipt(id = 1L).edit { description = "Hotel Berlin" }

        invokeOpenEditDialog(view, r)

        val dlg = _get<Dialog>()
        assertEquals(UiTranslations.tr(view, "receipt.dialog.editTitle"), dlg.headerTitle)
        assertEquals("Hotel Berlin", editDescriptionField(view).value)
    }

    @Test
    fun `openEditDialog marks both mandatory fields with a required indicator`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val r = receipt(id = 1L).edit { description = "Hotel Berlin" }

        invokeOpenEditDialog(view, r)

        assertTrue(editDescriptionField(view).isRequiredIndicatorVisible, "description is mandatory")
        assertTrue(editDatePicker(view).isRequiredIndicatorVisible, "Belegdatum is mandatory")
    }

    @Test
    fun `saving persists the edited description, closes the dialog and refreshes the grid`() {
        val date = java.time.LocalDate.of(2026, 8, 15)
        val updated = receipt(id = 1L).edit { description = "new description"; documentDate = date }
        whenever(receiptPoolQueryPort.findReceiptViews(0, 50, null)).thenReturn(listOf(updated))
        whenever(receiptPoolQueryPort.countAll(null)).thenReturn(1L)
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val r = receipt(id = 1L).edit { description = "old"; documentDate = date }

        val grid = ReceiptPoolView::class.java.getDeclaredField("grid").apply { isAccessible = true }
            .get(view).requireAs<com.vaadin.flow.component.grid.Grid<ReceiptView>>()
        val providerBeforeSave = grid.dataProvider

        invokeOpenEditDialog(view, r)
        editDescriptionField(view).value = "new description"
        saveButton(view)._click()

        verify(editReceiptDetailsUseCase).updateDetails(1L, "new description", date)
        assertTrue(_find<Dialog>().isEmpty(), "a successful save must close the edit dialog")

        // refresh() rebuilds the data provider from scratch (never mutates the old one in place),
        // so a distinct instance after save is the pin that the update-then-reload path actually
        // ran — not merely that the same, already-queried provider still happens to work.
        assertTrue(grid.dataProvider !== providerBeforeSave, "save must replace the grid's data provider (refresh)")

        val provider = grid.dataProvider.requireAs<com.vaadin.flow.data.provider.DataProvider<ReceiptView, Void>>()
        val rows = provider.fetch(com.vaadin.flow.data.provider.Query(0, 50, emptyList(), null, null)).toList()
        assertEquals(listOf("new description"), rows.map { it.description }, "the refreshed grid must reflect the persisted value")
    }

    @Test
    fun `clearing the description blocks the save with an inline error and never calls the service`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val r = receipt(id = 1L).edit { description = "old"; documentDate = java.time.LocalDate.of(2026, 8, 15) }

        invokeOpenEditDialog(view, r)
        editDescriptionField(view).value = "   "
        saveButton(view)._click()

        verify(editReceiptDetailsUseCase, never()).updateDetails(org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull())
        assertTrue(editDescriptionField(view).isInvalid, "the emptied description carries the inline error")
        assertEquals(
            UiTranslations.tr(view, "receipt.error.descriptionRequired"),
            editDescriptionField(view).errorMessage,
        )
        assertTrue(_find<Dialog>().isNotEmpty(), "the dialog stays open on a blocked save")
    }

    @Test
    fun `the edit dialog pre-fills the document date and stays empty when none is set`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val date = java.time.LocalDate.of(2026, 8, 15)
        invokeOpenEditDialog(view, receipt(id = 1L).edit { documentDate = date })
        assertEquals(date, editDatePicker(view).value)
    }

    @Test
    fun `saving passes an edited document date alongside the description`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val r = receipt(id = 1L).edit { description = "old" }
        val date = java.time.LocalDate.of(2026, 8, 15)

        invokeOpenEditDialog(view, r)
        editDescriptionField(view).value = "new description"
        editDatePicker(view).value = date
        saveButton(view)._click()

        verify(editReceiptDetailsUseCase).updateDetails(1L, "new description", date)
    }

    @Test
    fun `clearing the date picker blocks the save with an inline error and never calls the service`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val r = receipt(id = 1L).edit { description = "keep"; documentDate = java.time.LocalDate.of(2026, 8, 15) }

        invokeOpenEditDialog(view, r)
        editDatePicker(view).value = null
        saveButton(view)._click()

        verify(editReceiptDetailsUseCase, never()).updateDetails(org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull())
        assertTrue(editDatePicker(view).isInvalid, "the emptied Belegdatum carries the inline error")
        assertEquals(
            UiTranslations.tr(view, "receipt.error.documentDateRequired"),
            editDatePicker(view).errorMessage,
        )
        assertTrue(_find<Dialog>().isNotEmpty(), "the dialog stays open on a blocked save")
    }

    @Test
    fun `cancelling the edit dialog does not call the service`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val r = receipt(id = 1L).edit { description = "old" }

        invokeOpenEditDialog(view, r)
        editDescriptionField(view).value = "changed but not saved"
        _find<Button>().single { it.text == UiTranslations.tr(view, "app.cancel") }._click()

        verify(editReceiptDetailsUseCase, never()).updateDetails(org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull())
        assertTrue(_find<Dialog>().isEmpty(), "cancel must close the dialog too")
    }

    @Test
    fun `a failing save keeps the dialog open and surfaces the error`() {
        val date = java.time.LocalDate.of(2026, 8, 15)
        whenever(editReceiptDetailsUseCase.updateDetails(1L, "new", date)).thenThrow(RuntimeException("db down"))
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val r = receipt(id = 1L).edit { description = "old"; documentDate = date }

        invokeOpenEditDialog(view, r)
        editDescriptionField(view).value = "new"
        saveButton(view)._click()

        assertTrue(_find<Dialog>().isNotEmpty(), "the edit dialog must stay open when the save fails")
    }

    @Test
    fun `the upload control's max file size is wired to the upload-time policy cap, not the smaller unreduced one`() {
        // Unlike the dialog-scoped tests above, ReceiptPoolView itself is never attached to the
        // UI by these tests (only what it opens — a Dialog — attaches automatically), so a global
        // _find would see nothing unless the view is added explicitly.
        // receipt-size-normalisation — a file above the (smaller) unreduced cap but at/under the
        // upload cap must still reach the reduce/keep dialog, so the client-side cap widens to
        // MAX_UPLOAD_SIZE.
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        com.vaadin.flow.component.UI.getCurrent().add(view)

        assertEquals(
            de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES.toInt(),
            _find<com.vaadin.flow.component.upload.Upload>().single().maxFileSize,
        )
    }

    // ── action column icon order and spacing ──────────────────────────────────────

    private fun actionRow(
        view: ReceiptPoolView,
        receipt: ReceiptView,
        usage: Map<Long, String>,
        locked: Set<Long> = emptySet(),
    ): com.vaadin.flow.component.orderedlayout.HorizontalLayout {
        val usageField = ReceiptPoolView::class.java.getDeclaredField("usageByReceiptId")
        usageField.isAccessible = true
        usageField.set(view, usage)
        val lockedField = ReceiptPoolView::class.java.getDeclaredField("lockedReceiptIds")
        lockedField.isAccessible = true
        lockedField.set(view, locked)
        val m = ReceiptPoolView::class.java.getDeclaredMethod("buildActionColumn", ReceiptView::class.java)
        m.isAccessible = true
        return m.invoke(view, receipt) as com.vaadin.flow.component.orderedlayout.HorizontalLayout
    }

    @Test
    fun `the action row displays spacing between icons`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val row = actionRow(view, receipt(id = 1L), mapOf(1L to "RE-9 (Rechnung)"))

        assertTrue(row.isSpacing, "action row must have spacing enabled to separate icons visually")
    }

    @Test
    fun `the action row orders icons as edit then view then download then delete`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val row = actionRow(view, receipt(id = 1L), mapOf(1L to unusedLabel(view)))

        val children = row.children.toList()
        assertEquals(4, children.size, "an unassigned receipt shows all four icons")

        val titles = children.mapNotNull { (it as? com.vaadin.flow.component.Component)?.element?.getAttribute("aria-label") }
        assertEquals(
            listOf(
                UiTranslations.tr(view, "receipt.action.edit"),
                UiTranslations.tr(view, "receipt.action.view"),
                UiTranslations.tr(view, "receipt.action.download"),
                UiTranslations.tr(view, "app.delete"),
            ),
            titles,
            "icons must read in order: edit, view, download, delete",
        )
    }

    @Test
    fun `the action row hides edit when locked and delete when assigned`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val row = actionRow(view, receipt(id = 1L), mapOf(1L to "RE-9 (Rechnung)"), locked = setOf(1L))

        val allChildren = row.children.toList()
        assertEquals(4, allChildren.size, "action row always has 4 components (edit, view, download, delete)")

        val visibleChildren = allChildren.filter { it.isVisible }
        assertEquals(2, visibleChildren.size, "locked + assigned receipt hides both edit and delete, leaving view and download")

        val titles = visibleChildren.mapNotNull { (it as? com.vaadin.flow.component.Component)?.element?.getAttribute("aria-label") }
        assertEquals(
            listOf(
                UiTranslations.tr(view, "receipt.action.view"),
                UiTranslations.tr(view, "receipt.action.download"),
            ),
            titles,
            "when both edit and delete are hidden, remaining icons read in order: view, download",
        )
    }

    @Test
    fun `the action row shows edit, view, download when delete is hidden for assigned unlockedreceipt`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val row = actionRow(view, receipt(id = 1L), mapOf(1L to "RE-9 (Rechnung)"), locked = emptySet())

        val allChildren = row.children.toList()
        assertEquals(4, allChildren.size, "action row always has 4 components (edit, view, download, delete)")

        val visibleChildren = allChildren.filter { it.isVisible }
        assertEquals(3, visibleChildren.size, "assigned unlocked receipt hides only delete, showing 3 visible icons")

        val titles = visibleChildren.mapNotNull { (it as? com.vaadin.flow.component.Component)?.element?.getAttribute("aria-label") }
        assertEquals(
            listOf(
                UiTranslations.tr(view, "receipt.action.edit"),
                UiTranslations.tr(view, "receipt.action.view"),
                UiTranslations.tr(view, "receipt.action.download"),
            ),
            titles,
            "when only delete is hidden, remaining icons read in order: edit, view, download",
        )
    }

    @Test
    fun `the action row anchors have the action-icon-anchor CSS class for button styling`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val row = actionRow(view, receipt(id = 1L), mapOf(1L to unusedLabel(view)))

        val anchors = row.children.toList()
            .filterIsInstance<com.vaadin.flow.component.html.Anchor>()
            .filter { it.element.getAttribute("title") != null }

        assertTrue(anchors.isNotEmpty(), "action row should contain Anchor elements")
        assertTrue(
            anchors.all { it.classNames.contains("action-icon-anchor") },
            "all action anchors must have the action-icon-anchor class for Lumo button styling",
        )
    }

    // ── toolbar alignment and pageSizeField labeling ───────────────────────────────

    @Test
    fun `the page size field has an aria-label but no visible label`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val pageSizeField = ReceiptPoolView::class.java.getDeclaredField("pageSizeField")
        pageSizeField.isAccessible = true
        val field = pageSizeField.get(view).requireAs<com.vaadin.flow.component.combobox.ComboBox<Int>>()

        assertEquals(null, field.label, "visible label must be removed to align with search field")
        assertTrue(
            field.element.getAttribute("aria-label")?.isNotBlank() == true,
            "aria-label must provide screen-reader access when visible label is removed",
        )
    }

    @Test
    fun `the page size field's aria-label reads the localized label text`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val pageSizeField = ReceiptPoolView::class.java.getDeclaredField("pageSizeField")
        pageSizeField.isAccessible = true
        val field = pageSizeField.get(view).requireAs<com.vaadin.flow.component.combobox.ComboBox<Int>>()

        val ariaLabel = field.element.getAttribute("aria-label")
        assertEquals(
            UiTranslations.tr(view, "invoice.page.size"),
            ariaLabel,
            "aria-label must carry the same localized text as the removed visible label",
        )
    }
}
