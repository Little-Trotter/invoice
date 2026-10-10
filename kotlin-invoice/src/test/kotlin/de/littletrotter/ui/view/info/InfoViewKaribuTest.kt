package de.littletrotter.ui.view.info

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.H2
import com.github.mvysny.kaributesting.v10._get
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * UI-5 slice 3c — [InfoView] had no test file at all: neither the happy-path page-title update
 * (a live [UI]) nor the no-UI early return had ever been exercised.
 */
class InfoViewKaribuTest {

    private fun identity(): ApplicationIdentity =
        mock<ApplicationIdentity>().also { whenever(it.displayName).thenReturn("Little Trotter") }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `constructing InfoView with no attached UI skips the page-title update`() {
        // Deliberately no MockVaadin.setup() — UI.getCurrent() is null outside a Vaadin request,
        // the early-return branch updatePageTitle documents.
        assertEquals(null, UI.getCurrent())

        val view = InfoView("1.2.3", identity())

        assertEquals(UiTranslations.tr("info.title"), view._get<H2>().text)
    }

    @Test
    fun `constructing InfoView with a live UI sets the page title`() {
        MockVaadin.setup()

        InfoView("1.2.3", identity())

        assertEquals(
            "${UiTranslations.tr("info.title")} | ${UiTranslations.tr("app.title")}",
            UI.getCurrent().internals.title,
        )
    }
}
