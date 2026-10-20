package de.littletrotter.application.security

import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InvocationContext
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort

/**
 * TS-8 — enforces [RequiresPermission] on every CDI-managed bean method it's placed on.
 *
 * `@Priority(APPLICATION)` (2000) runs before JTA's `@Transactional` interceptor (Quarkus wires
 * that around `PLATFORM_BEFORE + 200`, i.e. 4200) so a denied call fails before any transaction —
 * and before any repository access — ever starts, not just before the method body's own logic.
 *
 * The binding annotation's `value` is `@Nonbinding` ([RequiresPermission]'s KDoc), so this single
 * interceptor instance handles every [AppPermission] — it reads the actual required permission
 * back off the intercepted method (falling back to the declaring class, for a bean annotated at
 * class level) via reflection on [InvocationContext.getMethod].
 */
@Interceptor
@RequiresPermission(AppPermission.INVOICE_FULL_EDIT) // binding-only usage; see RequiresPermission's KDoc for why the value is inert here
@Priority(Interceptor.Priority.APPLICATION)
class PermissionInterceptor @Inject constructor(
    private val appRoleProvider: AppRoleQueryPort,
) {

    @AroundInvoke
    fun checkPermission(context: InvocationContext): Any? {
        val required = context.method.getAnnotation(RequiresPermission::class.java)?.value
            ?: context.method.declaringClass.getAnnotation(RequiresPermission::class.java)?.value
            ?: return context.proceed()

        // ANY-of semantics: one held permission out of the listed ones grants access (see
        // RequiresPermission's KDoc for why a method can legitimately list more than one).
        if (required.none { appRoleProvider.hasPermission(it) }) {
            throw AccessDeniedException(
                "Missing permission (any of ${required.joinToString()}) for " +
                    "${context.method.declaringClass.simpleName}.${context.method.name}",
            )
        }
        return context.proceed()
    }
}
