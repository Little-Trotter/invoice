package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppDialogTest {
    @BeforeEach
    fun setUp() = MockVaadin.setup()

    @AfterEach
    fun tearDown() = MockVaadin.tearDown()

    @Test
    fun `sizes map to the specified widths and M is default`() {
        assertEquals("min(92vw, 28rem)", AppDialog("t", AppDialog.Size.S).width)
        assertEquals("min(92vw, 40rem)", AppDialog("t", AppDialog.Size.M).width)
        assertEquals("min(94vw, 64rem)", AppDialog("t", AppDialog.Size.L).width)
        assertEquals("min(92vw, 40rem)", AppDialog("t").width)
        assertEquals("t", AppDialog("t").headerTitle)
    }

    @Test
    fun `footer orders cancel first and makes the last action primary`() {
        val dialog = AppDialog("t")
        val cancel = Button("c").apply { addThemeVariants(ButtonVariant.LUMO_SMALL) }
        val a = Button("a")
        val b = Button("b").apply { addThemeVariants(ButtonVariant.LUMO_SMALL) }
        dialog.footer(cancel, a, b)
        assertTrue(cancel.themeNames.contains("tertiary"))
        assertFalse(cancel.themeNames.contains("small"))
        assertFalse(b.themeNames.contains("small"))
        assertEquals(listOf(cancel.element, a.element, b.element), dialog.footer.element.children.toList())
        assertTrue(b.themeNames.contains("primary"))
        assertFalse(a.themeNames.contains("primary"))
        dialog.footer(cancel)
        assertEquals(listOf(cancel.element), dialog.footer.element.children.toList())
    }

    @Test
    fun `confirmDelete is cancelable with error confirm button and runs callback`() {
        var confirmed = 0
        val dialog = confirmDelete("Delete?", "Really?") { confirmed++ }
        assertTrue(dialog.isOpened)
        assertTrue(dialog.element.getProperty("cancelButtonVisible", false))
        assertEquals("error", dialog.element.getProperty("confirmTheme"))
        assertEquals(UiTranslations.tr("app.delete"), dialog.element.getProperty("confirmText"))
        assertEquals(UiTranslations.tr("app.cancel"), dialog.element.getProperty("cancelText"))
        ComponentUtil.fireEvent(dialog, ConfirmDialog.ConfirmEvent(dialog, false))
        assertEquals(1, confirmed)
    }
}
