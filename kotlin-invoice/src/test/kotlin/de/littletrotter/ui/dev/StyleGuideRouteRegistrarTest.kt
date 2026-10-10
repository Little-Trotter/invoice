package de.littletrotter.ui.dev

import com.vaadin.flow.router.RouteConfiguration
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.view.system.StyleGuideView
import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

class StyleGuideRouteRegistrarTest {

    private val registrar = StyleGuideRouteRegistrar()

    @Test
    fun `registers the route in development mode`() {
        val routes = mock<RouteConfiguration>()

        registrar.register(LaunchMode.DEVELOPMENT) { routes }

        verify(routes).setRoute("dev/styleguide", StyleGuideView::class.java, MainLayout::class.java)
    }

    @Test
    fun `registers nothing in normal mode`() {
        val routes = mock<RouteConfiguration>()

        registrar.register(LaunchMode.NORMAL) { routes }

        verifyNoInteractions(routes)
    }

    @Test
    fun `registers nothing in test mode`() {
        val routes = mock<RouteConfiguration>()

        registrar.register(LaunchMode.TEST) { routes }

        verifyNoInteractions(routes)
    }

    @Test
    fun `startup observer outside development leaves the application routes untouched`() {
        registrar.onStart(mock<StartupEvent>(), LaunchMode.TEST)
    }
}
