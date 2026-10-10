package de.littletrotter.ui.component.statistics

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RevenueMatrixKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val rows = listOf(LabeledValue("Netto", "1"), LabeledValue("Brutto", "2", bold = true))

    private fun group(heading: String? = null) = PeriodCardGroup(
        heading,
        listOf(
            PeriodCard("Q1", rows, barShare = 100, drillKey = 1),
            PeriodCard("Q2", rows, barShare = 250, drillKey = 2),
            PeriodCard("Gesamt", rows, primary = true),
        ),
    )

    private fun cardRows(m: RevenueMatrix) = m.children.toList().filterIsInstance<Div>().filter { it.hasClassName("revenue-card-row") }
    private fun cards(row: Div) = row.children.toList().filterIsInstance<Div>()

    @Test
    fun `renders cards with class per kind, highlights the active key and sizes the bar`() {
        val m = RevenueMatrix {}
        m.render(listOf(group()), active = 2)

        val (q1, q2, total) = cards(cardRows(m).single())
        assertTrue(q1.hasClassName("revenue-card--quarter"))
        assertFalse(q1.hasClassName("revenue-card--active"))
        assertTrue(q2.hasClassName("revenue-card--active"))
        assertTrue(total.hasClassName("revenue-card--total"))
        val fill = q1.children.toList().filterIsInstance<Div>().last().children.toList().single()
        assertEquals("100%", fill.element.style.get("--lt-bar-h"))
        val clamped = q2.children.toList().filterIsInstance<Div>().last().children.toList().single()
        assertEquals("100%", clamped.element.style.get("--lt-bar-h"))
        assertTrue(total.children.toList().filterIsInstance<Div>().none { it.hasClassName("revenue-card__bar-track") })
    }

    @Test
    fun `no active key highlights nothing`() {
        val m = RevenueMatrix {}
        m.render(listOf(group()), active = null)

        assertTrue(cards(cardRows(m).single()).none { it.hasClassName("revenue-card--active") })
    }

    @Test
    fun `clicking a drillable card reports its key, the total card is inert`() {
        val clicked = mutableListOf<Int>()
        val m = RevenueMatrix { clicked += it }
        m.render(listOf(group()), active = null)
        val (q1, _, total) = cards(cardRows(m).single())

        q1._click()
        total._click()

        assertEquals(listOf(1), clicked)
    }

    @Test
    fun `headings are only printed when given, and render replaces earlier rows`() {
        val m = RevenueMatrix {}
        m.render(listOf(group("EUR"), group("CHF")), active = null)
        assertEquals(listOf("EUR", "CHF"), m.children.toList().filterIsInstance<Span>().map { it.text })
        assertEquals(2, cardRows(m).size)

        m.render(listOf(group()), active = null)
        assertEquals(0, m.children.toList().filterIsInstance<Span>().size)
        assertEquals(1, cardRows(m).size)
    }
}
