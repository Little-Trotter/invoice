package de.littletrotter.ui.component

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.dom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageShellTest {
    private fun heading(shell: PageShell): Element = shell.element.getChild(0).getChild(0)

    @Test
    fun `without subtitle only the title is rendered and filters are hidden`() {
        val shell = PageShell("Invoices")
        assertTrue(shell.classNames.contains("lt-page-shell"))
        assertEquals(1, heading(shell).childCount)
        assertEquals("Invoices", heading(shell).getChild(0).text)
        assertFalse(shell.filters.isVisible)
        assertEquals(3, shell.element.childCount)
    }

    @Test
    fun `shell is full width by default and narrow only on request`() {
        val shell = PageShell("Invoices")
        assertFalse(shell.classNames.contains("lt-page-shell--narrow"))
        assertSame(shell, shell.narrow())
        assertTrue(shell.classNames.contains("lt-page-shell--narrow"))
    }

    @Test
    fun `header actions size to their buttons instead of taking the full width`() {
        val shell = PageShell("Invoices")
        assertEquals(null, shell.actions.width)
    }

    @Test
    fun `subtitle is rendered under the title`() {
        val shell = PageShell("Invoices", "All open")
        assertEquals(2, heading(shell).childCount)
        assertEquals("All open", heading(shell).getChild(1).text)
    }

    @Test
    fun `slots take components and filters become visible`() {
        val shell = PageShell("T")
        val action = Button("a")
        val filter = Span("f")
        val body = Div()
        assertSame(shell, shell.actions(action).filters(filter).content(body))
        assertTrue(shell.actions.children.anyMatch { it === action })
        assertTrue(shell.filters.isVisible)
        assertTrue(shell.filters.children.anyMatch { it === filter })
        assertTrue(shell.content.children.anyMatch { it === body })
    }
}
