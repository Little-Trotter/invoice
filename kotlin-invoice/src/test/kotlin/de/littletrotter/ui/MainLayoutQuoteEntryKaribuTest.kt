package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.sidenav.SideNav
import com.vaadin.flow.component.sidenav.SideNavItem
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.settings.featureGateWithDisabled
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.application.security.permissions
import de.littletrotter.ui.navigation.NavItem
import de.littletrotter.ui.navigation.NavSection
import de.littletrotter.ui.navigation.buildCommandPaletteIndex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** The offer entries appear only when the offer component is deployed and the caller holds the matching permission. */
class MainLayoutQuoteEntryKaribuTest {

    private val appRoleProvider: AppRoleQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun drawerPaths(enabled: Boolean?, vararg roles: AppRole): List<String> {
        whenever(appRoleProvider.currentRoles()).thenReturn(roles.toSet())
        val layout = MainLayout(null, null)
        layout.quoteAvailability = enabled?.let { QuoteAvailability(it) }
        val field = layout.javaClass.getDeclaredField("appRoleProvider").apply { isAccessible = true }
        field.set(layout, appRoleProvider)
        val method = layout.javaClass.getDeclaredMethod("createDrawer").apply { isAccessible = true }
        method.invoke(layout)
        return descendants(layout).filterIsInstance<SideNavItem>().mapNotNull { it.path }
    }

    private fun sectionNav(enabled: Boolean?, label: String, vararg roles: AppRole): SideNav? {
        whenever(appRoleProvider.currentRoles()).thenReturn(roles.toSet())
        val layout = MainLayout(null, null)
        layout.quoteAvailability = enabled?.let { QuoteAvailability(it) }
        layout.javaClass.getDeclaredField("appRoleProvider").apply { isAccessible = true }.set(layout, appRoleProvider)
        layout.javaClass.getDeclaredMethod("createDrawer").apply { isAccessible = true }.invoke(layout)
        return descendants(layout).filterIsInstance<SideNav>().firstOrNull { it.label == label }
    }

    private fun descendants(root: Component): List<Component> = root.children.toList().flatMap { listOf(it) + descendants(it) }

    @Test
    fun `quoting users see the offer list`() {
        val paths = drawerPaths(true, AppRole.QUOTING)

        assertTrue("quotes" in paths, paths.toString())
    }

    @Test
    fun `the Angebote section is present exactly when the component is available`() {
        assertTrue(sectionNav(true, "Angebote", AppRole.QUOTING, AppRole.ADMIN) != null)
        assertTrue(sectionNav(false, "Angebote", AppRole.QUOTING, AppRole.ADMIN) == null)
        assertTrue(sectionNav(null, "Angebote", AppRole.QUOTING, AppRole.ADMIN) == null)
    }

    @Test
    fun `the offer list sits under Angebote and not under Verwaltung`() {
        fun paths(label: String) = sectionNav(true, label, AppRole.QUOTING, AppRole.ADMIN)
            ?.let { nav -> descendants(nav).filterIsInstance<SideNavItem>().mapNotNull { it.path } }
            .orEmpty()
        assertTrue("quotes" in paths("Angebote"), paths("Angebote").toString())
        assertFalse("quotes" in paths("Verwaltung"))
    }

    @Test
    fun `without the component no offer entry is rendered`() {
        val paths = drawerPaths(false, AppRole.QUOTING, AppRole.ADMIN)

        assertTrue(paths.none { it.startsWith("quotes") }, paths.toString())
    }

    @Test
    fun `a deployed component switched off in the settings hides the section and entries`() {
        whenever(appRoleProvider.currentRoles()).thenReturn(setOf(AppRole.QUOTING, AppRole.ADMIN))
        val layout = MainLayout(null, null)
        layout.quoteAvailability = QuoteAvailability(true, featureGateWithDisabled(FeatureFlag.QUOTE))
        layout.javaClass.getDeclaredField("appRoleProvider").apply { isAccessible = true }.set(layout, appRoleProvider)
        layout.javaClass.getDeclaredMethod("createDrawer").apply { isAccessible = true }.invoke(layout)

        val paths = descendants(layout).filterIsInstance<SideNavItem>().mapNotNull { it.path }
        assertTrue(paths.none { it.startsWith("quotes") }, paths.toString())
        assertTrue(descendants(layout).filterIsInstance<SideNav>().none { it.label == "Angebote" })
    }

    @Test
    fun `a layout built outside the container reads as component absent`() {
        assertTrue(drawerPaths(null, AppRole.QUOTING, AppRole.ADMIN).none { it.startsWith("quotes") })
    }

    @Test
    fun `without the permission the entry is hidden even when the component is deployed`() {
        val paths = drawerPaths(true, AppRole.INVOICING)

        assertTrue(paths.none { it.startsWith("quotes") }, paths.toString())
    }

    @Test
    fun `the palette filter hides the offer entries together with the drawer`() {
        val granted = setOf(AppRole.INVOICING, AppRole.QUOTING, AppRole.ADMIN).permissions()
        val on = buildCommandPaletteIndex(itemFilter = { item -> item in visible(granted, true) })
        val off = buildCommandPaletteIndex(itemFilter = { item -> item in visible(granted, false) })

        assertTrue(on.any { it.navItem == NavItem.QUOTES })
        assertTrue(on.any { it.navItem == NavItem.QUOTE_STATISTICS })
        assertTrue(off.none { it.navItem == NavItem.QUOTES || it.navItem == NavItem.QUOTE_RECEIPT_POOL || it.navItem == NavItem.QUOTE_STATISTICS })
        assertTrue(off.any { it.navItem == NavItem.INVOICES })
    }

    private fun visible(granted: Set<de.littletrotter.application.security.AppPermission>, enabled: Boolean): List<NavItem> =
        NavSection.entries.flatMap { MainLayout.visibleNavItems(it, granted, MainLayout.quoteAvailabilityPredicate(QuoteAvailability(enabled))) }

    @Test
    fun `the availability predicate gates exactly the offer entries`() {
        val off = MainLayout.quoteAvailabilityPredicate(QuoteAvailability(false))
        val on = MainLayout.quoteAvailabilityPredicate(QuoteAvailability(true))
        val absent = MainLayout.quoteAvailabilityPredicate(null)

        NavItem.entries.forEach { item ->
            assertTrue(on(item))
            val gated = item == NavItem.QUOTES || item == NavItem.QUOTE_RECEIPT_POOL || item == NavItem.QUOTE_STATISTICS
            assertEquals(!gated, off(item), item.name)
            assertEquals(!gated, absent(item), item.name)
        }
    }
}
