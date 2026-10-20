package de.littletrotter.ui.components.freetext

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [FreeTextChips] itself: rendering, visibility and click wiring against a [ChipSource] test
 * double. Deriving actual candidates from a live form (service period, project number, B2B terms)
 * is a host concern — kotlin-invoice's own InvoiceFreeTextChipSource and FreeTextChipsCandidatesTest
 * — never this component's.
 */
class FreeTextChipsKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private class StubSource(var candidates: List<ChipCandidate> = emptyList()) : ChipSource {
        override fun candidates(): List<ChipCandidate> = candidates
    }

    @Test
    fun `happy path - refresh with an empty source stays hidden with no children`() {
        val chips = FreeTextChips(StubSource()) {}

        chips.refresh()

        assertFalse(chips.isVisible)
        assertTrue(chips.children.toList().isEmpty())
    }

    @Test
    fun `happy path - refresh with candidates becomes visible and renders one button per candidate`() {
        val source = StubSource(
            listOf(
                ChipCandidate("Leistungszeitraum", "Leistungszeitraum", "01.08.2026 – 10.08.2026", group = "period"),
                ChipCandidate("KostSt.", "Kostenstelle", "4711", group = "b2bTerm", key = "BT-19"),
            ),
        )
        val chips = FreeTextChips(source) {}

        chips.refresh()

        assertTrue(chips.isVisible)
        val buttons = chips.children.toList().filterIsInstance<Button>()
        assertEquals(2, buttons.size)
        val second = buttons[1]
        assertTrue(second.children.toList().filterIsInstance<Span>().any { it.text == "KostSt." })
        assertTrue(second.children.toList().filterIsInstance<Span>().any { it.text == "4711" })
        assertEquals("Kostenstelle: 4711", second.element.getAttribute("aria-label"))
    }

    @Test
    fun `boundary - clicking a chip invokes onInsert with that candidate, and no other`() {
        val clicked = mutableListOf<ChipCandidate>()
        val a = ChipCandidate("A", "Alpha", "a-value", group = "g")
        val b = ChipCandidate("B", "Beta", "b-value", group = "g")
        val chips = FreeTextChips(StubSource(listOf(a, b))) { clicked += it }
        chips.refresh()

        chips.children.toList().filterIsInstance<Button>()[1].click()

        assertEquals(listOf(b), clicked)
    }

    @Test
    fun `boundary - a second refresh rebuilds from scratch, dropping stale chips`() {
        val source = StubSource(listOf(ChipCandidate("A", "Alpha", "a", group = "g")))
        val chips = FreeTextChips(source) {}
        chips.refresh()

        source.candidates = emptyList()
        chips.refresh()

        assertFalse(chips.isVisible)
        assertTrue(chips.children.toList().isEmpty())
    }

    @Test
    fun `boundary - a chip never built (refresh not yet called) stays hidden with no children`() {
        val chips = FreeTextChips(StubSource(listOf(ChipCandidate("A", "Alpha", "a", group = "g")))) {}

        assertFalse(chips.isVisible)
        assertTrue(chips.children.toList().isEmpty())
    }
}
