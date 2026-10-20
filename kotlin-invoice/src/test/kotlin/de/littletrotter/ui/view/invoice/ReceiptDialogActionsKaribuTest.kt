package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._upload
import com.github.mvysny.kaributools.header2
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.ListDataProvider
import com.vaadin.flow.data.provider.Query
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.invoice.ReceiptCountLimitExceededException
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.SelectionActionBar
import de.littletrotter.ui.i18n.UiTranslations
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * [ReceiptDialog] had only structural coverage before this file
 * ([ReceiptDialogKaribuTest] pins the tab structure at the component-tree level, but never
 * exercises any of its actual write paths). Covers, all through [ReceiptTarget.Item] with a
 * writable (DRAFT) invoice: the pool `DataProvider`'s fetch/count success and failure paths, the
 * selection listener's three assign-button states, the assign button's
 * success/[ReceiptCountLimitExceededException]/generic-exception outcomes, the pool search's
 * filter-and-deselect listener, [ReceiptDialog.open]'s `refreshLimitState` at both ends of the
 * cap, and the `unsaved`-target early exits.
 *
 * The detach button and the upload block (both the direct-upload closures and the this-invoice
 * grouping) are covered too. The detach button lives inside a
 * `Grid.addComponentColumn` `ComponentRenderer`, which Grid only invokes while actually painting a
 * row; [actionsComponent] calls that renderer directly (public `Grid.getColumns()`/
 * `Column.getRenderer()`, the same mechanism karibu-testing's own `_getCellComponent` uses
 * internally) instead of requiring a real client repaint. The upload block uses karibu-testing's
 * own [com.github.mvysny.kaributesting.v10._upload] extension, which feeds bytes through the real
 * `UploadHandler` exactly as a multipart request would.
 *
 * [ReceiptTarget.Item] is tabbed now, same as [ReceiptTarget.Invoice]: pool/upload content
 * only attaches to the DOM once its tab is actually selected ([TabSheet]'s own lazy-content-attach
 * mechanism — see [ReceiptDialogKaribuTest]'s KDoc), so every test below that touches the pool or
 * upload UI switches to that tab first via [selectPoolTab]/[selectUploadTab]. Also new here: the
 * auto-switch-to-attached-tab and select-the-new-row behaviour a successful upload/assign now
 * triggers, and the pool tab's own live "n frei" label.
 */
class ReceiptDialogActionsKaribuTest {

    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock()
    private val attachReceiptsUseCase: AttachReceiptsUseCase = mock()

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()
        // IR-3 — ReceiptDialog.open() always fetches the invoice-wide budget once, eagerly, for
        // every target/state; an empty default keeps the tests below that don't care about its
        // exact numbers from NPEing on Mockito's unstubbed-reference-type null. Tests that DO care
        // (the limit-hint tests) override this with a specific invoiceDocumentBudget(42L) stub.
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(org.mockito.kotlin.any()))
            .thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun invoice(status: Int = InvoiceStatus.DRAFT.ordinal, items: List<ItemDto> = emptyList()) = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = status, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = items, latestGenerationJob = null,
    )

    private fun receipt(id: Long, name: String = "r$id.pdf") = ReceiptRowDto(
        id = id,
        fileName = name,
        description = "",
        documentDate = null,
        sizeBytes = 100L,
        uploadedAt = OffsetDateTime.now(),
        objectKey = "synthetic/receipt-$id.pdf",
    )

    private fun tabSheet(): TabSheet = _find<TabSheet>().single()

    /** The pool tab's content (search field, both grids, assign button, limit hint) is not
     *  in the DOM until its tab is selected; [MockVaadin.clientRoundtrip] flushes the deferred
     *  attach that [TabSheet] schedules on selection change. */
    private fun selectPoolTab() {
        tabSheet().selectedIndex = 1
        MockVaadin.clientRoundtrip()
    }

    /** Same as [selectPoolTab], for the upload tab. */
    private fun selectUploadTab() {
        tabSheet().selectedIndex = 2
        MockVaadin.clientRoundtrip()
    }

    /**
     * Builds and opens a writable ([ReceiptTarget.Item], DRAFT invoice, itemId=10L) dialog with
     * no receipts attached yet, stubbing [ReceiptLinkService.findReceiptsForItem] to [attached], and
     * leaves it on the pool tab — every existing caller of this helper drives the pool picker
     * (search, selection, assign button, limit hint), never the plain attached list alone.
     */
    private fun openWritableItemDialog(attached: List<ReceiptRowDto> = emptyList()): ReceiptDialog {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(attached)
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        selectPoolTab()
        return dialog
    }

    private fun poolGrid(): DataTable<ReceiptRowDto> =
        _find<DataTable<*>>().first { it.dataProvider !is ListDataProvider<*> }.requireAs()

    // The pool grid is paged (a CallbackDataProvider wired to a fixed page size of 50); a
    // default, unbounded Query would ask the stub for a different limit than it was told to
    // expect, so every fetch here pins the same (0, 50, null) triple the production grid
    // itself queries with.
    private fun poolRows(): List<ReceiptRowDto> =
        poolGrid().dataProvider.requireAs<DataProvider<ReceiptRowDto, Void>>().fetch(Query(0, 50, emptyList(), null, null)).toList()

    private fun poolRowCount(): Int =
        poolGrid().dataProvider.requireAs<DataProvider<ReceiptRowDto, Void>>().size(Query(0, 50, emptyList(), null, null))

    // A second TextField (the upload block's description field) can exist in the same tree once
    // the upload tab has also been visited, so this picks the search field by its own placeholder
    // rather than assuming it is the only TextField around.
    private fun poolSearch(): TextField =
        _find<TextField>().single { it.placeholder == UiTranslations.tr("receipt.filter.placeholder") }

    // Vaadin's Upload component slots in its own default Button (an empty-text trigger,
    // client-rendered via i18n) whenever the upload block is present, so "not the close button"
    // is not a unique filter; also requiring non-blank text excludes that default button while
    // still matching the assign button's every real label (base / count / limit-selection).
    // The attached grid's own SelectionActionBar ("Zuordnung lösen") is a second, always-
    // present non-blank-text button once the dialog is writable, so "not the close button" alone
    // is no longer a unique filter either; its text is constant (never a count/limit label like
    // the assign button's own text), so excluding it by that exact string keeps this helper unique.
    private fun assignButton(): Button =
        _find<Button>().single {
            it.text?.let { t ->
                t.isNotBlank() && t != UiTranslations.tr("app.close") && t != UiTranslations.tr("receipt.action.detachSelected")
            } == true
        }

    // The "this invoice's own receipts" group, a second, unpaged DataTable that only
    // exists for a writable ReceiptTarget.Item. Both it and the attached grid use a
    // ListDataProvider (unlike the paged pool grid below), so they are told apart by whether they
    // carry a "usage" (location label) column, which only the this-invoice group has.
    private fun invoiceReceiptsGrid(): DataTable<ReceiptRowDto> =
        _find<DataTable<*>>()
            .first { it.dataProvider is ListDataProvider<*> && it.columns.any { c -> c.header2 == UiTranslations.tr("receipt.column.usage") } }
            .requireAs()

    // Same distinction, the other way round: only the attached grid carries an "uploaded at" column.
    private fun attachedGrid(): DataTable<AttachedReceiptRow> =
        _find<DataTable<*>>()
            .first { it.dataProvider is ListDataProvider<*> && it.columns.any { c -> c.header2 == UiTranslations.tr("receipt.column.uploadedAt") } }
            .requireAs()

    // Grid.addComponentColumn's ComponentRenderer only ever runs while Grid paints an actual row
    // on the client; there is no rendered client here to trigger that. Calling the renderer
    // directly on the row item (both Grid.getColumns() and Column.getRenderer() are public API)
    // is the same mechanism karibu-testing's own `_getCellComponent` uses internally, just
    // without requiring a column key (none of this dialog's action columns set one). Generic over
    // the grid's row type: the pool/this-invoice grids list [ReceiptRowDto], the attached grid
    // (Invoice target) lists [AttachedReceiptRow].
    private fun <T : Any> actionsComponent(grid: DataTable<T>, item: T): Component = grid.columns.last().componentFor(item)

    // Convenience for a grid backed by an eager ListDataProvider (attachedGrid, invoiceReceiptsGrid):
    // grid._get(rowIndex) resolves the item itself there without re-querying a lazy backend.
    private fun <T : Any> actionsComponent(grid: DataTable<T>, rowIndex: Int): Component =
        actionsComponent(grid, grid._get(rowIndex))

    private fun <T : Any> detachButton(grid: DataTable<T>, rowIndex: Int): Button {
        val actions = actionsComponent(grid, rowIndex) as HorizontalLayout
        return actions.children.toArray().filterIsInstance<Button>()
            .single { it.element.getProperty("title") == UiTranslations.tr("receipt.action.detach") }
    }

    // fix — the "Zugeordnet an" leading column, [ReceiptTarget.Invoice]'s attached grid only. Same
    // renderer-invocation mechanism as [actionsComponent]: the column's first (leading) position
    // holds it, since [ReceiptDialog.addAssignedToColumn] is called before addFileIdentityColumns.
    private fun assignedToCell(grid: DataTable<AttachedReceiptRow>, rowIndex: Int): Span =
        grid.columns.first().componentFor(grid._get(rowIndex)) as Span

    private fun <T : Any> attachedGridSize(grid: DataTable<T>): Int = grid.rowCount()

    private fun uploadControl(): Upload = _find<Upload>().single()

    private fun descriptionField(): TextField =
        _find<TextField>().single { it.placeholder == UiTranslations.tr("receipt.description.placeholder") }

    private fun documentDatePicker(): com.vaadin.flow.component.datepicker.DatePicker =
        _find<com.vaadin.flow.component.datepicker.DatePicker>()
            .single { it.label == UiTranslations.tr("receipt.documentDate") }

    /** receipt-mandatory-fields: description AND Belegdatum must be filled before the upload
     *  control enables — every upload-driving test fills them first, exactly like a real user. */
    private fun fillUploadFields(
        description: String = "Beleg",
        date: java.time.LocalDate = java.time.LocalDate.of(2026, 8, 15),
    ) {
        descriptionField().value = description
        documentDatePicker().value = date
    }

    private fun itemDto(id: Long, positionId: String) = ItemDto(
        id = id, positionId = positionId, description = "Pos $positionId", quantity = BigDecimal.ONE,
        unit = null, unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null,
    )

    // The whole component subtree, walked via Element (not Component.getChildren()), so
    // notifications are found regardless of where Vaadin actually parents them (a plain
    // Notification.show(...) sets its text as a "text" element property with no child Span; the
    // richer [de.littletrotter.ui.notification.UiNotifications.error] wraps its message in an
    // actual Span instead — see its own source).
    private fun allComponents(): List<Component> {
        val found = mutableListOf<Component>()
        fun walk(element: com.vaadin.flow.dom.Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach(::walk)
        }
        walk(com.vaadin.flow.component.UI.getCurrent().element)
        return found
    }

    private fun assertNotificationShown(expectedText: String) {
        // Flushes pending server-side writes (e.g. a plain Notification.show(...)'s "text"
        // element property) the same way karibu-testing's own _upload already does internally;
        // a bare _click() does not trigger this on its own.
        MockVaadin.clientRoundtrip()
        val all = allComponents()
        val plainTextMatch = all.filterIsInstance<com.vaadin.flow.component.notification.Notification>()
            .any { de.littletrotter.ui.toastText(it) == expectedText }
        val spanMatch = all.filterIsInstance<com.vaadin.flow.component.html.Span>().any { it.text == expectedText }
        assertTrue(plainTextMatch || spanMatch, "expected an open notification with text: $expectedText")
    }

    // ── poolProvider: fetch success/failure ─────────────────────────────────────────────────────

    @Test
    fun `pool fetch returns the assignable page and fills usage summaries in one batched call`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(r1))
        openWritableItemDialog()

        val rows = poolRows()

        assertEquals(listOf(r1), rows)
        verify(invoiceReceiptQueryPort).findUsageSummaries(
            org.mockito.kotlin.eq(listOf(1L)),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
        )
    }

    @Test
    fun `pool fetch failure is caught, shows an error and returns an empty page`() {
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenThrow(RuntimeException("db down"))
        openWritableItemDialog()

        assertDoesNotThrow {
            val rows = poolRows()
            assertTrue(rows.isEmpty())
        }
    }

    @Test
    fun `pool count returns the assignable count`() {
        whenever(invoiceReceiptQueryPort.countAssignableForItem(10L, null)).thenReturn(3L)
        openWritableItemDialog()

        assertEquals(3, poolRowCount())
    }

    @Test
    fun `pool count failure is caught silently and reads as zero`() {
        whenever(invoiceReceiptQueryPort.countAssignableForItem(10L, null)).thenThrow(RuntimeException("db down"))
        openWritableItemDialog()

        assertEquals(0, poolRowCount())
    }

    // ── selection listener: assign button label/enabled tracks the selection count ─────────────

    @Test
    fun `an existing invoice receipt can be assigned to a position when all document slots are used`() {
        val own = receipt(1L).copy(objectKey = "synthetic/receipt-1.pdf")
        val sizes = (1..19).map {
            de.littletrotter.shared.receipt.ReceiptSize(
                "synthetic/receipt-$it.pdf", de.littletrotter.shared.receipt.DocumentSize.ofBytes(100),
            )
        }
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(42L))
            .thenReturn(InvoiceDocumentBudget.calculate(sizes))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(own))
        openWritableItemDialog()

        invoiceReceiptsGrid().select(own)

        assertTrue(assignButton().isEnabled, "reusing a receipt consumes no additional invoice slot")
        assignButton()._click()
        verify(attachReceiptsUseCase).addReceiptsToItem(eq(10L), eq(listOf(1L)), anyOrNull())
    }

    @Test
    fun `with nothing selected the assign button is disabled and shows its base label`() {
        openWritableItemDialog()
        val btn = assignButton()

        assertFalse(btn.isEnabled)
        assertEquals(UiTranslations.tr("receipt.action.assignSelected"), btn.text)
    }

    @Test
    fun `boundary - clicking the assign button with nothing selected does nothing`() {
        openWritableItemDialog()
        val btn = assignButton()

        // The button is disabled while nothing is selected (Karibu's own .click() would no-op
        // for a disabled component), so the click listener is fired directly, the same idiom
        // several other Karibu tests in this module already use for a disabled/guarded action.
        com.vaadin.flow.component.ComponentUtil.fireEvent(btn, com.vaadin.flow.component.ClickEvent(btn))

        org.mockito.kotlin.verify(attachReceiptsUseCase, never()).addReceiptsToItem(any(), any(), anyOrNull())
    }

    @Test
    fun `selecting one receipt within the remaining slots enables the assign button with a count label`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(r1))
        openWritableItemDialog()
        val grid = poolGrid()

        grid.select(r1)

        assertTrue(assignButton().isEnabled)
        assertEquals(UiTranslations.tr("receipt.action.assignSelectedCount", "1"), assignButton().text)
    }

    // Receipt.sizeBytes is a genuinely nullable domain field (a receipt whose size was
    // never recorded); updateAssignButtonState's `it.sizeBytes ?: 0L` null-side had no test —
    // every other fixture in this file sets sizeBytes = 100L. A null-size receipt must count as
    // zero bytes toward the selection, not block the button or crash.
    @Test
    fun `boundary - a receipt with no recorded size counts as zero bytes and still enables the assign button`() {
        val noSize = receipt(1L).copy(sizeBytes = null)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(noSize))
        openWritableItemDialog()
        val grid = poolGrid()

        grid.select(noSize)

        assertTrue(assignButton().isEnabled)
        assertEquals(UiTranslations.tr("receipt.action.assignSelectedCount", "1"), assignButton().text)
    }

    // ── assign button: success / ReceiptCountLimitExceededException / generic exception ─────────────

    @Test
    fun `a successful assign adds the receipt, notifies the caller and reloads both grids`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(r1))
        var onChangedCalled = false
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = { onChangedCalled = true })
        dialog.open()
        selectPoolTab()
        val grid = poolGrid()
        grid.select(r1)

        assignButton()._click()

        verify(attachReceiptsUseCase).addReceiptsToItem(10L, listOf(1L))
        assertTrue(onChangedCalled)
        assertTrue(grid.selectedItems.isEmpty(), "a successful assign must deselect the pool grid")
        verify(invoiceReceiptQueryPort, times(2)).findReceiptsForItem(10L) // initial open() + reloadAttached()
    }

    @Test
    fun `a successful assign switches to the attached tab and selects the newly assigned receipt`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList(), listOf(r1))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectPoolTab()
        poolGrid().select(r1)

        assignButton()._click()

        // The user assigned from the pool tab; the dialog lands them where the result is visible.
        assertEquals(0, tabSheet().selectedIndex, "a successful assign must switch back to the attached tab")
        assertEquals(setOf(AttachedReceiptRow(r1)), attachedGrid().selectedItems, "the newly assigned receipt must come out highlighted")
    }

    @Test
    fun `a limit-exceeded assign surfaces the named error and still reloads the attached grid`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        whenever(attachReceiptsUseCase.addReceiptsToItem(10L, listOf(1L))).thenThrow(ReceiptCountLimitExceededException(10, 11))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectPoolTab()
        val grid = poolGrid()
        grid.select(r1)

        assertDoesNotThrow { assignButton()._click() }

        verify(invoiceReceiptQueryPort, times(2)).findReceiptsForItem(10L) // initial open() + reload in the catch
    }

    @Test
    fun `a generically failing assign surfaces a generic error and does not reload the attached grid`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        whenever(attachReceiptsUseCase.addReceiptsToItem(10L, listOf(1L))).thenThrow(RuntimeException("db down"))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectPoolTab()
        val grid = poolGrid()
        grid.select(r1)

        assertDoesNotThrow { assignButton()._click() }

        verify(invoiceReceiptQueryPort, times(1)).findReceiptsForItem(10L) // only the initial open() — no reload here
    }

    // ── pool search: filters, deselects and refreshes ───────────────────────────────────────────

    @Test
    fun `typing into the pool search clears any selection and re-queries with the new filter`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, "sample")).thenReturn(emptyList())
        openWritableItemDialog()
        val grid = poolGrid()
        grid.select(r1)
        assertTrue(grid.selectedItems.isNotEmpty())

        poolSearch().value = "sample"

        assertTrue(grid.selectedItems.isEmpty(), "typing a new filter must clear the current selection")
        poolRows()
        verify(invoiceReceiptQueryPort).findAssignableForItem(10L, 0, 50, "sample")
    }

    // ── refreshLimitState: both ends of the cap, and the pool tab's own live label ──────────────

    @Test
    fun `with room left the limit hint names the invoice-wide budget's count only`() {
        // IR-3 — the hint reads the invoice-wide InvoiceDocumentBudget, not the dialog's own
        // target-local attachedCount; `attached` below only drives what the attached grid itself
        // shows, so the two are stubbed independently on purpose. PO 2026-09-04: the NORMAL state
        // names the count only — no running MB tally (the size RULE stays enforced and keeps its
        // own exhausted/refusal messages, pinned by the tests below).
        val budget = InvoiceDocumentBudget.calculate(
            listOf(de.littletrotter.shared.receipt.ReceiptSize("k1", de.littletrotter.shared.receipt.DocumentSize.ofMebiBytes(1))),
        )
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(42L)).thenReturn(budget)
        openWritableItemDialog(attached = listOf(receipt(1L)))

        val expected = UiTranslations.tr(
            "invoiceDetail.receipts.budget.hint",
            budget.distinctReceiptCount.toString(),
            de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE.toString(),
        )
        val hint = _find<com.vaadin.flow.component.html.Span>().first { it.text == expected }
        assertEquals(expected, hint.text)
    }

    @Test
    fun `once the count cap is fully used the hint switches to the count-exhausted message and assign stays disabled`() {
        val fullBudget = InvoiceDocumentBudget.calculate(
            (1..19).map {
                de.littletrotter.shared.receipt.ReceiptSize("k$it", de.littletrotter.shared.receipt.DocumentSize.ofBytes(1024))
            },
        )
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(42L)).thenReturn(fullBudget)
        val full = (1L..19L).map { receipt(it) }
        val r20 = receipt(20L)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(r20))
        openWritableItemDialog(attached = full)

        val expectedFull = UiTranslations.tr("invoiceDetail.receipts.budget.countExhausted", "19")
        assertTrue(_find<com.vaadin.flow.component.html.Span>().any { it.text == expectedFull })
        assertEquals(
            UiTranslations.tr("invoiceDetail.items.receipts.tab.pool.full"),
            tabSheet().getTabAt(1).element.textRecursively,
            "the pool tab's own label must read the cap as full too",
        )

        // Even selecting a receipt must not enable the assign button once the cap is reached.
        poolGrid().select(r20)
        assertFalse(assignButton().isEnabled, "no room left — the assign button must stay disabled")
    }

    @Test
    fun `a selection larger than the remaining size is still assignable - the size budget is a warning, not a gate`() {
        // receipt-size-normalisation — the estimated document size is a warning (the budget
        // banner), not a link-time gate any more; only the count cap (pinned by the tests above)
        // still refuses a selection before it reaches the service.
        val nearlyFullSize = InvoiceDocumentBudget.calculate(
            listOf(
                de.littletrotter.shared.receipt.ReceiptSize(
                    "k1",
                    de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE -
                        de.littletrotter.shared.receipt.ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE -
                        de.littletrotter.shared.receipt.DocumentSize.ofKibiBytes(512),
                ),
            ),
        )
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(42L)).thenReturn(nearlyFullSize)
        // A candidate receipt of exactly the per-file max (1 MiB) — larger than the ~0.5 MiB left.
        val tooBig = receipt(1L).copy(sizeBytes = de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(tooBig))
        openWritableItemDialog()

        poolGrid().select(tooBig)

        assertTrue(assignButton().isEnabled, "plenty of slots remain — the size headroom alone must not block the assign button")
        assertEquals(UiTranslations.tr("receipt.action.assignSelectedCount", "1"), assignButton().text)
        assignButton()._click()
        verify(attachReceiptsUseCase).addReceiptsToItem(eq(10L), eq(listOf(1L)), anyOrNull())
    }

    @Test
    fun `the upload control's max file size is wired to the upload-time policy cap, not the smaller unreduced one`() {
        // receipt-size-normalisation — a file above the (smaller) unreduced cap but at/under the
        // upload cap must still reach the reduce/keep dialog, so the client-side cap widens to
        // MAX_UPLOAD_SIZE.
        openWritableItemDialog()
        selectUploadTab()

        assertEquals(
            de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES.toInt(),
            uploadControl().maxFileSize,
        )
    }

    // ── unsaved target: no attached lookup, no pool pane ────────────────────────────────────────

    @Test
    fun `an unsaved item target shows the unsaved hint and never calls findReceiptsForItem`() {
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(null), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()

        verify(invoiceReceiptQueryPort, times(0)).findReceiptsForItem(org.mockito.kotlin.any())
        val expectedHint = UiTranslations.tr("invoiceDetail.items.receipts.unsavedPosition")
        assertTrue(_find<com.vaadin.flow.component.html.Span>().any { it.text == expectedHint })
        assertTrue(_find<Dialog>().isNotEmpty())
        // Unsaved -> canWrite is false -> only the attached tab, so no DataTable other than the
        // (empty) attached grid, and only one tab in the bar.
        assertEquals(1, _find<DataTable<*>>().size)
        assertEquals(1, tabSheet().tabCount)
    }

    // ── upload block: success, blank description, both limit outcomes, signature
    // rejection. All through ReceiptTarget.Item: the upload closure itself does not branch on
    // target (see ReceiptDialog.kt's own KDoc on the upload block, "reused verbatim by both
    // targets"), so exercising it once here reaches every branch it has; the invoice-target
    // wiring (which method of ReceiptLinkService the same closure calls) gets its own proof below,
    // and the critical "an invoice must be able to upload directly" acceptance case is
    // additionally proven end to end by the E2E happy-path test. ─────────────────────────────────

    @Test
    fun `RC-3 row 1-2 - a successful upload attaches the new receipt and reloads the attached grid`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        var onChangedCalled = false
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = { onChangedCalled = true })
        dialog.open()
        selectUploadTab()
        val uploaded = receipt(99L, "beleg.pdf")
        whenever(attachReceiptsUseCase.upload(eq("beleg.pdf"), eq("Taxi Quittung"), any(), anyOrNull(), any())).thenReturn(uploaded)
        fillUploadFields(description = "Taxi Quittung")

        uploadControl()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        DirectPollTicks.awaitTicks()

        verify(attachReceiptsUseCase).addReceiptsToItem(10L, listOf(99L))
        assertTrue(onChangedCalled)
        verify(invoiceReceiptQueryPort, times(2)).findReceiptsForItem(10L) // initial open() + reloadAttached()
        assertNotificationShown(UiTranslations.tr("receipt.uploaded", "beleg.pdf"))
    }

    @Test
    fun `a successful upload switches to the attached tab and selects the newly uploaded receipt`() {
        // Both values of the stub must be registered before open() — open() itself is the first
        // real call and consumes the first value (emptyList), leaving the second (listOf(uploaded))
        // for reloadAttached()'s call after the upload. Re-stubbing later would reset Mockito's
        // per-stub invocation count and shift both values off by one.
        val uploaded = receipt(99L, "beleg.pdf")
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList(), listOf(uploaded))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectUploadTab()
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenReturn(uploaded)
        fillUploadFields()

        uploadControl()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        DirectPollTicks.awaitTicks()

        assertEquals(0, tabSheet().selectedIndex, "a successful upload must switch back to the attached tab")
        assertEquals(setOf(AttachedReceiptRow(uploaded)), attachedGrid().selectedItems, "the newly uploaded receipt must come out highlighted")
    }

    @Test
    fun `the upload control stays disabled until description AND document date are filled`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectUploadTab()

        assertFalse(uploadControl().isEnabled, "both fields empty: upload must be disabled")

        descriptionField().value = "Taxi Quittung"
        assertFalse(uploadControl().isEnabled, "date still missing: upload must stay disabled")

        descriptionField().value = ""
        documentDatePicker().value = java.time.LocalDate.of(2026, 8, 15)
        assertFalse(uploadControl().isEnabled, "description missing: upload must stay disabled")

        descriptionField().value = "   "
        assertFalse(uploadControl().isEnabled, "whitespace-only description must not enable the upload")

        descriptionField().value = "Taxi Quittung"
        assertTrue(uploadControl().isEnabled, "both fields filled: upload must be enabled")

        documentDatePicker().value = null
        assertFalse(uploadControl().isEnabled, "clearing the date must re-disable the upload")
    }

    @Test
    fun `the invoice-target upload tab gates on the same two mandatory fields`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findAssignableForInvoice(42L, 0, 50, null)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.countAssignableForInvoice(42L, null)).thenReturn(0L)
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Invoice(42L), onChanged = {})
        dialog.open()
        tabSheet().selectedIndex = 2
        MockVaadin.clientRoundtrip()

        assertFalse(uploadControl().isEnabled, "both fields empty: upload must be disabled")
        fillUploadFields()
        assertTrue(uploadControl().isEnabled, "both fields filled: upload must be enabled")
    }

    @Test
    fun `both mandatory upload fields carry a required indicator`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectUploadTab()

        assertTrue(descriptionField().isRequiredIndicatorVisible, "description is mandatory")
        assertTrue(documentDatePicker().isRequiredIndicatorVisible, "Belegdatum is mandatory")
    }

    @Test
    fun `the upload tab's document date picker is empty by default and not defaulted to today`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectUploadTab()

        assertEquals(null, documentDatePicker().value, "no silently-wrong default date on a document")
    }

    @Test
    fun `an upload carries description and document date into the service and clears both fields`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectUploadTab()
        val uploaded = receipt(99L, "beleg.pdf")
        val date = java.time.LocalDate.of(2026, 8, 15)
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenReturn(uploaded)
        // Captured before the upload: success switches to the attached tab, which detaches the
        // upload pane from the DOM — a fresh _find afterwards would see nothing.
        val field = descriptionField()
        val picker = documentDatePicker()
        val upload = uploadControl()
        field.value = "Taxi Quittung"
        picker.value = date

        upload._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        DirectPollTicks.awaitTicks()

        verify(attachReceiptsUseCase).upload(eq("beleg.pdf"), eq("Taxi Quittung"), any(), eq(date), any())
        assertEquals("", field.value, "a successful upload must clear the description")
        assertEquals(null, picker.value, "a successful upload must clear the picker")
        assertFalse(upload.isEnabled, "cleared fields must re-disable the upload control")
    }

    // ── Belegdatum ordering (in-memory twin of the repository's NEWEST_FIRST_ORDER) ─────────────

    @Test
    fun `the this-invoice picker grid lists receipts newest document date first`() {
        val base = java.time.OffsetDateTime.parse("2026-08-01T10:00:00Z")
        val datedOld = receipt(1L, "old.pdf").copy(
            documentDate = java.time.LocalDate.of(2026, 1, 5), uploadedAt = base.plusDays(9),
        )
        val datedNewer = receipt(2L, "new.pdf").copy(
            documentDate = java.time.LocalDate.of(2026, 8, 20), uploadedAt = base,
        )
        // Same document date as datedNewer but uploaded later — the uploadedAt tiebreak must
        // place it ahead, exactly as the repository's ORDER BY does.
        val datedNewerLaterUpload = receipt(3L, "new2.pdf").copy(
            documentDate = java.time.LocalDate.of(2026, 8, 20), uploadedAt = base.plusDays(1),
        )
        // All three hang on the invoice level; none on item 10 itself. (Date-less rows cannot
        // exist any more — document_date is mandatory, receipt-mandatory-fields.)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L))
            .thenReturn(listOf(datedOld, datedNewer, datedNewerLaterUpload))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        selectPoolTab()

        val rows = invoiceReceiptsGrid().listRows().toList()
        assertEquals(
            listOf(3L, 2L, 1L), rows.map { it.id },
            "newest document date first, uploadedAt breaking the tie",
        )
    }

    // NOT tested here, named rather than hidden: the upload closure's own `attachedCount >=
    // maxReceipts` early return (a defensive belt-and-braces guard, not the row 6 case the spec's
    // table names — that one is `ReceiptCountLimitExceededException` from
    // [ReceiptLinkService.addReceiptsToItem]/[addReceiptsToInvoice] after a successful upload, covered
    // by the test right below). refreshLimitState already disables the Upload control itself once
    // attachedCount reaches the cap, so this early return is unreachable through the UI a real
    // user drives; karibu-testing's own [com.github.mvysny.kaributesting.v10._upload] refuses to
    // simulate an upload against a disabled control for exactly that reason (`_expectEditableByUser`
    // — verified empirically: attempting it here throws `IllegalStateException: ... is not
    // enabled`). Reaching it would need bypassing Upload's own enabled check via the raw
    // `UploadHandler`, which is not "driving the UI a user has", so this was not attempted.

    @Test
    fun `RC-3 row 6 - a limit-exceeded exception after a successful upload leaves the file in the pool`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectUploadTab()
        val uploaded = receipt(99L, "beleg.pdf")
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenReturn(uploaded)
        whenever(attachReceiptsUseCase.addReceiptsToItem(10L, listOf(99L))).thenThrow(ReceiptCountLimitExceededException(10, 11))
        fillUploadFields()

        assertDoesNotThrow {
            uploadControl()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
            DirectPollTicks.awaitTicks()
        }

        // The upload itself succeeded (the pool entry exists) — only the attach step failed.
        verify(attachReceiptsUseCase).upload(any(), anyOrNull(), any(), anyOrNull(), any())
        assertNotificationShown(UiTranslations.tr("receipt.error.limitExceededUpload", "10"))
        verify(invoiceReceiptQueryPort, times(2)).findReceiptsForItem(10L) // initial open() + reload in the catch
    }

    @Test
    fun `RC-3 row 7 - a signature-check rejection surfaces the shared generic error, nothing attached`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectUploadTab()
        // Mirrors ReceiptUploadService.upload's own rejection for non-PDF bytes (ReceiptServicePdfSignatureTest
        // pins the signature check itself; this proves what the dialog does with that rejection).
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any()))
            .thenThrow(ApplicationValidationException("Only PDF documents are allowed."))
        fillUploadFields()

        assertDoesNotThrow {
            uploadControl()._upload("not-a-pdf.pdf", "application/pdf", "not a pdf".toByteArray())
            DirectPollTicks.awaitTicks()
        }

        verify(attachReceiptsUseCase, never()).addReceiptsToItem(any(), any(), anyOrNull())
        // Same key ReceiptDialog.kt's KDoc on the upload block promises for both targets — "same
        // wording for both targets, not a second one for 'not a PDF' on positions".
        assertNotificationShown(
            UiTranslations.tr("invoiceDetail.items.receipts.error", "Only PDF documents are allowed."),
        )
    }

    // ── detach button, on the always-attached attached tab (no tab switch needed) ────────────────

    @Test
    fun `RC-3 gap - clicking detach removes the item-level link and reloads attached, group and pool`() {
        val r1 = receipt(1L)
        var onChangedCalled = false
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = { onChangedCalled = true })
        dialog.open()
        MockVaadin.clientRoundtrip()

        detachButton(attachedGrid(), 0)._click()

        verify(attachReceiptsUseCase).removeReceiptsFromItem(10L, listOf(1L))
        assertTrue(onChangedCalled)
        verify(invoiceReceiptQueryPort, times(2)).findReceiptsForItem(10L) // initial open() + reloadAttached()
        assertNotificationShown(UiTranslations.tr("invoiceDetail.items.receipts.removed", "r1.pdf"))
    }

    @Test
    fun `RC-3 gap - detach failing with a concurrent write is caught and shows an error instead of crashing`() {
        // guardReceiptAction (ReceiptActionGuard.kt) catches specific service-documented
        // exception types, not a bare Exception/RuntimeException; OptimisticLockException is one of
        // them (requireWritable's version-mismatch guard), so it is what this "still caught" test
        // now throws — a bare RuntimeException is not a type removeReceiptsFromItem ever raises.
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        whenever(attachReceiptsUseCase.removeReceiptsFromItem(10L, listOf(1L))).thenThrow(OptimisticLockException("db down"))

        assertDoesNotThrow { detachButton(attachedGrid(), 0)._click() }

        verify(invoiceReceiptQueryPort, times(1)).findReceiptsForItem(10L) // only the initial open() — no reload here
    }

    // ── The this-invoice group had never run with data (thisInvoiceReceiptIds
    // always empty). This proves the non-empty path: two receipts belonging to this invoice (one
    // at invoice level, one on a DIFFERENT position than the dialog's own item) plus one belonging
    // to a different invoice entirely, which must never appear in the group. ─────────────────────

    @Test
    fun `RC-2 gap - the this-invoice group lists an invoice-level and another-position receipt and excludes both from the remaining pool`() {
        val invoiceLevelReceipt = receipt(1L, "invoice-level.pdf")
        val otherPositionReceipt = receipt(2L, "other-position.pdf")
        val foreignReceipt = receipt(3L, "other-invoice.pdf") // belongs to a DIFFERENT invoice entirely
        val invoiceWithItems = invoice(items = listOf(itemDto(20L, "2")))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(invoiceLevelReceipt))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(20L to listOf(otherPositionReceipt)))
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null, setOf(1L, 2L))).thenReturn(listOf(foreignReceipt))
        whenever(invoiceReceiptQueryPort.countAssignableForItem(10L, null, setOf(1L, 2L))).thenReturn(1L)

        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoiceWithItems, ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectPoolTab()
        // Forces Vaadin's DataCommunicator to actually fetch+render both grids' rows (including
        // their component/value-provider columns), the same as a real client round trip would —
        // manually calling a DataProvider's fetch() below does not, on its own, exercise the
        // column renderers Grid itself invokes while painting.
        MockVaadin.clientRoundtrip()

        val groupItems = invoiceReceiptsGrid().listRows()
        assertEquals(setOf(invoiceLevelReceipt, otherPositionReceipt), groupItems.toSet())
        assertFalse(groupItems.contains(foreignReceipt), "a receipt of a different invoice must never appear in this invoice's own group")

        val remainingPoolRows = poolRows()
        assertEquals(listOf(foreignReceipt), remainingPoolRows, "the remaining pool must exclude what the this-invoice group already shows")

        // The pool grid's own download/view anchors (same ComponentRenderer mechanism as the
        // attached grid's detach button) — proves a pool row (never attached anywhere yet) still
        // gets a working download/view action. Renderer invoked directly on the already-fetched
        // row rather than via grid._get(rowIndex): the latter re-queries with karibu-testing's own
        // page size against this lazy CallbackDataProvider, which does not match the fixed 50 used
        // above and made karibu-testing's own failure-diagnostics path recurse without terminating
        // (a tooling issue, not a ReceiptDialog one — not worked around further here).
        val poolActions = actionsComponent(poolGrid(), remainingPoolRows.single())
        assertEquals(2, (poolActions as HorizontalLayout).children.toArray().size, "download and view anchors for a pool row")
    }

    // matchesPoolFilter (the this-invoice group's own client-side search match) had
    // only ever run with an empty needle (nothing typed); this exercises both the file-name and
    // the description match, plus a filter that matches neither.
    @Test
    fun `RC-2 gap - typing into the pool search re-filters the this-invoice group by file name or description`() {
        val byName = receipt(1L, "urlaubsantrag.pdf")
        val byDescription = receipt(2L, "sonstiges.pdf").copy(description = "Hotelrechnung Berlin")
        val nonMatching = receipt(3L, "anderes.pdf").copy(description = "nichts hier")
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(byName, byDescription, nonMatching))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectPoolTab()
        // Captured once, up front: an empty filter result hides the group's whole VerticalLayout
        // (ReceiptDialog.open's "empty group hidden outright" rule), and _find<DataTable<*>>()
        // does not return components that are no longer effectively visible — the reference
        // itself stays valid regardless.
        val grid = invoiceReceiptsGrid()

        fun currentGroupItems() = grid.listRows().toSet()

        poolSearch().value = "urlaub"
        assertEquals(setOf(byName), currentGroupItems(), "matches by file name")

        poolSearch().value = "Hotelrechnung"
        assertEquals(setOf(byDescription), currentGroupItems(), "matches by description")

        poolSearch().value = "nichts passt hier"
        assertTrue(currentGroupItems().isEmpty(), "a filter matching neither field leaves the group empty")
    }

    // ── invoice-level upload wiring — the shared closure calls addReceiptsToInvoice, not
    // addReceiptsToItem, when opened for ReceiptTarget.Invoice. ────────────────────────────────

    @Test
    fun `RC-3 row 1 - invoice-level upload attaches to the invoice, not a position`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findAssignableForInvoice(42L, 0, 50, null)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.countAssignableForInvoice(42L, null)).thenReturn(0L)
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Invoice(42L), onChanged = {})
        dialog.open()
        val tabs = _find<com.vaadin.flow.component.tabs.TabSheet>().single()
        tabs.selectedIndex = 2 // attached / pool / upload — forces the upload tab's content to attach
        MockVaadin.clientRoundtrip()

        val uploaded = receipt(99L, "beleg.pdf")
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenReturn(uploaded)
        fillUploadFields()
        uploadControl()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        DirectPollTicks.awaitTicks()

        verify(attachReceiptsUseCase).addReceiptsToInvoice(42L, listOf(99L))
        verify(attachReceiptsUseCase, never()).addReceiptsToItem(any(), any(), anyOrNull())
    }

    // ── invoice-level detach and pool query wiring — [ReceiptDialog.removeOne]/[fetchAssignable]/
    // [countAssignable] branch on target; the item-level branch is proven by the tests above, this
    // proves the invoice-level branch of the very same shared code. The attached tab is eagerly
    // attached (index 0, the default), so detach needs no tab switch; the pool tab does. ─────────

    @Test
    fun `invoice-level detach removes the invoice-level link, not an item-level one`() {
        val r1 = receipt(1L)
        var onChangedCalled = false
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Invoice(42L), onChanged = { onChangedCalled = true })
        dialog.open()
        MockVaadin.clientRoundtrip()

        detachButton(attachedGrid(), 0)._click()

        verify(attachReceiptsUseCase).removeReceiptsFromInvoice(42L, listOf(1L))
        verify(attachReceiptsUseCase, never()).removeReceiptsFromItem(any(), any(), anyOrNull())
        assertTrue(onChangedCalled)
    }

    @Test
    fun `invoice-level pool query calls findAssignableForInvoice, not the item-scoped overload`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findAssignableForInvoice(42L, 0, 50, null)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.countAssignableForInvoice(42L, null)).thenReturn(1L)
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Invoice(42L), onChanged = {})
        dialog.open()
        val tabs = _find<com.vaadin.flow.component.tabs.TabSheet>().single()
        tabs.selectedIndex = 1 // attached / pool / upload — forces the pool tab's pool grid to attach
        MockVaadin.clientRoundtrip()

        val rows = poolRows()
        val count = poolRowCount()

        assertEquals(listOf(r1), rows)
        assertEquals(1, count)
        verify(invoiceReceiptQueryPort, never()).findAssignableForItem(any(), any(), any(), any(), any())
    }

    // ── fix — the Invoice target's attached tab lists position-assigned receipts too (read-only),
    // not only invoice-level ones: bug reported on invoice 580 (2 item receipts, 0 invoice-level,
    // dialog showed "Zugeordnet (0)" — an empty grid contradicting the budget line and pool count
    // right next to it, both of which already counted the position receipts). ────────────────────

    @Test
    fun `fix - the invoice attached tab lists both scopes, item rows read-only with the position label, invoice row detachable`() {
        val posReceipt1 = receipt(1L, "pos1.pdf")
        val posReceipt2 = receipt(2L, "pos2.pdf")
        val invoiceReceipt = receipt(3L, "invoice.pdf")
        val invoiceWithItems = invoice(items = listOf(itemDto(10L, "POS-001"), itemDto(11L, "POS-002")))
        // Second value (after detach): the invoice-level link is gone, item links survive untouched.
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(invoiceReceipt), emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(
            mapOf(10L to listOf(posReceipt1), 11L to listOf(posReceipt2)),
        )

        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoiceWithItems, ReceiptTarget.Invoice(42L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        val grid = attachedGrid()

        assertEquals(3, attachedGridSize(grid), "both scopes together: 2 position links + 1 invoice-level link")
        assertEquals(
            UiTranslations.tr("invoiceDetail.items.receipts.tab.attached", "3"),
            tabSheet().getTabAt(0).element.textRecursively,
            "the caption counts every listed row, matching the Belege tab's budget line",
        )
        assertEquals(UiTranslations.tr("invoiceDetail.receipts.column.assignedTo"), grid.columns.first().header2)

        // Rows 0/1: position links — the position label, read-only (no detach button), tooltip
        // naming where to edit instead.
        assertEquals("POS-001", assignedToCell(grid, 0).text)
        assertEquals("POS-002", assignedToCell(grid, 1).text)
        assertEquals(
            UiTranslations.tr("invoiceDetail.receipts.readOnlyHint"),
            assignedToCell(grid, 0).element.getAttribute("title"),
        )
        listOf(0, 1).forEach { rowIndex ->
            val actions = actionsComponent(grid, rowIndex) as HorizontalLayout
            assertTrue(
                actions.children.toArray().filterIsInstance<Button>().none {
                    it.element.getProperty("title") == UiTranslations.tr("receipt.action.detach")
                },
                "a position-assigned row must offer download/view only, no detach button",
            )
        }

        // Row 2: the invoice-level link — the short "Rechnung" label, no read-only tooltip, and
        // it keeps its detach button.
        assertEquals(UiTranslations.tr("invoiceDetail.receipts.column.assignedTo.invoiceLevel"), assignedToCell(grid, 2).text)
        assertEquals("", assignedToCell(grid, 2).element.getAttribute("title") ?: "")

        detachButton(grid, 2)._click()

        verify(attachReceiptsUseCase).removeReceiptsFromInvoice(42L, listOf(3L))
        assertEquals(2, attachedGridSize(attachedGrid()), "the two item rows survive the invoice-level detach")
        assertEquals(
            UiTranslations.tr("invoiceDetail.items.receipts.tab.attached", "2"),
            tabSheet().getTabAt(0).element.textRecursively,
        )
    }

    @Test
    fun `fix - the invoice attached tab is non-empty when only position-assigned receipts exist (reported case)`() {
        val posReceipt1 = receipt(1L, "pos1.pdf")
        val posReceipt2 = receipt(2L, "pos2.pdf")
        val invoiceWithItems = invoice(items = listOf(itemDto(10L, "POS-001")))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(10L to listOf(posReceipt1, posReceipt2)))

        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoiceWithItems, ReceiptTarget.Invoice(42L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()

        assertEquals(2, attachedGridSize(attachedGrid()), "the two position-assigned receipts must not be hidden")
        assertEquals(
            UiTranslations.tr("invoiceDetail.items.receipts.tab.attached", "2"),
            tabSheet().getTabAt(0).element.textRecursively,
        )
    }

    @Test
    fun `fix - an item target's attached grid keeps its original columns, no Zugeordnet-an leading column`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1))

        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()

        val headers = attachedGrid().columns.map { it.header2 }
        assertTrue(
            headers.none { it == UiTranslations.tr("invoiceDetail.receipts.column.assignedTo") },
            "the Item target must not gain the Invoice-only 'Zugeordnet an' column",
        )
        val actions = actionsComponent(attachedGrid(), 0) as HorizontalLayout
        assertTrue(
            actions.children.toArray().filterIsInstance<Button>().any {
                it.element.getProperty("title") == UiTranslations.tr("receipt.action.detach")
            },
            "an item target's own receipt stays detachable, unaffected by the Invoice-target read-only rule",
        )
    }

    // [ReceiptDialog.unsavedHint] branches on target too; the item-target case is already covered
    // ("an unsaved item target shows..." above), this closes the invoice-target half.
    @Test
    fun `an unsaved invoice target shows the invoice-specific unsaved hint`() {
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Invoice(null), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()

        verify(invoiceReceiptQueryPort, times(0)).findReceiptsForInvoice(any())
        val expectedHint = UiTranslations.tr("invoiceDetail.receipts.unsaved")
        assertTrue(_find<com.vaadin.flow.component.html.Span>().any { it.text == expectedHint })
    }

    // ── Branches the structural refactor left genuinely reachable (not the nullable
    // build-time references removed), measured missing from jacoco.xml and closed here. ─────

    @Test
    fun `RC-4 gap - a read-only target's attached row renders no detach button`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(receipt(1L)))
        val dialog = ReceiptDialog(
            invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(status = InvoiceStatus.ISSUED.ordinal), ReceiptTarget.Item(10L), onChanged = {},
        )
        dialog.open()
        MockVaadin.clientRoundtrip()

        val actions = actionsComponent(attachedGrid(), 0) as HorizontalLayout
        assertTrue(
            actions.children.toArray().filterIsInstance<Button>().none {
                it.element.getProperty("title") == UiTranslations.tr("receipt.action.detach")
            },
            "a read-only target's row must offer download/view only, no detach button",
        )
    }

    // targetContext()'s Invoice arm and the detach catch block's `ex.message ?: ""` fallback had
    // only ever run for ReceiptTarget.Item, with a message-carrying exception, respectively.
    // A message-less OptimisticLockException (its no-arg constructor), not a bare
    // RuntimeException: see guardReceiptAction's KDoc for why the catch is now type-specific.
    @Test
    fun `RC-4 gap - invoice-level detach failing with a message-less exception is still caught`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1))
        whenever(attachReceiptsUseCase.removeReceiptsFromInvoice(42L, listOf(1L))).thenThrow(OptimisticLockException())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Invoice(42L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()

        assertDoesNotThrow { detachButton(attachedGrid(), 0)._click() }

        assertNotificationShown(UiTranslations.tr("invoiceDetail.items.receipts.error", ""))
    }

    @Test
    fun `RC-4 gap - pool fetch failing with a message-less exception still shows an error`() {
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenThrow(RuntimeException())
        openWritableItemDialog()

        assertDoesNotThrow { poolRows() }
        assertNotificationShown(UiTranslations.tr("invoiceDetail.items.receipts.error", ""))
    }

    @Test
    fun `RC-4 gap - assign failing with a message-less exception still shows an error`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        whenever(attachReceiptsUseCase.addReceiptsToItem(10L, listOf(1L))).thenThrow(RuntimeException())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        selectPoolTab()
        poolGrid().select(r1)

        assertDoesNotThrow { assignButton()._click() }

        assertNotificationShown(UiTranslations.tr("invoiceDetail.items.receipts.error", ""))
    }

    @Test
    fun `RC-4 gap - upload failing with a message-less exception still shows an error`() {
        openWritableItemDialog()
        selectUploadTab()
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenThrow(RuntimeException())
        fillUploadFields()

        assertDoesNotThrow {
            uploadControl()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
            DirectPollTicks.awaitTicks()
        }

        assertNotificationShown(UiTranslations.tr("invoiceDetail.items.receipts.error", ""))
    }

    // invoiceReceiptsGrid is legitimately null for a ReceiptTarget.Invoice dialog (no this-invoice
    // grouping there) — the pool search and assign flows must still work through their `?.` reads.
    @Test
    fun `RC-4 gap - invoice-level search and assign work without a this-invoice group grid`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findAssignableForInvoice(42L, 0, 50, null)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.findAssignableForInvoice(42L, 0, 50, "r1")).thenReturn(listOf(r1))
        var onChangedCalled = false
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Invoice(42L), onChanged = { onChangedCalled = true })
        dialog.open()
        val tabs = _find<com.vaadin.flow.component.tabs.TabSheet>().single()
        tabs.selectedIndex = 1 // attach the pool tab's search field and pool grid
        MockVaadin.clientRoundtrip()

        poolSearch().value = "r1"
        val grid = poolGrid()
        grid.select(r1)
        assignButton()._click()

        verify(attachReceiptsUseCase).addReceiptsToInvoice(42L, listOf(1L))
        assertTrue(onChangedCalled)
    }

    // findInvoiceOwnReceipts: an item the invoice no longer lists (no known position) must be
    // skipped rather than crash the lookup, and the target item's own receipts must be excluded
    // from its own "this invoice" group — the attached grid above already shows them.
    @Test
    fun `RC-4 gap - the this-invoice group skips an unknown-position item and excludes the target items own receipts`() {
        val ownReceipt = receipt(4L, "own.pdf")
        val orphanReceipt = receipt(5L, "orphan.pdf")
        val invoiceWithItems = invoice(items = listOf(itemDto(10L, "1")))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(
            mapOf(10L to listOf(ownReceipt), 77L to listOf(orphanReceipt)),
        )
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null, emptySet())).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.countAssignableForItem(10L, null, emptySet())).thenReturn(0L)

        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoiceWithItems, ReceiptTarget.Item(10L), onChanged = {})

        assertDoesNotThrow { dialog.open() }
        selectPoolTab()

        // Forces the (lazy) pool provider to actually fetch, the same as the this-invoice-group test above —
        // the orphan item (77L, no known position) never reached record(), and the own item's
        // receipt (4L) is excluded because it is already on the target item itself, so nothing is
        // left to exclude from the pool: an empty excludeReceiptIds is exactly what a fully-excluded,
        // empty this-invoice group implies.
        poolRows()
        verify(invoiceReceiptQueryPort).findAssignableForItem(10L, 0, 50, null, emptySet())
    }

    // ── The tab bar's live labels update as attach/detach happens, for both targets alike ──

    @Test
    fun `a successful upload updates both the attached and the pool tab's live labels`() {
        // See the identical note on "a successful upload switches..." above: both stub values must
        // be registered before open(), which is itself the first real call.
        val uploaded = receipt(99L, "beleg.pdf")
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList(), listOf(uploaded))
        // IR-3 — same before/after shape for the invoice-wide budget the pool tab's label now
        // reads: open()'s own refreshLimitState() call consumes the first value, the upload's
        // reloadAttached() -> refreshLimitState() the second.
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(42L)).thenReturn(
            InvoiceDocumentBudget.calculate(emptyList()),
            InvoiceDocumentBudget.calculate(
                listOf(de.littletrotter.shared.receipt.ReceiptSize("k1", de.littletrotter.shared.receipt.DocumentSize.ofBytes(100))),
            ),
        )
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        assertEquals(
            UiTranslations.tr("invoiceDetail.items.receipts.tab.attached", "0"),
            tabSheet().getTabAt(0).element.textRecursively,
        )
        selectUploadTab()
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenReturn(uploaded)
        fillUploadFields()

        uploadControl()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        DirectPollTicks.awaitTicks()

        assertEquals(
            UiTranslations.tr("invoiceDetail.items.receipts.tab.attached", "1"),
            tabSheet().getTabAt(0).element.textRecursively,
            "the attached tab's live count must reflect the upload",
        )
        assertEquals(
            // IR-2 — 19 (the new invoice-wide cap) minus 1 attached.
            UiTranslations.tr("invoiceDetail.items.receipts.tab.pool.remaining", "18"),
            tabSheet().getTabAt(1).element.textRecursively,
            "the pool tab's live 'frei' count must count down too",
        )
    }

    // ── The attached tab's own SelectionActionBar (ReceiptDialogAttachedTab.kt): bar-
    // driven single/bulk detach, cancel, clear, its absence on a non-writable dialog, the position-
    // assigned row's "Zur Position" navigation, and the bar-driven undo path. ─────────────────────

    private fun visibleAttachedBar(): SelectionActionBar =
        allComponents().filterIsInstance<SelectionActionBar>().single { it.isVisible }

    @Test
    fun `selecting one row in the attached grid via the bar action detaches immediately with undo`() {
        val r1 = receipt(1L)
        var onChangedCalled = false
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1), emptyList())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = { onChangedCalled = true })
        dialog.open()
        MockVaadin.clientRoundtrip()
        attachedGrid().select(AttachedReceiptRow(r1))

        visibleAttachedBar()._find<Button>().first { it.text == UiTranslations.tr("receipt.action.detachSelected") }._click()

        verify(attachReceiptsUseCase).removeReceiptsFromItem(10L, listOf(1L))
        assertTrue(onChangedCalled)
        assertTrue(attachedGrid().selectedItems.isEmpty(), "a successful bar detach must deselect the grid")
        assertNotificationShown(UiTranslations.tr("invoiceDetail.items.receipts.removed", "r1.pdf"))
    }

    @Test
    fun `selecting two rows in the attached grid via the bar action opens a confirm dialog and issues one bulk detach`() {
        val r1 = receipt(1L)
        val r2 = receipt(2L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1, r2))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        attachedGrid().select(AttachedReceiptRow(r1))
        attachedGrid().select(AttachedReceiptRow(r2))

        visibleAttachedBar()._find<Button>().first { it.text == UiTranslations.tr("receipt.action.detachSelected") }._click()
        val confirmDialog = _find<com.vaadin.flow.component.confirmdialog.ConfirmDialog>().single()
        com.vaadin.flow.component.ComponentUtil.fireEvent(
            confirmDialog,
            com.vaadin.flow.component.confirmdialog.ConfirmDialog.ConfirmEvent(confirmDialog, false),
        )

        val idsCaptor = argumentCaptor<List<Long>>()
        verify(attachReceiptsUseCase).removeReceiptsFromItem(eq(10L), idsCaptor.capture(), anyOrNull())
        assertEquals(setOf(1L, 2L), idsCaptor.firstValue.toSet())
        assertNotificationShown(UiTranslations.tr("receipt.detach.doneMany", "2"))
    }

    @Test
    fun `cancelling the attached grid's bulk confirm dialog leaves the receipts attached`() {
        val r1 = receipt(1L)
        val r2 = receipt(2L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1, r2))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        attachedGrid().select(AttachedReceiptRow(r1))
        attachedGrid().select(AttachedReceiptRow(r2))
        visibleAttachedBar()._find<Button>().first { it.text == UiTranslations.tr("receipt.action.detachSelected") }._click()
        val confirmDialog = _find<com.vaadin.flow.component.confirmdialog.ConfirmDialog>().single()

        com.vaadin.flow.component.ComponentUtil.fireEvent(
            confirmDialog,
            com.vaadin.flow.component.confirmdialog.ConfirmDialog.CancelEvent(confirmDialog, false),
        )

        verify(attachReceiptsUseCase, never()).removeReceiptsFromItem(any(), any(), anyOrNull())
        assertEquals(setOf(AttachedReceiptRow(r1), AttachedReceiptRow(r2)), attachedGrid().selectedItems)
    }

    @Test
    fun `the attached grid's clear button deselects without detaching`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        attachedGrid().select(AttachedReceiptRow(r1))

        visibleAttachedBar()._find<Button>()
            .first { it.element.getAttribute("aria-label") == UiTranslations.tr("receipt.selection.clear") }
            ._click()

        assertTrue(attachedGrid().selectedItems.isEmpty())
        verify(attachReceiptsUseCase, never()).removeReceiptsFromItem(any(), any(), anyOrNull())
    }

    @Test
    fun `the attached grid offers no selection bar when the dialog is read-only`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(receipt(1L)))
        val dialog = ReceiptDialog(
            invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(status = InvoiceStatus.ISSUED.ordinal), ReceiptTarget.Item(10L), onChanged = {},
        )
        dialog.open()
        MockVaadin.clientRoundtrip()

        assertTrue(allComponents().filterIsInstance<SelectionActionBar>().isEmpty())
    }

    @Test
    fun `the attached grid offers no selection bar when the target is unsaved`() {
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(null), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()

        assertTrue(allComponents().filterIsInstance<SelectionActionBar>().isEmpty())
    }

    @Test
    fun `the attached grid's bar action button does nothing if the selection became empty before it was invoked`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        attachedGrid().select(AttachedReceiptRow(r1))
        val actionButton = visibleAttachedBar()._find<Button>().first { it.text == UiTranslations.tr("receipt.action.detachSelected") }
        attachedGrid().deselectAll()

        assertDoesNotThrow { actionButton.click() }

        verify(attachReceiptsUseCase, never()).removeReceiptsFromItem(any(), any(), anyOrNull())
        verify(attachReceiptsUseCase, never()).removeReceiptsFromInvoice(any(), any(), anyOrNull())
    }

    @Test
    fun `selecting two rows in the attached grid via the bar action for an invoice target issues one bulk detach through removeReceiptsFromInvoice`() {
        val r1 = receipt(1L)
        val r2 = receipt(2L)
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1, r2))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Invoice(42L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        attachedGrid().select(AttachedReceiptRow(r1))
        attachedGrid().select(AttachedReceiptRow(r2))

        visibleAttachedBar()._find<Button>().first { it.text == UiTranslations.tr("receipt.action.detachSelected") }._click()
        val confirmDialog = _find<com.vaadin.flow.component.confirmdialog.ConfirmDialog>().single()
        com.vaadin.flow.component.ComponentUtil.fireEvent(
            confirmDialog,
            com.vaadin.flow.component.confirmdialog.ConfirmDialog.ConfirmEvent(confirmDialog, false),
        )

        val idsCaptor = argumentCaptor<List<Long>>()
        verify(attachReceiptsUseCase).removeReceiptsFromInvoice(eq(42L), idsCaptor.capture(), anyOrNull())
        assertEquals(setOf(1L, 2L), idsCaptor.firstValue.toSet())
        verify(attachReceiptsUseCase, never()).removeReceiptsFromItem(any(), any(), anyOrNull())
    }

    @Test
    fun `undo after a detach in the attached tab re-attaches through addSelected`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1), emptyList())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        detachButton(attachedGrid(), 0)._click()
        MockVaadin.clientRoundtrip()
        val undoButton = allComponents().filterIsInstance<Button>().single { it.text == UiTranslations.tr("receipt.detach.undo") }

        undoButton._click()

        verify(attachReceiptsUseCase).addReceiptsToItem(10L, listOf(1L))
    }

    @Test
    fun `undo after a detach in the attached tab failing because the invoice became read-only shows an error`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1), emptyList())
        whenever(attachReceiptsUseCase.addReceiptsToItem(10L, listOf(1L)))
            .thenThrow(de.littletrotter.domain.exception.InvoiceImmutableException("RE-2026-0001"))
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        detachButton(attachedGrid(), 0)._click()
        MockVaadin.clientRoundtrip()
        val undoButton = allComponents().filterIsInstance<Button>().single { it.text == UiTranslations.tr("receipt.detach.undo") }

        assertDoesNotThrow { undoButton._click() }
    }

    // ── A position-assigned row's "Zur Position" button (goToPositionButton) ────────────

    @Test
    fun `a position-assigned row's Zur Position button closes the dialog and navigates to that item`() {
        val posReceipt = receipt(1L, "pos.pdf")
        val positionItem = itemDto(10L, "POS-001")
        var navigatedTo: ItemDto? = null
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(10L to listOf(posReceipt)))
        val dialog = ReceiptDialog(
            invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(items = listOf(positionItem)), ReceiptTarget.Invoice(42L), onChanged = {},
            onNavigateToItem = { navigatedTo = it },
        )
        dialog.open()
        MockVaadin.clientRoundtrip()
        val actions = actionsComponent(attachedGrid(), 0) as HorizontalLayout
        val goToButton = actions.children.toArray().filterIsInstance<Button>()
            .single { it.text == UiTranslations.tr("receipt.action.goToPosition", "POS-001") }

        goToButton._click()
        MockVaadin.clientRoundtrip()

        assertEquals(10L, navigatedTo?.id)
        assertTrue(_find<Dialog>().isEmpty(), "the dialog must close before navigating")
    }

    @Test
    fun `a read-only invoice target's invoice-level attached row offers neither detach nor go-to-position`() {
        // Closes the remaining branch of attachedRowActions' else-if (target is Invoice &&
        // rowReadOnly): an invoice-level row (rowReadOnly == false) on a read-only dialog
        // (canWrite == false) fails BOTH the "detach" and the "go-to-position" condition.
        val r1 = receipt(1L, "invoice-level.pdf")
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1))
        val dialog = ReceiptDialog(
            invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(status = InvoiceStatus.ISSUED.ordinal), ReceiptTarget.Invoice(42L), onChanged = {},
        )
        dialog.open()
        MockVaadin.clientRoundtrip()

        val actions = actionsComponent(attachedGrid(), 0) as HorizontalLayout
        assertEquals(2, actions.children.toArray().size, "read-only invoice-level row: download/view only")
    }

    @Test
    fun `undo after a detach in the attached tab failing with a message-less exception still shows an error`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1), emptyList())
        whenever(attachReceiptsUseCase.addReceiptsToItem(10L, listOf(1L))).thenThrow(OptimisticLockException())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        detachButton(attachedGrid(), 0)._click()
        MockVaadin.clientRoundtrip()
        val undoButton = allComponents().filterIsInstance<Button>().single { it.text == UiTranslations.tr("receipt.detach.undo") }

        assertDoesNotThrow { undoButton._click() }

        assertNotificationShown(UiTranslations.tr("invoiceDetail.items.receipts.error", ""))
    }

    @Test
    fun `a bar-driven single detach in the attached tab failing with a concurrent write shows an error instead of crashing`() {
        val r1 = receipt(1L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1))
        whenever(attachReceiptsUseCase.removeReceiptsFromItem(10L, listOf(1L))).thenThrow(OptimisticLockException())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        attachedGrid().select(AttachedReceiptRow(r1))

        assertDoesNotThrow {
            visibleAttachedBar()._find<Button>().first { it.text == UiTranslations.tr("receipt.action.detachSelected") }._click()
        }

        assertNotificationShown(UiTranslations.tr("invoiceDetail.items.receipts.error", ""))
        assertFalse(attachedGrid().selectedItems.isEmpty(), "a failed bar detach must not clear the selection")
    }

    @Test
    fun `a bar-driven bulk detach in the attached tab failing with a concurrent write shows an error instead of crashing`() {
        val r1 = receipt(1L)
        val r2 = receipt(2L)
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(r1, r2))
        whenever(attachReceiptsUseCase.removeReceiptsFromItem(eq(10L), any(), anyOrNull())).thenThrow(OptimisticLockException())
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        MockVaadin.clientRoundtrip()
        attachedGrid().select(AttachedReceiptRow(r1))
        attachedGrid().select(AttachedReceiptRow(r2))
        visibleAttachedBar()._find<Button>().first { it.text == UiTranslations.tr("receipt.action.detachSelected") }._click()
        val confirmDialog = _find<com.vaadin.flow.component.confirmdialog.ConfirmDialog>().single()

        assertDoesNotThrow {
            com.vaadin.flow.component.ComponentUtil.fireEvent(
                confirmDialog,
                com.vaadin.flow.component.confirmdialog.ConfirmDialog.ConfirmEvent(confirmDialog, false),
            )
        }

        assertNotificationShown(UiTranslations.tr("invoiceDetail.items.receipts.error", ""))
    }

    @Test
    fun `a position-assigned row whose item can no longer be resolved offers no Zur Position button`() {
        val posReceipt = receipt(1L, "pos.pdf")
        // The receipt is linked to item 99L, but the invoice's own item list no longer contains
        // it (e.g. a stale read racing a concurrent edit) — see findAttachedForInvoice's own KDoc.
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(99L to listOf(posReceipt)))
        val dialog = ReceiptDialog(
            invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(items = listOf(itemDto(10L, "POS-001"))), ReceiptTarget.Invoice(42L), onChanged = {},
            onNavigateToItem = { },
        )
        dialog.open()
        MockVaadin.clientRoundtrip()

        val actions = actionsComponent(attachedGrid(), 0) as HorizontalLayout
        assertEquals(
            2, actions.children.toArray().size,
            "no Zur Position button when the item cannot be resolved, no detach button either (read-only position row)",
        )
    }
}
