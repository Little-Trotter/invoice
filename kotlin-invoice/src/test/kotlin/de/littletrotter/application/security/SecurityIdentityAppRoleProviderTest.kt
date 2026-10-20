package de.littletrotter.application.security

import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ContextNotActiveException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort

/**
 * TS-8 — [SecurityIdentityAppRoleProvider] is the CDI-active [AppRoleQueryPort]; this exercises its
 * decision logic directly against a mocked [SecurityIdentity], the same "test the companion, not
 * the CDI-wired component" idiom [de.littletrotter.ui.MainLayout] already documents for
 * `tabTitle`/`bellBadgeText`.
 */
class SecurityIdentityAppRoleProviderTest {

    @Test
    fun `isAuthenticated is false for the anonymous identity`() {
        val identity = mock<SecurityIdentity>()
        whenever(identity.isAnonymous).thenReturn(true)

        assertFalse(SecurityIdentityAppRoleProvider(identity).isAuthenticated())
    }

    @Test
    fun `isAuthenticated is true for a non-anonymous identity`() {
        val identity = mock<SecurityIdentity>()
        whenever(identity.isAnonymous).thenReturn(false)

        assertTrue(SecurityIdentityAppRoleProvider(identity).isAuthenticated())
    }

    @Test
    fun `isAuthenticated is false without an active request context`() {
        val identity = mock<SecurityIdentity>()
        whenever(identity.isAnonymous).thenThrow(ContextNotActiveException())

        assertFalse(SecurityIdentityAppRoleProvider(identity).isAuthenticated())
    }

    @Test
    fun `anonymous identity - the dev-bypass case - grants every role`() {
        val identity = mock<SecurityIdentity>()
        whenever(identity.isAnonymous).thenReturn(true)

        val roles = SecurityIdentityAppRoleProvider(identity).currentRoles()

        assertEquals(setOf(AppRole.INVOICING, AppRole.ADMIN, AppRole.QUOTING), roles)
    }

    @Test
    fun `an authenticated identity with the invoicing group grants only INVOICING`() {
        val identity = mock<SecurityIdentity>()
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.roles).thenReturn(setOf("invoicing"))

        val roles = SecurityIdentityAppRoleProvider(identity).currentRoles()

        assertEquals(setOf(AppRole.INVOICING), roles)
    }

    @Test
    fun `an authenticated identity with the admin group grants only ADMIN`() {
        val identity = mock<SecurityIdentity>()
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.roles).thenReturn(setOf("admin"))

        val roles = SecurityIdentityAppRoleProvider(identity).currentRoles()

        assertEquals(setOf(AppRole.ADMIN), roles)
    }

    @Test
    fun `an authenticated identity carrying both groups grants both roles - additive membership`() {
        val identity = mock<SecurityIdentity>()
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.roles).thenReturn(setOf("invoicing", "admin"))

        val roles = SecurityIdentityAppRoleProvider(identity).currentRoles()

        assertEquals(setOf(AppRole.INVOICING, AppRole.ADMIN), roles)
    }

    @Test
    fun `an authenticated identity with only unrelated groups grants nothing`() {
        val identity = mock<SecurityIdentity>()
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.roles).thenReturn(setOf("authentik Admins"))

        val roles = SecurityIdentityAppRoleProvider(identity).currentRoles()

        assertEquals(emptySet<AppRole>(), roles)
    }

    @Test
    fun `no active request context - a background thread - falls back to every role`() {
        val identity = mock<SecurityIdentity>()
        whenever(identity.isAnonymous).thenThrow(ContextNotActiveException())

        val roles = SecurityIdentityAppRoleProvider(identity).currentRoles()

        assertEquals(setOf(AppRole.INVOICING, AppRole.ADMIN, AppRole.QUOTING), roles)
    }

    @Test
    fun `hasPermission delegates through currentRoles and the additive permission union`() {
        val identity = mock<SecurityIdentity>()
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.roles).thenReturn(setOf("invoicing"))
        val provider = SecurityIdentityAppRoleProvider(identity)

        org.junit.jupiter.api.Assertions.assertTrue(provider.hasPermission(AppPermission.INVOICE_FULL_EDIT))
        org.junit.jupiter.api.Assertions.assertFalse(provider.hasPermission(AppPermission.SYSTEM_VIEW))
    }
}
