package de.littletrotter.infrastructure.auth

import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.AppRoleProvider
import io.quarkus.security.identity.SecurityIdentity
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * PO decision of 2026-09-07 — **the invoicing application is the authentication gate for the mail
 * component.** The component (`little-trotter-email`) has no identity provider of its own and is
 * not reachable from outside the machine; every `/mail/…` request is authorised here first.
 *
 * ## The contract
 *
 * `GET /internal/auth/verify` is Caddy's `forward_auth` target. It answers with a status code and
 * two headers, never with a body:
 *
 * - **`204 No Content`** — the caller's session is valid. `X-Lt-User` carries the authenticated
 *   user name, `X-Lt-Groups` the caller's groups as a comma-separated list in this application's
 *   own `invoicing`/`admin` vocabulary ([AppRole.claimName]), in a stable order. The proxy copies
 *   exactly those two headers onto the request it forwards to the component.
 * - **`401 Unauthorized`** — no valid session. **No identity header is set at all**, not an empty
 *   one: the component must never see a half-filled identity, and the proxy must not be able to
 *   copy one off a denied response.
 *
 * Anything other than `204` is a denial as far as the proxy is concerned; the endpoint itself only
 * ever produces the two cases above.
 *
 * ## Why this is the same session as the UI's
 *
 * The endpoint does not implement any session handling of its own. It reads the [SecurityIdentity]
 * that Quarkus's OIDC `web-app` mechanism establishes for the request — the very same mechanism,
 * tenant and session cookie every Vaadin route of this application is protected by
 * (`quarkus.oidc.*`, application.properties). The cookie is scoped to `/` (Quarkus's default
 * `quarkus.oidc.authentication.cookie-path`), so it is sent on this path too; that scoping is what
 * makes "already logged into the invoicing UI ⇒ authorised for the component, with no second
 * login" true, and it is pinned by `InternalAuthVerifySessionSharingTest` rather than assumed.
 *
 * The path carries a `permit` HTTP permission rule of its own (application.properties). That is
 * deliberate and load-bearing: under the global `authenticated` policy an unauthenticated request
 * would be answered with Quarkus's OIDC *challenge* — a `302` to Authentik — and `forward_auth`
 * would then bounce the user to a login that returns them to the invoicing application rather than
 * to `/mail/…`. Permitting the path lets the request through as anonymous so this class can answer
 * the `401` the proxy needs. Authorisation is not weakened by that: it moves from the HTTP policy
 * into [verify], which is the only reader of the identity here.
 *
 * ## Internal-only — and what happens if that ever stops being true
 *
 * `/internal/…` is refused from outside the machine by the reverse proxy, exactly as `/q/…`
 * already is (`docker/caddy/Caddyfile`; see the deployment chapter's mail section). It is reachable
 * only from the proxy itself, on `invoice-net`.
 *
 * The whole trust model of the mail component rests on that. The component performs no
 * authentication: it *trusts* `X-Lt-User`/`X-Lt-Groups` because only the edge can set them — the
 * edge strips whatever the client sent and replaces them with what this endpoint returned. If
 * `/internal/auth/verify` ever became reachable from outside, an attacker could not forge those
 * headers directly (the component is still only reachable through the proxy), but the endpoint
 * would become an oracle for probing session validity from the public internet, and any future
 * relaxation that let a client reach `/mail/…` without traversing the header-stripping edge would
 * turn "trust the headers" into "accept any claimed identity" — administrator included. There is
 * no second check behind this one. Keeping `/internal/…` internal is therefore not hardening in
 * depth, it is the single assumption the component's entire authorisation model is built on.
 *
 * ## With `AUTH_ENABLED=false`
 *
 * The `%dev`/`%test` bypass (`auth.enabled=false`, application.properties) disables the OIDC tenant
 * entirely — there is no login mechanism to have a session with, and the development stack has no
 * Authentik at all. In that mode this endpoint **always answers `204`**, handing out the fixed
 * identity `X-Lt-User: system` with `X-Lt-Groups: invoicing,admin` (every role). That keeps
 * `make dev` usable end to end — the mail component behind the same proxy stays reachable and sees
 * a caller with full rights — and it is the same two conventions the rest of the application
 * already applies under the bypass: `system` is what
 * [de.littletrotter.application.shared.OidcAuditActorProvider] stamps into audit fields there, and
 * "every role" is what [de.littletrotter.application.security.SecurityIdentityAppRoleProvider]
 * grants there. It is a dev bypass, never meant to reach a real deployment, and it is exactly as
 * safe (or not) as those two are — no more, no less.
 *
 * Unlike those two classes this one keys the bypass off `auth.enabled` explicitly instead of off
 * [SecurityIdentity.isAnonymous] alone: here the anonymous identity is *precisely* the caller that
 * must be rejected with a `401`, so "anonymous ⇒ grant everything" would invert the endpoint's
 * purpose in production. The switch has to be read, not inferred.
 */
@Path("/internal/auth/verify")
class InternalAuthVerifyResource @Inject constructor(
    private val securityIdentity: SecurityIdentity,
    private val appRoleProvider: AppRoleProvider,
    /** The application-wide authentication switch — `AUTH_ENABLED`, see the class KDoc's last section. */
    @param:ConfigProperty(name = "auth.enabled")
    private val authEnabled: Boolean,
) {

    /**
     * The `forward_auth` check. No request context handling and no exception funnel here on
     * purpose: this method only ever runs inside an HTTP request, so unlike the shared providers
     * ([AppRoleProvider], [de.littletrotter.application.shared.AuditActorProvider]) it has no
     * background-thread call site whose missing request scope would have to degrade to a default.
     */
    @GET
    fun verify(): Response =
        if (authEnabled && securityIdentity.isAnonymous) {
            // No identity headers on the denial path — see the contract table in the class KDoc.
            Response.status(Response.Status.UNAUTHORIZED).build()
        } else {
            Response.noContent()
                .header(USER_HEADER, userName())
                .header(GROUPS_HEADER, groupNames())
                .build()
        }

    /**
     * `preferred_username` — the same human-readable name
     * [de.littletrotter.application.shared.OidcAuditActorProvider] writes into audit fields
     * (`quarkus.oidc.token.principal-claim`, application.properties), so a mail journal entry and
     * an invoice audit row name the actor identically. Anonymous is only reachable under the
     * `auth.enabled=false` bypass; [verify] has already rejected it otherwise.
     */
    private fun userName(): String =
        if (securityIdentity.isAnonymous) DEV_BYPASS_USER else securityIdentity.principal.name

    /**
     * The caller's groups, translated into this application's own role vocabulary by
     * [AppRoleProvider] — so a group Authentik happens to carry that is not an application role is
     * dropped rather than forwarded, and the bypass's "every role" rule applies here unchanged.
     * Iterating [AppRole.entries] rather than the returned set makes the order stable
     * (`invoicing,admin`) regardless of set iteration order, which keeps the header assertable.
     *
     * A valid session whose user holds no application role yields an *empty* header value, not a
     * `401`: the session is genuinely valid and saying so is this endpoint's job — deciding what a
     * caller with no groups may do is the component's.
     */
    private fun groupNames(): String {
        val roles = appRoleProvider.currentRoles()
        return AppRole.entries.filter { it in roles }.joinToString(GROUP_SEPARATOR) { it.claimName }
    }

    companion object {
        /** The authenticated user name the edge copies onto the forwarded request. */
        const val USER_HEADER = "X-Lt-User"

        /** The caller's application groups, [GROUP_SEPARATOR]-separated, in [AppRole.entries] order. */
        const val GROUPS_HEADER = "X-Lt-Groups"

        /** Comma, no space — the component splits on it. */
        const val GROUP_SEPARATOR = ","

        /**
         * The identity handed out while `auth.enabled=false`; matches
         * [de.littletrotter.application.shared.OidcAuditActorProvider]'s dev-bypass actor.
         */
        const val DEV_BYPASS_USER = "system"

        /** The path this resource is mounted on — shared with the tests that pin its scoping. */
        const val VERIFY_PATH = "/internal/auth/verify"
    }
}
