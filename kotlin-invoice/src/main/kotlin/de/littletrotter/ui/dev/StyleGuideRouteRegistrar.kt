package de.littletrotter.ui.dev

import com.vaadin.flow.router.RouteConfiguration
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.view.system.StyleGuideView
import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

/**
 * Makes the component style guide reachable at `dev/styleguide` while developing. The page has no
 * route annotation on purpose, so production and test deployments never expose it; the route only
 * exists when the application runs in development mode.
 */
@ApplicationScoped
class StyleGuideRouteRegistrar {

    // The StartupEvent parameter is what makes the container call this observer at startup.
    fun onStart(@Observes ignored: StartupEvent, launchMode: LaunchMode) {
        register(launchMode) { RouteConfiguration.forApplicationScope() }
    }

    /** [routes] is resolved lazily: outside development there may be no Vaadin service to ask. */
    fun register(launchMode: LaunchMode, routes: () -> RouteConfiguration) {
        if (launchMode != LaunchMode.DEVELOPMENT) return
        routes().setRoute(ROUTE, StyleGuideView::class.java, MainLayout::class.java)
    }

    companion object {
        const val ROUTE = "dev/styleguide"
    }
}
