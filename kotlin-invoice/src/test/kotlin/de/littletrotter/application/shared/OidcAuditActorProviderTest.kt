/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.application.shared

import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ContextNotActiveException
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.Principal
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort

/**
 * TS-2 — [OidcAuditActorProvider] against a mocked [SecurityIdentity]: no CDI container, no
 * running Authentik. Covers both branches [currentActor] can take: a real principal name (the
 * authenticated case) and the anonymous fallback (the dev-bypass case, `auth.enabled=false`,
 * where no authentication mechanism ever runs and Quarkus's default identity stays anonymous).
 */
class OidcAuditActorProviderTest {

    @Test
    fun `currentActor returns the security identity's principal name when authenticated`() {
        val principal: Principal = mock()
        whenever(principal.name).thenReturn("alice")
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.principal).thenReturn(principal)

        val provider = OidcAuditActorProvider(identity, ActorOverride())

        assertEquals("alice", provider.currentActor())
    }

    @Test
    fun `currentActor falls back to system for an anonymous identity`() {
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenReturn(true)

        val provider = OidcAuditActorProvider(identity, ActorOverride())

        assertEquals("system", provider.currentActor())
    }

    @Test
    fun `currentActor falls back to system when no request context is active`() {
        // Background-thread call sites (concurrent domain operations, scheduled jobs, NATS
        // consumers) have no active RequestScoped context, so the SecurityIdentity client proxy
        // throws on first access — proven here by mocking that exact failure mode.
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenThrow(ContextNotActiveException())

        val provider = OidcAuditActorProvider(identity, ActorOverride())

        assertEquals("system", provider.currentActor())
    }

    // ─── currentActorId()/currentActorDisplayName() ──────────────────────────────────

    @Test
    fun `RS-8 - currentActorId returns the JWT sub claim, independent of the preferred_username principal name`() {
        val jwt: JsonWebToken = mock()
        whenever(jwt.subject).thenReturn("oidc-sub-123")
        whenever(jwt.name).thenReturn("alice") // principal-claim=preferred_username, per application.properties
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.principal).thenReturn(jwt)

        val provider = OidcAuditActorProvider(identity, ActorOverride())

        assertEquals("oidc-sub-123", provider.currentActorId())
    }

    @Test
    fun `RS-8 - currentActorId falls back to currentActor when the principal is not a JsonWebToken`() {
        val principal: Principal = mock()
        whenever(principal.name).thenReturn("alice")
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.principal).thenReturn(principal)

        val provider = OidcAuditActorProvider(identity, ActorOverride())

        assertEquals("alice", provider.currentActorId())
    }

    @Test
    fun `RS-8 - currentActorId falls back to system for an anonymous identity`() {
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenReturn(true)

        val provider = OidcAuditActorProvider(identity, ActorOverride())

        assertEquals("system", provider.currentActorId())
    }

    @Test
    fun `RS-8 - currentActorId falls back to system when no request context is active`() {
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenThrow(ContextNotActiveException())

        val provider = OidcAuditActorProvider(identity, ActorOverride())

        assertEquals("system", provider.currentActorId())
    }

    @Test
    fun `RS-8 - currentActorDisplayName returns the security identity's principal name when authenticated`() {
        val principal: Principal = mock()
        whenever(principal.name).thenReturn("alice")
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.principal).thenReturn(principal)

        val provider = OidcAuditActorProvider(identity, ActorOverride())

        assertEquals("alice", provider.currentActorDisplayName())
    }

    @Test
    fun `RS-8 - currentActorDisplayName is null for an anonymous identity`() {
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenReturn(true)

        val provider = OidcAuditActorProvider(identity, ActorOverride())

        assertNull(provider.currentActorDisplayName())
    }

    @Test
    fun `RS-8 - currentActorDisplayName is null when no request context is active`() {
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenThrow(ContextNotActiveException())

        val provider = OidcAuditActorProvider(identity, ActorOverride())

        assertNull(provider.currentActorDisplayName())
    }

    @Test
    fun `RS-8 - the AuditActorQueryPort default methods fall back to currentActor and null for an implementation that only overrides currentActor`() {
        // SystemAuditActorProvider is exactly this case in production; a minimal anonymous
        // implementation here proves the INTERFACE's own defaults, independent of that class.
        val minimal = object : AuditActorQueryPort {
            override fun currentActor(): String = "system"
        }

        assertEquals("system", minimal.currentActorId())
        assertNull(minimal.currentActorDisplayName())
    }

    @Test
    fun `an active override names its actor in every audit accessor and ends with the block`() {
        val identity: SecurityIdentity = mock()
        val override = ActorOverride()
        val provider = OidcAuditActorProvider(identity, override)

        override.runAs("clerk") {
            assertEquals("clerk", provider.currentActor())
            assertEquals("clerk", provider.currentActorId())
            assertEquals("clerk", provider.currentActorDisplayName())
        }

        whenever(identity.isAnonymous).thenReturn(true)
        assertEquals("system", provider.currentActor())
    }

    private fun tokenIdentity(
        username: String?,
        name: String? = null,
        email: String? = null,
        sub: String? = null,
    ): SecurityIdentity {
        val token: JsonWebToken = mock()
        whenever(token.name).thenReturn(username)
        whenever(token.getClaim<Any?>("name")).thenReturn(name)
        whenever(token.getClaim<Any?>("email")).thenReturn(email)
        whenever(token.subject).thenReturn(sub)
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenReturn(false)
        whenever(identity.principal).thenReturn(token)
        return identity
    }

    @Test
    fun `display name prefers username then name then email then subject`() {
        fun display(identity: SecurityIdentity) =
            OidcAuditActorProvider(identity, ActorOverride()).currentActorDisplayName()

        assertEquals("alice", display(tokenIdentity("alice", "Alice A", "a@x.test", "sub-1")))
        assertEquals("Alice A", display(tokenIdentity(null, "Alice A", "a@x.test", "sub-1")))
        assertEquals("a@x.test", display(tokenIdentity(null, null, "a@x.test", "sub-1")))
        assertEquals("sub-1", display(tokenIdentity(null, null, null, "sub-1")))
    }

    @Test
    fun `blank claims count as missing`() {
        val provider = OidcAuditActorProvider(tokenIdentity("  ", "", "a@x.test", "sub-1"), ActorOverride())

        assertEquals("a@x.test", provider.currentActorDisplayName())
        assertEquals("a@x.test", provider.currentActor())
    }

    @Test
    fun `no usable claim yields system actor and no display name`() {
        val provider = OidcAuditActorProvider(tokenIdentity(null), ActorOverride())

        assertEquals("system", provider.currentActor())
        assertNull(provider.currentActorDisplayName())
    }
}
