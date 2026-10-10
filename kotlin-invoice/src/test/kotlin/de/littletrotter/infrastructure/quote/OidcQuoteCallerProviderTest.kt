package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.QuoteCaller
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.application.shared.ActorOverride
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ContextNotActiveException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.Principal

@DisplayName("OidcQuoteCallerProvider")
class OidcQuoteCallerProviderTest {

    private val identity = mock<SecurityIdentity>()
    private val roles = mock<AppRoleProvider>()
    private val actorOverride = ActorOverride()
    private val provider = OidcQuoteCallerProvider(identity, roles, actorOverride)

    @Test
    fun `an authenticated user is forwarded with display name and ordered application groups`() {
        whenever(roles.currentRoles()).thenReturn(setOf(AppRole.ADMIN, AppRole.QUOTING))
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.principal).thenReturn(Principal { "anna" })

        val caller = provider.currentCaller()

        assertEquals("anna", caller.userName)
        assertEquals(listOf("admin", "quoting"), caller.groups)
    }

    @Test
    fun `an anonymous session is the system identity`() {
        whenever(roles.currentRoles()).thenReturn(setOf(AppRole.INVOICING))
        whenever(identity.isAnonymous).thenReturn(true)

        val caller = provider.currentCaller()

        assertEquals("system", caller.userName)
        assertEquals(listOf("invoicing"), caller.groups)
    }

    @Test
    fun `without a request context the caller is the system identity`() {
        whenever(roles.currentRoles()).thenReturn(emptySet())
        whenever(identity.isAnonymous).doThrow(ContextNotActiveException("no request"))

        val caller = provider.currentCaller()

        assertEquals("system", caller.userName)
        assertEquals(emptyList<String>(), caller.groups)
    }

    @Test
    fun `an actor override calls the offer service as that person in the quoting group`() {
        val caller = actorOverride.runAs("clerk") { provider.currentCaller() }

        assertEquals(QuoteCaller("clerk", listOf("quoting")), caller)
    }
}
