package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._upload
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.upload.Upload
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.ReceiptReductionFailedException
import de.littletrotter.application.invoice.ReceiptUnreducedTooLargeException
import de.littletrotter.application.invoice.ReceiptUploadMode
import de.littletrotter.application.invoice.ReceiptUploadTooLargeException
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.invoice.port.inbound.PreviewReceiptReductionQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptAutoReduceQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptReductionPreview
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
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
 * The upload-time reduce/keep decision ([offerReceiptUpload]), wired end to end through
 * [ReceiptDialog]'s upload tab: confirming a choice passes the chosen [ReceiptUploadMode] into
 * [AttachReceiptsUseCase.upload], and the result toast reads the returned [ReceiptRowDto]'s
 * [ReceiptNormalisationStage] ([showReceiptUploadResult]) rather than a fixed "uploaded" wording.
 * The three size-specific rejection exceptions get their own wording too
 * ([receiptUploadExceptionMessage]).
 */
class ReceiptDialogReduceUploadKaribuTest {

    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock()
    private val attachReceiptsUseCase: AttachReceiptsUseCase = mock()
    private val originalProvider = ReceiptUploadAdviceSource.provider

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()
        val autoReduce: ReceiptAutoReduceQueryPort = mock()
        val previewPort: PreviewReceiptReductionQueryPort = mock()
        whenever(autoReduce.isAutoReduceEnabled()).thenReturn(false)
        whenever(previewPort.previewReduction(any())).thenReturn(
            ReceiptReductionPreview(DocumentSize.ofBytes(900L * 1024), DocumentSize.ofBytes(300L * 1024), true, null),
        )
        ReceiptUploadAdviceSource.provider = { ReceiptUploadAdvice(autoReduce, previewPort) }
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        ReceiptUploadAdviceSource.provider = originalProvider
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun launcher() = ReceiptDialogLauncher(invoiceReceiptQueryPort, attachReceiptsUseCase, invoiceDetailQueryPort, mock())

