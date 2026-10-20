package de.littletrotter.shared.edge

import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.AuthenticationRequest
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpHeaders
import io.vertx.ext.web.RoutingContext
import org.jboss.logging.Logger

private const val HTTP_UNAUTHORIZED = 401

/**
 * The one place where an HTTP request becomes a [SecurityIdentity] behind the trusted edge. It reads
 * the two headers the edge sets after the application has validated the session
 * ([EdgeIdentityHeaders]), has [EdgeIdentityReader] validate them, and turns an accepted result into
 * an identity whose roles are chosen by the [EdgeRoleMapping]. It decides nothing about
 * authorization itself.
 *
 * **Read [EdgeIdentity]'s KDoc before changing anything here.** It carries the trust boundary: the
 * headers are believed only because nothing but the edge can reach the process.
 *
 * ## Absent and malformed both end in "no identity", and that is the point
 *
 * [authenticate] never fails a request outright. An absent header yields no identity; a malformed
 * one yields no identity either and is logged as the security event it is, with its reason and never
 * with the value. Everything that needs an identity then refuses, while surfaces that deliberately
 * need none keep working. Failing the request instead would let a stray header on the edge break
 * embedded pages in a way the user cannot act on and the operator cannot see.
 *
 * ## The challenge is 401 and can be nothing else
 *
 * There is no identity provider to redirect to; a request without the headers has reached the
 * application in a way the deployment does not intend.
 *
 * ## Extension point
 *
 * An application that must check an additional credential before believing the headers overrides
 * [outcomeOf]; the default is the plain header reading. Whatever it returns still goes through the
 * same fail-closed mapping to an identity.
 */
abstract class EdgeIdentityMechanism(private val roleMapping: EdgeRoleMapping = GroupsAsRoles) :
    HttpAuthenticationMechanism {

    private val log = Logger.getLogger(EdgeIdentityMechanism::class.java)

    /**
     * @param user all values of `X-Lt-User`
     * @param groups all values of `X-Lt-Groups`
     * @param authorization all values of `Authorization`, for subclasses that gate on a credential
     */
    protected open fun outcomeOf(
        user: List<String>,
        groups: List<String>,
        authorization: List<String>,
    ): EdgeIdentityOutcome = EdgeIdentityReader.read(user, groups)

    override fun authenticate(context: RoutingContext, identityProviderManager: IdentityProviderManager): Uni<SecurityIdentity> {
        val headers = context.request().headers()
        val outcome = outcomeOf(
            headers.getAll(EdgeIdentityHeaders.USER),
            headers.getAll(EdgeIdentityHeaders.GROUPS),
            headers.getAll(HttpHeaders.AUTHORIZATION.toString()),
        )
        return Uni.createFrom().item(identityFrom(outcome))
    }

    /**
     * Public so the whole decision is unit-testable without a Vert.x request: the mapping from
     * outcome to identity is the behaviour, the header extraction above is framework plumbing.
     */
    fun identityFrom(outcome: EdgeIdentityOutcome): SecurityIdentity? = when (outcome) {
        is EdgeIdentityOutcome.Accepted -> build(outcome.identity)
        EdgeIdentityOutcome.Absent -> null
        is EdgeIdentityOutcome.Rejected -> {
            // The reason names only the header. The value is attacker-controlled and is never written
            // anywhere: an unvalidated string in a log is an injection into whatever reads the log.
            log.warnf("Refusing a trusted-edge identity: %s", outcome.reason)
            null
        }
    }

    private fun build(identity: EdgeIdentity): SecurityIdentity =
        QuarkusSecurityIdentity.builder()
            .setPrincipal { identity.user }
            .addRoles(roleMapping.rolesOf(identity.groups))
            .build()

    /** No credential type: this mechanism resolves the identity itself and needs no IdentityProvider. */
    override fun getCredentialTypes(): Set<Class<out AuthenticationRequest>> = emptySet()

    override fun getChallenge(context: RoutingContext): Uni<ChallengeData> =
        Uni.createFrom().item(ChallengeData(HTTP_UNAUTHORIZED))
}
