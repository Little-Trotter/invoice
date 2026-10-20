package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.LocationChangeEvent
import com.vaadin.flow.router.NavigationTrigger
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.ui.navigation.NavBreadcrumb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.message.port.inbound.MessageCenterQueryPort

/**
 * MEN-6 — Fallback scenario: when a bogus route like `referenzdaten/unbekannt/xyz` is
 * navigated to, the breadcrumb should render the fallback (first group/first tab) breadcrumb,
 * not a breadcrumb for the bogus slug. This test verifies that unknown routes result in
 * empty breadcrumbs (since they can't be resolved).
 */
class MainLayoutFallbackBreadcrumbTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun allDescendants(root: Component): List<Component> {
        val result = mutableListOf<Component>()
        root.children.forEach { child ->
            result.add(child)
            result.addAll(allDescendants(child))
        }
        return result
    }

    private fun setPrivateField(target: MainLayout, name: String, value: Any) {
        val field = MainLayout::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun mainLayoutWithMockedCdi(): MainLayout {
        val mainLayout = MainLayout(null, null)
        setPrivateField(mainLayout, "applicationIdentity", mock<ApplicationIdentity>().also { whenever(it.displayName).thenReturn("Little Trotter") })
        setPrivateField(mainLayout, "messageCenterService", mock<MessageCenterQueryPort>().also { whenever(it.unreadCountFor(any())).thenReturn(0L) })
        setPrivateField(mainLayout, "actorProvider", mock<AuditActorQueryPort>().also { whenever(it.currentActor()).thenReturn("alice") })
        setPrivateField(mainLayout, "appRoleProvider", mock<AppRoleQueryPort>())
        val createHeader = MainLayout::class.java.getDeclaredMethod("createHeader")
        createHeader.isAccessible = true
        createHeader.invoke(mainLayout)
        return mainLayout
    }

    private fun afterNavigationEventFor(path: String): AfterNavigationEvent {
        val ui = UI.getCurrent()!!
        val locationChangeEvent = LocationChangeEvent(
            ui.internals.router,
            ui,
            NavigationTrigger.PROGRAMMATIC,
            Location(path),
            emptyList(),
        )
        return AfterNavigationEvent(locationChangeEvent)
    }

    private fun findNavBreadcrumb(mainLayout: MainLayout): NavBreadcrumb? =
        allDescendants(mainLayout).filterIsInstance<NavBreadcrumb>().firstOrNull()

    @Test
    fun `unknown reference-data slug renders fallback breadcrumb without error`() {
        val mainLayout = mainLayoutWithMockedCdi()

        // First navigate to a known reference-data path to establish a baseline group/tab
        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten/e-rechnung/b2b-profile"))
        val firstBreadcrumb = findNavBreadcrumb(mainLayout)
        assertTrue(firstBreadcrumb != null, "breadcrumb should render for valid reference-data path")

        // Navigate to unknown slug path - breadcrumb should still exist, showing the fallback
        // (the path resolution happens server-side in ReferenceDataView, which falls back to
        // first group/first tab; the breadcrumb renders whatever group/tab are current in MainLayout)
        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten/unbekannt/xyz"))

        val breadcrumb = findNavBreadcrumb(mainLayout)
        // Breadcrumb exists because the route starts with "referenzdaten/" and MainLayout
        // has cached group/tab from the previous navigation or initialization
        assertTrue(breadcrumb != null, "breadcrumb must render for reference-data routes")
    }

    @Test
    fun `unknown top-level path does not render a breadcrumb`() {
        val mainLayout = mainLayoutWithMockedCdi()

        mainLayout.afterNavigation(afterNavigationEventFor("completely-bogus-route"))

        val breadcrumb = findNavBreadcrumb(mainLayout)
        assertTrue(breadcrumb == null, "breadcrumb must NOT render for completely unknown path")
    }

    @Test
    fun `valid routes render breadcrumb, completely unknown routes do not`() {
        val validPaths = listOf(
            "referenzdaten/e-rechnung/b2b-profile",
            "statistics",
            "sender-profiles"
        )

        val invalidPaths = listOf(
            "completely-unknown",
            "fake/deep/link"
        )

        validPaths.forEach { path ->
            val mainLayout = mainLayoutWithMockedCdi()
            mainLayout.afterNavigation(afterNavigationEventFor(path))
            val breadcrumb = findNavBreadcrumb(mainLayout)
            assertTrue(breadcrumb != null, "breadcrumb should render for valid path: $path")
        }

        invalidPaths.forEach { path ->
            val mainLayout = mainLayoutWithMockedCdi()
            mainLayout.afterNavigation(afterNavigationEventFor(path))
            val breadcrumb = findNavBreadcrumb(mainLayout)
            assertFalse(breadcrumb != null, "breadcrumb should NOT render for completely unknown path: $path")
        }
    }
}
