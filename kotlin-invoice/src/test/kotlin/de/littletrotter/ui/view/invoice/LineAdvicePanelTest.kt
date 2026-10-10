package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexLayout
import de.littletrotter.ui.component.StatusChip
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * AP-IMP-5 — [LineAdvicePanel] had no dedicated test before this file: the
 * hidden-when-empty/visible-when-not-empty toggle in [LineAdvicePanel.update] and the per-chip
 * dismiss click were both unexercised.
 */
class LineAdvicePanelTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun chipRow(panel: LineAdvicePanel): FlexLayout =
        panel.component.children.toList().filterIsInstance<FlexLayout>().first()

    @Test
    fun `the panel starts hidden with no advice`() {
        val panel = LineAdvicePanel { it }
        assertFalse(panel.component.isVisible)
    }

    @Test
    fun `update with a non-empty advice list makes the panel visible and renders one chip per item`() {
        val panel = LineAdvicePanel { it }
        panel.update(listOf(LineAdvice("CODE_A", "msg.a"), LineAdvice("CODE_B", "msg.b")))

        assertTrue(panel.component.isVisible)
        assertEquals(2, chipRow(panel).children.toList().filterIsInstance<StatusChip>().size)
    }

    @Test
    fun `update with an empty list hides the panel again and clears previous chips`() {
        val panel = LineAdvicePanel { it }
        panel.update(listOf(LineAdvice("CODE_A", "msg.a")))
        panel.update(emptyList())

        assertFalse(panel.component.isVisible)
        assertTrue(chipRow(panel).children.toList().isEmpty())
    }

    @Test
    fun `clicking a chip's dismiss control hides just that chip`() {
        val panel = LineAdvicePanel { it }
        panel.update(listOf(LineAdvice("CODE_A", "msg.a")))

        val chip = chipRow(panel).children.toList().filterIsInstance<StatusChip>().first()
        val dismiss = chip.children.toList().filterIsInstance<Span>().first { it.text == "×" }
        assertTrue(chip.isVisible)

        dismiss._click()

        assertFalse(chip.isVisible, "dismissing must hide the chip in place, not remove/replace it")
    }
}
