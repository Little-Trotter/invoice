package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.shared.Tooltip
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppButtonsTest {
    @BeforeEach
    fun setUp() = MockVaadin.setup()

    @AfterEach
    fun tearDown() = MockVaadin.tearDown()

    @Test
    fun `primary carries the primary variant and fires onClick`() {
        var clicks = 0
        val b = AppButtons.primary("Go") { clicks++ }
        assertEquals("Go", b.text)
        assertTrue(b.themeNames.contains("primary"))
        b._click()
        assertEquals(1, clicks)
    }

    @Test
    fun `secondary has no variant`() {
        val b = AppButtons.secondary("Go") {}
        assertTrue(b.themeNames.isEmpty())
        assertNull(b.icon)
    }

    @Test
    fun `tertiary carries the tertiary variant`() {
        assertTrue(AppButtons.tertiary("Go") {}.themeNames.contains("tertiary"))
    }

    @Test
    fun `cancel is a tertiary text button with the translated label`() {
        var clicks = 0
        val b = AppButtons.cancel { clicks++ }
        assertEquals(UiTranslations.tr("app.cancel"), b.text)
        assertTrue(b.themeNames.contains("tertiary"))
        assertFalse(b.themeNames.contains("primary"))
        b._click()
        assertEquals(1, clicks)
    }

    @Test
    fun `danger carries the error variant and no primary`() {
        val b = AppButtons.danger("Del", VaadinIcon.TRASH) {}
        assertTrue(b.themeNames.contains("error"))
        assertFalse(b.themeNames.contains("primary"))
        assertNotNull(b.icon)
    }

    @Test
    fun `icon button has tooltip aria label and icon plus tertiary variants`() {
        var clicks = 0
        val b = AppButtons.icon(VaadinIcon.PENCIL, "Edit") { clicks++ }
        assertEquals("Edit", b.ariaLabel.orElse(null))
        assertTrue(b.themeNames.containsAll(listOf("icon", "tertiary")))
        assertEquals("Edit", Tooltip.forComponent(b).text)
        b._click()
        assertEquals(1, clicks)
    }
}
