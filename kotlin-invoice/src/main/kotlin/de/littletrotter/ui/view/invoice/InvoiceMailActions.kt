package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.mail.port.inbound.ComposeInvoiceMailUseCase
import de.littletrotter.application.dto.MailComposeResult
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.InvoiceMailReadinessQueryPort
import de.littletrotter.application.mail.port.inbound.MailSendGate
import de.littletrotter.application.settings.port.inbound.FeatureSettingsQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.mail.MailComposeView
import de.littletrotter.ui.view.mail.MailJournalView
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.jboss.logging.Logger

/**
 * The invoice detail view's mail-related pieces: the send history line and the
 * hand-over into the mail draft ([openMailCompose]), extracted into a `@Dependent` CDI
 * bean of its own, injected once into [InvoiceCockpitHero]. Not itself a [Component] — [buildHistory] takes
 * [host] purely as translation/dialog-attach context, the same idiom [InvoiceStatusActions]
 * already uses.
 *
 * Strictly optional. [mailDispatch] is `null` in every component test that does not consider
 * mail, and its `isAvailable()` is `false` on an instance without the component — in both cases
 * no mail hint is rendered. There is deliberately no greyed-out history: an instance without the
 * mail component must not carry a hint that it exists (concept §11).
 */
@Dependent
class InvoiceMailActions
@Inject
constructor(
    private val mailDispatch: ComposeInvoiceMailUseCase?,
    private val featureSettingsGate: FeatureSettingsQueryPort?,
    private val mailReadiness: InvoiceMailReadinessQueryPort? = null,
) {
    internal val log = Logger.getLogger(javaClass)

    /** The send button rendered last for this view. A cockpit rebuild during a handover replaces the
     *  clicked instance; the outcome is applied to whichever instance is attached by then. */
    private var sendButton: SendButtonSlot? = null

    /** Id of the invoice whose handover is running. A send button rebuilt while it runs shows the same
     *  in-flight state instead of offering a second click that would create a second draft. */
    private var composeInFlightFor: Long? = null

    /** SFS — the mail feature flag only. Whether the button/history render at all already runs
     *  through [ComposeInvoiceMailUseCase.isAvailable], which ANDs this flag with the
     *  deployment state; consulted here only for the compose-click guard's German error text. */
    private val emailFlagEnabled: Boolean
        get() = featureSettingsGate?.isEnabled(FeatureFlag.EMAIL_SEND) ?: true

    private fun tr(host: Component, key: String, vararg params: Any): String = UiTranslations.tr(host, key, *params)

    /**
     * Whether a mail dispatch adapter is deployed and enabled. Decides if an invoice in review is
     * sent by the "E-Mail senden" action (which continues into the mail draft) or by the plain
     * "Versenden" action; a `null` [mailDispatch] (component tests, instances without the mail
     * component) counts as unavailable.
     */
    fun isMailAvailable(): Boolean = mailDispatch?.isAvailable() == true

    /**
     * The readiness gate the send button of [current] renders with; `null` when no send button is
     * offered at all (invoice not issued, or no mail component). The cockpit hero uses it to decide
     * whether a rebuild would change anything.
     */
    internal fun sendGate(current: InvoiceDetailDto): MailSendGate? {
        if (current.statusEnum() != InvoiceStatus.ISSUED || !isMailAvailable()) return null
        return mailReadiness?.readiness(current.id) ?: MailSendGate.READY
    }

    /**
     * The explicit second step after finalizing: "E-Mail senden" (primary) or, when the local
     * projection already records a dispatch, "Erneut senden" (secondary). `null` unless the invoice
     * is issued and the mail component is available. Enabled only when the readiness gate is
     * [MailSendGate.READY]; otherwise the button stays visible but disabled, with the tooltip of the
     * first blocker. A click shows its progress at once (disabled, "wird vorbereitet") and either
     * navigates to the draft or restores the button after reporting the error.
     *
     * The handover is bound to the invoice, not to this button instance: the cockpit is rebuilt on
     * every push and poll tick, so the clicked button may be gone when the outcome arrives. A button
     * built while the handover runs starts in the in-flight state, and the outcome restores whichever
     * instance is attached by then.
     */
    internal fun buildSendButton(host: Component, current: InvoiceDetailDto): Button? {
        val gate = sendGate(current) ?: return null
        val resend = current.mailSent == true
        val label = tr(host, if (resend) "invoiceDetail.mail.resend" else "invoiceDetail.mail.send")
        lateinit var button: Button
        // The listener only switches to the loading state and returns, so that state reaches the browser
        // in the click's own response; the handover (an HTTP call into the mail component) runs off the
        // session lock and its outcome is applied through the UI's push channel.
        val onClick = {
            composeInFlightFor = current.id
            showComposing(host, button)
            runOffLock(host, { composeDraft(current).getOrThrow() }) { outcome -> applyComposeOutcome(host, current, outcome) }
        }
        button = if (resend) AppButtons.secondary(label, ActionIcons.SEND_MAIL) { onClick() } else AppButtons.primary(label, ActionIcons.SEND_MAIL) { onClick() }
        button.isDisableOnClick = true
        button.addClassNames("ch-action-btn", "ch-btn--mail")
        val blocker = sendBlockerTooltipKey(gate)
        if (blocker != null) {
            button.isEnabled = false
            button.element.setAttribute("title", tr(host, blocker))
        }
        if (composeInFlightFor == current.id) showComposing(host, button)
        sendButton = SendButtonSlot(button, label, enabledWhenIdle = blocker == null)
        return button
    }

    private fun showComposing(host: Component, button: Button) {
        button.isEnabled = false
        button.text = tr(host, "invoiceDetail.mail.composing")
    }

    /**
     * Applies the handover outcome on the UI thread: navigates on success, otherwise reports the
     * failure and restores the send button that is attached by then (the clicked instance or its
     * replacement from a rebuild in between). Only when [host] itself has left the UI — the user
     * navigated away — is there nothing left to show.
     */
    private fun applyComposeOutcome(host: Component, current: InvoiceDetailDto, outcome: Result<MailComposeResult?>) {
        composeInFlightFor = null
        if (!host.isAttached) return
        if (showComposeOutcome(host, current, outcome)) return
        sendButton?.takeIf { it.button.isAttached }?.let {
            it.button.text = it.label
            it.button.isEnabled = it.enabledWhenIdle
        }
    }

    /**
     * Runs the handover synchronously and opens the compose view of the new draft in this window.
     *
     * Returns `true` only when the draft was opened; any failure is reported to the user and
     * returns `false`. The send button does not call this on the click thread: it runs
     * [composeDraft] off the session lock and applies the outcome with [showComposeOutcome].
     */
    internal fun openMailCompose(host: Component, current: InvoiceDetailDto): Boolean =
        showComposeOutcome(host, current, composeDraft(current))

    /**
     * The part of the handover that talks to the mail component and may therefore take seconds.
     * Never throws: every failure arrives as a failed [Result] so the off-thread runnable cannot
     * lose the outcome. `success(null)` means the mail switch is off.
     */
    internal fun composeDraft(current: InvoiceDetailDto): Result<MailComposeResult?> =
        runCatching {
            val dispatch = checkNotNull(mailDispatch) { "mail dispatch is not available" }
            // SFS — a dialog that was already open when an admin flipped the switch: refuse with the
            // German offer text rather than surfacing the service's English last-line-of-defence message.
            if (emailFlagEnabled) dispatch.compose(current.id) else null
        }

    /**
     * Shows the outcome of [composeDraft] on the UI thread; returns `true` only when the draft was opened.
     *
     * A [MailDispatchException] is shown to the user verbatim — it always carries a cause the user can
     * act on (no recipient email, no generated document yet, component unreachable), which is exactly
     * why the port throws instead of degrading. Any other exception is logged and reported generically:
     * the same "click listeners catch broadly" rule (AP-EXC-2) the cockpit's own cancellation and
     * recalculation actions follow.
     */
    // UI event boundary — UI event entry, the sole catch-all of this flow: a Vaadin click listener that
    // lets an exception escape leaves the user with a silent, unresponsive button.
    internal fun showComposeOutcome(host: Component, current: InvoiceDetailDto, outcome: Result<MailComposeResult?>): Boolean {
        outcome.exceptionOrNull()?.let {
            reportComposeFailure(host, current, it)
            return false
        }
        return when (val result = outcome.getOrNull()) {
            is MailComposeResult.Ready -> {
                UI.getCurrent()?.navigate(
                    "mail/compose/${result.handover.draftId}",
                    QueryParameters.simple(mapOf(MailComposeView.INVOICE_PARAMETER to current.id.toString())),
                )
                true
            }
            null -> {
                UiNotifications.error(tr(host, "settings.flag.email.disabled"))
                false
            }
        }
    }

    private fun reportComposeFailure(host: Component, current: InvoiceDetailDto, e: Throwable) {
        if (e is MailDispatchException) {
            // A refused handover is a user-facing outcome, not a defect, but a refusal nobody saw (the
            // user had already navigated away) must still be traceable in the log.
            log.warnf("Mail compose handover for invoice %d refused: %s", current.id, e.message)
            // The service's own text is English; the reproduction gate has a German message of its own.
            val text = if (e::class.simpleName == REPRODUCTION_NOT_SENDABLE_TYPE || e.message?.contains(REPRODUCTION_NOT_SENDABLE_CODE) == true) {
                tr(host, "document.reproduction.sendBlocked")
            } else {
                e.message ?: tr(host, "invoiceDetail.mail.error")
            }
            UiNotifications.error(text)
        } else {
            log.error("Mail compose handover failed for invoice ${current.id}: ${e.message}", e)
            UiNotifications.error(tr(host, "invoiceDetail.mail.error"))
        }
    }

    /**
     * H-7 — the lead fact of the cockpit's fact rail ("Versendet" + timestamp, plus the journal
     * link), read **only** from the local projection
     * ([InvoiceDetailDto.mailSent]/[InvoiceDetailDto.mailSentAt]).
     *
     * No synchronous call into the component happens on this view's render path — that is the explicit
     * requirement of H-7, and it is what keeps the fact correct when the component is stopped, being
     * upgraded, or has been removed from the instance entirely. The journal deep link is added only
     * when the component is actually enabled: a link into a component that is not installed would be
     * the dead link the concept rules out.
     *
     * `null` when nothing was ever observed — an invoice that was never mailed shows no fact at
     * all rather than an empty "not sent" placeholder.
     */
    fun buildHistory(host: Component, current: InvoiceDetailDto): Span? {
        if (current.mailSent != true) return null
        val fact = Span(Icon(VaadinIcon.CHECK), Span(tr(host, "invoiceDetail.mail.history.lead")).apply { addClassName("ch-fact-label") })
            .apply { addClassNames("ch-fact", "ch-fact--lead") }
        current.mailSentAt?.let { fact.add(Span(DATE_TIME_FORMAT.format(it)).apply { addClassName("ch-fact-value") }) }
        if (mailDispatch?.isAvailable() == true) {
            fact.add(
                RouterLink(tr(host, "invoiceDetail.mail.history.journal"), MailJournalView::class.java).apply {
                    setQueryParameters(QueryParameters.simple(mapOf("reference" to current.invoiceNumber)))
                    addClassName("ch-fact-link")
                },
            )
        }
        return fact
    }
}

