package de.littletrotter.ui.navigation

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Nav
import com.vaadin.flow.component.html.OrderedList
import com.vaadin.flow.component.html.Span
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [NavBreadcrumb] itself (the [Div] rendering, `init` block and default constructor
 * parameters) had only ever been exercised through [NavBreadcrumb.pathToSegments] directly
 * ([NavBreadcrumbTest]) or via [de.littletrotter.ui.MainLayout] passing all three constructor
 * arguments explicitly ([de.littletrotter.ui.MainLayoutBreadcrumbKaribuTest]) — the
 * `currentGroup`/`currentTab` default values were never evaluated.
 */
class NavBreadcrumbKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    @Test
    fun `happy path - constructing with only the path uses the default null group and tab`() {
        val breadcrumb = NavBreadcrumb("statistics")

        val nav = breadcrumb.children.toList().filterIsInstance<Nav>().single()
        assertTrue(nav.element.getAttribute("aria-label").isNotBlank())
        val list = nav.children.toList().filterIsInstance<OrderedList>().single()
        assertEquals(2, list.componentCount)
    }

    @Test
    fun `boundary - unknown route with defaulted group and tab renders no segments`() {
        val breadcrumb = NavBreadcrumb("unknown-route")

        val nav = breadcrumb.children.toList().filterIsInstance<Nav>().single()
        val list = nav.children.toList().filterIsInstance<OrderedList>().single()
        assertEquals(0, list.componentCount)
    }

    @Test
    fun `boundary - a Current segment renders a span marked aria-current page`() {
        val breadcrumb = NavBreadcrumb("statistics")

        val nav = breadcrumb.children.toList().filterIsInstance<Nav>().single()
        val list = nav.children.toList().filterIsInstance<OrderedList>().single()
        val currentSegment = list.children.toList().filterIsInstance<Div>().last()
        assertEquals("page", currentSegment.element.getAttribute("aria-current"))
        assertTrue(currentSegment.children.toList().filterIsInstance<Span>().isNotEmpty())
    }

    @Test
    fun `boundary - a Link segment for a drawer child's parent renders an anchor with the route path`() {
        val breadcrumb = NavBreadcrumb("system/einstellungen")

        val nav = breadcrumb.children.toList().filterIsInstance<Nav>().single()
        val list = nav.children.toList().filterIsInstance<OrderedList>().single()
        assertEquals(3, list.componentCount)
        val linkSegment = list.children.toList().filterIsInstance<Div>()[1]
        val anchor = linkSegment.children.toList().filterIsInstance<Anchor>().single()
        assertEquals("system", anchor.href)
        assertTrue(anchor.element.getAttribute("title").isNotBlank())
    }
}
