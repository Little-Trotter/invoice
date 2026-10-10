package de.littletrotter.application.security

/**
 * TS-8 — the application roles, sourced from the Authentik `groups` claim
 * (`quarkus.oidc.roles.role-claim-path=groups`, TS-2's application.properties). [claimName] is the
 * literal Authentik group name the OIDC `groups` claim carries — it matches
 * `docker/authentik/blueprints/little-trotter-invoice.yaml`'s `authentik_core.group` names
 * exactly (lowercase, no per-permission groups: Authentik only carries roles, this enum owns the
 * permission breakdown). A user may hold both roles; permissions are additive.
 */
enum class AppRole(val claimName: String, val permissions: Set<AppPermission>) {
    INVOICING(
        claimName = "invoicing",
        permissions = setOf(
            AppPermission.INVOICE_FULL_EDIT,
            AppPermission.INVOICE_JOBS,
            AppPermission.RECEIPTS_AND_STATISTICS,
            AppPermission.MASTER_DATA_EDIT,
            AppPermission.ARTICLES_EDIT,
            AppPermission.DOCUMENT_REPRODUCE,
            AppPermission.MAIL_COMPOSE_AND_SEND,
            AppPermission.MAIL_JOURNAL_VIEW,
        ),
    ),
    ADMIN(
        claimName = "admin",
        permissions = setOf(
            AppPermission.REFERENCE_DATA_EDIT,
            AppPermission.SYSTEM_VIEW,
            AppPermission.DATABASE_MAINTENANCE,
            AppPermission.DOCUMENT_REPRODUCE,
            AppPermission.MAIL_TEMPLATE_EDIT,
            AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN,
            AppPermission.MAIL_JOURNAL_VIEW,
            AppPermission.QUOTE_VIEW,
            AppPermission.QUOTE_SETTINGS_EDIT,
            AppPermission.QUOTE_LIBRARY_ARCHIVE,
        ),
    ),
    /**
     * Offer module group: working with offers. The offer app is a separate component that
     * authorises on the same Authentik group; these permissions gate this application's own
     * offer views, which call it.
     */
    QUOTING(
        claimName = "quoting",
        permissions = setOf(
            AppPermission.QUOTE_VIEW,
            AppPermission.QUOTE_EDIT,
            AppPermission.QUOTE_SEND,
            AppPermission.QUOTE_LIBRARY_EDIT,
            AppPermission.QUOTE_LIBRARY_ARCHIVE,
        ),
    ),
    ;

    companion object {
        /** Maps a raw OIDC role/group string to its [AppRole] (case-insensitive — Authentik group
         *  names are stable, but this avoids depending on exact claim casing), or `null` for
         *  anything else Authentik might send (unrelated groups simply aren't application roles). */
        fun fromClaim(raw: String): AppRole? = entries.firstOrNull { it.claimName.equals(raw, ignoreCase = true) }
    }
}

/** The union of every [AppPermission] granted by any role in this set — the additive rule from
 *  TS-8's role model (a user with both roles gets the sum of both permission sets). */
fun Set<AppRole>.permissions(): Set<AppPermission> = flatMapTo(mutableSetOf()) { it.permissions }
