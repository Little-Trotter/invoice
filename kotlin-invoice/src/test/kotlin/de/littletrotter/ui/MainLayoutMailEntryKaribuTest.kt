package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.sidenav.SideNav
import com.vaadin.flow.component.sidenav.SideNavItem
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.application.security.permissions
import de.littletrotter.ui.navigation.NavItem
import de.littletrotter.ui.navigation.NavSection
import de.littletrotter.ui.navigation.buildCommandPaletteIndex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Both availability states of the mail menu entry (H-5), asserted on **every** surface
 * that renders the navigation model.
 *
 * The point of one availability predicate on [MainLayout.visibleNavItems] is that the drawer and the command palette cannot
 * disagree. So the disabled case here does not just check the drawer: it checks the drawer, the
 * palette index built through the very filter [MainLayout] installs, and that no component in the
 * whole layout tree links to a mail route. "Not present anywhere" is the assertion, not "disabled".
 *
 * Instantiation follows [MainLayoutAccessControlTest]'s idiom: a plain `MainLayout(null, null)` with the
 * collaborators this behaviour needs reflection-injected, then the private `createDrawer()`
 * invoked explicitly (TS-8 moved it into `@PostConstruct`, which a plain unit test never fires).
 */
@DisplayName("MainLayout — the mail component's menu entry (enabled and disabled)")
class MainLayoutMailEntryKaribuTest {

    private val appRoleProvider: AppRoleProvider = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(appRoleProvider.currentRoles()).thenReturn(setOf(AppRole.INVOICING, AppRole.ADMIN))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun layoutWithMail(enabled: Boolean?): MainLayout {
        // mailAvailability is now a constructor parameter, not a reflection-set field (the
        // CdiInjectInspection fix), so it's passed directly instead of via setPrivateField.
        val layout = MainLayout(enabled?.let { MailAvailability(it) }, null)
        setPrivateField(layout, "appRoleProvider", appRoleProvider)
        invokePrivate(layout, "createDrawer")
        return layout
    }

