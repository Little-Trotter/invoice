package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.html.Image
import de.littletrotter.application.invoice.ReceiptUploadMode
import de.littletrotter.application.invoice.port.inbound.PreviewReceiptReductionQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptAutoReduceQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptReductionPreview
import de.littletrotter.shared.receipt.DocumentSize
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [offerReceiptUpload]'s three-way decision ([de.littletrotter.shared.receipt.ReceiptUploadDecision])
 * and [ReceiptReduceDialog]'s own reduce/keep choice — both upload sites ([ReceiptDialog]'s upload
 * tab, [ReceiptPoolView]'s upload block) route every picked file through this one function, so it
 * is pinned once here rather than duplicated per call site.
 */
class ReceiptUploadDeciderKaribuTest {

    private val autoReduce: ReceiptAutoReduceQueryPort = mock()
    private val previewPort: PreviewReceiptReductionQueryPort = mock()
    private val originalProvider = ReceiptUploadAdviceSource.provider

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()
        ReceiptUploadAdviceSource.provider = { ReceiptUploadAdvice(autoReduce, previewPort) }
        whenever(autoReduce.isAutoReduceEnabled()).thenReturn(false)
    }

    @AfterEach
    fun teardown() {
        ReceiptUploadAdviceSource.provider = originalProvider
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun previewOf(original: Int, reduced: Int, png: ByteArray? = byteArrayOf(1)) = ReceiptReductionPreview(
        originalSize = DocumentSize.ofBytes(original.toLong()),
        reducedSize = DocumentSize.ofBytes(reduced.toLong()),
        reachesTarget = reduced <= ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES,
        firstPagePng = png,
    )

    private fun aboveTarget(bytes: Int, reduced: Int = 300 * 1024, png: ByteArray? = byteArrayOf(1)): ByteArray {
        whenever(previewPort.previewReduction(any())).thenReturn(previewOf(bytes, reduced, png))
        return bytesOfSize(bytes)
    }

    private fun bytesOfSize(bytes: Int) = ByteArray(bytes)

    @Test
    fun `ACCEPT_AS_IS - a file at or under the target size uploads immediately with REDUCE, no dialog`() {
        var confirmedMode: ReceiptUploadMode? = null

        offerReceiptUpload(null, bytesOfSize(ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES.toInt())) {
            confirmedMode = it
        }

        assertEquals(ReceiptUploadMode.REDUCE, confirmedMode)
        assertTrue(_find<Dialog>().isEmpty(), "no reduce/keep choice is offered for a file already at the target")
    }

    @Test
    fun `REJECT - a file above the upload cap is refused with the size-formatted error, no dialog`() {
        val oversized = ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES.toInt() + 1
        var called = false

        offerReceiptUpload(null, bytesOfSize(oversized)) { called = true }

        assertFalse(called, "an oversized file must never reach the upload callback")
        assertTrue(_find<Dialog>().isEmpty())
        val expected = UiTranslations.tr(
            "receipt.error.uploadTooLarge",
            DocumentSizeFormatter.format(de.littletrotter.shared.receipt.DocumentSize.ofBytes(oversized.toLong())),
            DocumentSizeFormatter.format(ReceiptSizePolicy.MAX_UPLOAD_SIZE),
        )
        val texts = getNotifications().map { it.element.textRecursively }
        assertTrue(texts.any { it.contains(expected) }, texts.toString())
    }

    @Test
    fun `setting on - a file above the target is reduced without a dialog and without a preview`() {
        whenever(autoReduce.isAutoReduceEnabled()).thenReturn(true)
        var confirmedMode: ReceiptUploadMode? = null

        offerReceiptUpload(null, bytesOfSize(ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES.toInt() + 1)) { confirmedMode = it }

        assertEquals(ReceiptUploadMode.REDUCE, confirmedMode)
        assertTrue(_find<Dialog>().isEmpty())
        verify(previewPort, never()).previewReduction(any())
    }

    @Test
    fun `setting off - the dialog shows both sizes, the target and the preview and does not upload yet`() {
        var confirmedMode: ReceiptUploadMode? = null
        val bytes = aboveTarget(900 * 1024)

        offerReceiptUpload(null, bytes) { confirmedMode = it }

        assertEquals(null, confirmedMode, "nothing is uploaded until a version is chosen")
        assertEquals(UiTranslations.tr("receipt.reduceDialog.title"), _get<Dialog>().headerTitle)
        val body = UiTranslations.tr(
            "receipt.reduceDialog.body",
            DocumentSizeFormatter.format(DocumentSize.ofBytes(900L * 1024)),
            DocumentSizeFormatter.format(DocumentSize.ofBytes(300L * 1024)),
            DocumentSizeFormatter.format(ReceiptSizePolicy.TARGET_RECEIPT_SIZE),
        )
        assertTrue(_find<Span>().any { it.text == body })
        assertEquals(1, _find<Image>().size, "the reduced first page is previewed")
    }

    @Test
    fun `setting off - without a rendered preview the dialog still opens`() {
        offerReceiptUpload(null, aboveTarget(900 * 1024, png = null)) { }

        assertTrue(_find<Image>().isEmpty())
        assertEquals(1, _find<Dialog>().size)
    }

    @Test
    fun `above the unreduced cap the original button is disabled and the reason is shown`() {
        offerReceiptUpload(null, aboveTarget(2 * 1024 * 1024)) { }

        assertFalse(_find<Button>().single { it.text == UiTranslations.tr("receipt.reduceDialog.original") }.isEnabled)
        assertTrue(_find<Span>().any { it.text == UiTranslations.tr("receipt.reduce.keep.disabled") && it.isVisible })
    }

    @Test
    fun `at or under the unreduced cap the original can be chosen and is passed on as KEEP_UNREDUCED`() {
        var confirmedMode: ReceiptUploadMode? = null
        offerReceiptUpload(null, aboveTarget(900 * 1024)) { confirmedMode = it }

        _find<Button>().single { it.text == UiTranslations.tr("receipt.reduceDialog.original") }._click()

        assertEquals(ReceiptUploadMode.KEEP_UNREDUCED, confirmedMode)
        assertTrue(_find<Dialog>().isEmpty(), "choosing closes the dialog")
    }

    @Test
    fun `choosing the reduced version passes REDUCE`() {
        var confirmedMode: ReceiptUploadMode? = null
        offerReceiptUpload(null, aboveTarget(900 * 1024)) { confirmedMode = it }

        _find<Button>().single { it.text == UiTranslations.tr("receipt.reduceDialog.reduced") }._click()

        assertEquals(ReceiptUploadMode.REDUCE, confirmedMode)
    }

    @Test
    fun `cancelling the dialog never invokes the callback`() {
        var called = false
        offerReceiptUpload(null, aboveTarget(900 * 1024)) { called = true }

        _find<Button>().single { it.text == UiTranslations.tr("app.cancel") }._click()

        assertFalse(called, "cancel must not upload anything")
        assertTrue(_find<Dialog>().isEmpty(), "cancel must close the dialog")
    }

    @Test
    fun `a failing preview shows an error and no dialog`() {
        whenever(previewPort.previewReduction(any())).thenThrow(IllegalStateException("boom"))

        offerReceiptUpload(null, bytesOfSize(900 * 1024)) { }

        assertTrue(_find<Dialog>().isEmpty())
        assertTrue(getNotifications().isNotEmpty())
    }

    @Test
    fun `result text - a reduction that misses the target names the target and the stored size`() {
        val outcome = ReceiptUploadOutcome("r.pdf", de.littletrotter.domain.masterdata.ReceiptNormalisationStage.RASTERISED, 3_000_000L, 700_000L, reductionRequested = true)

        val text = receiptUploadResultText(null, outcome)

        assertEquals(
            UiTranslations.tr(
                "receipt.autoReduce.targetMissed",
                DocumentSizeFormatter.format(ReceiptSizePolicy.TARGET_RECEIPT_SIZE),
                DocumentSizeFormatter.format(DocumentSize.ofBytes(700_000L)),
            ),
            text,
        )
    }

    @Test
    fun `result text - a reduction within the target reports before and after`() {
        val outcome = ReceiptUploadOutcome("r.pdf", de.littletrotter.domain.masterdata.ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, 3_000_000L, 300_000L, reductionRequested = true)

        assertEquals(
            UiTranslations.tr(
                "receipt.autoReduced",
                DocumentSizeFormatter.format(DocumentSize.ofBytes(3_000_000L)),
                DocumentSizeFormatter.format(DocumentSize.ofBytes(300_000L)),
            ),
            receiptUploadResultText(null, outcome),
        )
    }
}
