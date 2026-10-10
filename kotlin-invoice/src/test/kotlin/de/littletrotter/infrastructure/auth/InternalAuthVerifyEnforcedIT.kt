package de.littletrotter.infrastructure.auth

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.infrastructure.auth.InternalAuthVerifyResource.Companion.GROUPS_HEADER
import de.littletrotter.infrastructure.auth.InternalAuthVerifyResource.Companion.USER_HEADER
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * PO 2026-09-07 — the production posture of [InternalAuthVerifyResource]: `auth.enabled=true`, so
 * the dev bypass is off and a request without a session must be refused. This is the mode Caddy's
 * `forward_auth` actually runs against, and the only one in which the `401` branch exists at all.
 *
 * The profile turns the switch on **without** enabling the OIDC tenant. Those two are wired
 * together in `application.properties`, where `quarkus.oidc.tenant-enabled` simply interpolates
 * `auth.enabled`, because a real deployment wants exactly that; a test cannot follow it, since an
 * enabled tenant performs discovery against an Authentik no test stack runs. Splitting them here
 * is what makes the
 * production branch reachable in a build with no identity provider — the identity itself is then
 * supplied by `@TestSecurity`, which installs a non-anonymous [io.quarkus.security.identity
 * .SecurityIdentity] independently of any tenant, exactly as
 * [de.littletrotter.application.security.ServiceLayerPermissionIT] already does for the role gate.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(EnforcedAuthProfile::class)
class InternalAuthVerifyEnforcedIT {

    @field:TestHTTPResource("/internal/auth/verify")
    lateinit var verifyUrl: URL

    private fun get(): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(verifyUrl.toString())).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `a request with no session is refused with 401 and carries no identity headers at all`() {
        // No cookie, no credential — the forward_auth subrequest of a caller who never logged in.
        val response = get()

        assertEquals(401, response.statusCode())
        assertFalse(
            response.headers().firstValue(USER_HEADER).isPresent,
            "a denied response must not carry an identity the proxy could copy onto the forwarded request",
        )
        assertFalse(response.headers().firstValue(GROUPS_HEADER).isPresent, "same for the groups header")
    }

    @Test
    @TestSecurity(user = "alice", roles = ["invoicing"])
    fun `a valid session is answered 204 with the user name and its single group`() {
        val response = get()

        assertEquals(204, response.statusCode())
        assertEquals("alice", response.headers().firstValue(USER_HEADER).orElse(null))
        assertEquals("invoicing", response.headers().firstValue(GROUPS_HEADER).orElse(null))
    }

    @Test
    @TestSecurity(user = "root", roles = ["admin", "invoicing"])
    fun `group membership is reflected faithfully for a caller holding both roles`() {
        val response = get()

        assertEquals(204, response.statusCode())
        assertEquals("root", response.headers().firstValue(USER_HEADER).orElse(null))
        assertEquals("invoicing,admin", response.headers().firstValue(GROUPS_HEADER).orElse(null))
    }

    @Test
    @TestSecurity(user = "guest", roles = ["some-unrelated-authentik-group"])
    fun `an Authentik group that is not an application role is not forwarded as one`() {
        // Still a valid session, so still a 204 — but the component must not be told the caller is
        // in a group this application does not know. `orElse("")` because an empty header value is
        // free to be dropped on the wire; what matters is that no role name leaked.
        val response = get()

        assertEquals(204, response.statusCode())
        assertEquals("guest", response.headers().firstValue(USER_HEADER).orElse(null))
        assertEquals("", response.headers().firstValue(GROUPS_HEADER).orElse(""))
    }
}

/**
 * Authentication on, OIDC tenant off — see the class KDoc above for why those two have to be
 * separated here. Declared top-level for the same reason as `ComplianceBlockStageProfile` in
 * `B2bInvoiceE2EIT`: ArchUnit's test engine trips over nested Kotlin `object`s without a public
 * constructor.
 */
class EnforcedAuthProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "auth.enabled" to "true",
        "quarkus.oidc.tenant-enabled" to "false",
    )
}
