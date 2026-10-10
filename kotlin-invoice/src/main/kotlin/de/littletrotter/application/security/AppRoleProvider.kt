package de.littletrotter.application.security

import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.inject.Inject

/** Resolves the caller's [AppRole]s for the current request. */
interface AppRoleProvider {
    fun currentRoles(): Set<AppRole>

    /** Whether a real, logged-in user backs the current caller (false for the anonymous dev bypass
     *  and for contexts without a request, where there is no session to end). */
    fun isAuthenticated(): Boolean

    /** Convenience: whether the current caller's roles grant [permission]. */
    fun hasPermission(permission: AppPermission): Boolean = permission in currentRoles().permissions()
}

/**
 * TS-8 — the CDI-active [AppRoleProvider]: derives [AppRole]s from [SecurityIdentity.roles],
 * which `quarkus.oidc.roles.role-claim-path=groups` (application.properties, TS-2) populates from
 * the Authentik `groups` claim.
 *
 * **Dev-bypass parity (TS-8 acceptance):** with `auth.enabled=false` (the %dev/%test bypass —
 * application.properties) the OIDC tenant is disabled entirely and [SecurityIdentity] stays the
 * container's anonymous default — there is no login mechanism to populate roles from at all. This
 * class keys the bypass off [SecurityIdentity.isAnonymous] directly rather than reading
 * `auth.enabled` itself, deliberately reusing the same signal
 * [de.littletrotter.application.shared.OidcAuditActorProvider] already relies on: in production,
 * every route is authenticated before Vaadin navigation ever runs
 * (`quarkus.http.auth.permission.authenticated.policy=authenticated`), so an anonymous identity
 * there would mean "no login mechanism ran" — exactly the dev-bypass condition, and the only case
 * where granting every role is safe. This also makes `@TestSecurity`-driven Karibu/IT tests work
 * without touching `auth.enabled` at all: `@TestSecurity` installs a non-anonymous synthetic
 * identity regardless of whether the OIDC tenant is enabled, so denial scenarios can be exercised
 * under the %test profile's bypass-friendly config.
 *
 * Falls back to every role on [ContextNotActiveException] too — the same "no request-driven actor
 * exists" case [de.littletrotter.application.shared.OidcAuditActorProvider] degrades to `"system"`
 * for (background NATS consumers, schedulers): there was never a logged-in user to restrict, so
 * granting everything is the correct default, not a bypass of anything real.
 */
@ApplicationScoped
class SecurityIdentityAppRoleProvider @Inject constructor(
    private val securityIdentity: SecurityIdentity,
) : AppRoleProvider {
    override fun isAuthenticated(): Boolean =
        try {
            !securityIdentity.isAnonymous
        } catch (expected: ContextNotActiveException) {
            false
        }

    override fun currentRoles(): Set<AppRole> =
        try {
            if (securityIdentity.isAnonymous) {
                EVERY_ROLE
            } else {
                securityIdentity.roles.mapNotNullTo(mutableSetOf(), AppRole::fromClaim)
            }
        } catch (expected: ContextNotActiveException) {
            EVERY_ROLE
        }

    companion object {
        private val EVERY_ROLE: Set<AppRole> = AppRole.entries.toSet()
    }
}
