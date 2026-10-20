package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StatusChipKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `clickable chip is a button with aria label and tooltip and fires its action`() {
        var clicked = 0
        val chip = clickableStatusChip(ChipAppearance("Ungültig", ChipTone.ERROR), "Ungültig — öffnen", "tip") { clicked++ }

        assertEquals("button", chip.element.tag)
        assertTrue(chip.hasClassName("status-chip--error"))
        assertTrue(chip.hasClassName("status-chip--clickable"))
        assertEquals("Ungültig — öffnen", chip.element.getAttribute("aria-label"))
        assertEquals("tip", chip.element.getAttribute("title"))
        assertEquals("Ungültig", chip.element.text)

        chip._click()

        assertEquals(1, clicked)
    }
}
