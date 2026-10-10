package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.dom.Element
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [openLegendDialog] generalises the original
 * `openValidationLegend` into the one builder every tile-column header's legend button uses
 * ([tileColumnHeader]). These tests cover the generic mechanics only; the actual per-enum content
 * is [de.littletrotter.ui.view.invoice.InvoiceListColumnLegends]'s concern.
 */
class LegendDialogTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    @Test
    fun `renders the title, one row per entry given, and the close button label`() {
        openLegendDialog(
            title = "Erklärung",
            closeLabel = "Schließen",
            rows = listOf(
                LegendRow(Span("tile-a"), "Satz A"),
                LegendRow(Span("tile-b"), "Satz B", word = "Wort B"),
            ),
        )

        val dialog = _get<Dialog>()
        assertEquals("Erklärung", dialog.headerTitle)

        val texts = collectText(dialog.element)
        assertTrue(texts.contains("Satz A"), "first row's sentence must be rendered")
        assertTrue(texts.contains("Satz B"), "second row's sentence must be rendered")
        assertTrue(texts.contains("Wort B"), "a row's visible word must be rendered when given")
        // The close button lives in the dialog footer, outside the dialog's own content element.
        assertEquals("Schließen", _get<Button> { text = "Schließen" }.text, "close button must carry the given label")
    }

    @Test
    fun `is closable on Escape`() {
        openLegendDialog(title = "Erklärung", closeLabel = "Schließen", rows = emptyList())

        assertTrue(_get<Dialog>().isCloseOnEsc)
    }

    private fun collectText(element: Element): List<String> {
        val texts = mutableListOf<String>()
        fun walk(el: Element) {
            texts += el.text ?: ""
            el.children.forEach { walk(it) }
        }
        walk(element)
        return texts
    }
}
