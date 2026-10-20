package de.littletrotter.ui.security

import com.vaadin.flow.component.Component
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.permissions

/**
 * TS-8 — the pure decision core of the Vaadin navigation guard: given a navigation target and the
 * caller's roles, decides allow / redirect / deny against [RouteAccess]'s matrix. No Vaadin
 * `UI`/CDI dependency, so this is unit-testable without MockVaadin or a CDI container — the actual
 * Vaadin wiring ([de.littletrotter.ui.MainLayout.beforeEnter]) is a thin dispatcher on top,
 * applying [Decision] to a `BeforeEnterEvent` (same "test the companion, not the CDI-wired
 * component" idiom [de.littletrotter.ui.MainLayout] already uses for `tabTitle`/`bellBadgeText`).
 */
object NavigationAccessGuard {

    sealed interface Decision {
        /** The caller's roles grant the required permission (or none is required). */
        data object Allow : Decision

        /** TS-8's root-redirect special case: denied at [RouteAccess]'s root target, forwarded to
         *  the caller's first permitted landing view instead of a 403 dead end. */
        data class RedirectTo(val target: Class<out Component>) : Decision

        /** Denied anywhere else — the caller sees a 403, not a silent fallback. */
        data class Deny(val reason: String) : Decision
    }

    /**
     * @param targetClass the navigation target Vaadin resolved for this request.
     * @param roles the caller's [AppRole]s ([de.littletrotter.application.security.port.inbound.AppRoleQueryPort]).
     * @param rootTarget the view class bound to the root route (`""`) — [InvoiceListView][de.littletrotter.ui.view.invoice.InvoiceListView]
     *   in production; a parameter here purely so this function stays free of that import chain
     *   and easy to exercise with a fake class in tests.
     */
    fun evaluate(
        targetClass: Class<*>,
        roles: Set<AppRole>,
        rootTarget: Class<out Component>,
    ): Decision {
        if (!RouteAccess.isGoverned(targetClass)) return Decision.Allow

        val required = RouteAccess.permissionFor(targetClass)
        if (required == null || required in roles.permissions()) return Decision.Allow

        if (targetClass == rootTarget) {
            return Decision.RedirectTo(RouteAccess.firstPermittedLandingView(roles))
        }

        return Decision.Deny("missing permission $required for ${targetClass.simpleName}")
    }
}
