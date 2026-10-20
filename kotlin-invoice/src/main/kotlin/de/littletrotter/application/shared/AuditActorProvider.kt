package de.littletrotter.application.shared

import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.eclipse.microprofile.jwt.JsonWebToken
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort

/**
 * TS-2 — the CDI-active [AuditActorQueryPort]: derives the audit actor from the OIDC identity
 * Quarkus establishes for the request ([SecurityIdentity]). `quarkus.oidc.token.principal-claim`
 * (application.properties) is set to `preferred_username`, so [SecurityIdentity.principal]'s name
 * is the human-readable authentik username, not an opaque subject id — that is what ends up in
 * message-center rows, invoice audit columns, and everywhere else [AuditActorQueryPort] is used.
 *
 * Falls back to `"system"` when the identity is anonymous. That is not a login-optional feature —
 * every route requires authentication once `auth.enabled=true` (the compose/prod default, see
 * application.properties) — it only degrades this class to [SystemAuditActorProvider]'s old fixed
 * behavior for the dev bypass (`auth.enabled=false`, default for `%dev`/`%test`), where no
 * authentication mechanism ever runs and [SecurityIdentity] stays the container's anonymous
 * default.
 *
 * [securityIdentity] is `@RequestScoped` (Quarkus Security), a narrower scope than this bean's own
 * `@ApplicationScoped` — CDI allows the injection (a client proxy), but *invoking* it is only
 * valid while an HTTP request context is active. Several call sites go through this provider from
 * a background thread with no such context (e.g. `InvoiceLifecycleServiceTestBuilder`'s concurrent
 * scenarios, or a real NATS consumer/scheduled job) — [ContextNotActiveException] there is
 * expected, not a bug, and "system" is the correct actor for that non-request-driven work anyway
 * (there never was a logged-in user to attribute it to).
 */
@ApplicationScoped
class OidcAuditActorProvider @Inject constructor(
    private val securityIdentity: SecurityIdentity,
    private val actorOverride: ActorOverride,
) : AuditActorQueryPort {
    override fun currentActor(): String =
        actorOverride.current() ?: try {
            if (securityIdentity.isAnonymous) SYSTEM_ACTOR else resolveName() ?: SYSTEM_ACTOR
        } catch (expected: ContextNotActiveException) {
            SYSTEM_ACTOR
        }

    /**
     * The ID token's own `sub` claim, independent of `quarkus.oidc.token.principal-claim`
     * (which only changes what [SecurityIdentity.principal]'s [java.security.Principal.getName]
     * returns, here `preferred_username` — see [currentActor]'s own KDoc). Quarkus OIDC's
     * `web-app` code-flow principal ([io.quarkus.oidc.runtime.OidcJwtCallerPrincipal]) implements
     * [JsonWebToken], whose [JsonWebToken.getSubject] reads the claim directly regardless of that
     * setting. Falls back to [currentActor] (never a raw claim lookup) if the principal is ever
     * something else — every real login path here always yields a [JsonWebToken].
     */
    override fun currentActorId(): String =
        actorOverride.current() ?: try {
            if (securityIdentity.isAnonymous) {
                SYSTEM_ACTOR
            } else {
                (securityIdentity.principal as? JsonWebToken)?.subject ?: currentActor()
            }
        } catch (expected: ContextNotActiveException) {
            SYSTEM_ACTOR
        }

    override fun currentActorDisplayName(): String? =
        actorOverride.current() ?: try {
            if (securityIdentity.isAnonymous) null else resolveName()
        } catch (expected: ContextNotActiveException) {
            null
        }

    /**
     * Human-readable name of the signed-in user. The configured principal claim
     * (`preferred_username`) comes first; the identity provider does not always fill it, so the
     * `name` and `email` claims and finally the subject follow. Blank values count as missing —
     * otherwise the journal would show an empty actor or, via the database fallback, the opaque
     * subject although a readable claim exists.
     */
    private fun resolveName(): String? {
        val principal = securityIdentity.principal
        val token = principal as? JsonWebToken
        return listOf(
            principal.name,
            token?.getClaim<Any?>("name")?.toString(),
            token?.getClaim<Any?>("email")?.toString(),
            token?.subject,
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private companion object {
        /** Actor attributed to non-request-driven work (dev bypass, background threads) — see
         *  this class's own KDoc for why [ContextNotActiveException] falls back to it. */
        const val SYSTEM_ACTOR = "system"
    }
}

/**
 * Pre-TS-2 fixed actor. Kept only as a CDI [Alternative] — never active by default, so
 * [OidcAuditActorProvider] above is the sole bean CDI resolves for `AuditActorQueryPort` — because
 * several application services still default-parameter this class directly
 * (`auditActorProvider: AuditActorQueryPort = SystemAuditActorProvider()`, e.g.
 * [de.littletrotter.application.reference.NumberRangeService]): that default is only ever reached
 * by plain unit tests that construct the service outside CDI, never by production wiring (CDI
 * always supplies every constructor parameter explicitly, Kotlin defaults are inert there), so
 * rewriting every one of those call sites/tests would be pure churn for no behavior change.
 */
@Alternative
@ApplicationScoped
class SystemAuditActorProvider : AuditActorQueryPort {
    override fun currentActor(): String = "system"
}
