package de.littletrotter.ui.component.statistics

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StatusCardsRowKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `renders count, share and colour family per card and the context label`() {
        val row = StatusCardsRow()
        row.render(
            listOf(
                StatusCard("Offen", 3, "75,0%", StatusColorFamily.SUCCESS, VaadinIcon.EYE),
                StatusCard("Gesamt", 4, "100%", StatusColorFamily.TOTAL, VaadinIcon.LIST),
            ),
            "Angezeigt für: Jahr 2026",
        )

        val cards = row.children.toList().filterIsInstance<Div>()
        assertEquals(2, cards.size)
        val texts = cards[0].children.toList().filterIsInstance<Span>().map { it.text }
        assertEquals(listOf("3", "75,0%"), texts)
        assertTrue(cards[0].hasClassName(StatusColorFamily.SUCCESS.cssClass))
        assertEquals("Angezeigt für: Jahr 2026", row.contextLabel.text)
    }

    @Test
    fun `render replaces earlier cards`() {
        val row = StatusCardsRow()
        val card = StatusCard("A", 1, "—", StatusColorFamily.NEUTRAL, VaadinIcon.EYE)
        row.render(listOf(card, card), "x")
        row.render(listOf(card), "y")

        assertEquals(1, row.children.toList().filterIsInstance<Div>().size)
        assertEquals("y", row.contextLabel.text)
    }
}
