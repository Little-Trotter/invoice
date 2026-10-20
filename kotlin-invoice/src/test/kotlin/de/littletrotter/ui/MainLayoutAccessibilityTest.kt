package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.sidenav.SideNavItem
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.LocationChangeEvent
import com.vaadin.flow.router.NavigationTrigger
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.navigation.NavItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.message.port.inbound.MessageCenterQueryPort

/**
 * MEN-6 — Accessibility features: skip link position and drawer item path binding.
 * Tests the server-side verifiable aspects of accessibility:
 * - Skip link is the first navbar element with correct id and href
 * - SideNavItem paths carry aria-current-compatible identifiers
 */
class MainLayoutAccessibilityTest {

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

    /** TS-8 moved [MainLayout.createDrawer] from `init {}` into a `@PostConstruct` callback (its
     *  permission filter needs `appRoleProvider`), so a plain unit test must both reflection-
     *  inject an [AppRoleQueryPort] and invoke `createDrawer()` explicitly. This class asserts the
     *  full, unfiltered drawer, so the mock grants every [AppRole] (permission *filtering* is
     *  [MainLayoutAccessControlTest]'s job). */
    private fun mainLayoutWithMockedCdi(): MainLayout {
        val mainLayout = MainLayout(null, null)
        setPrivateField(mainLayout, "applicationIdentity", mock<ApplicationIdentity>().also { whenever(it.displayName).thenReturn("Little Trotter") })
        setPrivateField(mainLayout, "messageCenterService", mock<MessageCenterQueryPort>().also { whenever(it.unreadCountFor(any())).thenReturn(0L) })
        setPrivateField(mainLayout, "actorProvider", mock<AuditActorQueryPort>().also { whenever(it.currentActor()).thenReturn("alice") })
        setPrivateField(
            mainLayout,
            "appRoleProvider",
            mock<AppRoleQueryPort>().also { whenever(it.currentRoles()).thenReturn(AppRole.entries.toSet())
                whenever(it.isAuthenticated()).thenReturn(true)
            },
        )
        val createHeader = MainLayout::class.java.getDeclaredMethod("createHeader")
        createHeader.isAccessible = true
        createHeader.invoke(mainLayout)
        val createDrawer = MainLayout::class.java.getDeclaredMethod("createDrawer")
        createDrawer.isAccessible = true
        createDrawer.invoke(mainLayout)
        return mainLayout
    }

    private fun afterNavigationEventFor(path: String): AfterNavigationEvent {
        val ui = com.vaadin.flow.component.UI.getCurrent()!!
        val locationChangeEvent = LocationChangeEvent(
            ui.internals.router,
            ui,
            NavigationTrigger.PROGRAMMATIC,
            Location(path),
            emptyList(),
        )
        return AfterNavigationEvent(locationChangeEvent)
    }

    @Test
    fun `skip link is present in the navbar with id skip-to-content`() {
        val mainLayout = mainLayoutWithMockedCdi()

        val skipLinks = allDescendants(mainLayout)
            .filterIsInstance<Anchor>()
            .filter { it.id.orElse(null) == "skip-to-content" }

        assertEquals(1, skipLinks.size, "skip link with id='skip-to-content' must be present")
    }

    @Test
    fun `skip link has href pointing to main-content`() {
        val mainLayout = mainLayoutWithMockedCdi()

        val skipLink = allDescendants(mainLayout)
            .filterIsInstance<Anchor>()
            .firstOrNull { it.id.orElse(null) == "skip-to-content" }

        assertNotNull(skipLink, "skip link must exist")
        assertEquals("#main-content", skipLink!!.href, "skip link must href to #main-content")
    }

    @Test
    fun `all drawer SideNavItems have non-empty path attributes for aria-current binding`() {
        val mainLayout = mainLayoutWithMockedCdi()

        val sideNavItems = allDescendants(mainLayout)
            .filterIsInstance<SideNavItem>()
            // Only check actual nav items (with paths), not section headers (no path)
            .filter { it.path != null && it.path.isNotEmpty() }

        assertTrue(sideNavItems.isNotEmpty(), "drawer must have SideNavItems with paths")
        sideNavItems.forEach { item ->
            assertNotNull(item.path, "SideNavItem path must not be null: ${item.label}")
            assertTrue(item.path.isNotEmpty(), "SideNavItem path must not be empty: ${item.label}")
        }
    }

