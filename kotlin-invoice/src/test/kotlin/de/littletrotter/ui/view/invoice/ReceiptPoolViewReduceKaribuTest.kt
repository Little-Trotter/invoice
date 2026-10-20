package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.port.inbound.UploadReceiptUseCase
import de.littletrotter.application.invoice.port.inbound.ReceiptPoolQueryPort
import de.littletrotter.application.invoice.port.inbound.EditReceiptDetailsUseCase
import de.littletrotter.application.invoice.port.inbound.DeleteReceiptUseCase
import de.littletrotter.application.invoice.port.inbound.ReceiptView
import de.littletrotter.testing.receiptView
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._getFormattedRow
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.OffsetDateTime

/**
 * [ReceiptPoolView]'s "Hinweis" column ([ReceiptPoolView.receiptHintText]): names an unreduced
 * receipt above the target size or the sizes of one that was reduced at upload.
 */
class ReceiptPoolViewReduceKaribuTest {

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

    private fun view() = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

    private fun receipt(
        id: Long = 1L,
        sizeBytes: Long? = 100L,
        stage: ReceiptNormalisationStage = ReceiptNormalisationStage.NONE,
        originalSizeBytes: Long? = null,
    ) = receiptView {
        this.id = id
        fileName = "beleg.pdf"
        this.sizeBytes = sizeBytes
        this.normalisationStage = stage
        this.originalSizeBytes = originalSizeBytes
        uploadedAt = OffsetDateTime.now()
    }

    private fun grid(v: ReceiptPoolView) = ReceiptPoolView::class.java.getDeclaredField("grid").apply { isAccessible = true }
        .get(v).requireAs<com.vaadin.flow.component.grid.Grid<ReceiptView>>()

    private fun hintText(v: ReceiptPoolView, r: ReceiptView): String = grid(v)._getFormattedRow(r)[6]

    // ── the "Hinweis" column ───────────────────────────────────────────────────────────────────

    @Test
    fun `an unreduced receipt above the target shows the unreduced hint with its size`() {
        val v = view()
        val r = receipt(sizeBytes = ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES + 1024)

        val expected = UiTranslations.tr(v, "receipt.hint.unreduced", DocumentSizeFormatter.format(DocumentSize.ofBytes(r.sizeBytes!!)))
        assertEquals(expected, hintText(v, r))
    }

    @Test
    fun `an unreduced receipt at or under the target shows no hint`() {
        val v = view()
        val r = receipt(sizeBytes = ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES)

        assertEquals("", hintText(v, r))
    }

    @Test
    fun `a reduced receipt shows the from-to size hint`() {
        val v = view()
        val r = receipt(sizeBytes = 700_000L, stage = ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, originalSizeBytes = 2_000_000L)

        val expected = UiTranslations.tr(
            v,
            "receipt.hint.reduced",
            DocumentSizeFormatter.format(DocumentSize.ofBytes(2_000_000L)),
            DocumentSizeFormatter.format(DocumentSize.ofBytes(700_000L)),
        )
        assertEquals(expected, hintText(v, r))
    }

    @Test
    fun `a reduced receipt with no recorded original size shows no hint`() {
        val v = view()
        val r = receipt(sizeBytes = 700_000L, stage = ReceiptNormalisationStage.RASTERISED, originalSizeBytes = null)

        assertEquals("", hintText(v, r))
    }
}
