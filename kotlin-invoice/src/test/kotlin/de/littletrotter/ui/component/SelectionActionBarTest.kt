package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [SelectionActionBar] is reused by three call sites (the receipts tab's two tables and
 *  [de.littletrotter.ui.view.invoice.ReceiptDialog]'s attached grid); this pins the shared
 *  component's own behaviour once instead of at every call site. */
class SelectionActionBarTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun bar(onClear: () -> Unit = {}, onAction: () -> Unit = {}): SelectionActionBar =
        SelectionActionBar(
            countText = { n -> "$n selected" },
            actionText = "Unlink selected",
            actionIcon = VaadinIcon.UNLINK,
            clearAriaLabel = "Clear selection",
            onClear = onClear,
            onAction = onAction,
        )

    @Test
    fun `clicking the clear button invokes onClear`() {
        var cleared = false
        val b = bar(onClear = { cleared = true })

        b._find<Button>().first { it.element.getAttribute("aria-label") == "Clear selection" }._click()

        assertTrue(cleared)
    }

    @Test
    fun `clicking the action button invokes onAction`() {
        var acted = false
        val b = bar(onAction = { acted = true })

        b._find<Button>().first { it.text == "Unlink selected" }._click()

        assertTrue(acted)
    }

    @Test
    fun `setCount renders through the caller-supplied formatter`() {
        val b = bar()

        b.setCount(3)

        val count = b._find<Span>().single { it.hasClassName("selection-action-bar__count") }
        assertEquals("3 selected", count.text)
    }

    @Test
    fun `detachRowButton carries the given text as tooltip and accessible name, and forwards its click`() {
        var clicked = false
        val button = detachRowButton("Zuordnung entfernen") { clicked = true }

        assertEquals("Zuordnung entfernen", button.element.getProperty("title"))
        assertEquals("Zuordnung entfernen", button.element.getAttribute("aria-label"))
        button._click()
        assertTrue(clicked)
    }
}
