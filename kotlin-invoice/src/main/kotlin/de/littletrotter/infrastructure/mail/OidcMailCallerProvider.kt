package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailCaller
import de.littletrotter.application.mail.MailCallerProvider
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.AppRoleProvider
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.inject.Inject
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Derives the forwarded mail identity from the logged-in user's OIDC session.
 *
 * Groups are translated by [AppRoleProvider] into this application's own role vocabulary, so the
 * mail component sees exactly the roles the invoicing UI itself acts on (`invoicing`, `admin`) —
 * the names its own role mapping recognises — and a directory group that is no application role is
 * never forwarded. The e-mail address comes from the `email` claim, because the principal name
 * (`preferred_username`) is a display name and must not be mistaken for a mailbox.
 *
 * Without a request context (background thread) or without a session (authentication switched off
 * for development) the caller is the system identity with every role, the same convention the audit
 * trail and the role provider already apply.
 */
@ApplicationScoped
class OidcMailCallerProvider @Inject constructor(
    private val securityIdentity: SecurityIdentity,
    private val appRoleProvider: AppRoleProvider,
) : MailCallerProvider {

    override fun currentCaller(): MailCaller {
        val roles = appRoleProvider.currentRoles()
        val groups = AppRole.entries.filter { it in roles }.map { it.claimName }
        return try {
            if (securityIdentity.isAnonymous) {
                MailCaller(SYSTEM_USER, groups, null)
            } else {
                MailCaller(securityIdentity.principal.name, groups, emailClaim())
            }
        } catch (expected: ContextNotActiveException) {
            MailCaller(SYSTEM_USER, groups, null)
        }
    }

    private fun emailClaim(): String? =
        (securityIdentity.principal as? JsonWebToken)
            ?.getClaim<Any?>(EMAIL_CLAIM)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val SYSTEM_USER = "system"
        const val EMAIL_CLAIM = "email"
    }
}
