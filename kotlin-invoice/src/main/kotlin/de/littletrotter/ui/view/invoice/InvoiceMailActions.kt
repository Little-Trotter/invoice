package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.mail.MailComposeResult
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.InvoiceMailReadinessQueryPort
import de.littletrotter.application.mail.port.inbound.MailSendGate
import de.littletrotter.application.settings.FeatureSettingsGate
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
    private val mailDispatch: InvoiceMailDispatchService?,
    private val featureSettingsGate: FeatureSettingsGate?,
    private val mailReadiness: InvoiceMailReadinessQueryPort? = null,
) {
    /** Invoice whose "document is being created" hint was already shown, so the retries that follow
     *  every refresh while the draft is pending do not repeat the notification. */
    private var pendingHintShownFor: Long? = null

    internal val log = Logger.getLogger(javaClass)

    /** SFS — the mail feature flag only. Whether the button/history render at all already runs
     *  through [InvoiceMailDispatchService.isAvailable], which ANDs this flag with the
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
     * True while an issuing generation exists for [invoiceId] whose document cannot be attached yet.
     * Gates the send button; re-evaluated on every cockpit render, which the job-finished push and
     * the poll tick already trigger, so the button enables itself when the result arrives.
     */
    fun isDocumentPending(invoiceId: Long): Boolean =
        mailReadiness?.readiness(invoiceId) == MailSendGate.JOB_IN_FLIGHT

    /**
     * Runs the handover and opens the compose view of the new draft in this window.
     *
     * Returns `false` only when the document is not ready yet ([MailComposeResult.DocumentPending]):
     * the caller keeps the wish and retries on the next refresh. `true` means the flow is over,
     * whether the draft opened or a failure was reported.
     *
     * A [MailDispatchException] is shown to the user verbatim — it always carries a cause the user can
     * act on (no recipient email, no generated document yet, component unreachable), which is exactly
     * why the port throws instead of degrading. Any other exception is logged and reported generically:
     * the same "click listeners catch broadly" rule (AP-EXC-2) the cockpit's own storno and
     * recalculation actions follow.
     */
    // UI event boundary — UI event entry, the sole catch-all of this flow: a Vaadin click listener that
    // lets an exception escape leaves the user with a silent, unresponsive button.
    internal fun openMailCompose(host: Component, current: InvoiceDetailDto): Boolean {
        val dispatch = mailDispatch ?: return true
        // SFS — a dialog that was already open when an admin flipped the switch: refuse here with the
        // German offer text rather than surfacing the service's English last-line-of-defence message.
        if (!emailFlagEnabled) {
            UiNotifications.error(tr(host, "settings.flag.email.disabled"))
            return true
        }
        try {
            when (val result = dispatch.compose(current.id)) {
                is MailComposeResult.Ready -> {
                    pendingHintShownFor = null
                    UI.getCurrent()?.navigate(
                        "mail/compose/${result.handover.draftId}",
                        QueryParameters.simple(mapOf(MailComposeView.INVOICE_PARAMETER to current.id.toString())),
                    )
                }
                MailComposeResult.DocumentPending -> {
                    if (pendingHintShownFor != current.id) {
                        pendingHintShownFor = current.id
                        UiNotifications.info(tr(host, "invoiceDetail.mail.documentPending"))
                    }
                    return false
                }
            }
        } catch (e: MailDispatchException) {
            // The service's own text is English; the reproduction gate has a German message of its own.
            val text = if (e::class.simpleName == REPRODUCTION_NOT_SENDABLE_TYPE || e.message?.contains(REPRODUCTION_NOT_SENDABLE_CODE) == true) {
                tr(host, "document.reproduction.sendBlocked")
            } else {
                e.message ?: tr(host, "invoiceDetail.mail.error")
            }
            UiNotifications.error(text)
        } catch (e: Exception) {
            log.error("Mail compose handover failed for invoice ${current.id}: ${e.message}", e)
            UiNotifications.error(tr(host, "invoiceDetail.mail.error"))
        }
        return true
    }

    /**
     * H-7 — the send history, read **only** from the local projection
     * ([InvoiceDetailDto.mailSent]/[InvoiceDetailDto.mailSentAt]).
     *
     * No synchronous call into the component happens on this view's render path — that is the explicit
     * requirement of H-7, and it is what keeps the line correct when the component is stopped, being
     * upgraded, or has been removed from the instance entirely. The journal deep link is added only
     * when the component is actually enabled: a link into a component that is not installed would be
     * the dead link the concept rules out.
     *
     * `null` when nothing was ever observed — an invoice that was never mailed shows no history row at
     * all rather than an empty "not sent" placeholder.
     */
    fun buildHistory(host: Component, current: InvoiceDetailDto): Div? {
        if (current.mailSent != true) return null
        val sentAt = current.mailSentAt
        val label = if (sentAt == null) {
            tr(host, "invoiceDetail.mail.history.sent")
        } else {
            tr(host, "invoiceDetail.mail.history.sentAt", DATE_TIME_FORMAT.format(sentAt))
        }
        val row = Div(Span(label).apply { addClassName("mail-history-label") }).apply {
            addClassName("mail-history")
        }
        if (mailDispatch?.isAvailable() == true) {
            row.add(
                RouterLink(tr(host, "invoiceDetail.mail.history.journal"), MailJournalView::class.java).apply {
                    setQueryParameters(QueryParameters.simple(mapOf("reference" to current.invoiceNumber)))
                    addClassName("mail-history-journal")
                },
            )
        }
        return row
    }

    private companion object {
        const val REPRODUCTION_NOT_SENDABLE_TYPE = "ReproductionNotSendableException"
        const val REPRODUCTION_NOT_SENDABLE_CODE = "DOCUMENT_REPRODUCTION_NOT_SENDABLE"
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
}
