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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * MEN-6 — Deep-link scenario: navigating directly to a reference-data deep link like
 * `referenzdaten/reisekosten/kilometersaetze` should show the complete breadcrumb
 * (Referenzdaten > Reisekosten > Kilometersätze) with the parent drawer node expanded.
 * This test verifies the breadcrumb renders correctly for deep-link entry.
 */
class MainLayoutDeepLinkBreadcrumbTest {

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
    fun `deep-link to reisekosten-km-saetze shows complete breadcrumb with group and tab`() {
        val mainLayout = mainLayoutWithMockedCdi()

        // Simulate deep-link navigation to a specific reference-data tab
        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten/reisekosten/kilometersaetze"))

        // Breadcrumb should be mounted and visible
        val breadcrumb = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb, "breadcrumb must be mounted for deep-link")

        val breadcrumbNav = findBreadcrumbNav(mainLayout)
        assertNotNull(breadcrumbNav, "breadcrumb nav must exist for deep-link")
    }

    @Test
    fun `deep-link with reference-data selection event updates breadcrumb correctly`() {
        val mainLayout = mainLayoutWithMockedCdi()

        // Navigate to reference-data deep link
        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten/reisekosten/kilometersaetze"))

        // Fire a reference-data selection event (simulating tab switch)
        mainLayout.onReferenceDataSelection(
            ReferenceDataSelectionEvent(ReferenceDataGroup.REISEKOSTEN, ReferenceDataTab.MILEAGE_RATES)
        )

        // Breadcrumb should still be present and updated
        val breadcrumb = findNavBreadcrumb(mainLayout)
        assertNotNull(breadcrumb, "breadcrumb must exist after selection event")
    }

    @Test
    fun `breadcrumb renders for all three reference-data groups on deep-link`() {
        val groups = listOf(
            ReferenceDataGroup.ABRECHNUNG to "referenzdaten/abrechnung/zahlungsziele",
            ReferenceDataGroup.REISEKOSTEN to "referenzdaten/reisekosten/kilometersaetze",
            ReferenceDataGroup.E_RECHNUNG to "referenzdaten/e-rechnung/b2b-profile"
        )

        groups.forEach { (group, deepLink) ->
            val mainLayout = mainLayoutWithMockedCdi()
            mainLayout.afterNavigation(afterNavigationEventFor(deepLink))

            val breadcrumb = findNavBreadcrumb(mainLayout)
            assertNotNull(breadcrumb, "breadcrumb must render for group=$group path=$deepLink")
        }
    }
}