/** The send button rendered last, with what a failed handover restores it to. */
private class SendButtonSlot(val button: Button, val label: String, val enabledWhenIdle: Boolean)

private const val REPRODUCTION_NOT_SENDABLE_TYPE = "ReproductionNotSendableException"
private const val REPRODUCTION_NOT_SENDABLE_CODE = "DOCUMENT_REPRODUCTION_NOT_SENDABLE"
private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/**
 * Tooltip key of the first thing that keeps the mail from being sent, or `null` when
 * [MailSendGate.READY]. The gate enum is already ordered by priority, so a plain mapping of the one
 * value the port returned is the "first blocker wins" rule.
 */
internal fun sendBlockerTooltipKey(gate: MailSendGate): String? =
    when (gate) {
        MailSendGate.READY -> null
        MailSendGate.JOB_IN_FLIGHT -> "invoiceDetail.finalize.inProgress.tooltip"
        MailSendGate.NOT_ISSUED -> "invoiceDetail.mail.gate.notIssued"
        MailSendGate.VALIDATION_PENDING -> "invoiceDetail.mail.gate.validationPending"
        MailSendGate.NOT_VALID -> "invoiceDetail.mail.gate.notValid"
        MailSendGate.DOCUMENT_MISSING -> "invoiceDetail.mail.gate.documentMissing"
        MailSendGate.REPRODUCED -> "document.reproduction.sendBlocked"
        MailSendGate.NO_RECIPIENT_EMAIL -> "invoiceDetail.mail.gate.noRecipientEmail"
    }
