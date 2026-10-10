package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.QuoteCaller
import de.littletrotter.application.quote.QuoteCallerProvider
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.application.shared.ActorOverride
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.inject.Inject

/**
 * Derives the forwarded offer identity from the logged-in user's OIDC session.
 *
 * Groups are translated by [AppRoleProvider] into this application's own role vocabulary
 * (`quoting`, `invoicing`, `admin`), the names the offer component's own role mapping recognises;
 * a directory group that is no application role is never forwarded.
 *
 * Without a request context (background thread) or without a session (authentication switched off
 * for development) the caller is the system identity with every role, the same convention the audit
 * trail and the role provider already apply.
 */
@ApplicationScoped
class OidcQuoteCallerProvider @Inject constructor(
    private val securityIdentity: SecurityIdentity,
    private val appRoleProvider: AppRoleProvider,
    private val actorOverride: ActorOverride,
) : QuoteCallerProvider {

    override fun currentCaller(): QuoteCaller {
        actorOverride.current()?.let { return QuoteCaller(it, listOf(AppRole.QUOTING.claimName)) }
        val roles = appRoleProvider.currentRoles()
        val groups = AppRole.entries.filter { it in roles }.map { it.claimName }
        return try {
            if (securityIdentity.isAnonymous) {
                QuoteCaller(SYSTEM_USER, groups)
            } else {
                QuoteCaller(securityIdentity.principal.name, groups)
            }
        } catch (expected: ContextNotActiveException) {
            QuoteCaller(SYSTEM_USER, groups)
        }
    }

    private companion object {
        const val SYSTEM_USER = "system"
    }
}
