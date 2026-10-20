package de.littletrotter.infrastructure.auth

import de.littletrotter.infrastructure.auth.InternalAuthVerifyResource.Companion.VERIFY_PATH
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * PO 2026-09-07 — pins the two configuration facts [InternalAuthVerifyResource]'s contract rests
 * on, which cannot be observed from the resource's own code and must therefore not be *assumed*:
 *
 *  1. **The OIDC session cookie is actually sent to `/internal/auth/verify`.** "A user already
 *     logged into the invoicing UI is authorised for the mail component without a second login" is
 *     only true if the browser attaches the same session cookie to the `forward_auth` subrequest.
 *     A cookie is scoped by *path*: Quarkus's `quarkus.oidc.authentication.cookie-path` defaults to
 *     `/`, which covers this path — but any override (say `/ui`) would silently make every
 *     `/mail/…` request unauthenticated for a fully logged-in user, with nothing in the resource,
 *     its tests or the proxy to show why. This test fails the build if such an override ever
 *     appears, in any profile.
 *  2. **The path is permitted at HTTP level, so the resource can answer `401` itself.** Under the
 *     global `authenticated` policy Quarkus would answer an unauthenticated request with the OIDC
 *     challenge — a `302` to Authentik — and `forward_auth` would send the user to a login that
 *     returns them to the invoicing application instead of to `/mail/…`.
 *
 * Read from the packaged `application.properties` on the test classpath rather than from a booted
 * Quarkus config, so the assertion is about what the artefact ships, independent of any test
 * profile's overrides — the same "no database, no container, runs in every build" shape as
 * [de.littletrotter.infrastructure.persistence.MigrationDirectoryHygieneTest].
 */
class InternalAuthVerifySessionSharingTest {

    private val properties: Properties = Properties().also { props ->
        val stream = checkNotNull(javaClass.getResourceAsStream("/application.properties")) {
            "application.properties must be on the test classpath"
        }
        stream.use { props.load(it) }
    }

    @Test
    fun `the OIDC session cookie is scoped to a path that covers the verify endpoint`() {
        val cookiePaths = properties.stringPropertyNames()
            .filter { it.endsWith("quarkus.oidc.authentication.cookie-path") }
            .map { it to properties.getProperty(it) }

        cookiePaths.forEach { (key, path) ->
            assertTrue(
                VERIFY_PATH.startsWith(path),
                "$key='$path' does not cover $VERIFY_PATH — the UI's session cookie would not be " +
                    "sent to the forward_auth endpoint, and every /mail/… request would look " +
                    "unauthenticated to a fully logged-in user",
            )
        }
        // Nothing to assert away when the key is absent: Quarkus's own default is "/", which
        // covers every path. The loop above exists for the day someone adds an override.
    }

    @Test
    fun `the verify endpoint is permitted at HTTP level so it answers 401 instead of an OIDC redirect`() {
        assertEquals(VERIFY_PATH, properties.getProperty("quarkus.http.auth.permission.internal-auth.paths"))
        assertEquals("permit", properties.getProperty("quarkus.http.auth.permission.internal-auth.policy"))
    }

    @Test
    fun `the session the endpoint reads is the code-flow session of the application's own UI`() {
        // web-app (not `service`) is what makes the identity come from a session cookie at all;
        // and the tenant follows the very same auth.enabled switch the resource reads, so
        // "authentication is on" cannot mean one thing for the UI and another here.
        assertEquals("web-app", properties.getProperty("quarkus.oidc.application-type"))
        assertEquals("\${auth.enabled}", properties.getProperty("quarkus.oidc.tenant-enabled"))
        assertEquals("groups", properties.getProperty("quarkus.oidc.roles.role-claim-path"))
        assertEquals("preferred_username", properties.getProperty("quarkus.oidc.token.principal-claim"))
    }
}