    private fun setPrivateField(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun invokePrivate(target: Any, name: String) {
        val method = target.javaClass.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(target)
    }

    private fun allDescendants(root: Component): List<Component> =
        root.children.toList().flatMap { listOf(it) + allDescendants(it) }

    private fun drawerPaths(layout: MainLayout): List<String> =
        allDescendants(layout).filterIsInstance<SideNavItem>().mapNotNull { it.path }

    private val menuPath: String = "mail/journal"

    private fun mailItem(layout: MainLayout): SideNavItem? =
        allDescendants(layout).filterIsInstance<SideNavItem>().firstOrNull { it.path == menuPath }

    private val granted: Set<AppPermission> get() = appRoleProvider.currentRoles().permissions()

    // ── Enabled ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MAIL_ENABLED=true — the drawer carries the entry, pointing at the mail journal route")
    fun drawerShowsTheEntryWhenEnabled() {
        val layout = layoutWithMail(enabled = true)
        val item = mailItem(layout)
        assertTrue(item != null, "expected the mail entry in the drawer, got ${drawerPaths(layout)}")
        assertFalse(item!!.isRouterIgnore, "the journal is a Flow route and must go through the router")
    }

    @Test
    @DisplayName("the E-Mail section is in the drawer exactly when the component is available")
    fun sectionPresentIffAvailable() {
        fun hasSection(enabled: Boolean?) =
            allDescendants(layoutWithMail(enabled)).filterIsInstance<SideNav>().any { it.label == "E-Mail" }
        assertTrue(hasSection(true))
        assertFalse(hasSection(false))
        assertFalse(hasSection(null))
    }

    @Test
    @DisplayName("MAIL_ENABLED=true — the command palette carries it too, through the same filter")
    fun paletteShowsTheEntryWhenEnabled() {
        val filter = mailFilter(enabled = true)
        val entries = buildCommandPaletteIndex(filter)
        val email = entries.singleOrNull { it.navItem == NavItem.MAIL_JOURNAL }
        assertTrue(email != null, "expected the mail entry in the palette index")
        assertTrue(email!!.routePath == menuPath, "the palette entry must carry the journal route")
    }

    // ── Disabled ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MAIL_ENABLED=false — the entry is absent from the drawer, not disabled")
    fun drawerHidesTheEntryWhenDisabled() {
        val layout = layoutWithMail(enabled = false)
        val paths = drawerPaths(layout)
        assertFalse(menuPath in paths, "the mail entry must not be rendered at all, got $paths")
        // Not "present but disabled" either: nothing in the whole tree mentions the route.
        assertTrue(
            allDescendants(layout).filterIsInstance<SideNavItem>().none { it.path?.startsWith("mail") == true },
            "no drawer item may link into the mail component when it is not installed",
        )
    }

    @Test
    @DisplayName("MAIL_ENABLED=false — the command palette hides it as well, from the same one filter")
    fun paletteHidesTheEntryWhenDisabled() {
        val entries = buildCommandPaletteIndex(mailFilter(enabled = false))
        assertTrue(entries.none { it.navItem == NavItem.MAIL_JOURNAL }, "the palette must not offer an uninstalled component")
        assertTrue(entries.none { it.routePath.startsWith("mail") })
        // The rest of the index is untouched — this filter removes one entry, not a section.
        assertTrue(entries.any { it.navItem == NavItem.INVOICES })
        assertTrue(entries.any { it.navItem == NavItem.REFERENCE_DATA })
    }

    @Test
    @DisplayName("no MailAvailability at all (a layout built outside CDI) reads as 'component absent'")
    fun missingAvailabilityHidesTheEntry() {
        assertFalse(menuPath in drawerPaths(layoutWithMail(enabled = null)))
        assertFalse(MainLayout.mailAvailabilityPredicate(null)(NavItem.MAIL_JOURNAL))
        assertTrue(MainLayout.mailAvailabilityPredicate(null)(NavItem.INVOICES), "only the mail entry is gated")
    }

    // ── The predicate itself ───────────────────────────────────────────────────

    @Test
    @DisplayName("the availability predicate gates only the two mail entries, never any routed view")
    fun predicateGatesOnlyTheMailEntry() {
        val off = MainLayout.mailAvailabilityPredicate(MailAvailability(false))
        val on = MainLayout.mailAvailabilityPredicate(MailAvailability(true))
        NavItem.entries.forEach { item ->
            assertTrue(on(item), "everything is visible with the component installed: $item")
            if (item != NavItem.MAIL_JOURNAL && item != NavItem.MAIL_TEMPLATES) assertTrue(off(item), "a routed view must never be gated by mail availability: $item")
        }
        assertFalse(off(NavItem.MAIL_JOURNAL))
        assertFalse(off(NavItem.MAIL_TEMPLATES))
    }

    @Test
    @DisplayName("permission filtering and availability filtering compose — both must hold")
    fun permissionAndAvailabilityCompose() {
        val invoicingOnly = setOf(AppRole.INVOICING).permissions()
        val visible = MainLayout.visibleNavItems(
            NavSection.MAIL,
            invoicingOnly,
            MainLayout.mailAvailabilityPredicate(MailAvailability(true)),
        )
        assertTrue(NavItem.MAIL_JOURNAL in visible)
        assertFalse(NavItem.INVOICES in visible)

        val hidden = MainLayout.visibleNavItems(
            NavSection.MAIL,
            invoicingOnly,
            MainLayout.mailAvailabilityPredicate(MailAvailability(false)),
        )
        assertFalse(NavItem.MAIL_JOURNAL in hidden)
        assertTrue(hidden.isEmpty())
    }

    private fun mailFilter(enabled: Boolean): (NavItem) -> Boolean {
        val predicate = MainLayout.mailAvailabilityPredicate(MailAvailability(enabled))
        return { item -> item in MainLayout.visibleNavItems(item.section, granted, predicate) }
    }
}
