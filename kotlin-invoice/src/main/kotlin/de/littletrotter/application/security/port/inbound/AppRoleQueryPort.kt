package de.littletrotter.application.security.port.inbound

import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.permissions

/** Resolves the caller's [AppRole]s for the current request. */
interface AppRoleQueryPort {
    fun currentRoles(): Set<AppRole>

    /** Whether a real, logged-in user backs the current caller (false for the anonymous dev bypass
     *  and for contexts without a request, where there is no session to end). */
    fun isAuthenticated(): Boolean

    /** Convenience: whether the current caller's roles grant [permission]. */
    fun hasPermission(permission: AppPermission): Boolean = permission in currentRoles().permissions()
}
