package de.littletrotter.shared.edge

import io.quarkus.security.identity.IdentityProviderManager
import io.vertx.core.MultiMap
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HTTP_UNAUTHORIZED = 401

/**
 * The mechanism that turns the trusted edge's headers into a `SecurityIdentity`: no identity in both
 * the absent and the malformed case, so everything that needs one refuses, and a challenge that is a
 * plain 401 because there is no identity provider to redirect anyone to.
 *
 * The parsing table itself is [EdgeIdentityReaderTest]'s job.
 */
class EdgeIdentityMechanismTest {

    private val mechanism = object : EdgeIdentityMechanism() {}

    private fun requestWith(vararg headers: Pair<String, String>): RoutingContext {
        val multiMap = MultiMap.caseInsensitiveMultiMap()
        headers.forEach { (name, value) -> multiMap.add(name, value) }
        val httpRequest: HttpServerRequest = mock()
        whenever(httpRequest.headers()).thenReturn(multiMap)
        val context: RoutingContext = mock()
        whenever(context.request()).thenReturn(httpRequest)
        return context
    }

    private fun authenticate(context: RoutingContext, target: EdgeIdentityMechanism = mechanism) =
        target.authenticate(context, mock<IdentityProviderManager>()).await().indefinitely()

    @Test
    fun `the edge's headers become an identity whose principal is the user and whose roles are the groups`() {
        val identity = authenticate(
            requestWith(EdgeIdentityHeaders.USER to "anna", EdgeIdentityHeaders.GROUPS to "invoicing,everyone"),
        )!!

        assertEquals("anna", identity.principal.name)
        assertEquals(setOf("invoicing", "everyone"), identity.roles)
        assertEquals(false, identity.isAnonymous)
    }

    @Test
    fun `a header name is matched case-insensitively, because HTTP header names are`() {
        val identity = authenticate(requestWith("x-lt-user" to "olaf", "X-LT-GROUPS" to "admin"))!!

        assertEquals("olaf", identity.principal.name)
        assertEquals(setOf("admin"), identity.roles)
    }

    @Test
    fun `an application role mapping decides which groups become roles`() {
        val onlyAdmin = object : EdgeIdentityMechanism(EdgeRoleMapping { groups -> groups.filter { it == "admin" }.toSet() }) {}

        val identity = authenticate(
            requestWith(EdgeIdentityHeaders.USER to "anna", EdgeIdentityHeaders.GROUPS to "admin,everyone"),
            onlyAdmin,
        )!!

        assertEquals(setOf("admin"), identity.roles)
    }

    @Test
    fun `no headers at all yields no identity — this is what makes the component fail closed`() {
        assertNull(authenticate(requestWith()))
    }

    @Test
    fun `a user header without groups is an identity with no roles`() {
        val identity = authenticate(requestWith(EdgeIdentityHeaders.USER to "anna"))!!

        assertTrue(identity.roles.isEmpty())
    }

    @Test
    fun `a groups header without a user yields no identity`() {
        assertNull(authenticate(requestWith(EdgeIdentityHeaders.GROUPS to "admin")))
    }

    @Test
    fun `a blank user yields no identity`() {
        assertNull(authenticate(requestWith(EdgeIdentityHeaders.USER to "  ", EdgeIdentityHeaders.GROUPS to "admin")))
    }

    @Test
    fun `an oversized user yields no identity`() {
        val user = "a".repeat(EdgeIdentityHeaders.MAX_USER_LENGTH + 1)

        assertNull(authenticate(requestWith(EdgeIdentityHeaders.USER to user, EdgeIdentityHeaders.GROUPS to "admin")))
    }

    @Test
    fun `an oversized groups header yields no identity`() {
        val groups = "g".repeat(EdgeIdentityHeaders.MAX_GROUPS_LENGTH + 1)

        assertNull(authenticate(requestWith(EdgeIdentityHeaders.USER to "anna", EdgeIdentityHeaders.GROUPS to groups)))
    }

    @Test
    fun `a malformed header yields no identity either, rather than a repaired one`() {
        // A duplicated header is the shape a forgery takes; the mechanism does not pick a winner
        // between the proxy's value and the caller's.
        val forged = requestWith(
            EdgeIdentityHeaders.USER to "anna",
            EdgeIdentityHeaders.USER to "mallory",
            EdgeIdentityHeaders.GROUPS to "admin",
        )

        assertNull(authenticate(forged))
    }

    @Test
    fun `a control character in a group yields no identity`() {
        val request = requestWith(EdgeIdentityHeaders.USER to "anna", EdgeIdentityHeaders.GROUPS to "admin\u0007")

        assertNull(authenticate(request))
    }

    @Test
    fun `the outcome-to-identity mapping is total and refuses everything that is not Accepted`() {
        assertNull(mechanism.identityFrom(EdgeIdentityOutcome.Absent))
        assertNull(mechanism.identityFrom(EdgeIdentityOutcome.Rejected("whatever the edge sent was not valid")))
        assertTrue(mechanism.identityFrom(EdgeIdentityOutcome.Accepted(EdgeIdentity("anna", listOf("admin")))) != null)
    }

    @Test
    fun `the challenge is a bare 401 — there is no identity provider to redirect to`() {
        val challenge = mechanism.getChallenge(requestWith()).await().indefinitely()

        assertEquals(HTTP_UNAUTHORIZED, challenge.status)
    }

    @Test
    fun `the mechanism needs no IdentityProvider, because it resolves the identity itself`() {
        assertTrue(mechanism.getCredentialTypes().isEmpty())
    }

    @Test
    fun `the default role mapping keeps the groups as sent`() {
        assertEquals(setOf("a", "b"), GroupsAsRoles.rolesOf(listOf("a", "b", "a")))
    }
}
