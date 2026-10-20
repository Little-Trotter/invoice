package de.littletrotter.ui.view.info

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tabs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [DisclaimerPanel] had no test file at all: neither the happy-path rendering
 * nor the language-tab selection listener (German/English visibility toggle) had ever been exercised.
 */
class DisclaimerPanelKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun tabsAndContents(panel: DisclaimerPanel): Pair<Tabs, List<VerticalLayout>> {
        val languageSection = panel.children.toList().filterIsInstance<VerticalLayout>().last()
        val tabs = languageSection.children.toList().filterIsInstance<Tabs>().single()
        val contents = languageSection.children.toList().filterIsInstance<VerticalLayout>()
        return tabs to contents
    }

    @Test
    fun `happy path - constructing shows the version and defaults to the German tab visible`() {
        val panel = DisclaimerPanel("1.2.3", "Little Trotter")

        val (tabs, contents) = tabsAndContents(panel)
        assertEquals(2, tabs.componentCount)
        val germanContent = contents[0]
        val englishContent = contents[1]
        assertEquals(true, germanContent.isVisible)
        assertEquals(false, englishContent.isVisible)
    }

    @Test
    fun `boundary - selecting the English tab shows English and hides German`() {
        val panel = DisclaimerPanel("1.2.3", "Little Trotter")
        val (tabs, contents) = tabsAndContents(panel)
        val germanContent = contents[0]
        val englishContent = contents[1]

        tabs.selectedIndex = 1

        assertEquals(false, germanContent.isVisible)
        assertEquals(true, englishContent.isVisible)
    }

    @Test
    fun `boundary - selecting back to the German tab shows German and hides English`() {
        val panel = DisclaimerPanel("1.2.3", "Little Trotter")
        val (tabs, contents) = tabsAndContents(panel)
        val germanContent = contents[0]
        val englishContent = contents[1]

        tabs.selectedIndex = 1
        tabs.selectedIndex = 0

        assertEquals(true, germanContent.isVisible)
        assertEquals(false, englishContent.isVisible)
    }

    @Test
    fun `boundary - blank app version is still rendered as the version span`() {
        val panel = DisclaimerPanel("", "Little Trotter")

        val versionSection = panel.children.toList().filterIsInstance<VerticalLayout>().first()
        val version = versionSection.children.toList().last()
        assertEquals("", (version as com.vaadin.flow.component.html.Span).text)
    }

    @Test
    fun `happy path - brand hero shows the transparent horse and the product name as text`() {
        val panel = DisclaimerPanel("1.2.3", "Musterfirma Rechnung")

        val hero = panel.children.toList().filterIsInstance<VerticalLayout>().first()
        val image = hero.children.toList().filterIsInstance<Image>().single()
        assertEquals("images/brand-horse.png", image.src)
        assertEquals("Musterfirma Rechnung Logo: stilisiertes Pferd", image.alt.orElse(""))
        val name = hero.children.toList().filterIsInstance<Span>().single { it.hasClassName("disclaimer-brand-name") }
        assertEquals("Musterfirma Rechnung", name.text)
    }
}
