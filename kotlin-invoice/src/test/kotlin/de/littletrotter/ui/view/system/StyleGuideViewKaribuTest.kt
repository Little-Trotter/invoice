package de.littletrotter.ui.view.system

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StyleGuideViewKaribuTest {

    @BeforeEach
    fun setUp() {
        MockVaadin.setup()
        UI.getCurrent().add(StyleGuideView())
    }

    @AfterEach
    fun tearDown() = MockVaadin.tearDown()

    @Test
    fun `renders one chip per colour tone`() {
        val chips = _find<StatusChip>()
        assertEquals(8, chips.size)
        assertTrue(chips.all { chip -> chip.classNames.any { it.startsWith("status-chip--") } })
    }

    @Test
    fun `grid shows three money rows`() {
        val grid = _get<Grid<*>>()
        assertEquals(3, grid.dataCommunicator.itemCount)
    }

    @Test
    fun `dark mode toggle can be switched both ways`() {
        val toggle = _get<Checkbox>()
        toggle.value = true
        toggle.value = false
        assertEquals(false, toggle.value)
    }

    @Test
    fun `each notification button opens one notification and the single slot keeps only the latest`() {
        val tiers = listOf("success", "warning", "error", "info")
        tiers.forEach {
            _get<Button> { text = UiTranslations.tr("styleguide.notify.$it") }._click()
            MockVaadin.clientRoundtrip()
            assertEquals(1, getNotifications().size)
        }
    }

    @Test
    fun `every other button works and the three dialog sizes open with a working footer`() {
        // Notifications attach to the modal dialog that is open at the time, so they are covered
        // by their own test instead of being clicked here.
        val notificationTexts = listOf("success", "warning", "error", "info").map { UiTranslations.tr("styleguide.notify.$it") }
        _find<Button>().filter { it.isEnabled && it.text !in notificationTexts }.forEach { it._click() }

        val dialogs = _find<Dialog>().filterIsInstance<AppDialog>()
        assertEquals(AppDialog.Size.entries.map { it.width }.toSet(), dialogs.map { it.width }.toSet())
        assertTrue(_find<ConfirmDialog>().isNotEmpty())
        dialogs.forEach { dialog ->
            dialog._get<Button> { text = UiTranslations.tr("styleguide.dialog.confirm") }._click()
        }
        assertTrue(dialogs.none { it.isOpened })
    }

    @Test
    fun `cancel in the dialog footer closes it`() {
        _get<Button> { text = UiTranslations.tr("styleguide.dialog.small") }._click()
        val dialog = _get<AppDialog>()
        dialog._get<Button> { text = UiTranslations.tr("app.cancel") }._click()
        assertTrue(!dialog.isOpened)
    }
}
