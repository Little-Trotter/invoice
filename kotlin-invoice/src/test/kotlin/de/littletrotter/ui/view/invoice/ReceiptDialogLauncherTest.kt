package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.shared.receipt.BudgetVerdict
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * [ReceiptDialogLauncher]'s two entry points ([ReceiptDialogLauncher.openItemReceiptDialog]/
 * [ReceiptDialogLauncher.openInvoiceReceiptDialog]) and its reload-and-notify wiring — tested
 * directly against the launcher bean, with no [InvoiceDetailView] involved. The budget-hint/format
 * pure functions ([budgetHint], [applyBudgetHint], [formatReceiptSize]) are covered here too since
 * they live in the same file.
 */
class ReceiptDialogLauncherTest {

    private val invoiceReceiptQueryPort = mock<InvoiceReceiptQueryPort>()
    private val attachReceiptsUseCase = mock<AttachReceiptsUseCase>()
    private val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun launcher() = ReceiptDialogLauncher(invoiceReceiptQueryPort, attachReceiptsUseCase, invoiceDetailQueryPort)

    private fun invoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender"), recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun item() = ItemDto(
        id = 5L, positionId = "1", description = "Position", quantity = BigDecimal.ONE,
        unit = InvoiceItemUnit.PIECE.name, unitPriceNet = Money(BigDecimal("10.00")),
        taxRate = null, taxRateRefId = null, netAmount = null, vatAmount = null, grossAmount = null,
    )

    private fun dialog(): Dialog = _get<Dialog>()

    @Test
    fun `happy path - opening the item receipt dialog opens a ReceiptDialog titled for that position`() {
        launcher().openItemReceiptDialog(invoice(), item())

        val dlg = dialog()
        assertEquals(UiTranslations.tr(dlg, "invoiceDetail.items.receipts.dialog.title"), dlg.headerTitle)
    }

    @Test
    fun `boundary - opening the invoice-level receipt dialog opens the invoice-wide title instead of the item one`() {
        launcher().openInvoiceReceiptDialog(invoice())

        val dlg = dialog()
        assertEquals(UiTranslations.tr(dlg, "invoiceDetail.receipts.dialog.title"), dlg.headerTitle)
    }

    @Test
    fun `boundary - reloadAndNotify with a concurrently deleted invoice never calls the bound state`() {
        val state = InvoiceDetailState()
        var notified: InvoiceDetailDto? = null
        state.onReceiptsChanged { notified = it }
        val target = launcher()
        target.bind(state)
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(null)

        target.reloadAndNotify(42L)

        assertEquals(null, notified, "a concurrently deleted invoice must not overwrite anything with null")
    }

    @Test
    fun `boundary - reloadAndNotify with a committed invoice hands the fresh DTO to the bound state`() {
        val state = InvoiceDetailState()
        var notified: InvoiceDetailDto? = null
        state.onReceiptsChanged { notified = it }
        val target = launcher()
        target.bind(state)
        val fresh = invoice().copy(version = 7L)
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(fresh)

        target.reloadAndNotify(42L)

        assertEquals(fresh, notified)
    }

    @Test
    fun `boundary - reloadAndNotify with nothing bound is a no-op, not a crash`() {
        val target = launcher()
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice())

        assertDoesNotThrow { target.reloadAndNotify(42L) }
    }

    @Test
    fun `boundary - budgetHint reports count exhausted once the invoice-wide slot cap is used up`() {
        val budget = InvoiceDocumentBudget(
            distinctReceiptCount = 19, receiptSize = DocumentSize.ZERO, estimatedDocumentSize = DocumentSize.ZERO,
            remainingSize = DocumentSize.ZERO, remainingSlots = 0, isFull = true,
            verdict = BudgetVerdict.Accepted,
        )

        val hint = budgetHint(null, budget)

        assertEquals(BudgetHintState.COUNT_EXHAUSTED, hint.state)
    }

    @Test
    fun `boundary - budgetHint stays normal when slots remain, regardless of how little size headroom is left`() {
        // receipt-size-normalisation — the estimated size is a warning (the budget banner
        // elsewhere), not a link-time gate any more, so budgetHint no longer has a size-exhausted
        // state: slots remaining is the only thing that flips it out of NORMAL.
        val budget = InvoiceDocumentBudget(
            distinctReceiptCount = 5, receiptSize = DocumentSize.ZERO, estimatedDocumentSize = DocumentSize.ZERO,
            remainingSize = DocumentSize.ofBytes(100), remainingSlots = 14, isFull = false,
            verdict = BudgetVerdict.Accepted,
        )

        val hint = budgetHint(null, budget)

        assertEquals(BudgetHintState.NORMAL, hint.state)
    }

    @Test
    fun `boundary - budgetHint reports the normal state and applyBudgetHint keeps the muted styling for it`() {
        val budget = InvoiceDocumentBudget(
            distinctReceiptCount = 3, receiptSize = DocumentSize.ZERO, estimatedDocumentSize = DocumentSize.ZERO,
            remainingSize = DocumentSize.ofBytes(5_000_000), remainingSlots = 16, isFull = false,
            verdict = BudgetVerdict.Accepted,
        )
        val span = Span()

        span.applyBudgetHint(budgetHint(null, budget))

        assertTrue(span.classNames.contains("text-muted"))
        assertFalse(span.classNames.contains("text-warning"))
        assertEquals("polite", span.element.getAttribute("aria-live"))
    }

    @Test
    fun `boundary - applyBudgetHint swaps in the warning class once a limit is exhausted`() {
        val budget = InvoiceDocumentBudget(
            distinctReceiptCount = 19, receiptSize = DocumentSize.ZERO, estimatedDocumentSize = DocumentSize.ZERO,
            remainingSize = DocumentSize.ZERO, remainingSlots = 0, isFull = true,
            verdict = BudgetVerdict.Accepted,
        )
        val span = Span().apply { addClassName("text-muted") }

        span.applyBudgetHint(budgetHint(null, budget))

        assertTrue(span.classNames.contains("text-warning"))
        assertFalse(span.classNames.contains("text-muted"))
    }

    @Test
    fun `boundary - formatReceiptSize renders bytes, kilobytes, megabytes and the null placeholder at their boundaries`() {
        // formatReceiptSize formats explicitly with Locale.GERMAN (decimal comma), independent
        // of the host JVM's default locale.
        assertEquals("-", formatReceiptSize(null))
        assertEquals("500 B", formatReceiptSize(500L))
        assertEquals("2,0 KB", formatReceiptSize(2048L))
        assertEquals("1,0 MB", formatReceiptSize(1024L * 1024))
    }

    @Test
    fun `boundary - receiptNewestFirst orders by document date, then upload time, then id, all descending`() {
        val older = de.littletrotter.application.dto.ReceiptRowDto(
            id = 1L, fileName = "a.pdf", description = "", documentDate = java.time.LocalDate.of(2026, 1, 1),
            sizeBytes = null, uploadedAt = null, objectKey = "obj-1",
        )
        val newer = older.copy(id = 2L, documentDate = java.time.LocalDate.of(2026, 2, 1))

        val sorted = listOf(older, newer).sortedWith(receiptNewestFirst)

        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }
}