    private fun invoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender"), recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun receipt(
        id: Long,
        stage: ReceiptNormalisationStage = ReceiptNormalisationStage.NONE,
        originalSizeBytes: Long? = null,
        sizeBytes: Long? = 4L,
    ) = ReceiptRowDto(
        id = id, fileName = "beleg.pdf", description = "", documentDate = null,
        sizeBytes = sizeBytes, uploadedAt = OffsetDateTime.now(), objectKey = "synthetic/receipt-$id.pdf",
        normalisationStage = stage, originalSizeBytes = originalSizeBytes,
    )

    private fun openInvoiceDialog() {
        launcher().openInvoiceReceiptDialog(invoice())
        MockVaadin.clientRoundtrip()
    }

    private fun selectUploadTab() {
        _find<TabSheet>().single().selectedIndex = 2
        MockVaadin.clientRoundtrip()
    }

    private fun fillUploadFields() {
        _find<TextField>().single { it.placeholder == UiTranslations.tr("receipt.description.placeholder") }.value = "Taxi"
        _find<DatePicker>().single { it.label == UiTranslations.tr("receipt.documentDate") }.value = LocalDate.of(2026, 8, 15)
    }

    private fun notificationTexts(): List<String> = getNotifications().map { de.littletrotter.ui.toastText(it) }

    // ── A: confirming a choice in the reduce dialog passes the mode into the upload port ─────────

    @Test
    fun `confirming keep in the reduce dialog uploads with KEEP_UNREDUCED`() {
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), eq(ReceiptUploadMode.KEEP_UNREDUCED)))
            .thenReturn(receipt(7L))
        openInvoiceDialog()
        selectUploadTab()
        fillUploadFields()

        _find<Upload>().single()._upload("beleg.pdf", "application/pdf", ByteArray(900 * 1024))
        DirectPollTicks.awaitTicks()
        _find<Button>().single { it.text == UiTranslations.tr("receipt.reduceDialog.original") }._click()
        DirectPollTicks.awaitTicks()

        verify(attachReceiptsUseCase).upload(eq("beleg.pdf"), eq("Taxi"), any(), anyOrNull(), eq(ReceiptUploadMode.KEEP_UNREDUCED))
    }

    @Test
    fun `cancelling the reduce dialog uploads nothing`() {
        openInvoiceDialog()
        selectUploadTab()
        fillUploadFields()

        _find<Upload>().single()._upload("beleg.pdf", "application/pdf", ByteArray(900 * 1024))
        DirectPollTicks.awaitTicks()
        _find<Button>().single { it.text == UiTranslations.tr("app.cancel") }._click()

        verify(attachReceiptsUseCase, never()).upload(any(), anyOrNull(), any(), anyOrNull(), any())
    }

    // ── B: result messages, by normalisation stage ────────────────────────────────────────────

    @Test
    fun `an IMAGES_DOWNSAMPLED result shows the from-to size sentence`() {
        val uploaded = receipt(7L, stage = ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, originalSizeBytes = 2_000_000L, sizeBytes = 300_000L)
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenReturn(uploaded)
        openInvoiceDialog()
        selectUploadTab()
        fillUploadFields()

        _find<Upload>().single()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        DirectPollTicks.awaitTicks()

        val expected = UiTranslations.tr(
            "receipt.autoReduced",
            DocumentSizeFormatter.format(DocumentSize.ofBytes(2_000_000L)),
            DocumentSizeFormatter.format(DocumentSize.ofBytes(300_000L)),
        )
        assertTrue(notificationTexts().any { it == expected }, notificationTexts().toString())
    }

    @Test
    fun `a RASTERISED result shows the from-to sentence plus the searchability warning`() {
        val uploaded = receipt(7L, stage = ReceiptNormalisationStage.RASTERISED, originalSizeBytes = 3_000_000L, sizeBytes = 300_000L)
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any())).thenReturn(uploaded)
        openInvoiceDialog()
        selectUploadTab()
        fillUploadFields()

        _find<Upload>().single()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        DirectPollTicks.awaitTicks()

        val expected = UiTranslations.tr(
            "receipt.autoReduced",
            DocumentSizeFormatter.format(DocumentSize.ofBytes(3_000_000L)),
            DocumentSizeFormatter.format(DocumentSize.ofBytes(300_000L)),
        ) + " " + UiTranslations.tr("receipt.reduced.rasterised")
        assertTrue(notificationTexts().any { it == expected }, notificationTexts().toString())
    }

    // ── B: the three size-specific rejection exceptions ───────────────────────────────────────

    @Test
    fun `ReceiptReductionFailedException surfaces the page-count wording`() {
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any()))
            .thenThrow(ReceiptReductionFailedException(12, DocumentSize.ofBytes(900_000L)))
        openInvoiceDialog()
        selectUploadTab()
        fillUploadFields()

        _find<Upload>().single()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        DirectPollTicks.awaitTicks()

        val expected = UiTranslations.tr("receipt.error.reductionFailed", "12", DocumentSizeFormatter.format(DocumentSize.ofBytes(900_000L)))
        assertTrue(notificationTexts().any { it.contains(expected) }, notificationTexts().toString())
    }

    @Test
    fun `ReceiptUnreducedTooLargeException surfaces the unreduced-cap wording`() {
        val uploaded = DocumentSize.ofBytes(1_500_000L)
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any()))
            .thenThrow(ReceiptUnreducedTooLargeException(uploaded, ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE))
        openInvoiceDialog()
        selectUploadTab()
        fillUploadFields()

        _find<Upload>().single()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        DirectPollTicks.awaitTicks()

        val expected = UiTranslations.tr(
            "receipt.error.unreducedTooLarge",
            DocumentSizeFormatter.format(uploaded),
            DocumentSizeFormatter.format(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE),
        )
        assertTrue(notificationTexts().any { it.contains(expected) }, notificationTexts().toString())
    }

    @Test
    fun `ReceiptUploadTooLargeException thrown by the service surfaces the same wording as the client-side rejection`() {
        val uploaded = DocumentSize.ofBytes(9_000_000L)
        whenever(attachReceiptsUseCase.upload(any(), anyOrNull(), any(), anyOrNull(), any()))
            .thenThrow(ReceiptUploadTooLargeException(uploaded, ReceiptSizePolicy.MAX_UPLOAD_SIZE))
        openInvoiceDialog()
        selectUploadTab()
        fillUploadFields()

        _find<Upload>().single()._upload("beleg.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        DirectPollTicks.awaitTicks()

        val expected = UiTranslations.tr(
            "receipt.error.uploadTooLarge",
            DocumentSizeFormatter.format(uploaded),
            DocumentSizeFormatter.format(ReceiptSizePolicy.MAX_UPLOAD_SIZE),
        )
        assertTrue(notificationTexts().any { it.contains(expected) }, notificationTexts().toString())
    }
}
