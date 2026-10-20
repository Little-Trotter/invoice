package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.StatisticsFilter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * SC2 — [RankingTable] now renders a caption row + a vertical stack of ranking cards instead of a
 * flat row list. These tests assert the same behavioral guarantees as before the redesign (the
 * empty-state early return, the 3-vs-4-column fork, refresh-replaces semantics), plus the new
 * card-specific guarantees (top-rank distinction, proportional bar, no divide-by-zero) — by
 * rendered text/CSS class lookup instead of positional cell counts, since the redesign changes
 * the DOM shape on purpose.
 */
class RankingTableTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun filter() = StatisticsFilter()

    private fun hasClass(component: Component, name: String): Boolean = component.element.classList.contains(name)

    private fun captionRow(table: RankingTable<*>): Div? =
        table.children.toList().filterIsInstance<Div>().firstOrNull { hasClass(it, "ranking-caption-row") }

    private fun cardStack(table: RankingTable<*>): Div? =
        table.children.toList().filterIsInstance<Div>().firstOrNull { hasClass(it, "ranking-card-stack") }

    private fun cards(table: RankingTable<*>): List<Div> =
        cardStack(table)?.children?.toList()?.filterIsInstance<Div>()?.filter { hasClass(it, "ranking-card") } ?: emptyList()

    /** Number of rendered "slots" (name + value columns) in a card — everything except the rank
     *  badge and the bar track, found two levels down (card -> content row -> cells). */
    private fun slotCount(card: Div): Int {
        val row = card.children.toList().filterIsInstance<Div>().first { !hasClass(it, "ranking-card-bar-track") }
        return row.children.toList().count { hasClass(it, "ranking-card-name") || hasClass(it, "ranking-card-value") }
    }

    private fun barFill(card: Div): Div {
        val track = card.children.toList().filterIsInstance<Div>().first { hasClass(it, "ranking-card-bar-track") }
        return track.children.toList().filterIsInstance<Div>().first { hasClass(it, "ranking-card-bar-fill") }
    }

    @Test
    fun `an empty result shows the no-data message and nothing else`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe"),
            rowMapper = { item, _ -> listOf(item, "0", "0") },
            fetch = { _, _ -> emptyList() },
        )
        table.refresh(filter())

        assertEquals(1, table.children.toList().size)
        assertTrue((table.children.toList()[0] as Span).text.isNotBlank())
        assertEquals(null, captionRow(table), "no caption row for an empty result")
        assertEquals(null, cardStack(table), "no card stack for an empty result")
    }

    @Test
    fun `a 3-header table renders exactly 3 slots per card, no 4th`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe"),
            rowMapper = { item, _ -> listOf(item, "5", "100,00 EUR") },
            fetch = { _, _ -> listOf("Kunde A") },
        )
        table.refresh(filter())

        val caption = captionRow(table)!!
        assertEquals(3, caption.children.toList().size - 1, "rank spacer + 3 header slots")
        val rankedCards = cards(table)
        assertEquals(1, rankedCards.size)
        assertEquals(3, slotCount(rankedCards[0]), "name + 2 value columns for a 3-header table, no 4th")
    }

    @Test
    fun `a 4-header table renders the fourth slot when the row provides it`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe", "Anteil"),
            rowMapper = { item, _ -> listOf(item, "5", "100,00 EUR", "12 %") },
            fetch = { _, _ -> listOf("Kunde A") },
        )
        table.refresh(filter())

        assertEquals(4, slotCount(cards(table)[0]), "has4Cols must add the 4th slot when the row provides a value")
    }

    @Test
    fun `a 4-header table whose rowMapper returns only 3 values skips the fourth slot`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe", "Anteil"),
            rowMapper = { item, _ -> listOf(item, "5", "100,00 EUR") }, // no 4th value
            fetch = { _, _ -> listOf("Kunde A") },
        )
        table.refresh(filter())

        assertEquals(3, slotCount(cards(table)[0]), "values.size >= 4 is false — the 4th slot must be skipped, no crash")
    }

    @Test
    fun `the top-ranked item is visually distinguished from the rest`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe"),
            rowMapper = { item, _ -> listOf(item, "1", "100,00 EUR") },
            fetch = { _, _ -> listOf("A", "B", "C") },
        )
        table.refresh(filter())

        val rankedCards = cards(table)
        assertEquals(3, rankedCards.size)
        assertTrue(hasClass(rankedCards[0], "ranking-card--top"), "index 0 must carry the top-rank class")
        assertFalse(hasClass(rankedCards[1], "ranking-card--top"), "index 1 must not carry the top-rank class")
        assertFalse(hasClass(rankedCards[2], "ranking-card--top"), "index 2 must not carry the top-rank class")
    }

    @Test
    fun `a second refresh clears the previous cards instead of appending`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe"),
            rowMapper = { item, _ -> listOf(item, "1", "1") },
            fetch = { _, _ -> listOf("A", "B") },
        )
        table.refresh(filter())
        assertEquals(2, cards(table).size)
        assertEquals(2, table.children.toList().size, "caption row + card stack")

        table.refresh(filter())
        assertEquals(2, cards(table).size, "removeAll() at the top of refresh must prevent duplication")
        assertEquals(2, table.children.toList().size)
    }

    @Test
    fun `a ranking whose top value is zero renders proportional bars without dividing by zero`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe"),
            rowMapper = { item, _ -> listOf(item, "0", "0,00 EUR") },
            fetch = { _, _ -> listOf("A", "B") },
        )
        table.refresh(filter())

        val rankedCards = cards(table)
        assertEquals(2, rankedCards.size)
        rankedCards.forEach { card ->
            assertEquals("0%", barFill(card).style.get("--lt-bar-w"), "zero top value must render a zero-width bar, not crash")
        }
    }

    @Test
    fun `a non-zero ranking sizes bars proportionally to the top item`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe"),
            rowMapper = { item, _ ->
                val amount = when (item) { "A" -> "200,00 EUR"; "B" -> "100,00 EUR"; else -> "0,00 EUR" }
                listOf(item, amount, amount)
            },
            fetch = { _, _ -> listOf("A", "B") },
        )
        table.refresh(filter())

        val rankedCards = cards(table)
        assertEquals("100%", barFill(rankedCards[0]).style.get("--lt-bar-w"), "the top item's bar is always full width")
        assertEquals("50%", barFill(rankedCards[1]).style.get("--lt-bar-w"), "half the top item's value must be a half-width bar")
    }

    // ── magnitude() — thousands separator, negative amounts, and an unparseable value ──────────

    @Test
    fun `a thousands-separated amount with a period is parsed correctly for the bar width`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe"),
            rowMapper = { item, _ ->
                val amount = when (item) { "A" -> "1.200,00 EUR"; else -> "600,00 EUR" }
                listOf(item, amount, amount)
            },
            fetch = { _, _ -> listOf("A", "B") },
        )
        table.refresh(filter())

        val rankedCards = cards(table)
        assertEquals("100%", barFill(rankedCards[0]).style.get("--lt-bar-w"), "a period-thousands amount must still parse as the top value")
        assertEquals("50%", barFill(rankedCards[1]).style.get("--lt-bar-w"), "half of a period-thousands top value must be a half-width bar")
    }

    @Test
    fun `a negative amount is treated as zero magnitude, never a crash`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe"),
            rowMapper = { item, _ ->
                val amount = when (item) { "A" -> "100,00 EUR"; else -> "-50,00 EUR" }
                listOf(item, amount, amount)
            },
            fetch = { _, _ -> listOf("A", "B") },
        )
        table.refresh(filter())

        val rankedCards = cards(table)
        assertEquals("100%", barFill(rankedCards[0]).style.get("--lt-bar-w"))
        assertEquals("0%", barFill(rankedCards[1]).style.get("--lt-bar-w"), "a negative value must be coerced to zero, not a negative bar")
    }

    @Test
    fun `an unparseable value contributes zero magnitude instead of crashing`() {
        val table = RankingTable<String>(
            headers = listOf("Name", "Anzahl", "Summe"),
            rowMapper = { item, _ -> listOf(item, "n/a", "n/a") },
            fetch = { _, _ -> listOf("A") },
        )

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { table.refresh(filter()) }

        assertEquals("0%", barFill(cards(table)[0]).style.get("--lt-bar-w"), "a value that does not parse must render a zero-width bar, not crash")
    }
}
