package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._upload
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.upload.Upload
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The invoice-level receipt dialog opened by [ReceiptDialogLauncher]: a direct upload attaches to
 * the invoice and reloads the committed invoice through [InvoiceDetailQueryPort], the invoice pool
 * is queried through the invoice-target service methods, and the estimated document size no
 * longer gates the upload itself (receipt-size-normalisation) — only the count cap, and the
 * upload-time size caps `ReceiptSizePolicy` applies regardless of the budget, still can.
 */
class ReceiptDialogUploadBoundaryKaribuTest {

    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock()
    private val attachReceiptsUseCase: AttachReceiptsUseCase = mock()
    private val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun launcher() = ReceiptDialogLauncher(invoiceReceiptQueryPort, attachReceiptsUseCase, invoiceDetailQueryPort, mock())

    private fun invoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender"), recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun receipt(id: Long) = ReceiptRowDto(
        id = id,
        fileName = "beleg.pdf",
        description = "",
        documentDate = null,
        sizeBytes = 4L,
        uploadedAt = OffsetDateTime.now(),
        objectKey = "synthetic/receipt-$id.pdf",
    )

    private fun openInvoiceDialog() {
        launcher().openInvoiceReceiptDialog(invoice())
        MockVaadin.clientRoundtrip()
    }

    private fun selectTab(index: Int) {
        _find<TabSheet>().single().selectedIndex = index
        MockVaadin.clientRoundtrip()
    }

    private fun uploadFilled() {
        selectTab(2)
        _find<TextField>().single { it.placeholder == UiTranslations.tr("receipt.description.placeholder") }.value = "Taxi"
        _find<DatePicker>().single { it.label == UiTranslations.tr("receipt.documentDate") }.value = LocalDate.of(2026, 8, 15)
        _find<Upload>().single()._upload("beleg.pdf", "application/pdf", pdfBytes)
        DirectPollTicks.awaitTicks()
    }

    @Test
    fun `happy path - an invoice-level upload attaches to the invoice and refreshes the detail view`() {
        whenever(attachReceiptsUseCase.upload(eq("beleg.pdf"), eq("Taxi"), any(), anyOrNull(), any())).thenReturn(receipt(7L))
        openInvoiceDialog()

        uploadFilled()

        verify(attachReceiptsUseCase).addReceiptsToInvoice(eq(42L), eq(listOf(7L)), anyOrNull())
        verify(invoiceDetailQueryPort).findInvoiceDetailDto(42L)
        assertEquals(0, _find<TabSheet>().single().selectedIndex, "a successful upload lands on the attached tab")
    }

    @Test
    fun `boundary - the invoice pool is queried through the invoice-target service methods`() {
        openInvoiceDialog()

        selectTab(1)

        verify(invoiceReceiptQueryPort).countAssignableForInvoice(eq(42L), anyOrNull())
        verify(invoiceReceiptQueryPort, never()).countAssignableForItem(anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `boundary - a tiny remaining document size no longer blocks the upload, only the count cap does`() {
        // receipt-size-normalisation — the estimated document size is a warning (the budget
        // banner), not a link-time gate any more; only the count cap (asserted elsewhere) still
        // refuses an upload before it reaches the service.
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(any()))
            .thenReturn(InvoiceDocumentBudget.calculate(emptyList()).copy(remainingSize = DocumentSize.ofBytes(2)))
        whenever(attachReceiptsUseCase.upload(eq("beleg.pdf"), eq("Taxi"), any(), anyOrNull(), any())).thenReturn(receipt(7L))
        openInvoiceDialog()

        uploadFilled()

        verify(attachReceiptsUseCase).upload(eq("beleg.pdf"), eq("Taxi"), any(), anyOrNull(), any())
        verify(attachReceiptsUseCase).addReceiptsToInvoice(eq(42L), eq(listOf(7L)), anyOrNull())
    }
}
