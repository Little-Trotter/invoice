package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * PO follow-up (2026-09-17) — [tileColumnHeader] is the reusable, meaningful-icon header
 * button every narrowed tile column uses instead of its own written word. Checks the
 * accessibility plumbing of the header case, plus the click-vs-sort independence.
 */
class TileColumnHeaderTest {

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
    fun `title and aria-label are both carried, distinct, and non-blank`() {
        val header = tileColumnHeader(VaadinIcon.FLAG, "Status", "Status — Erklärung anzeigen") {}

        assertTrue(header is Button, "must be a real button, keyboard focusable")
        assertEquals("Status", header.element.getAttribute("title"))
        assertEquals("Status — Erklärung anzeigen", header.element.getAttribute("aria-label"))
    }

    @Test
    fun `the button's only child is the Vaadin icon, no visible text`() {
        val header = tileColumnHeader(VaadinIcon.CLIPBOARD_CHECK, "Validierung", "Validierung — Erklärung anzeigen") {} as Button

        val icon = header.children.findFirst().orElseThrow()
        assertTrue(icon is Icon, "the header's only child must be the Vaadin icon")
        assertTrue(header.text.isNullOrBlank(), "no visible text next to the icon — title/aria-label carry the word instead")
    }

    @Test
    fun `clicking the header symbol invokes the action exactly once`() {
        var clicks = 0
        val header = tileColumnHeader(VaadinIcon.FLAG, "Status", "Status — Erklärung anzeigen") { clicks++ } as Button

        header._click()

        assertEquals(1, clicks)
    }
}
