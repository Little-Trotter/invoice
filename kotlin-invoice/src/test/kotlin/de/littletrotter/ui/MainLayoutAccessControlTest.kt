package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.sidenav.SideNav
import com.vaadin.flow.component.sidenav.SideNavItem
import com.vaadin.flow.router.AccessDeniedException
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.NavigationTrigger
import com.vaadin.flow.router.RouteConfiguration
import com.vaadin.flow.server.VaadinService
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.navigation.NavItem
import de.littletrotter.ui.security.RouteAccess
import de.littletrotter.ui.view.info.InfoView
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.InvoiceListView
import de.littletrotter.ui.view.invoice.InvoiceStatisticsView
import de.littletrotter.ui.view.invoice.PrintJobListView
import de.littletrotter.ui.view.invoice.ReceiptPoolView
import de.littletrotter.ui.view.masterdata.ArticleView
import de.littletrotter.ui.view.masterdata.RecipientView
import de.littletrotter.ui.view.masterdata.SenderProfileView
import de.littletrotter.ui.view.referencedata.ReferenceDataView
import de.littletrotter.ui.view.system.SystemView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.message.port.inbound.MessageCenterQueryPort

/**
 * TS-8 — [MainLayout] has no no-arg constructor path through the container (`@Inject
 * lateinit var` fields), so this builds a plain instance directly and sets `appRoleProvider`
 * via reflection — the same instantiation constraint [MainLayoutNavTest] documents for the
 * pre-existing TS-2 checks. Unlike that class's source scans, every test here builds a real
 * instance and drives it through real Vaadin API calls ([MainLayout.beforeEnter] against a real
 * [BeforeEnterEvent], [MainLayout]'s private `createDrawer()` against the real component tree) —
 * exercising the actual compiled branches, not just confirming source text is present.
 *
 * [BeforeEnterEvent] is constructed directly from a `Router`/`Location`/`NavigationTrigger`/`UI`
 * rather than reached via a full `UI.navigate()`: the real route targets (`SystemView`,
 * `InvoiceListView`, ...) need CDI-backed services to construct, which
 * [de.littletrotter.ui.security.NavigationAccessGuardTest] already avoids at the pure decision
 * layer — this class is the "does MainLayout correctly apply that decision to a real event"
 * half, using the event's own real target-`Class` (never an instance), so no view needs
 * constructing at all.
 *
 * MEN-2 rebuilt the drawer as one [SideNav] per section with [SideNavItem] leaves instead of a
 * hand-rolled `RouterLink` list — [SideNavItem] takes a plain path string, no route registration
 * needed, so the collection helper below reads [SideNavItem.getPath] rather than a `RouterLink`
 * href, and only the *direct* children of each [SideNav] count as "the menu" (the REFERENCE_DATA
 * node's three [de.littletrotter.ui.view.referencedata.ReferenceDataGroup] children are nested
 * one level deeper and are never role-gated on their own — hiding their parent already hides
 * them).
 */
class MainLayoutAccessControlTest {

    private lateinit var mainLayout: MainLayout
    private val appRoleProvider: AppRoleQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        // [beforeEnterEvent] resolves a real `Router` off the session, so the target views'
        // @Route annotations need to be registered even though [SideNavItem]-based drawer items
        // (unlike the old `RouterLink` ones) no longer require it themselves — same
        // `setAnnotatedRoute` idiom ReferenceDataViewRouteKaribuTest already uses (parses the
        // @Route annotation only, never instantiates the CDI-backed view).
        RouteAccess.ENTRIES.forEach { (viewClass, _) -> RouteConfiguration.forSessionScope().setAnnotatedRoute(viewClass) }

