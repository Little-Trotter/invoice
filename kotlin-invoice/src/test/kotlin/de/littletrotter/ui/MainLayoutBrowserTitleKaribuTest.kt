package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.LocationChangeEvent
import com.vaadin.flow.router.NavigationTrigger
import com.vaadin.flow.router.PageTitle
import de.littletrotter.application.ApplicationIdentity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@PageTitle("Widget Title")
private class DummyPageTitleRouteTarget : Div()

private class DummyNoPageTitleRouteTarget : Div()

/**
 * UI-5 slice 3c — [MainLayout.applyBrowserTitle]'s `event.locationChangeEvent.routeTargetChain
 * .lastOrNull()?.javaClass?.getAnnotation(PageTitle::class.java)?.value` chain: every existing
 * `afterNavigation` test in this module builds its [LocationChangeEvent] with an empty
 * `routeTargetChain`, so only the "no view title" path (an empty chain) had ever been exercised —
 * never a chain whose last element does or does not carry a [PageTitle] annotation.
 */
class MainLayoutBrowserTitleKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun setPrivateField(target: MainLayout, name: String, value: Any) {
        val field = MainLayout::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun mainLayoutWithIdentity(): MainLayout {
        val mainLayout = MainLayout(null, null)
        setPrivateField(mainLayout, "applicationIdentity", mock<ApplicationIdentity>().also { whenever(it.displayName).thenReturn("Little Trotter") })
        return mainLayout
    }

    private fun afterNavigationEventWithChain(routeTargetChain: List<com.vaadin.flow.component.HasElement>): AfterNavigationEvent {
        val ui = UI.getCurrent()!!
        val locationChangeEvent = LocationChangeEvent(
            ui.internals.router,
            ui,
            NavigationTrigger.PROGRAMMATIC,
            Location("dummy"),
            routeTargetChain,
        )
        return AfterNavigationEvent(locationChangeEvent)
    }

    @Test
    fun `a route target chain ending in a PageTitle-annotated view sets that title in the browser tab`() {
        val mainLayout = mainLayoutWithIdentity()

        val applyBrowserTitle = MainLayout::class.java.getDeclaredMethod("applyBrowserTitle", AfterNavigationEvent::class.java)
        applyBrowserTitle.isAccessible = true
        applyBrowserTitle.invoke(mainLayout, afterNavigationEventWithChain(listOf(DummyPageTitleRouteTarget())))

        assertEquals("Little Trotter – Widget Title", UI.getCurrent().internals.title)
    }

    @Test
    fun `a route target chain ending in a view with no PageTitle annotation falls back to the edition name alone`() {
        val mainLayout = mainLayoutWithIdentity()

        val applyBrowserTitle = MainLayout::class.java.getDeclaredMethod("applyBrowserTitle", AfterNavigationEvent::class.java)
        applyBrowserTitle.isAccessible = true
        applyBrowserTitle.invoke(mainLayout, afterNavigationEventWithChain(listOf(DummyNoPageTitleRouteTarget())))

        assertEquals("Little Trotter", UI.getCurrent().internals.title)
    }
}
