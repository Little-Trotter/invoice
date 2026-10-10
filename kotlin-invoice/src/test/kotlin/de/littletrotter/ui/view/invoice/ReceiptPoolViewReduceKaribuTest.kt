package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.application.invoice.port.inbound.NormaliseReceiptUseCase
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

/**
 * [ReceiptPoolView]'s "Hinweis" column ([ReceiptPoolView.receiptHintText]) and its "Beleg
 * reduzieren" row action ([ReceiptPoolView.reduceReceipt]) — the pool's own retrofit path for a
 * receipt uploaded before the upload-time reducer existed, or left at
 * [ReceiptNormalisationStage.NONE] because it met the target back then.
 */
class ReceiptPoolViewReduceKaribuTest {

    private val receiptUploadService: ReceiptUploadService = mock()
    private val receiptPoolService: ReceiptPoolService = mock()
    private val normaliseReceiptUseCase: NormaliseReceiptUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun view() = ReceiptPoolView(receiptUploadService, receiptPoolService, normaliseReceiptUseCase)

    private fun receipt(
        id: Long = 1L,
        sizeBytes: Long? = 100L,
        stage: ReceiptNormalisationStage = ReceiptNormalisationStage.NONE,
        originalSizeBytes: Long? = null,
    ) = Receipt().apply {
        this.id = id
        fileName = "beleg.pdf"
        this.sizeBytes = sizeBytes
        this.normalisationStage = stage
        this.originalSizeBytes = originalSizeBytes
        uploadedAt = OffsetDateTime.now()
    }

    private fun grid(v: ReceiptPoolView) = ReceiptPoolView::class.java.getDeclaredField("grid").apply { isAccessible = true }
        .get(v).requireAs<com.vaadin.flow.component.grid.Grid<Receipt>>()

    private fun hintText(v: ReceiptPoolView, r: Receipt): String = grid(v)._getFormattedRow(r)[6]

    private fun actionRow(v: ReceiptPoolView, r: Receipt): HorizontalLayout {
        val m = ReceiptPoolView::class.java.getDeclaredMethod("buildActionColumn", Receipt::class.java)
        m.isAccessible = true
        return m.invoke(v, r) as HorizontalLayout
    }

    private fun reduceButton(v: ReceiptPoolView, r: Receipt): Button =
        actionRow(v, r).children.toList().filterIsInstance<Button>()
            .single { it.element.getAttribute("aria-label") == UiTranslations.tr(v, "receipt.action.reduce") }

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

    // ── the "Beleg reduzieren" row action ─────────────────────────────────────────────────────

    @Test
    fun `the reduce action is visible only for an unreduced receipt above the target`() {
        val v = view()

        assertTrue(reduceButton(v, receipt(sizeBytes = ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES + 1)).isVisible)
        assertFalse(reduceButton(v, receipt(sizeBytes = ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES)).isVisible, "at target: nothing to reduce")
        assertFalse(
            reduceButton(v, receipt(sizeBytes = ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES + 1, stage = ReceiptNormalisationStage.RASTERISED))
                .isVisible,
            "already reduced: no further action offered",
        )
    }

    @Test
    fun `clicking the reduce action calls the port and refreshes the grid`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)
        val normalised = ReceiptRowDto(
            id = 1L, fileName = "beleg.pdf", description = "", documentDate = null,
            sizeBytes = 700_000L, uploadedAt = OffsetDateTime.now(), objectKey = "synthetic/receipt-1.pdf",
            normalisationStage = ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, originalSizeBytes = 2_000_000L,
        )
        whenever(normaliseReceiptUseCase.normalise(1L)).thenReturn(normalised)
        val v = view()
        val r = receipt(id = 1L, sizeBytes = 2_000_000L)
        val providerBeforeReduce = grid(v).dataProvider

        // Built via reflection (buildActionColumn), not attached to the UI, the same idiom
        // ReceiptDialogActionsKaribuTest uses for a guarded action's click listener: firing the
        // event directly instead of karibu's own _click() (which requires real attachment).
        val btn = reduceButton(v, r)
        ComponentUtil.fireEvent(btn, ClickEvent(btn))

        verify(normaliseReceiptUseCase).normalise(1L)
        // refresh() rebuilds the data provider from scratch (never mutates the old one in place),
        // so a distinct instance is the pin that the reduce-then-reload path actually ran.
        assertTrue(grid(v).dataProvider !== providerBeforeReduce, "a successful reduce must replace the grid's data provider (refresh)")
    }
}
