package de.littletrotter.application.security

import jakarta.interceptor.InterceptorBinding
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.FUNCTION

/**
 * TS-8 — service-layer defense in depth for mutating operations: annotate a business method (or a
 * whole bean, for a class where every method needs the same permission) with the [AppPermission]
 * it requires. [PermissionInterceptor] enforces it via [de.littletrotter.application.security.AppRoleProvider]
 * before the method body runs, independent of the Vaadin navigation guard
 * ([de.littletrotter.ui.security.NavigationAccessGuard]) — UI hiding alone is not enforcement, so a
 * mutating call reaches the same check even when invoked outside the UI path.
 *
 * [value] is `@Nonbinding`: CDI interceptor bindings normally compare every member for equality
 * when matching a target to an interceptor, which would require one `@Interceptor` per
 * [AppPermission]. Marking it non-binding makes every [RequiresPermission] usage resolve to the
 * same [PermissionInterceptor], which then reads the actual required permission back off the
 * intercepted method via reflection.
 *
 * Listing more than one permission means ANY of them grants access (used for
 * the B2B field-value methods on [de.littletrotter.application.reference.B2bFieldValueQueries],
 * which one invoicing-role flow calls in a master-data context and another in an invoice-edit
 * context) — a caller must hold at least one listed permission, never all of them.
 */
@InterceptorBinding
@Target(FUNCTION, CLASS)
@Retention(RUNTIME)
annotation class RequiresPermission(
    @get:jakarta.enterprise.util.Nonbinding
    vararg val value: AppPermission,
)

/** Thrown by [PermissionInterceptor] when the caller's roles don't grant a [RequiresPermission]-
 *  guarded method's required [AppPermission]. Deliberately its own type (not a subtype of
 *  [de.littletrotter.application.error.ApplicationUserException]) — an authorization failure is a
 *  cross-cutting security concern, not a domain validation/not-found/conflict outcome. */
class AccessDeniedException(message: String) : RuntimeException(message)
