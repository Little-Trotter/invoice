package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.UiTranslations

/**
 * The bar pinned to the bottom of the compose view: what state the mail is in on the left, discard
 * and send on the right. It announces blockers before the click; the send button itself stays
 * clickable so the click explains the problem instead of doing nothing.
 */
internal class MailComposeSendBar(discardButton: Button, sendButton: Button) : Div() {

    internal val dot = Span().apply { addClassName("lt-actionbar-sticky__dot") }
    internal val label = Span().apply { addClassName("lt-actionbar-sticky__label") }

    init {
        addClassName("lt-actionbar-sticky")
        add(
            Div(dot, label).apply { addClassName("lt-actionbar-sticky__state") },
            Div(discardButton, sendButton).apply { addClassName("lt-actionbar-sticky__actions") },
        )
    }

    /** Shows [state]; [unresolved] names the open placeholders and the attachment figures complete the calm states. */
    fun show(state: MailComposeState, unresolved: List<String>, attachmentCount: Int, attachmentBytes: Long) {
        dot.element.classList.removeAll(DOT_MODIFIERS)
        when (state) {
            MailComposeState.TEMPLATE -> Unit
            MailComposeState.EDITED -> dot.addClassName("lt-actionbar-sticky__dot--primary")
            else -> dot.addClassName("lt-actionbar-sticky__dot--warning")
        }
        label.text = when (state) {
            MailComposeState.TEMPLATE -> withAttachments(tr("mail.compose.state.template"), attachmentCount, attachmentBytes)
            MailComposeState.EDITED -> withAttachments(tr("mail.compose.state.edited"), attachmentCount, attachmentBytes)
            MailComposeState.UNRESOLVED ->
                tr("mail.compose.state.unresolved", unresolved.size, unresolved.joinToString(", ") { MailPlaceholderText.token(it) })
            MailComposeState.RECIPIENT -> tr("mail.compose.state.recipient")
            MailComposeState.SUBJECT -> tr("mail.compose.state.subject")
            MailComposeState.BODY -> tr("mail.compose.state.body")
        }
    }

    private fun withAttachments(text: String, count: Int, bytes: Long): String {
        if (count == 0) return text
        val size = DocumentSizeFormatter.formatCompact(DocumentSize(bytes))
        val key = if (count == 1) "mail.compose.state.attachments.one" else "mail.compose.state.attachments"
        return "$text · ${tr(key, count, size)}"
    }

    private fun tr(key: String, vararg params: Any): String = UiTranslations.tr(key, *params)
}

/** The state colours of the dot; the bar clears them all before it sets the one that applies. */
private val DOT_MODIFIERS = listOf(
    "lt-actionbar-sticky__dot--primary",
    "lt-actionbar-sticky__dot--warning",
)
