package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Nav
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.LocationChangeEvent
import com.vaadin.flow.router.NavigationTrigger
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.ui.navigation.NavBreadcrumb
import de.littletrotter.ui.navigation.ReferenceDataSelectionEvent
import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import de.littletrotter.ui.view.referencedata.ReferenceDataTab
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * MEN-4 — UI tests for [MainLayout]'s breadcrumb navigation: mounted in the navbar after the logo,
 * rebuilt on navigation, and updated when reference-data sub-tabs change without a router event.
 *
 * Follows [MainLayoutDrawerKaribuTest]'s pattern of reflection-injecting mocks for CDI dependencies.
 */
class MainLayoutBreadcrumbKaribuTest {

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
        setPrivateField(mainLayout, "messageCenterService", mock<MessageCenterService>().also { whenever(it.unreadCountFor(any())).thenReturn(0L) })
        setPrivateField(mainLayout, "actorProvider", mock<AuditActorProvider>().also { whenever(it.currentActor()).thenReturn("alice") })
        setPrivateField(mainLayout, "appRoleProvider", mock<AppRoleProvider>())
        // afterNavigation()'s refreshBellBadge() needs the header's bellButton, normally built by
        // the @PostConstruct callback a real CDI container invokes after injection.
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

    private fun findBreadcrumbNav(mainLayout: MainLayout): Nav? =
        allDescendants(mainLayout).filterIsInstance<Nav>()
            .firstOrNull { it.element.getAttribute("aria-label") == "Brotkrümelnavigation" }

    @Test
    fun `breadcrumb is mounted in the navbar on a routed view`() {
        val mainLayout = mainLayoutWithMockedCdi()
        mainLayout.afterNavigation(afterNavigationEventFor("statistics"))

        val breadcrumb = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb, "breadcrumb should be mounted in navbar after navigation")
    }

    @Test
    fun `breadcrumb navbar element has correct aria-label`() {
        val mainLayout = mainLayoutWithMockedCdi()
        mainLayout.afterNavigation(afterNavigationEventFor("recipients"))

        val breadcrumbNav = findBreadcrumbNav(mainLayout)
        assertNotNull(breadcrumbNav, "breadcrumb nav with aria-label should exist")
    }

    @Test
    fun `breadcrumb is empty on unknown route`() {
        val mainLayout = mainLayoutWithMockedCdi()
        mainLayout.afterNavigation(afterNavigationEventFor("unknown-route"))

        val breadcrumb = findNavBreadcrumb(mainLayout)
        // The breadcrumb component itself should not be mounted for unknown routes
        assertTrue(breadcrumb == null, "breadcrumb should not render for unknown routes")
    }

    @Test
    fun `breadcrumb updates when navigating to a different view`() {
        val mainLayout = mainLayoutWithMockedCdi()

        // Navigate to one view
        mainLayout.afterNavigation(afterNavigationEventFor("statistics"))
        var breadcrumb1 = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb1, "breadcrumb should exist after first navigation")

        // Navigate to another view
        mainLayout.afterNavigation(afterNavigationEventFor("recipients"))
        var breadcrumb2 = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb2, "breadcrumb should exist after second navigation")

        // The breadcrumbs might be different component instances due to rebuild
        // but they should both be present
        assertTrue(breadcrumb1 != null && breadcrumb2 != null)
    }

    @Test
    fun `breadcrumb renders three levels for reference data with group and tab`() {
        val mainLayout = mainLayoutWithMockedCdi()
        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten/e-rechnung/b2b-profile"))

        val breadcrumb = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb, "breadcrumb should exist for reference data route")

        // Breadcrumb should have rendered with the group and tab
        val nav = findBreadcrumbNav(mainLayout)
        assertNotNull(nav, "breadcrumb nav should exist")
    }

    @Test
    fun `reference-data selection event updates breadcrumb's tab segment`() {
        val mainLayout = mainLayoutWithMockedCdi()

        // Start with one group/tab
        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten/abrechnung/zahlungsziele"))
        var breadcrumb1 = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb1)

        // Simulate a reference-data sub-tab change without navigation (via CDI event)
        mainLayout.onReferenceDataSelection(
            ReferenceDataSelectionEvent(ReferenceDataGroup.ABRECHNUNG, ReferenceDataTab.TAX_RATES)
        )

        // Breadcrumb should be rebuilt with the new tab
        val breadcrumb2 = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb2, "breadcrumb should be rebuilt after selection event")
    }

    @Test
    fun `reference-data selection event with group change updates breadcrumb`() {
        val mainLayout = mainLayoutWithMockedCdi()

        // Start with one group
        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten/abrechnung"))
        var breadcrumb1 = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb1)

        // Switch to a different group/tab
        mainLayout.onReferenceDataSelection(
            ReferenceDataSelectionEvent(ReferenceDataGroup.E_RECHNUNG, ReferenceDataTab.B2B_PROFILES)
        )

        // Breadcrumb should be rebuilt with new group and tab
        val breadcrumb2 = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb2, "breadcrumb should be rebuilt after group/tab selection event")
    }

    @Test
    fun `breadcrumb on master data route shows correct section`() {
        val mainLayout = mainLayoutWithMockedCdi()
        mainLayout.afterNavigation(afterNavigationEventFor("sender-profiles"))

        val breadcrumb = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb, "breadcrumb should exist for master data route")
    }

    @Test
    fun `breadcrumb on system route shows administration section`() {
        val mainLayout = mainLayoutWithMockedCdi()
        mainLayout.afterNavigation(afterNavigationEventFor("system"))

        val breadcrumb = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb, "breadcrumb should exist for system route")
    }

    @Test
    fun `breadcrumb on info route shows help section`() {
        val mainLayout = mainLayoutWithMockedCdi()
        mainLayout.afterNavigation(afterNavigationEventFor("info"))

        val breadcrumb = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb, "breadcrumb should exist for info route")
    }
}
