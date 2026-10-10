package de.littletrotter.ui.security

import de.littletrotter.application.security.AppRole
import de.littletrotter.ui.view.info.InfoView
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.InvoiceListView
import de.littletrotter.ui.view.system.SystemView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TS-8 acceptance tests, exercised at the decision-logic layer against the REAL route matrix
 * ([RouteAccess]) and REAL view classes — [NavigationAccessGuard.evaluate] has no Vaadin `UI`/CDI
 * dependency, so this covers "route matrix enforced ... representative view per role, both denial
 * directions, root-redirect" without needing MockVaadin or a running CDI container. The Vaadin
 * wiring itself ([de.littletrotter.ui.MainLayout.beforeEnter]) is a thin, untested dispatcher on
 * top of this — see that method's KDoc.
 */
class NavigationAccessGuardTest {

    private val root = InvoiceListView::class.java

    @Test
    fun `allow - invoicing role reaches InvoiceListView, the representative invoicing view`() {
        val decision = NavigationAccessGuard.evaluate(InvoiceListView::class.java, setOf(AppRole.INVOICING), root)
        assertEquals(NavigationAccessGuard.Decision.Allow, decision)
    }

    @Test
    fun `allow - admin role reaches SystemView, the representative admin view`() {
        val decision = NavigationAccessGuard.evaluate(SystemView::class.java, setOf(AppRole.ADMIN), root)
        assertEquals(NavigationAccessGuard.Decision.Allow, decision)
    }

    @Test
    fun `deny direction 1 - invoicing role denied on system`() {
        val decision = NavigationAccessGuard.evaluate(SystemView::class.java, setOf(AppRole.INVOICING), root)
        assertTrue(decision is NavigationAccessGuard.Decision.Deny)
    }

    @Test
    fun `deny direction 2 - admin role denied on invoice detail`() {
        val decision = NavigationAccessGuard.evaluate(InvoiceDetailView::class.java, setOf(AppRole.ADMIN), root)
        assertTrue(decision is NavigationAccessGuard.Decision.Deny)
    }

    @Test
    fun `root redirect - admin-only user landing on the root route is redirected to SystemView, not denied`() {
        val decision = NavigationAccessGuard.evaluate(root, setOf(AppRole.ADMIN), root)
        assertEquals(NavigationAccessGuard.Decision.RedirectTo(SystemView::class.java), decision)
    }

    @Test
    fun `root is NOT redirected for invoicing role - it is already permitted there`() {
        val decision = NavigationAccessGuard.evaluate(root, setOf(AppRole.INVOICING), root)
        assertEquals(NavigationAccessGuard.Decision.Allow, decision)
    }

    @Test
    fun `no roles at all landing on root redirects to InfoView, never a dead end`() {
        val decision = NavigationAccessGuard.evaluate(root, emptySet(), root)
        assertEquals(NavigationAccessGuard.Decision.RedirectTo(InfoView::class.java), decision)
    }

    @Test
    fun `both roles allow every governed view`() {
        val both = setOf(AppRole.INVOICING, AppRole.ADMIN, AppRole.QUOTING)
        RouteAccess.ENTRIES.forEach { (viewClass, _) ->
            val decision = NavigationAccessGuard.evaluate(viewClass, both, root)
            assertEquals(NavigationAccessGuard.Decision.Allow, decision, viewClass.simpleName)
        }
    }

    @Test
    fun `no roles at all is denied on every non-root governed view except InfoView`() {
        RouteAccess.ENTRIES.filterNot { it.first == root }.forEach { (viewClass, permission) ->
            val decision = NavigationAccessGuard.evaluate(viewClass, emptySet(), root)
            if (permission == null) {
                assertEquals(NavigationAccessGuard.Decision.Allow, decision, viewClass.simpleName)
            } else {
                assertTrue(decision is NavigationAccessGuard.Decision.Deny, viewClass.simpleName)
            }
        }
    }

    @Test
    fun `an ungoverned class is always allowed - not this guard's concern`() {
        val decision = NavigationAccessGuard.evaluate(String::class.java, emptySet(), root)
        assertEquals(NavigationAccessGuard.Decision.Allow, decision)
    }

    @Test
    fun `InfoView is reachable with no permission-bearing role at all`() {
        val decision = NavigationAccessGuard.evaluate(InfoView::class.java, emptySet(), root)
        assertEquals(NavigationAccessGuard.Decision.Allow, decision)
    }

    @Test
    fun `Decision data classes expose their payload - RedirectTo target, Deny reason`() {
        val redirect = NavigationAccessGuard.Decision.RedirectTo(SystemView::class.java)
        assertEquals(SystemView::class.java, redirect.target)
        assertEquals(redirect, redirect.copy())

        val deny = NavigationAccessGuard.Decision.Deny("missing permission SYSTEM_VIEW for InvoiceDetailView")
        assertEquals("missing permission SYSTEM_VIEW for InvoiceDetailView", deny.reason)
        assertEquals(deny, deny.copy())
        assertTrue(deny.toString().contains("missing permission"))
    }
}
