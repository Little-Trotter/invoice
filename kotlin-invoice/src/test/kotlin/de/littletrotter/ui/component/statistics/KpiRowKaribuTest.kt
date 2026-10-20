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

class KpiRowKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val accent = KpiAccent(VaadinIcon.MONEY, KpiTone.ERROR)

    private fun cards(row: KpiRow): List<Div> = row.children.toList().filterIsInstance<Div>()
    private fun spans(card: Div): List<Span> = card.children.toList().filterIsInstance<Span>()

    @Test
    fun `renders one card per tile with its value`() {
        val row = KpiRow()
        row.render(listOf(KpiTile("A", "1", accent), KpiTile("B", "2", accent)))

        assertEquals(listOf("1", "2"), cards(row).map { spans(it)[0].text })
    }

    @Test
    fun `render replaces earlier cards`() {
        val row = KpiRow()
        row.render(listOf(KpiTile("A", "1", accent), KpiTile("B", "2", accent)))
        row.render(listOf(KpiTile("C", "3", accent)))

        assertEquals(1, cards(row).size)
    }

    @Test
    fun `delta badge is coloured by direction and absent without a delta`() {
        val row = KpiRow()
        row.render(
            listOf(
                KpiTile("up", "1", accent, delta = KpiDelta("↑ +5,0% vs. 2025", true)),
                KpiTile("down", "1", accent, delta = KpiDelta("↓ -5,0% vs. 2025", false)),
                KpiTile("none", "1", accent),
            ),
        )
        val (up, down, none) = cards(row)

        assertEquals("↑ +5,0% vs. 2025", spans(up)[1].text)
        assertTrue(spans(up)[1].hasClassName("lt-kpi-card__delta--up"))
        assertTrue(spans(down)[1].hasClassName("lt-kpi-card__delta--down"))
        assertEquals(1, spans(none).size)
    }

    @Test
    fun `extra values and caption render as further lines`() {
        val row = KpiRow()
        row.render(listOf(KpiTile("A", "1", accent, extraValues = listOf("x", "y"), caption = "note")))
        val card = cards(row).single()

        val extra = card.children.toList().filterIsInstance<Div>().first()
        assertEquals(listOf("x", "y"), extra.children.toList().filterIsInstance<Span>().map { it.text })
        assertTrue(spans(card).any { it.text == "note" })
    }
}
