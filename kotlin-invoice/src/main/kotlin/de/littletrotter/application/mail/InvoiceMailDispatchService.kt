package de.littletrotter.application.mail

import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.settings.FeatureFlag
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The one application service the invoice detail view talks to when the user
 * presses "E-Mail senden".
 *
 * It does three things and nothing else: decide whether the entry point exists at all
 * ([isAvailable]), turn one invoice id into an [InvoiceMailDraft]
 * (reading the aggregate and presigning its documents in the short read transaction of
 * [InvoiceMailComposeTransactions]), and hand that draft to [MailDispatchPort].
 *
 * **Availability is a bean question, not a boolean question.** [dispatchPort] is an
 * [Instance] on purpose: with `MAIL_ENABLED=false` the HTTP adapter is not a bean
 * (`@LookupIfProperty`), so `isResolvable` is `false` and there is nothing to call — the UI never
 * renders a button, rather than rendering a disabled one. [mailAvailability] is
 * still consulted as the *configured* intent, so a misconfiguration (feature on, adapter missing)
 * is visible as "not available" instead of as a `NullPointerException` at click time.
 */
@ApplicationScoped
class InvoiceMailDispatchService @Inject constructor(
    private val mailAvailability: MailAvailability,
    private val dispatchPort: Instance<MailDispatchPort>,
    private val transactions: InvoiceMailComposeTransactions,
    // SFS — the "System → Einstellungen" switch for the mail offer. Defaulted (null): Arc always
    // injects the real bean (kotlin defaults are invisible to it); component tests that never
    // wire it read "flag on" — pre-SFS behaviour, deployment state alone decides.
    private val featureSettingsGate: FeatureSettingsGate? = null,
) {

    private val log = Logger.getLogger(InvoiceMailDispatchService::class.java)

    /**
     * True only when the feature is configured on **and** an adapter bean actually exists **and**
     * no administrator has switched the send offer off.
     *
     * SFS — the third clause is the fachlich half: [de.littletrotter.application.mail.MailAvailability]
     * answers "is the component installed here" (deployment), `feature_setting` answers
     * "may the instance use it" (user decision, System → Einstellungen). This single AND is the
     * reason the UI needs no separate flag check: every render decision goes through here, and
     * [compose] refuses on exactly this predicate.
     */
    fun isAvailable(): Boolean =
        mailAvailability.enabled &&
            dispatchPort.isResolvable &&
            (featureSettingsGate?.isEnabled(FeatureFlag.EMAIL_SEND) ?: true)

    /**
     * Prepares the draft of invoice [invoiceId] in a short read transaction, then hands it over.
     *
     * Deliberately **not** `@Transactional`: the handover is a synchronous HTTP call that can take
     * many seconds with retries, and a database connection held across it starves the pool under
     * bulk load. The invoice is read, and its documents presigned, in
     * [InvoiceMailComposeTransactions.prepare]; the transaction is closed before the component is
     * called. The method writes nothing — the invoice is only marked as sent later, by
     * [MailSendResultProjection], from the component's own result event.
     *
     * [MailComposeResult.DocumentPending] is the expected answer while the document generation
     * has not finished; real failures still throw [MailDispatchException].
     */
    fun compose(invoiceId: Long): MailComposeResult {
        if (!isAvailable()) {
            // SFS — the two off-causes read apart in the message: an operator debugging a failed
            // click should see immediately whether the switch or the deployment is the blocker.
            val flagOff = featureSettingsGate?.let { !it.isEnabled(FeatureFlag.EMAIL_SEND) } == true
            throw MailDispatchException(
                if (flagOff && mailAvailability.enabled) {
                    "E-mail dispatch is switched off (System -> Settings)."
                } else {
                    "Mail dispatch is not available in this instance (mail.enabled=${mailAvailability.enabled})"
                },
            )
        }
        val draft = when (val step = transactions.prepare(invoiceId)) {
            InvoiceMailPreparation.DocumentPending -> return MailComposeResult.DocumentPending
            is InvoiceMailPreparation.Ready -> step.draft
        }
        val handover = dispatchPort.get().compose(draft)
        log.infof(
            "Mail draft %s created for invoice %s (%d attachment(s))",
            handover.draftId, draft.referenceKey, draft.attachments.size,
        )
        return MailComposeResult.Ready(handover)
    }
}
