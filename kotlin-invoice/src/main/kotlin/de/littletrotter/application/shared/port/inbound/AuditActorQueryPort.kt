package de.littletrotter.application.shared.port.inbound

interface AuditActorQueryPort {
    fun currentActor(): String

    /**
     * The stable identifier for `invoice_audit_journal`'s per-transaction `app.actor`
     * GUC: the OIDC `sub` claim, which survives a later username/display-name change, unlike
     * [currentActor] (the display name written into `created_by`/`updated_by` and the message
     * center — those columns are free-text "who did this, as it read at the time" and are
     * deliberately NOT switched to `sub`, an opaque id, here). Defaults to [currentActor] itself
     * so callers/mocks that only implement [currentActor] keep compiling and behave
     * identically outside the journal's GUC-setting path.
     */
    fun currentActorId(): String = currentActor()

    /**
     * The human-readable display name for the journal's `app.actor_name` GUC (OIDC
     * `preferred_username`/email). `null` when there is nothing more specific than
     * [currentActorId] itself (background jobs, dev bypass, no active identity).
     */
    fun currentActorDisplayName(): String? = null
}
