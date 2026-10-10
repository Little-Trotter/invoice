package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.icon.VaadinIcon
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [StatusQuickFilterRow] with a neutral enum: chips per non-empty status, marker, toggle, re-render. */
class StatusQuickFilterRowKaribuTest {

    private enum class Phase { ONE, TWO, THREE }

    private val toggled = mutableListOf<Phase>()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newRow() = StatusQuickFilterRow(
        Phase.entries,
        "Filter",
        { ChipSpec(it.name.lowercase(), ChipTone.entries[it.ordinal], VaadinIcon.BAN) { count -> "${it.name}: $count" } },
    ) { toggled += it }

    private fun chips(row: StatusQuickFilterRow<Phase>) = row.children.toList().filterIsInstance<NativeButton>()

    @Test
    fun `chips carry label count family spoken count and a hidden glyph`() {
        val row = newRow()

        row.render(mapOf(Phase.ONE to 3L, Phase.THREE to 12L), Phase.THREE)

        assertEquals("Filter", row.element.getAttribute("aria-label"))
        val chips = chips(row)
        assertEquals(listOf("one 3", "three 12"), chips.map { it.text })
        val three = chips[1]
        assertTrue(three.hasClassName("status-chip--${ChipTone.entries[2].css}"))
        assertTrue(three.hasClassName("quick-filter-chip--active"))
        assertEquals("true", three.element.getAttribute("aria-pressed"))
        assertEquals("THREE: 12", three.element.getAttribute("aria-label"))
        assertEquals("true", three.element.getChild(0).getAttribute("aria-hidden"))
        assertFalse(chips[0].hasClassName("quick-filter-chip--active"))
        assertEquals("false", chips[0].element.getAttribute("aria-pressed"))
    }

    @Test
    fun `a click reports the chip status`() {
        val row = newRow()
        row.render(mapOf(Phase.TWO to 1L), null)

        chips(row).single()._click()

        assertEquals(listOf(Phase.TWO), toggled)
    }

    @Test
    fun `zero and negative counts get no chip`() {
        val row = newRow()

        row.render(mapOf(Phase.ONE to 0L, Phase.TWO to -1L), Phase.ONE)

        assertTrue(chips(row).isEmpty())
    }

    @Test
    fun `a re-render replaces the previous chips completely`() {
        val row = newRow()
        row.render(mapOf(Phase.ONE to 3L, Phase.TWO to 12L), null)

        row.render(mapOf(Phase.ONE to 4L), null)

        assertEquals(listOf("one 4"), chips(row).map { it.text })
    }
}
