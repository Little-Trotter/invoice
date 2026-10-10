package de.littletrotter.infrastructure.auth

import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.infrastructure.auth.InternalAuthVerifyResource.Companion.GROUPS_HEADER
import de.littletrotter.infrastructure.auth.InternalAuthVerifyResource.Companion.USER_HEADER
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.Principal

/**
 * PO 2026-09-07 — [InternalAuthVerifyResource] against a mocked [SecurityIdentity]/[AppRoleProvider]:
 * no CDI container, no Authentik, no HTTP. Covers every branch of the response contract; the same
 * contract is proven over a real HTTP request in [InternalAuthVerifyIT] (bypass mode) and
 * [InternalAuthVerifyEnforcedIT] (authentication enforced).
 *
 * The role provider is mocked rather than driven through the real
 * [de.littletrotter.application.security.SecurityIdentityAppRoleProvider], because what is under
 * test here is the *header* the resource builds from a role set — the mapping from OIDC claims to
 * [AppRole] is that class's own contract and has its own test.
 */
class InternalAuthVerifyResourceTest {

    private fun identity(anonymous: Boolean, name: String? = null): SecurityIdentity {
        // The principal is built and stubbed BEFORE it is handed to thenReturn: creating a mock
        // inside an unfinished stubbing chain is what Mockito rejects as UnfinishedStubbingException.
        val principal: Principal? = name?.let { value ->
            val mocked: Principal = mock()
            whenever(mocked.name).thenReturn(value)
            mocked
        }
        val identity: SecurityIdentity = mock()
        whenever(identity.isAnonymous).thenReturn(anonymous)
        if (principal != null) {
            whenever(identity.principal).thenReturn(principal)
        }
        return identity
    }

    private fun roleProvider(vararg roles: AppRole): AppRoleProvider = mock<AppRoleProvider>().also {
        whenever(it.currentRoles()).thenReturn(roles.toSet())
    }

    private fun resource(identity: SecurityIdentity, roles: AppRoleProvider, authEnabled: Boolean = true) =
        InternalAuthVerifyResource(identity, roles, authEnabled)

    // ── Authentication enforced (the production mode) ───────────────────────────────────────────

    @Test
    fun `a valid session answers 204 with the authenticated user name and its groups`() {
        val response = resource(identity(anonymous = false, name = "alice"), roleProvider(AppRole.INVOICING)).verify()

        assertEquals(Response.Status.NO_CONTENT.statusCode, response.status)
        assertEquals("alice", response.getHeaderString(USER_HEADER))
        assertEquals("invoicing", response.getHeaderString(GROUPS_HEADER))
    }

    @Test
    fun `quoting is reflected after admin in the stable order`() {
        val response = resource(identity(anonymous = false, name = "alice"), roleProvider(AppRole.QUOTING, AppRole.ADMIN, AppRole.INVOICING)).verify()

        assertEquals("invoicing,admin,quoting", response.getHeaderString(GROUPS_HEADER))
    }

    @Test
    fun `both roles are reflected faithfully, in a stable order`() {
        // Passed in reverse declaration order on purpose: the header must not depend on how the
        // provider's set happens to iterate.
        val response = resource(identity(anonymous = false, name = "alice"), roleProvider(AppRole.ADMIN, AppRole.INVOICING)).verify()

        assertEquals("invoicing,admin", response.getHeaderString(GROUPS_HEADER))
    }

    @Test
    fun `an admin-only caller gets exactly the admin group`() {
        val response = resource(identity(anonymous = false, name = "root"), roleProvider(AppRole.ADMIN)).verify()

        assertEquals(Response.Status.NO_CONTENT.statusCode, response.status)
        assertEquals("root", response.getHeaderString(USER_HEADER))
        assertEquals("admin", response.getHeaderString(GROUPS_HEADER))
    }

    @Test
    fun `a valid session whose user holds no application role stays a 204 with an empty group list`() {
        // An Authentik user in unrelated groups only: the session IS valid, so saying "authorised"
        // is correct here; what a caller with no groups may do is the component's decision.
        val response = resource(identity(anonymous = false, name = "guest"), roleProvider()).verify()

        assertEquals(Response.Status.NO_CONTENT.statusCode, response.status)
        assertEquals("guest", response.getHeaderString(USER_HEADER))
        assertEquals("", response.getHeaderString(GROUPS_HEADER))
    }

    @Test
    fun `no or invalid session answers 401 and sets NO identity headers`() {
        // "No valid session" is exactly the anonymous identity: Quarkus OIDC leaves the request
        // anonymous when the session cookie is absent, and clears it when it cannot be validated.
        val response = resource(identity(anonymous = true), roleProvider(AppRole.ADMIN, AppRole.INVOICING)).verify()

        assertEquals(Response.Status.UNAUTHORIZED.statusCode, response.status)
        assertFalse(response.headers.containsKey(USER_HEADER), "a denied request must not carry an identity the proxy could copy")
        assertFalse(response.headers.containsKey(GROUPS_HEADER), "a denied request must not carry groups the proxy could copy")
    }

    // ── AUTH_ENABLED=false — the documented dev/test bypass ─────────────────────────────────────

    @Test
    fun `with authentication disabled the anonymous caller is answered 204 as system with every group`() {
        // The OIDC tenant is off entirely in that mode, so the identity is always anonymous — the
        // very case that is a 401 above. The KDoc states this handout; this pins it.
        val response = resource(
            identity(anonymous = true),
            roleProvider(AppRole.INVOICING, AppRole.ADMIN), // SecurityIdentityAppRoleProvider's own bypass
            authEnabled = false,
        ).verify()

        assertEquals(Response.Status.NO_CONTENT.statusCode, response.status)
        assertEquals("system", response.getHeaderString(USER_HEADER))
        assertEquals(InternalAuthVerifyResource.DEV_BYPASS_USER, response.getHeaderString(USER_HEADER), "the literal above is the constant's value, not a second convention")
        assertEquals("invoicing,admin", response.getHeaderString(GROUPS_HEADER))
    }

    @Test
    fun `with authentication disabled a non-anonymous identity still reports its own name`() {
        // Not reachable through the OIDC tenant (it is disabled with the switch), but reachable in
        // tests via @TestSecurity — which is exactly how InternalAuthVerifyIT drives this class.
        val response = resource(identity(anonymous = false, name = "alice"), roleProvider(AppRole.INVOICING), authEnabled = false).verify()

        assertEquals(Response.Status.NO_CONTENT.statusCode, response.status)
        assertEquals("alice", response.getHeaderString(USER_HEADER))
    }
}
