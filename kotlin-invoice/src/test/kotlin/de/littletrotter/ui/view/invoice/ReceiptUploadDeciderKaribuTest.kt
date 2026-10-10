package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.application.invoice.ReceiptUploadMode
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

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
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
    fun `OFFER_REDUCTION - a file above the target opens the reduce dialog and does not upload yet`() {
        var confirmedMode: ReceiptUploadMode? = null

        offerReceiptUpload(null, bytesOfSize(ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES.toInt() + 1)) {
            confirmedMode = it
        }

        assertEquals(null, confirmedMode, "nothing is uploaded until the dialog is confirmed")
        assertEquals(1, _find<Dialog>().size)
        assertEquals(UiTranslations.tr("receipt.reduce.title"), _get<Dialog>().headerTitle)
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

    private fun modeGroup(): RadioButtonGroup<ReceiptUploadMode> = _get<RadioButtonGroup<ReceiptUploadMode>>()

    @Test
    fun `above the unreduced cap the keep option is disabled and its hint explains why`() {
        // 2 MiB: within the reduce-offer range (above target, at/under the upload cap) but above
        // the smaller unreduced cap (1 MiB) — keep must be disabled outright.
        val twoMebiBytes = 2 * 1024 * 1024
        offerReceiptUpload(null, bytesOfSize(twoMebiBytes)) { }

        val group = modeGroup()
        assertFalse(
            group.itemEnabledProvider.test(ReceiptUploadMode.KEEP_UNREDUCED),
            "keep must be disabled above the unreduced cap",
        )
        val disabledHint = _find<Span>().single { it.text == UiTranslations.tr("receipt.reduce.keep.disabled") }
        assertTrue(disabledHint.isVisible)
    }

    @Test
    fun `at or under the unreduced cap keep is enabled and its hint only shows once keep is actually selected`() {
        // 900 KiB: above the target (768 KiB) but at/under the unreduced cap (1 MiB) — keep stays
        // selectable.
        val ninetyPercentOfCap = 900 * 1024
        offerReceiptUpload(null, bytesOfSize(ninetyPercentOfCap)) { }

        val group = modeGroup()
        assertTrue(group.itemEnabledProvider.test(ReceiptUploadMode.KEEP_UNREDUCED))
        assertEquals(ReceiptUploadMode.REDUCE, group.value, "reduce is preselected")
        assertTrue(
            _find<Span>().none { it.text == UiTranslations.tr("receipt.reduce.keep.hint") && it.isVisible },
            "the keep hint must not compete with the (preselected) reduce path",
        )

        group.value = ReceiptUploadMode.KEEP_UNREDUCED

        val keepHint = _find<Span>().single { it.text == UiTranslations.tr("receipt.reduce.keep.hint") }
        assertTrue(keepHint.isVisible, "selecting keep must reveal the hint")

        group.value = ReceiptUploadMode.REDUCE
        assertFalse(keepHint.isVisible, "switching back to reduce must hide the hint again")
    }

    @Test
    fun `confirming the dialog passes the selected mode to the callback and closes it`() {
        var confirmedMode: ReceiptUploadMode? = null
        offerReceiptUpload(null, bytesOfSize(900 * 1024)) { confirmedMode = it }
        modeGroup().value = ReceiptUploadMode.KEEP_UNREDUCED

        _find<Button>().single { it.text == UiTranslations.tr("receipt.reduce.confirm") }._click()

        assertEquals(ReceiptUploadMode.KEEP_UNREDUCED, confirmedMode)
        assertTrue(_find<Dialog>().isEmpty(), "confirming must close the dialog")
    }

    @Test
    fun `cancelling the dialog never invokes the callback`() {
        var called = false
        offerReceiptUpload(null, bytesOfSize(900 * 1024)) { called = true }

        _find<Button>().single { it.text == UiTranslations.tr("app.cancel") }._click()

        assertFalse(called, "cancel must not upload anything")
        assertTrue(_find<Dialog>().isEmpty(), "cancel must close the dialog")
    }
}
