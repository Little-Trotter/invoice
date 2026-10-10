package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UI-5 — [Section] had no dedicated test before this file: a plain header(bar+title)+content
 * wrapper, but the [barMod] modifier class is only conditionally applied.
 */
class SectionTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun header(section: Section): Div = section.children.toList().filterIsInstance<Div>()[0]
    private fun bar(section: Section): Div = header(section).children.toList().filterIsInstance<Div>()[0]

    @Test
    fun `renders the header with title and bar, followed by the content`() {
        val content = Span("body")
        val section = Section("My Title", content = content)

        val title = header(section).children.toList().filterIsInstance<Span>().single()
        assertEquals("My Title", title.text)
        assertTrue(section.children.toList().contains(content))
    }

    @Test
    fun `a blank barMod adds no extra class to the bar`() {
        val section = Section("Title", barMod = "", content = Span())

        assertFalse(bar(section).classNames.any { it != "sp-section-bar" })
    }

    @Test
    fun `a non-blank barMod adds its class to the bar`() {
        val section = Section("Title", barMod = "sp-section-bar--warn", content = Span())

        assertTrue(bar(section).classNames.contains("sp-section-bar--warn"))
    }
}
