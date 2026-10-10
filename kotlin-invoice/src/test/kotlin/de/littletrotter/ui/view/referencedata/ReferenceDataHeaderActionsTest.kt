package de.littletrotter.ui.view.referencedata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._value
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [installSearchAndCounter] and [entryCounterText] are the shared building blocks
 * every reference-data tab's [ReferenceDataHeaderActions.installHeaderActions] uses instead of a
 * hand-rolled toolbar; exercised standalone here (mirrors [de.littletrotter.ui.view
 * .AbstractCrudViewRowInteractionsTest]'s approach for the shared row-interaction wiring)
 * rather than through any one concrete tab.
 */
class ReferenceDataHeaderActionsTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `installSearchAndCounter adds exactly a search field and a counter span to the slot`() {
        val slot = Div()

        installSearchAndCounter(slot, "Suchen…") {}

        assertEquals(2, slot.componentCount)
        assertTrue(slot.getComponentAt(0) is TextField)
        assertTrue(slot.getComponentAt(1) is Span)
    }

    @Test
    fun `the search field carries the given placeholder`() {
        val slot = Div()

        installSearchAndCounter(slot, "Name oder Land …") {}

        val field = slot.getComponentAt(0) as TextField
        assertEquals("Name oder Land …", field.placeholder)
    }

    @Test
    fun `changing the search field value invokes onSearch with the trimmed query`() {
        val slot = Div()
        val received = mutableListOf<String>()

        installSearchAndCounter(slot, "Suchen…") { query -> received.add(query) }
        val field = slot.getComponentAt(0) as TextField
        // Vaadin's TextField does not trim itself — installSearchAndCounter's listener does.
        field._value = "  Alpha  "

        assertEquals(listOf("Alpha"), received)
    }

    @Test
    fun `clearing the search field invokes onSearch with an empty string`() {
        val slot = Div()
        val received = mutableListOf<String>()

        installSearchAndCounter(slot, "Suchen…") { query -> received.add(query) }
        val field = slot.getComponentAt(0) as TextField
        field._value = "Alpha"
        field._value = ""

        assertEquals(listOf("Alpha", ""), received)
    }

    @Test
    fun `calling installSearchAndCounter again on the same slot replaces the previous widgets`() {
        val slot = Div()

        installSearchAndCounter(slot, "Erste…") {}
        installSearchAndCounter(slot, "Zweite…") {}

        assertEquals(2, slot.componentCount, "a second call must not leave the first search field/counter behind")
        assertEquals("Zweite…", (slot.getComponentAt(0) as TextField).placeholder)
    }

    @Test
    fun `entryCounterText renders a plain count when the entity has no active flag`() {
        assertEquals("12 Einträge", entryCounterText(12L, null))
    }

    @Test
    fun `entryCounterText names both the total and the inactive count when given`() {
        assertEquals("12 Einträge, 2 inaktiv", entryCounterText(12L, 2L))
    }

    @Test
    fun `entryCounterText still names the inactive count when it is zero`() {
        // Always trustworthy, not merely present when non-zero — see the function's KDoc.
        assertEquals("12 Einträge, 0 inaktiv", entryCounterText(12L, 0L))
    }
}
