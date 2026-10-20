package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.shared.Tooltip
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RowEditButtonsTest {
    @BeforeEach
    fun setUp() = MockVaadin.setup()

    @AfterEach
    fun tearDown() = MockVaadin.tearDown()

    @Test
    fun `confirm is a small filled icon button with save tooltip and fires onClick`() {
        var clicks = 0
        val b = RowEditButtons.confirm { clicks++ }
        assertTrue(b.themeNames.containsAll(listOf("primary", "small", "icon")))
        assertFalse(b.themeNames.contains("tertiary"))
        assertEquals(UiTranslations.tr("app.save"), Tooltip.forComponent(b).text)
        b._click()
        assertEquals(1, clicks)
    }

    @Test
    fun `cancel is a small low-emphasis icon button with cancel tooltip and fires onClick`() {
        var clicks = 0
        val b = RowEditButtons.cancel { clicks++ }
        assertTrue(b.themeNames.containsAll(listOf("tertiary", "small", "icon")))
        assertFalse(b.themeNames.contains("primary"))
        assertEquals(UiTranslations.tr("app.cancel"), Tooltip.forComponent(b).text)
        b._click()
        assertEquals(1, clicks)
    }
}
