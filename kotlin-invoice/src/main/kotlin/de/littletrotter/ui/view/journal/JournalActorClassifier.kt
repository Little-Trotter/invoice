package de.littletrotter.ui.view.journal

/**
 * Tells a real application actor from the connecting Postgres role a direct, outside-the-
 * application write is attributed to (`invoice_audit_journal.actor`'s own `session_user` fallback,
 * `V1__baseline_schema.sql` section 13). Derived from how
 * [de.littletrotter.application.shared.AuditActorProvider] actually populates the two GUCs
 * `capture_invoice_audit_event()` reads, not guessed:
 *
 * - [de.littletrotter.application.shared.AuditActorProvider.currentActorId] returns either the
 *   OIDC `sub` claim (an opaque id, never a Postgres role name) or the literal `"system"` — the
 *   fixed fallback for a background job / no active identity (OidcAuditActorProvider's own
 *   KDoc). Both are values the APPLICATION itself sets.
 * - A row whose `actor` is anything else was never touched by AuditGucInterceptor at all (a
 *   direct `psql`/admin-script write), so `actor` fell through to the trigger's own `session_user`
 *   default — the literal connecting DB role (`lt_app`, `lt_owner`, ...).
 *
 * Used together with [JournalActionLabels.isExternalWrite] (PO decision — action 0 together with a
 * DB-role actor is the "Direkter Datenbankeingriff" signal, never action 0 alone): a transaction
 * that never ran any `@SetsAuditGuc`-annotated method leaves BOTH `app.actor` and `app.audit_action`
 * unset, so in practice the two signals always agree — checking the actor here is the defense-in-
 * depth the spec asks for, not a second, independent way to reach the same conclusion.
 */
object JournalActorClassifier {

    /** The literal fallback [de.littletrotter.application.shared.AuditActorProvider.currentActorId]
     *  writes for a background job / no active identity — never a Postgres role name. */
    private const val SYSTEM_ACTOR = "system"

    fun isDatabaseRole(actor: String): Boolean = actor != SYSTEM_ACTOR
}
