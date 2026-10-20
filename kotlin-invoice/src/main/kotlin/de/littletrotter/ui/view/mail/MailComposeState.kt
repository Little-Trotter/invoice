package de.littletrotter.ui.view.mail

/** What the send bar looks at: the form values and the placeholder names that have no value. */
internal data class MailComposeFields(
    val recipientValid: Boolean,
    val subject: String,
    val body: String,
    val renderedBody: String,
    val unresolved: List<String>,
)

/** How the send bar describes the mail in the compose view; the first matching blocker wins. */
internal enum class MailComposeState {
    /** Text still equals the rendered template. */
    TEMPLATE,

    /** The user changed the text and every placeholder can be filled. */
    EDITED,

    /** At least one placeholder has no value. */
    UNRESOLVED,

    /** The recipient address is missing or malformed. */
    RECIPIENT,

    /** The subject is blank. */
    SUBJECT,

    /** The message is blank. */
    BODY,
    ;

    /** Whether the state names something the user has to fix before the send can succeed. */
    val isBlocker: Boolean get() = this != TEMPLATE && this != EDITED

    companion object {
        /** Mirrors the checks of the send action in the same order, so the bar announces exactly the error a click would show. */
        fun of(fields: MailComposeFields): MailComposeState =
            when {
                !fields.recipientValid -> RECIPIENT
                fields.subject.isBlank() -> SUBJECT
                fields.body.isBlank() -> BODY
                fields.unresolved.isNotEmpty() -> UNRESOLVED
                fields.body != fields.renderedBody -> EDITED
                else -> TEMPLATE
            }
    }
}
