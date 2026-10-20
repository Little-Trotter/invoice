package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.invoice.port.inbound.HardToReadPage
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityCheckException
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [offerReadabilityHint]: silent when the check finds nothing, otherwise a dialog with preview and two choices. */
class ReceiptReadabilityHintKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val accept get() = UiTranslations.tr("receipt.readability.accept")
    private val rescan get() = UiTranslations.tr("receipt.readability.rescan")
    private val pages = listOf(HardToReadPage(2, 70, byteArrayOf(1, 2, 3)), HardToReadPage(3, null, byteArrayOf(4)))

    @Test
    fun `nothing found continues straight away without a dialog`() {
        var proceeded = 0
        offerReadabilityHint(null, ByteArray(0), { proceeded++ }, ReceiptReadabilityQueryPort { emptyList() })
        assertEquals(1, proceeded)
        assertTrue(_find<Dialog>().isEmpty())
    }

    @Test
    fun `a failed measurement is logged by continuing and never blocks the upload`() {
        var proceeded = 0
        val failing = ReceiptReadabilityQueryPort { throw ReceiptReadabilityCheckException("boom", IllegalStateException("decode")) }
        offerReadabilityHint(null, ByteArray(0), { proceeded++ }, failing)
        assertEquals(1, proceeded)
        assertTrue(_find<Dialog>().isEmpty())
    }

    @Test
    fun `any other failure is a defect and propagates`() {
        assertThrows(IllegalArgumentException::class.java) {
            offerReadabilityHint(null, ByteArray(0), {}, ReceiptReadabilityQueryPort { throw IllegalArgumentException("bug") })
        }
    }

    @Test
    fun `reported pages open the hint dialog with preview and page numbers and wait`() {
        var proceeded = 0
        offerReadabilityHint(null, ByteArray(0), { proceeded++ }, ReceiptReadabilityQueryPort { pages })
        assertEquals(0, proceeded)
        assertEquals(1, _find<Dialog>().size)
        assertEquals(1, _find<Image>().size)
        assertTrue(_find<Span>().any { it.text.contains("2, 3") }, "body names the pages")
        assertEquals(setOf(rescan, accept), _find<Button>().map { it.text }.toSet().intersect(setOf(rescan, accept)))
    }

    @Test
    fun `use anyway proceeds`() {
        var proceeded = 0
        offerReadabilityHint(null, ByteArray(0), { proceeded++ }, ReceiptReadabilityQueryPort { pages })
        _find<Button> { text = accept }.single()._click()
        assertEquals(1, proceeded)
    }

    @Test
    fun `upload again closes the dialog and does not proceed`() {
        var proceeded = 0
        offerReadabilityHint(null, ByteArray(0), { proceeded++ }, ReceiptReadabilityQueryPort { pages })
        _find<Button> { text = rescan }.single()._click()
        assertEquals(0, proceeded)
        assertFalse(_find<Dialog>().any { it.isOpened })
    }
}
