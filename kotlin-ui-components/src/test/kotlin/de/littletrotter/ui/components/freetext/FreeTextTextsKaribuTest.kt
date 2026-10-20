package de.littletrotter.ui.components.freetext

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * [FreeTextTexts]: the locale source (session UI when present, German otherwise) and the optional
 * message-format parameters.
 */
class FreeTextTextsKaribuTest {

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `without a current UI the German base bundle is used`() {
        assertEquals("Fett", FreeTextTexts.tr("freeText.toolbar.bold"))
    }

    @Test
    fun `a UI locale of English selects the English bundle and formats parameters`() {
        MockVaadin.setup()
        UI.getCurrent().locale = Locale.ENGLISH

        assertEquals("Bold", FreeTextTexts.tr("freeText.toolbar.bold"))
        assertEquals("A: insert \"B\" into the free text", FreeTextTexts.tr("freeText.chips.insert", "A", "B"))
    }
}
