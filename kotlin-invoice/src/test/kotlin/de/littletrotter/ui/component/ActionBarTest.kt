package de.littletrotter.ui.component

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionBarTest {
    @Test
    fun `vararg constructor right-aligns by default and keeps order`() {
        val a = Button("a")
        val b = Button("b")
        val bar = ActionBar(a, b)
        assertEquals(FlexComponent.JustifyContentMode.END, bar.justifyContentMode)
        assertTrue(bar.classNames.contains("sp-action-bar"))
        assertTrue(bar.children.allMatch { (it as Button).themeNames.none { n -> n == "small" } })
        assertEquals(listOf(a.element, b.element), bar.element.children.toList())
    }

    @Test
    fun `leading components are grouped on the left before the actions`() {
        val hint = Span("3 items")
        val action = Button("x")
        val bar = ActionBar(listOf(hint), action)
        val children = bar.element.children.toList()
        assertEquals(2, children.size)
        assertTrue(children[0].classList.contains("sp-action-bar-leading"))
        assertEquals(hint.element, children[0].getChild(0))
        assertEquals(action.element, children[1])
    }
}
