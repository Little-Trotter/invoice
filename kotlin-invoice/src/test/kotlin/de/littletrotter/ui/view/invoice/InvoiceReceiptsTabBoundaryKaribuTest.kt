package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.component.SelectionActionBar
import de.littletrotter.ui.i18n.UiTranslations
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.OffsetDateTime

/** The invoice receipt dialog's "Zur Position" hand-over and the Belege tab's bulk detach
 *  (failure and cancellation). */
class InvoiceReceiptsTabBoundaryKaribuTest {

    private val harness = DetailViewTestHarness()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        clearNotifications()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun receipt(id: Long) = ReceiptRowDto(
        id = id,
        fileName = "r$id.pdf",
        description = "",
        documentDate = null,
        sizeBytes = 100L,
        uploadedAt = OffsetDateTime.now(),
        objectKey = "obj-$id",
    )

    private fun item(id: Long, positionId: String) = ItemDto(
        id = id, positionId = positionId, description = "Pos $positionId", quantity = BigDecimal.ONE,
        unit = null, unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null,
    )

    private fun invoice(items: List<ItemDto> = emptyList()) = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = items, latestGenerationJob = null,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)?.let { component.children.toList().flatMap(::flatten) }.orEmpty()

    private fun uiComponents(): List<Component> {
        MockVaadin.clientRoundtrip()
        val found = mutableListOf<Component>()
        fun walk(element: com.vaadin.flow.dom.Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach(::walk)
        }
        walk(UI.getCurrent().element)
        return found
    }

    private fun clickDetachSelected(content: Component) {
        flatten(content).filterIsInstance<SelectionActionBar>().first { it.isVisible }
            ._find<Button>().first { it.text == UiTranslations.tr("receipt.action.detachSelected") }._click()
    }

    private fun bulkSelect(): Component {
        val r1 = receipt(1L)
        val r2 = receipt(2L)
        whenever(harness.invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1, r2))
        whenever(harness.invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        harness.view.invoice = invoice()
        val content = harness.invoiceReceiptsTab.apply { render(invoice()) }
        val invoiceGrid = flatten(content).filterIsInstance<Grid<*>>().first().requireAs<Grid<Any>>()
        invoiceGrid.select(r1)
        invoiceGrid.select(r2)
        clickDetachSelected(content)
        return content
    }

    @Test
    fun `happy path - the invoice receipt dialog's go-to-position button opens that position's receipt dialog`() {
        val position = item(10L, "POS-001")
        whenever(harness.invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(harness.invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(10L to listOf(receipt(1L))))
        harness.receiptDialogLauncher.openInvoiceReceiptDialog(invoice(listOf(position)))
        MockVaadin.clientRoundtrip()
        val attached = _find<Grid<*>>()
            .first { it.dataProvider is ListDataProvider<*> && (it.dataProvider as ListDataProvider<*>).items.isNotEmpty() }
            .requireAs<Grid<AttachedReceiptRow>>()
        val actions = attached.columns.last().componentFor(attached.listRows().first()) as HorizontalLayout

        actions.children.toList().filterIsInstance<Button>()
            .single { it.text == UiTranslations.tr("receipt.action.goToPosition", "POS-001") }._click()
        MockVaadin.clientRoundtrip()

        assertEquals(1, _find<Dialog>().count { it.isOpened }, "the invoice dialog closed, the position's dialog is open")
        verify(harness.invoiceReceiptQueryPort).findReceiptsForItem(10L)
    }

    @Test
    fun `boundary - a failing bulk detach surfaces an error instead of a success toast`() {
        whenever(harness.attachReceiptsUseCase.detachReceipts(eq(42L), any(), anyOrNull())).thenThrow(OptimisticLockException("stale version"))
        bulkSelect()
        val confirm = uiComponents().filterIsInstance<ConfirmDialog>().single()

        ComponentUtil.fireEvent(confirm, ConfirmDialog.ConfirmEvent(confirm, false))
        MockVaadin.clientRoundtrip()

        verify(harness.attachReceiptsUseCase).detachReceipts(eq(42L), any(), anyOrNull())
        val texts = (getNotifications() + uiComponents().filterIsInstance<com.vaadin.flow.component.notification.Notification>()).map { it.element.textRecursively }
        assertTrue(texts.none { it.contains(UiTranslations.tr("receipt.detach.doneMany", "2")) }, texts.toString())
        assertTrue(texts.isNotEmpty(), "an error toast is shown")
    }

    @Test
    fun `boundary - two selected receipts ask for confirmation before anything is detached`() {
        bulkSelect()

        assertEquals(1, uiComponents().filterIsInstance<ConfirmDialog>().size)
        verify(harness.attachReceiptsUseCase, never()).detachReceipts(any(), any(), anyOrNull())
    }

    @Test
    fun `boundary - cancelling the bulk confirmation detaches nothing`() {
        bulkSelect()
        val confirm = uiComponents().filterIsInstance<ConfirmDialog>().single()

        ComponentUtil.fireEvent(confirm, ConfirmDialog.CancelEvent(confirm, false))

        verify(harness.attachReceiptsUseCase, never()).detachReceipts(any(), any(), anyOrNull())
    }
}
