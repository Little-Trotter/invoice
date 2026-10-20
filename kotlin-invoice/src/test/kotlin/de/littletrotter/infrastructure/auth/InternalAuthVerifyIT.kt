package de.littletrotter.infrastructure.auth

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.infrastructure.auth.InternalAuthVerifyResource.Companion.GROUPS_HEADER
import de.littletrotter.infrastructure.auth.InternalAuthVerifyResource.Companion.USER_HEADER
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * PO 2026-09-07 — [InternalAuthVerifyResource] over a **real HTTP request** against the booted
 * application, under the `%test` profile's `auth.enabled=false` bypass: the documented dev/test
 * mode, and the one `make dev` runs in.
 *
 * What this adds over [InternalAuthVerifyResourceTest] (which covers the same branches against
 * mocks) is everything the resource cannot prove about itself: that the route is registered at
 * `/internal/auth/verify` at all, that the HTTP permission rule lets an unauthenticated request
 * reach it instead of answering with a redirect or a 403, and that the headers survive onto a
 * `204 No Content` response — Caddy's `forward_auth` copies them off exactly this response.
 *
 * The JDK `HttpClient` is used deliberately (no new test dependency, same choice this module's
 * outbound adapters and their tests already make) and it sends no cookie and no credential, which
 * is precisely the shape of the `forward_auth` subrequest for a caller with no session.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InternalAuthVerifyIT {

    @field:TestHTTPResource("/internal/auth/verify")
    lateinit var verifyUrl: URL

    private fun get(): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(verifyUrl.toString())).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `with AUTH_ENABLED false an unauthenticated request is answered 204 as system with every group`() {
        val response = get()

        assertEquals(204, response.statusCode(), "the dev stack must stay usable: the mail component behind the same proxy has to be reachable")
        assertEquals("system", response.headers().firstValue(USER_HEADER).orElse(null))
        assertEquals("invoicing,admin,quoting", response.headers().firstValue(GROUPS_HEADER).orElse(null))
        assertEquals("", response.body(), "forward_auth reads a status and two headers, never a body")
    }

    @Test
    @TestSecurity(user = "alice", roles = ["invoicing"])
    fun `an identity established for the request is reported over the wire, headers and all`() {
        // Proves the wire format end to end: the resource reads whatever SecurityIdentity the
        // request carries — in production the OIDC session identity, here @TestSecurity's — and
        // the two headers arrive intact on a 204.
        val response = get()

        assertEquals(204, response.statusCode())
        assertEquals("alice", response.headers().firstValue(USER_HEADER).orElse(null))
        assertEquals("invoicing", response.headers().firstValue(GROUPS_HEADER).orElse(null))
    }
}