        mainLayout = MainLayout(null, null)
        setPrivateField(mainLayout, "appRoleProvider", appRoleProvider)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun setPrivateField(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun beforeEnterEvent(target: Class<out Component>, path: String): BeforeEnterEvent {
        val router = VaadinService.getCurrent().router
        val ui = requireNotNull(UI.getCurrent())
        return BeforeEnterEvent(router, NavigationTrigger.PROGRAMMATIC, Location(path), target, ui, emptyList())
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    /** The [NavItem.routePath] a drawer-governed view class resolves to (every [RouteAccess]
     *  entry the drawer can render is also a [NavItem]). */
    private fun pathOf(viewClass: Class<out Component>): String =
        NavItem.entries.first { it.viewClass == viewClass }.routePath

    // ---- beforeEnter: real BeforeEnterEvent, real allow/deny/redirect branches ----

    @Test
    fun `allow - invoicing role reaches the root route, InvoiceListView`() {
        whenever(appRoleProvider.currentRoles()).thenReturn(setOf(AppRole.INVOICING))
        val event = beforeEnterEvent(InvoiceListView::class.java, "")

        mainLayout.beforeEnter(event)

        assertFalse(event.hasForwardTarget())
        assertFalse(event.hasErrorParameter())
    }

    @Test
    fun `deny direction 1 - invoicing role denied on system - rerouted to the AccessDeniedException error view`() {
        whenever(appRoleProvider.currentRoles()).thenReturn(setOf(AppRole.INVOICING))
        val event = beforeEnterEvent(SystemView::class.java, "system")

        mainLayout.beforeEnter(event)

        assertTrue(event.hasErrorParameter())
        assertEquals(AccessDeniedException::class.java, event.errorParameter.exception.javaClass)
        assertFalse(event.hasForwardTarget())
    }

    @Test
    fun `deny direction 2 - admin role denied on invoice detail`() {
        whenever(appRoleProvider.currentRoles()).thenReturn(setOf(AppRole.ADMIN))
        val event = beforeEnterEvent(InvoiceDetailView::class.java, "invoice/1")

        mainLayout.beforeEnter(event)

        assertTrue(event.hasErrorParameter())
        assertEquals(AccessDeniedException::class.java, event.errorParameter.exception.javaClass)
    }

    @Test
    fun `root redirect - admin-only user landing on the root route is forwarded to SystemView, not denied`() {
        whenever(appRoleProvider.currentRoles()).thenReturn(setOf(AppRole.ADMIN))
        val event = beforeEnterEvent(InvoiceListView::class.java, "")

        mainLayout.beforeEnter(event)

        assertFalse(event.hasErrorParameter())
        assertTrue(event.hasForwardTarget())
        assertEquals(SystemView::class.java, event.forwardTargetType)
    }

    @Test
    fun `allow - InfoView is reachable with no permission-bearing role at all`() {
        whenever(appRoleProvider.currentRoles()).thenReturn(emptySet())
        val event = beforeEnterEvent(InfoView::class.java, "info")

        mainLayout.beforeEnter(event)

        assertFalse(event.hasForwardTarget())
        assertFalse(event.hasErrorParameter())
    }

    // ---- createDrawer(): real component tree, "hidden not disabled" per role ----

    /** The paths of the drawer's top-level [SideNavItem]s — [SideNav.getItems] returns exactly
     *  the items directly added via `addItem`, not the REFERENCE_DATA node's nested group children
     *  ([SideNavItem] itself also implements `HasSideNavItems`, one level deeper — see class KDoc):
     *  "the menu" for hide-not-disable purposes is exactly this level. */
    private fun renderedDrawerRoutes(): Set<String> {
        val method = MainLayout::class.java.getDeclaredMethod("createDrawer")
        method.isAccessible = true
        method.invoke(mainLayout)
        return flatten(mainLayout)
            .filterIsInstance<SideNav>()
            .flatMap { sideNav -> sideNav.items }
            .map { item: SideNavItem -> item.path }
            .toSet()
    }

    @Test
    fun `menu shows only invoicing entries for an invoicing-only role`() {
        whenever(appRoleProvider.currentRoles()).thenReturn(setOf(AppRole.INVOICING))

        val expected = setOf(
            InvoiceListView::class.java,
            PrintJobListView::class.java,
            InvoiceStatisticsView::class.java,
            SenderProfileView::class.java,
            RecipientView::class.java,
            ReceiptPoolView::class.java,
            ArticleView::class.java,
            InfoView::class.java,
        ).map(::pathOf).toSet()

        assertEquals(expected, renderedDrawerRoutes())
    }

    @Test
    fun `menu shows only admin entries for an admin-only role`() {
        whenever(appRoleProvider.currentRoles()).thenReturn(setOf(AppRole.ADMIN))

        val expected = setOf(ReferenceDataView::class.java, SystemView::class.java, InfoView::class.java).map(::pathOf).toSet()

        assertEquals(expected, renderedDrawerRoutes())
    }

    @Test
    fun `menu shows the union of both sections for a dual-role user - the dev-bypass identity`() {
        whenever(appRoleProvider.currentRoles()).thenReturn(setOf(AppRole.INVOICING, AppRole.ADMIN))

        val expected = setOf(
            InvoiceListView::class.java, PrintJobListView::class.java, InvoiceStatisticsView::class.java,
            SenderProfileView::class.java, RecipientView::class.java, ReceiptPoolView::class.java, ArticleView::class.java,
            ReferenceDataView::class.java, SystemView::class.java, InfoView::class.java,
        ).map(::pathOf).toSet()

        assertEquals(expected, renderedDrawerRoutes())
    }

    @Test
    fun `menu shows only InfoView when the caller holds no application role at all`() {
        whenever(appRoleProvider.currentRoles()).thenReturn(emptySet())

        assertEquals(setOf(pathOf(InfoView::class.java)), renderedDrawerRoutes())
    }

    // ---- header: logout button only for authenticated users ----

    private fun renderHeader(authenticated: Boolean): List<Button> {
        setPrivateField(mainLayout, "applicationIdentity", mock<ApplicationIdentity>().also { whenever(it.displayName).thenReturn("Little Trotter") })
        setPrivateField(mainLayout, "messageCenterService", mock<MessageCenterQueryPort>().also { whenever(it.unreadCountFor(any())).thenReturn(0L) })
        setPrivateField(mainLayout, "actorProvider", mock<AuditActorQueryPort>().also { whenever(it.currentActor()).thenReturn("alice") })
        whenever(appRoleProvider.isAuthenticated()).thenReturn(authenticated)
        val createHeader = MainLayout::class.java.getDeclaredMethod("createHeader")
        createHeader.isAccessible = true
        createHeader.invoke(mainLayout)
        return flatten(mainLayout).filterIsInstance<Button>().filter { it.hasClassName("header-logout") }
    }

    @Test
    fun `header omits the logout button for the anonymous dev bypass`() {
        assertTrue(renderHeader(authenticated = false).isEmpty(), "no logout button without an authenticated identity")
    }

    @Test
    fun `header renders a working logout button for an authenticated identity`() {
        val button = renderHeader(authenticated = true).single()
        UI.getCurrent()!!.internals.dumpPendingJavaScriptInvocations()

        assertEquals(UiTranslations.tr("nav.logout"), button.text)
        assertEquals("vaadin:sign-out", (button.icon as Icon).element.getAttribute("icon"))

        button._click()

        val parameters = UI.getCurrent()!!.internals.dumpPendingJavaScriptInvocations()
            .flatMap { it.invocation.parameters }
        assertTrue(parameters.contains("/logout"), "click must navigate the browser to /logout")
    }
}
