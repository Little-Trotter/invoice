package de.littletrotter.application.mail.port.inbound

/** Reads the draft a user opened in the compose view. */
fun interface MailComposeDraftQueryPort {

    /** The draft, or `null` when it is unknown, expired or already sent (the component answers all three alike). */
    fun findDraft(draftId: String): MailComposeDraft?
}
