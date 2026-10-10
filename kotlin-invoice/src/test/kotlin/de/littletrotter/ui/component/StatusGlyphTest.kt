package de.littletrotter.ui.component

import com.vaadin.flow.component.icon.VaadinIcon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusGlyphTest {
    @Test
    fun `glyph carries tone class, icon and label as tooltip and accessible name`() {
        val glyph = StatusGlyph(VaadinIcon.CHECK_CIRCLE, ChipTone.OK, "Gültig")

        assertTrue(glyph.hasClassName("status-glyph--ok"))
        assertFalse(glyph.hasClassName("status-glyph--pulse"))
        assertEquals("img", glyph.element.getAttribute("role"))
        assertEquals("Gültig", glyph.element.getAttribute("aria-label"))
        assertEquals("Gültig", glyph.element.getAttribute("title"))
        assertEquals("", glyph.text)
        assertEquals(1, glyph.children.count())
        assertEquals("true", glyph.children.findFirst().get().element.getAttribute("aria-hidden"))
    }

    @Test
    fun `pulsing glyph gets the pulse class`() {
        assertTrue(StatusGlyph(VaadinIcon.CLOCK, ChipTone.ACCENT, "Läuft", pulsing = true).hasClassName("status-glyph--pulse"))
    }

    @Test
    fun `clickable glyph is a button with action name, tooltip and non-bubbling click`() {
        var clicks = 0
        val button = clickableStatusGlyph(
            GlyphAppearance(VaadinIcon.WARNING, ChipTone.WARN, "Nicht prüfbar", pulsing = true),
            ariaLabel = "Nicht prüfbar — öffnen",
            tooltip = "Erklärung",
        ) { clicks++ }

        assertEquals("button", button.element.tag)
        assertTrue(button.hasClassName("status-glyph--warn"))
        assertTrue(button.hasClassName("status-glyph--clickable"))
        assertTrue(button.hasClassName("status-glyph--pulse"))
        assertEquals("Nicht prüfbar — öffnen", button.element.getAttribute("aria-label"))
        assertEquals("Erklärung", button.element.getAttribute("title"))
        assertNull(button.element.getAttribute("role"))
    }

    @Test
    fun `clickable glyph without pulse has no pulse class`() {
        val button = clickableStatusGlyph(GlyphAppearance(VaadinIcon.CHECK, ChipTone.OK, "ok"), "a", "t") { }

        assertFalse(button.hasClassName("status-glyph--pulse"))
    }
}
