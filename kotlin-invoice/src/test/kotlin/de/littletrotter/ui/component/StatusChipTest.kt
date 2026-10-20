package de.littletrotter.ui.component

import com.vaadin.flow.component.icon.VaadinIcon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusChipTest {

    @Test
    fun `every tone renders the capsule class and its own tone modifier`() {
        ChipTone.entries.forEach { tone ->
            val chip = StatusChip("label", tone)
            assertTrue(chip.hasClassName("status-chip"), "tone=$tone")
            assertTrue(chip.hasClassName("status-chip--${tone.css}"), "tone=$tone")
            assertEquals("status", chip.element.getAttribute("role"))
            assertEquals("label", chip.text)
        }
    }

    @Test
    fun `tone modifiers are pairwise distinct`() {
        assertEquals(ChipTone.entries.size, ChipTone.entries.map { it.css }.toSet().size)
    }

    @Test
    fun `pulsing is opt-in`() {
        assertFalse(StatusChip("a", ChipTone.ACCENT).hasClassName("status-chip--pulse"))
        assertTrue(StatusChip("a", ChipTone.ACCENT, pulsing = true).hasClassName("status-chip--pulse"))
    }

    @Test
    fun `an icon replaces the dot and keeps the label text`() {
        val plain = StatusChip("a", ChipTone.WARN)
        val withIcon = StatusChip("a", ChipTone.WARN, icon = VaadinIcon.WARNING)

        assertFalse(plain.hasClassName("status-chip--icon"))
        assertEquals(0L, plain.children.count())
        assertTrue(withIcon.hasClassName("status-chip--icon"))
        assertEquals("vaadin:warning", withIcon.element.getChild(0).getAttribute("icon"))
        assertEquals("true", withIcon.element.getChild(0).getAttribute("aria-hidden"))
        assertEquals("a", withIcon.text)
    }
}
