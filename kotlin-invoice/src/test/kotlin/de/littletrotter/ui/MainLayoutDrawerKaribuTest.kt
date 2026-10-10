package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.sidenav.SideNav
import com.vaadin.flow.component.sidenav.SideNavItem
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.LocationChangeEvent
import com.vaadin.flow.router.NavigationTrigger
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.ui.navigation.NavItem
import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * MEN-2 — UI smoke test for [MainLayout]'s drawer: four `SideNav`s, all ten [NavItem] targets
 * reachable, and the REFERENCE_DATA node exposing its three [ReferenceDataGroup] children without
 * entering [de.littletrotter.ui.view.referencedata.ReferenceDataView].
 *
 * [MainLayout] is CDI-injected (`@Inject lateinit var` fields); [MainLayout.createDrawer] itself
 * only touches [MainLayout.getComponent]/`tr(...)`/`appRoleProvider` (TS-8's permission filter,
 * [de.littletrotter.ui.MainLayout.visibleNavItems]), so a bare `MainLayout(null, null)` plus a reflection-
 * injected [AppRoleProvider] mock granting every permission is enough to drive it directly — TS-8
 * moved the call itself from `init {}` to a `@PostConstruct` callback a plain unit test never
 * triggers, so [mainLayoutWithFullDrawer] invokes it explicitly. This class's own assertions are
 * about the full, unfiltered drawer (every section/target present) — permission *filtering*
 * itself is covered separately in [MainLayoutAccessControlTest]. The `afterNavigation` tests
 * further down additionally reflection-inject mocked CDI dependencies (same idiom as
 * `MessageCenterViewKaribuTest`) to exercise the reference-data expand/collapse wiring end to end.
 * The pure decision function itself is covered without any of this scaffolding in
 * [MainLayoutNavTest].
 */
class MainLayoutDrawerKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    /** Depth-first walk of the actual Flow component tree (drawer slot included — slotted
     *  children are still real children of the [MainLayout] element). */
    private fun allDescendants(root: Component): List<Component> {
        val result = mutableListOf<Component>()
        root.children.forEach { child ->
            result.add(child)
            result.addAll(allDescendants(child))
        }
        return result
    }

    private fun drawerSideNavs(mainLayout: MainLayout): List<SideNav> =
        allDescendants(mainLayout).filterIsInstance<SideNav>()

    private fun setPrivateField(target: MainLayout, name: String, value: Any) {
        val field = MainLayout::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    /** Every [AppRole] granted, i.e. every [de.littletrotter.application.security.AppPermission]
     *  — this class asserts the full, unfiltered drawer, so nothing TS-8 gates should ever be
     *  hidden here (permission *filtering* is [MainLayoutAccessControlTest]'s job). */
    private fun allPermissionsRoleProvider(): AppRoleProvider =
        mock<AppRoleProvider>().also { whenever(it.currentRoles()).thenReturn(AppRole.entries.toSet()) }

    /** A [MainLayout] with its drawer actually built: reflection-injects an all-permissions
     *  [AppRoleProvider] (createDrawer()'s TS-8 permission filter needs one) and invokes the
     *  private `createDrawer()` directly, since TS-8 moved it out of `init {}` into a
     *  `@PostConstruct` callback that a plain unit test never triggers. */
    private fun mainLayoutWithFullDrawer(): MainLayout {
        val mainLayout = MainLayout(null, null)
        setPrivateField(mainLayout, "appRoleProvider", allPermissionsRoleProvider())
        val createDrawer = MainLayout::class.java.getDeclaredMethod("createDrawer")
        createDrawer.isAccessible = true
        createDrawer.invoke(mainLayout)
        return mainLayout
    }

    @Test
    fun `the drawer has one SideNav per section, four in total`() {
        val sideNavs = drawerSideNavs(mainLayoutWithFullDrawer())
        assertEquals(4, sideNavs.size)
    }

    @Test
    fun `the drawer's SideNavs carry the four section header labels`() {
        val labels = drawerSideNavs(mainLayoutWithFullDrawer()).map { it.label }.toSet()
        assertEquals(setOf("Fakturierung", "Stammdaten", "Verwaltung", "Hilfe"), labels)
    }

    @Test
    fun `all ten nav targets are reachable as SideNavItem paths`() {
        val mainLayout = mainLayoutWithFullDrawer()
        val topLevelItems = drawerSideNavs(mainLayout).flatMap { sideNav ->
            allDescendants(sideNav).filterIsInstance<SideNavItem>()
        }
        // Only the ten NavItem entries themselves — not the three reference-data group children,
        // which don't correspond to a NavItem.
        // NavItem.MAIL_JOURNAL and NavItem.MAIL_TEMPLATES are excluded here for the same reason it is absent from this
        // drawer: it is gated on a MailAvailability this bare, reflection-wired layout never
        // receives, so "component not installed" is the state under test. Both of its states are
        // covered end to end in MainLayoutMailEntryKaribuTest.
        val topLevelPaths = topLevelItems.map { it.path }.toSet()
        // The offer entries are gated on QuoteAvailability in the same way; see MainLayoutQuoteEntryKaribuTest.
        val gated = setOf(NavItem.MAIL_JOURNAL, NavItem.MAIL_TEMPLATES, NavItem.QUOTES, NavItem.QUOTE_RECEIPT_POOL, NavItem.QUOTE_STATISTICS)
        val expectedPaths = NavItem.entries.filterNot { it in gated }.map { it.routePath }.toSet()

        assertEquals(expectedPaths, topLevelPaths.intersect(expectedPaths))
        assertTrue(expectedPaths.all { it in topLevelPaths }, "missing NavItem path(s): ${expectedPaths - topLevelPaths}")
    }

    @Test
    fun `the reference-data node has exactly the three reference-data group children`() {
        val mainLayout = mainLayoutWithFullDrawer()
        val referenceDataNode = drawerSideNavs(mainLayout)
            .flatMap { allDescendants(it).filterIsInstance<SideNavItem>() }
            .first { it.path == NavItem.REFERENCE_DATA.routePath }

        val expectedGroupPaths = ReferenceDataGroup.entries.map { MainLayout.referenceDataGroupPath(it) }.toSet()
        val childPaths = allDescendants(referenceDataNode).filterIsInstance<SideNavItem>().map { it.path }.toSet()

        assertEquals(3, childPaths.size)
        assertEquals(expectedGroupPaths, childPaths)
    }

    // ── MEN-2 — afterNavigation wires referenceDataExpandedFor into the actual SideNavItem ────
    // Unlike the construction tests above, [MainLayout.afterNavigation] also calls
    // [MainLayout.refreshBellBadge]/[MainLayout.applyBrowserTitle], which need the CDI-injected
    // fields — reflection-inject mocks, same "reflection into private fields" idiom as
    // `MessageCenterViewKaribuTest`.

    private fun referenceDataNode(mainLayout: MainLayout): SideNavItem =
        drawerSideNavs(mainLayout)
            .flatMap { allDescendants(it).filterIsInstance<SideNavItem>() }
            .first { it.path == NavItem.REFERENCE_DATA.routePath }

    /** Same as [mainLayoutWithFullDrawer] plus the message-center/actor mocks the
     *  `afterNavigation` tests below need — `afterNavigation()` also calls
     *  [MainLayout.refreshBellBadge]/[MainLayout.applyBrowserTitle], which touch those fields.
     *  [roleProvider] defaults to every permission granted; pass a narrower mock to exercise the
     *  REFERENCE_DATA node's nullable-when-hidden path (TS-8). */
    private fun mainLayoutWithMockedCdi(roleProvider: AppRoleProvider = allPermissionsRoleProvider()): MainLayout {
        val mainLayout = MainLayout(null, null)
        setPrivateField(mainLayout, "applicationIdentity", mock<ApplicationIdentity>().also { whenever(it.displayName).thenReturn("Little Trotter") })
        setPrivateField(mainLayout, "messageCenterService", mock<MessageCenterService>().also { whenever(it.unreadCountFor(any())).thenReturn(0L) })
        setPrivateField(mainLayout, "actorProvider", mock<AuditActorProvider>().also { whenever(it.currentActor()).thenReturn("alice") })
        setPrivateField(mainLayout, "appRoleProvider", roleProvider)
        // afterNavigation()'s refreshBellBadge() needs the header's bellButton, and the
        // REFERENCE_DATA SideNavItem (when granted) needs to exist for afterNavigation to
        // expand/collapse — both normally built by the @PostConstruct callback a real CDI
        // container invokes after injection.
        val createHeader = MainLayout::class.java.getDeclaredMethod("createHeader")
        createHeader.isAccessible = true
        createHeader.invoke(mainLayout)
        val createDrawer = MainLayout::class.java.getDeclaredMethod("createDrawer")
        createDrawer.isAccessible = true
        createDrawer.invoke(mainLayout)
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

    @Test
    fun `afterNavigation expands the reference-data node for a referenzdaten deep link`() {
        val mainLayout = mainLayoutWithMockedCdi()

        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten/e-rechnung/b2b-profile"))

        assertTrue(referenceDataNode(mainLayout).isExpanded)
    }

    @Test
    fun `afterNavigation collapses the reference-data node once the route leaves referenzdaten`() {
        val mainLayout = mainLayoutWithMockedCdi()
        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten"))
        assertTrue(referenceDataNode(mainLayout).isExpanded, "precondition: node starts expanded")

        mainLayout.afterNavigation(afterNavigationEventFor("statistics"))

        assertFalse(referenceDataNode(mainLayout).isExpanded)
    }

    // ── TS-8 — the REFERENCE_DATA node is absent (nullable) without permission ─────

    private fun invoicingOnlyRoleProvider(): AppRoleProvider =
        mock<AppRoleProvider>().also { whenever(it.currentRoles()).thenReturn(setOf(AppRole.INVOICING)) }

    @Test
    fun `a role lacking REFERENCE_DATA_EDIT has no reference-data node in the drawer`() {
        val mainLayout = mainLayoutWithMockedCdi(invoicingOnlyRoleProvider())

        val referenceDataItems = drawerSideNavs(mainLayout)
            .flatMap { allDescendants(it).filterIsInstance<SideNavItem>() }
            .filter { it.path == NavItem.REFERENCE_DATA.routePath }

        assertTrue(referenceDataItems.isEmpty(), "REFERENCE_DATA must be hidden, not just disabled, without permission")
    }

    @Test
    fun `afterNavigation does not NPE when the reference-data node is absent`() {
        val mainLayout = mainLayoutWithMockedCdi(invoicingOnlyRoleProvider())

        // Must not throw: MainLayout.referenceDataNavItem is null for this role, and
        // afterNavigation's `referenceDataNavItem?.setExpanded(...)` is the nullable-safe path
        // that exercises.
        mainLayout.afterNavigation(afterNavigationEventFor("referenzdaten/e-rechnung/b2b-profile"))
        mainLayout.afterNavigation(afterNavigationEventFor("statistics"))
    }

    // ── TS-8/MEN-2 — a fully-filtered section renders no SideNav at all ────────────

    @Test
    fun `a section whose every item is filtered away renders no SideNav for it`() {
        // ADMIN holds neither MASTER_DATA_EDIT nor ARTICLES_EDIT, so every MASTER_DATA item
        // (SENDER_PROFILES, RECIPIENTS, ARTICLES) is filtered out — the section itself must not
        // render as an empty headed block.
        val adminOnlyRoleProvider = mock<AppRoleProvider>().also { whenever(it.currentRoles()).thenReturn(setOf(AppRole.ADMIN)) }
        val mainLayout = mainLayoutWithMockedCdi(adminOnlyRoleProvider)

        val labels = drawerSideNavs(mainLayout).map { it.label }.toSet()

        assertFalse(labels.contains("Stammdaten"), "a fully-filtered section must not render an (empty) SideNav")
        assertEquals(setOf("Verwaltung", "Hilfe"), labels)
    }
}