    @Test
    fun `all top-level NavItems have corresponding SideNavItem paths in drawer`() {
        val mainLayout = mainLayoutWithMockedCdi()

        val sideNavPaths = allDescendants(mainLayout)
            .filterIsInstance<SideNavItem>()
            .map { it.path }
            .toSet()

        // The mail journal and templates are skipped: it renders only when the mail component is installed,
        // which this bare, reflection-wired layout never claims. MainLayoutMailEntryKaribuTest
        // covers both of its states.
        // The offer entries are skipped for the same reason (QuoteAvailability is never wired here);
        // MainLayoutQuoteEntryKaribuTest covers them.
        NavItem.entries.filterNot { it in GATED_ENTRIES }.forEach { navItem ->
            if (navItem != NavItem.REFERENCE_DATA) {
                assertTrue(
                    sideNavPaths.contains(navItem.routePath),
                    "SideNavItem path '${navItem.routePath}' for NavItem.${navItem.name} must exist in drawer"
                )
            }
        }
    }

    @Test
    fun `reference-data group children have correct matchNested path binding`() {
        val mainLayout = mainLayoutWithMockedCdi()
        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten/e-rechnung/b2b-profile"))

        val refDataItems = allDescendants(mainLayout)
            .filterIsInstance<SideNavItem>()
            .filter { it.path.startsWith("referenzdaten/") && it.path != "referenzdaten" }

        assertTrue(refDataItems.isNotEmpty(), "reference-data group children must exist")
        refDataItems.forEach { item ->
            // matchNested items should have their group path, e.g., "referenzdaten/e-rechnung"
            assertTrue(
                item.path.contains("referenzdaten/"),
                "reference-data group child must have referenzdaten/ path: ${item.path}"
            )
        }
    }

    // ── BU1 — logout button with icon and hover effect ──────────────────────

    private fun logoutButton(mainLayout: MainLayout): Button {
        val buttons = allDescendants(mainLayout).filterIsInstance<Button>().filter { it.hasClassName("header-logout") }
        assertEquals(1, buttons.size, "exactly one logout button must be in the header")
        return buttons.single()
    }

    @Test
    fun `logout button carries the sign-out icon and the nav-logout label`() {
        val button = logoutButton(mainLayoutWithMockedCdi())

        assertEquals(UiTranslations.tr("nav.logout"), button.text)
        assertTrue(button.text.isNotBlank() && !button.text.startsWith("!"), "label must be a resolved translation")
        assertEquals("vaadin:sign-out", (button.icon as Icon).element.getAttribute("icon"))
    }

    @Test
    fun `activating the logout button navigates the browser to the OIDC logout path`() {
        val button = logoutButton(mainLayoutWithMockedCdi())
        UI.getCurrent()!!.internals.dumpPendingJavaScriptInvocations()

        button._click()

        val parameters = UI.getCurrent()!!.internals.dumpPendingJavaScriptInvocations()
            .flatMap { it.invocation.parameters }
        assertTrue(parameters.contains("/logout"), "click must issue a real page navigation to /logout")
    }

    @Test
    fun `logout button is a focusable tertiary button`() {
        val button = logoutButton(mainLayoutWithMockedCdi())

        assertTrue(button.isEnabled)
        assertTrue(button.element.getAttribute("tabindex").let { it == null || it.toInt() >= 0 })
        assertTrue(button.element.getAttribute("theme").contains("tertiary"))
    }

    private companion object {
        val GATED_ENTRIES = setOf(NavItem.MAIL_JOURNAL, NavItem.MAIL_TEMPLATES, NavItem.QUOTES, NavItem.QUOTE_RECEIPT_POOL, NavItem.QUOTE_STATISTICS)
    }
}
