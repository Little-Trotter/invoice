package de.littletrotter.infrastructure.mail

import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.AppRoleProvider
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ContextNotActiveException
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.Principal

@DisplayName("OidcMailCallerProvider")
class OidcMailCallerProviderTest {

    private val identity = mock<SecurityIdentity>()
    private val roles = mock<AppRoleProvider>()
    private val provider = OidcMailCallerProvider(identity, roles)

    private fun jwt(name: String, email: Any?): JsonWebToken =
        mock<JsonWebToken>().also {
            whenever(it.name).thenReturn(name)
            whenever(it.getClaim<Any?>("email")).thenReturn(email)
        }

    private fun authenticated(principal: Principal) {
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.principal).thenReturn(principal)
    }

    @Test
    fun `an authenticated user is forwarded with display name, ordered application groups and e-mail claim`() {
        whenever(roles.currentRoles()).thenReturn(setOf(AppRole.ADMIN, AppRole.INVOICING))
        authenticated(jwt("anna", " anna@example.com "))

        val caller = provider.currentCaller()

        assertEquals("anna", caller.userName)
        assertEquals(listOf("invoicing", "admin"), caller.groups)
        assertEquals("anna@example.com", caller.email)
    }

    @Test
    fun `a missing or blank e-mail claim yields no address`() {
        whenever(roles.currentRoles()).thenReturn(emptySet())
        authenticated(jwt("anna", null))
        assertNull(provider.currentCaller().email)

        authenticated(jwt("anna", "   "))
        assertNull(provider.currentCaller().email)
        assertEquals(emptyList<String>(), provider.currentCaller().groups)
    }

    @Test
    fun `a principal that is no token has no e-mail claim`() {
        whenever(roles.currentRoles()).thenReturn(setOf(AppRole.ADMIN))
        authenticated(Principal { "plain" })

        val caller = provider.currentCaller()

        assertEquals("plain", caller.userName)
        assertNull(caller.email)
    }

    @Test
    fun `an anonymous caller is the system identity`() {
        whenever(roles.currentRoles()).thenReturn(AppRole.entries.toSet())
        whenever(identity.isAnonymous).thenReturn(true)

        val caller = provider.currentCaller()

        assertEquals("system", caller.userName)
        assertEquals(listOf("invoicing", "admin", "quoting"), caller.groups)
        assertNull(caller.email)
    }

    @Test
    fun `without a request context the caller is the system identity`() {
        whenever(roles.currentRoles()).thenReturn(AppRole.entries.toSet())
        whenever(identity.isAnonymous).doThrow(ContextNotActiveException())

        assertEquals("system", provider.currentCaller().userName)
    }
}
