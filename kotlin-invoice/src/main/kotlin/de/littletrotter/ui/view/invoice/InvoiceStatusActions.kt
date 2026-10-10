package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.router.RouteParameters
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.InvoiceDocumentSizeExceededException
import de.littletrotter.application.invoice.MultilingualTextsFeatureConfig
import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.domain.compliance.GateBlockReason
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.exception.InvoiceStornoNotAllowedException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.InvoiceDateBeforeServiceEndException
import de.littletrotter.domain.invoice.PdfUnrenderableTextException
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceStateMachine
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.PdfTextValidation
import de.littletrotter.ui.component.orPlaceholder
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import de.littletrotter.ui.view.logDomainRejection
import de.littletrotter.ui.view.logRejection
import org.jboss.logging.Logger

/** Cap on findings listed in the finalization-blocked notification. */
private const val MAX_LISTED_FINDINGS = 3
private const val I18N_STATUS_CHANGE_FAILED = "invoiceDetail.error.statusChangeFailed"

/** The message-bundle key per [GateBlockReason] a "blocked" notification names — every reason
 *  except [GateBlockReason.VALIDATION_UNAVAILABLE], which never reaches this lookup: it always
 *  returns earlier, opening [InvoiceStatusActions.openFinalizationOverrideDialog] instead. A
 *  lookup table, not a `when`, so that early-handled reason needs no branch of its own here. */
private val BLOCKED_REASON_KEYS = mapOf(
    GateBlockReason.VALIDATION_INVALID to "invoiceDetail.finalize.blocked.invalid",
    GateBlockReason.VALIDATION_PENDING to "invoiceDetail.finalize.blocked.pending",
    GateBlockReason.VALIDATION_NOT_RUN to "invoiceDetail.finalize.blocked.notRun",
    GateBlockReason.PROFILE_BLOCKED_OR_UNKNOWN to "invoiceDetail.finalize.blocked.profile",
    GateBlockReason.COMPLIANCE_RULE_VIOLATION to "invoiceDetail.finalize.blocked.complianceRule",
)

/** The credit-note dialog's two reason fields, grouped so [InvoiceStatusActions.submitCreditNote]
 *  takes one parameter instead of a growing field pair — split out of
 *  [InvoiceStatusActions.createCreditNoteAndNavigate] purely to stay under the module's
 *  LongParameterList budget, no behavioural change. */
private class CreditNoteReasonFields(
    val reasonField: TextArea,
    val reasonEnField: TextArea?,
    val tr: (String) -> String,
)

/**
 * The cockpit's status-transition buttons (zurück zu Prüfung, Entwurf, Senden, Annullieren, the
 * UNAVAILABLE finalize-override) and the Stornieren action, extracted out of
 * `InvoiceDetailViewCockpit.kt`/`InvoiceDetailViewStatusTransitions.kt` into a `@Dependent` CDI
 * bean of its own, the same shape as [InvoicePrintControls]/[InvoiceReceiptsTab]: injected once
 * into [InvoiceCockpitHero] for its whole lifetime. Not itself a [Component] — its build methods
 * take [host] purely as translation/dialog-attach context (the same idiom
 * [de.littletrotter.ui.view.invoice.budgetHint] already uses for a non-Component helper), so the
 * cockpit hero decides where the returned buttons live in its own actions row.
 *
 * A successful transition/send/recalculation-adjacent write no longer reaches back into
 * `InvoiceDetailView` directly: [bind] registers the [InvoiceDetailState] this bean reports
 * through, and every write applies its result via [InvoiceDetailState.applyChange] — the same
 * "callbacks via `InvoiceDetailState`" shape [InvoicePrintControls]/[ReceiptDialogLauncher]
 * already use. Storno (createCreditNote) never touches [state] at all — it creates a NEW invoice
 * and navigates away, there is nothing on this view to refresh.
 */
@Dependent
class InvoiceStatusActions
@Inject
constructor(
    private val changeInvoiceStatusUseCase: ChangeInvoiceStatusUseCase,
    private val generationJobQueryService: GenerationJobQueryService,
    private val multilingualTextsFeatureConfig: MultilingualTextsFeatureConfig,
    private val mailActions: InvoiceMailActions,
) {
    internal val log = Logger.getLogger(javaClass)
    private var state: InvoiceDetailState? = null

    /** Registers the [InvoiceDetailState] this bean reports transition/send outcomes to — called
     *  once by [InvoiceCockpitHero.bind]. */
    internal fun bind(state: InvoiceDetailState) {
        this.state = state
    }

    private fun tr(host: Component, key: String, vararg params: Any): String = UiTranslations.tr(host, key, *params)

    /** The status-transition buttons for [current]'s status (+ the UNAVAILABLE override) plus the
     *  Stornieren action when [canCreateCreditNote] — both share the cockpit's own actions row. */
    fun buildButtons(host: Component, current: InvoiceDetailDto, canCreateCreditNote: Boolean): List<Component> {
        val buttons = mutableListOf<Component>()
        addStatusActionButtons(host, current, buttons)
        // PO order 2026-08-31 — Storno creates a NEW invoice (the credit note); it is not a status
        // transition of `current` and must not go through visibleStatusActionsFor/STATUS_ACTIONS/
        // transitionAndRefresh. Gated by canCreateCreditNote so the button only ever appears where
        // InvoiceStornoService would actually allow it (in practice: status SENT, no credit note
        // against it yet) instead of being shown and then throwing.
        if (canCreateCreditNote) {
            buttons.add(
                AppButtons.secondary(tr(host, "invoiceDetail.createCreditNote"), VaadinIcon.FILE_REMOVE) {
                    createCreditNoteAndNavigate(host, current)
                }.apply { addClassNames(CH_ACTION_BTN, "ch-btn--storno") },
            )
        }
        return buttons
    }

    /**
     * PO decision 2026-09-13 — "Senden" (target `SENT`) is the one action that no longer calls
     * [transitionAndRefresh] directly; every other action (zurück zu Prüfung, Entwurf, Annullieren)
     * is unchanged.
     */
    private fun addStatusActionButtons(host: Component, current: InvoiceDetailDto, buttons: MutableList<Component>) {
        visibleStatusActionsFor(current.statusEnum()).forEach { action ->
            if (action.target == InvoiceStatus.SENT) {
                buttons.add(buildSendActionButton(host, current, action))
                // PO decision 2026-09-14 (addendum) — the UNAVAILABLE finalize-override, restored as
                // its own explicit action next to (not replacing) the normal "Senden" button.
                buildOverrideSendActionButtonOrNull(host, current)?.let { buttons.add(it) }
            } else {
                buttons.add(
                    AppButtons.secondary(tr(host, action.labelKey), action.icon) {
                        transitionAndRefresh(host, current, action.target)
                    }.apply { addClassNames(CH_ACTION_BTN, action.cssClass) },
                )
            }
        }
    }

    /**
     * The send button of an invoice in review. With an active mail module it is labelled "E-Mail
     * senden" and, besides requesting the send job, remembers that the mail draft opens once the
     * invoice is sent; without the module it is the plain "Versenden". Sending is only offered in
     * review, so no other status ever reaches a mail action.
     *
     * Disabled with a tooltip while a
     * `SEND`-triggered job is already in flight for this invoice ([hasInFlightSendJob]), otherwise
     * wired to [requestSendAndRefresh] instead of [transitionAndRefresh] — the click only requests the
     * job and returns immediately, it never calls `transition(id, SENT)` directly.
     */
    private fun buildSendActionButton(host: Component, current: InvoiceDetailDto, action: StatusAction): Button {
        val sendInFlight = hasInFlightSendJob(current.id)
        val sendByMail = mailActions.isMailAvailable()
        val label = if (sendByMail) tr(host, "invoiceDetail.mail.send") else tr(host, action.labelKey)
        val icon = if (sendByMail) ActionIcons.SEND_MAIL else action.icon
        val css = if (sendByMail) "ch-btn--mail" else action.cssClass
        return AppButtons.secondary(label, icon) { requestSendAndRefresh(host, current, sendByMail) }.apply {
            addClassNames("ch-action-btn", css)
            val blockedHint = when {
                sendInFlight -> tr(host, "invoiceDetail.finalize.inProgress.tooltip")
                sendByMail && mailActions.isDocumentPending(current.id) -> tr(host, "invoiceDetail.mail.documentPending")
                else -> null
            }
            if (blockedHint != null) {
                isEnabled = false
                element.setAttribute("title", blockedHint)
            }
        }
    }

    /** PO decision 2026-09-13 — "Sendevorgang läuft" state: scoped to `trigger == SEND`
     *  specifically (unlike `hasActiveGenerationJobs`, used elsewhere for the polling refresh, which
     *  counts every job kind) so an unrelated in-flight print/export job never disables this button. */
    private fun hasInFlightSendJob(invoiceId: Long): Boolean =
        generationJobQueryService.findByInvoiceId(invoiceId).any {
            it.trigger == GenerationJobTrigger.SEND &&
                it.status in setOf(GenerationJobStatus.PENDING, GenerationJobStatus.DELEGATED, GenerationJobStatus.RUNNING)
        }

    /**
     * PO decision 2026-09-14 (addendum) — restores the pre-feature `UNAVAILABLE` finalize-override
     * capability the async `requestSendAndRefresh` path otherwise has no equivalent for: a
     * `SEND`-triggered job resolving `UNAVAILABLE` means the validation TOOLING itself was
     * unreachable/misconfigured, not a data defect — the one reason worth an audited manual
     * escalation. `INVALID`/`PENDING`/`NOT_VALIDATED` get no such button, unchanged from the plain
     * message-center-only dead end [de.littletrotter.application.invoice.GenerationJobService
     * .resolveSendOutcome] already gives them.
     *
     * `null` (no button) unless [current]'s own, already-persisted `validationStatus` (written
     * independently by [de.littletrotter.application.invoice.DocumentGeneratedReactionHandler]'s
     * existing reaction — no new column/flag needed here) is `UNAVAILABLE` AND no `SEND` job is
     * currently in flight ([hasInFlightSendJob]) — deliberately the SAME gate the normal "Senden"
     * button's own in-flight check uses, so the two buttons never show contradictory availability.
     * Only ever rendered alongside the normal "Senden" action (see [addStatusActionButtons]), which
     * already restricts this to a send-eligible status.
     */
    private fun buildOverrideSendActionButtonOrNull(host: Component, current: InvoiceDetailDto): Button? {
        if (current.validationStatusEnum() != ValidationStatus.UNAVAILABLE) return null
        if (hasInFlightSendJob(current.id)) return null
        return AppButtons.secondary(tr(host, "invoiceDetail.finalize.override.action"), ActionIcons.OVERRIDE) {
            openFinalizationOverrideDialog(host, current)
        }.apply {
            addClassNames("ch-action-btn", "ch-btn--override")
            element.setAttribute("title", tr(host, "invoiceDetail.finalize.override.action.tooltip"))
        }
    }

    /**
     * PO decision 2026-09-13 — the "Senden" click's only effect: requests a `SEND`-triggered
     * [de.littletrotter.domain.invoice.GenerationJob] and returns immediately. The invoice's own
     * status is untouched here; the actual `IN_REVIEW -> SENT` transition happens later,
     * asynchronously, from [de.littletrotter.application.invoice.GenerationJobService
     * .resolveSendOutcome] once the job's own worker callback reports success and validation
     * resolves `VALID`. With [continueWithMail] the view is told to open the mail draft once that
     * transition has happened.
     */
    private fun requestSendAndRefresh(host: Component, current: InvoiceDetailDto, continueWithMail: Boolean) {
        try {
            val savedDto = changeInvoiceStatusUseCase.requestSend(current.id)
            if (continueWithMail) state?.pendingMailComposeInvoiceId = current.id
            state?.applyChange(savedDto)
            val message = if (continueWithMail) "invoiceDetail.mail.send.requested" else "invoiceDetail.finalize.requested"
            UiNotifications.success(tr(host, message))
        } catch (e: InvoiceValidationException) {
            // Click listener ("Senden" button): must not let an exception escape. Logged with the
            // invoice id so a failed send request is discoverable, not just a toast.
            logRejection(log, "Failed to request send for invoiceId=${current.id}", e)
            UiNotifications.error(UiNotifications.resolveMessage(host, e))
        } catch (e: InvoiceDocumentSizeExceededException) {
            // The hard stop GenerationJobCreationService now owns: an oversized receipt set was
            // allowed to be linked (PO decision), so this is the first point the user actually
            // learns generation was refused — named, not the generic status-change-failed wrapper.
            log.error("Failed to request send for invoiceId=${current.id}: document budget exceeded", e)
            UiNotifications.error(
                tr(
                    host,
                    "receipt.budget.tooLarge.blocked",
                    DocumentSizeFormatter.format(e.estimated),
                    DocumentSizeFormatter.format(e.limit),
                ),
            )
        } catch (e: PdfUnrenderableTextException) {
            log.warn("Send request rejected for invoiceId=${current.id}: ${e.message}")
            UiNotifications.error(UiNotifications.resolveMessage(host, e))
        } catch (e: InvoiceDateBeforeServiceEndException) {
            // Business rejection (§ 14 Abs. 4 Nr. 6 UStG), not an error: show its own text.
            log.warn("Send request rejected for invoiceId=${current.id}: ${e.message}")
            UiNotifications.error(UiNotifications.resolveMessage(host, e))
        } catch (e: Exception) {
            logRejection(log, "Failed to request send for invoiceId=${current.id}", e)
            UiNotifications.error(tr(host, I18N_STATUS_CHANGE_FAILED, UiNotifications.resolveMessage(host, e)))
            state.reloadOnConflict(e)
        }
    }

    internal fun transitionAndRefresh(host: Component, current: InvoiceDetailDto, target: InvoiceStatus, overrideReason: String? = null) {
        if (!current.statusEnum().canTransitionTo(target)) {
            UiNotifications.error(tr(host, I18N_STATUS_CHANGE_FAILED, "${current.statusEnum()} -> $target"))
            return
        }
        try {
            val savedDto = changeInvoiceStatusUseCase.transitionInvoice(current.id, target, overrideReason)
            state?.applyChange(savedDto)
            val savedStatus = savedDto.statusEnum()
            UiNotifications.success(tr(host, "invoiceDetail.statusChanged", tr(host, savedStatus.labelKey)))
        } catch (e: InvoiceFinalizationBlockedException) {
            handleFinalizationBlocked(host, current, e)
        } catch (e: InvoiceValidationException) {
            logRejection(log, "Failed to transition invoiceId=${current.id} to $target", e)
            UiNotifications.error(UiNotifications.resolveMessage(host, e))
        } catch (e: Exception) {
            // Click listener (status-transition buttons): must not let an exception escape. Logged
            // with the invoice id and the attempted target status.
            logRejection(log, "Failed to transition invoiceId=${current.id} to $target", e)
            UiNotifications.error(tr(host, I18N_STATUS_CHANGE_FAILED, UiNotifications.resolveMessage(host, e)))
            state.reloadOnConflict(e)
        }
    }

    /**
     * The "Fehlermeldung" side of the gate (Umsetzung §4): names the first findings with
     * rule id and correction hint rather than a bare "Validierung fehlgeschlagen". `UNAVAILABLE`
     * is the one blocked reason with a way forward, so it opens [openFinalizationOverrideDialog]
     * instead of just showing an error.
     */
    private fun handleFinalizationBlocked(host: Component, current: InvoiceDetailDto, ex: InvoiceFinalizationBlockedException) {
        if (ex.reason == GateBlockReason.VALIDATION_UNAVAILABLE) {
            openFinalizationOverrideDialog(host, current)
            return
        }
        val key = BLOCKED_REASON_KEYS.getValue(ex.reason)
        val findingsText = ex.findings.take(MAX_LISTED_FINDINGS).joinToString(separator = "\n") { finding ->
            val hint = resolveValidationHint(finding.ruleId)
            // A rule-engine finding carries an i18n KEY in `message` (the domain never produces
            // display text), while a KoSIT finding carries the validator's finished
            // sentence. The `validator` field tells them apart instead of guessing from the text.
            val text = if (finding.validator == "compliance-rules") {
                val args = finding.args.orEmpty().mapIndexed { index, argument ->
                    InvoiceUnitLabels.validationArgument(host, finding.message, index, argument)
                }
                tr(host, finding.message, *args.toTypedArray())
            } else {
                finding.message
            }
            listOfNotNull(finding.ruleId, text, hint).joinToString(" — ")
        }
        UiNotifications.error(if (findingsText.isBlank()) tr(host, key) else "${tr(host, key)}\n$findingsText")
    }

    /** The "Übersteuerungsdialog" side of the gate: `UNAVAILABLE` is the only blocked reason
     *  that's overridable at all, and only with a non-blank, logged Begründung (Umsetzung §3). */
    private fun openFinalizationOverrideDialog(host: Component, current: InvoiceDetailDto) {
        val dialog = AppDialog(tr(host, "invoiceDetail.finalize.override.title"))

        val reasonField = TextArea(tr(host, "invoiceDetail.finalize.override.reasonLabel")).apply { setWidthFull() }
        dialog.add(VerticalLayout(Span(tr(host, "invoiceDetail.finalize.override.explanation")), reasonField).apply { isPadding = false })

        val confirmBtn = AppButtons.primary(tr(host, "invoiceDetail.finalize.override.confirm")) {
            val reason = reasonField.value.trim()
            if (reason.isBlank()) {
                UiNotifications.errorOn(reasonField, tr(host, "invoiceDetail.finalize.override.reasonRequired"))
                return@primary
            }
            dialog.close()
            transitionAndRefresh(host, current, InvoiceStatus.SENT, reason)
        }
        val cancelBtn = AppButtons.tertiary(tr(host, "app.cancel")) { dialog.close() }
        dialog.footer(cancelBtn, confirmBtn)

        dialog.open()
    }

    /**
     * PO order 2026-08-31 — click handler for the "Stornieren" action wired in [buildButtons].
     * Opens a confirm dialog for the (mandatory, PO order 2026-08-31) Storno-Grund — same
     * `AppDialog` + `VerticalLayout()` + `TextArea()` + blank-check idiom as
     * [openFinalizationOverrideDialog]; confirming calls
     * [de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase.createCreditNote]
     * and navigates to the newly created credit note, cancelling closes the dialog without side
     * effects.
     *
     * Unlike [transitionAndRefresh], this never touches [state] — a Storno creates a NEW invoice
     * (the credit note), it is not a state transition of the one shown here, so there is nothing
     * on this view to refresh; the browser navigates away to the new invoice instead. The
     * `canCreateCreditNote` gate in [buildButtons] already keeps this button off ineligible
     * invoices, so the catch blocks below are a defensive fallback (e.g. two browser tabs racing to
     * storno the same invoice), not the primary path.
     *
     * Since the confirm button does something strictly heavier than before (it cancels the
     * original invoice AND creates the storno document, not just the latter), a warning block now
     * sits above the reason field spelling that out before the user confirms; the dialog is also
     * no longer dismissible by an outside click, so the warning cannot be skipped by accident.
     */
    internal fun createCreditNoteAndNavigate(host: Component, current: InvoiceDetailDto) {
        val dialog = AppDialog(tr(host, "invoiceDetail.createCreditNote.dialogTitle"))
        // The confirm button now does two things at once (cancels the original AND creates the
        // storno document), so the warning above must not be dismissible by an accidental click
        // outside the dialog; only the explicit Abbrechen/confirm buttons close it.
        dialog.isCloseOnOutsideClick = false

        val invoiceNumber = current.invoiceNumber.orPlaceholder(current.id.toString())
        val warningBlock = buildCreditNoteWarningBlock(host, invoiceNumber)
        val reasonField = TextArea(tr(host, "invoiceDetail.createCreditNote.reasonLabel")).apply {
            setWidthFull()
            isRequiredIndicatorVisible = true
        }
        val pdfTr: (String) -> String = { key -> tr(host, key) }
        val reasonEnField = buildCreditNoteReasonEnField(host, current)
        val reasonFields = CreditNoteReasonFields(reasonField, reasonEnField, pdfTr)
        val dialogBody = VerticalLayout(warningBlock, reasonField).apply { isPadding = false }
        reasonEnField?.let { dialogBody.add(it) }
        dialog.add(dialogBody)

        val confirmBtn = AppButtons.primary(tr(host, "invoiceDetail.createCreditNote.confirm")) {
            submitCreditNote(host, dialog, current, reasonFields)
        }
        val cancelBtn = AppButtons.tertiary(tr(host, "app.cancel")) { dialog.close() }
        dialog.footer(cancelBtn, confirmBtn)

        dialog.open()
    }

    /** Warning block above the reason field (PO wording, verbatim), not a tooltip and not a
     *  hint below the button. The i18n value carries one embedded newline between the headline
     *  sentence and the explanation paragraph; split here so the headline can render bold instead
     *  of splitting the translation into two keys. */
    private fun buildCreditNoteWarningBlock(host: Component, invoiceNumber: String): Div {
        val (warningHeadline, warningBody) = tr(host, "invoiceDetail.createCreditNote.warning", invoiceNumber)
            .split("\n", limit = 2)
            .let { it[0] to it.getOrElse(1) { "" } }
        return Div().apply {
            addClassName("storno-warning")
            add(Span(warningHeadline).apply { addClassName("storno-warning-headline") })
            if (warningBody.isNotBlank()) {
                add(Span(warningBody))
            }
        }
    }

    /** The second, optional English reason field exists
     *  in the component tree only when BOTH hold: the deployment flag is on AND this document's
     *  own language is English. A German document never gets this field, flag or no flag — it has
     *  no use for it (InvoiceStornoService.createCreditNote's own KDoc documents the same rule on
     *  the write side). `null`, not merely hidden, when either condition fails — testmatrix.md's
     *  border case for this package. */
    private fun buildCreditNoteReasonEnField(host: Component, current: InvoiceDetailDto): TextArea? {
        if (!multilingualTextsFeatureConfig.enabled || current.documentLanguage != "en") return null
        return TextArea(tr(host, "invoiceDetail.createCreditNote.reasonEnLabel")).apply {
            setWidthFull()
            helperText = tr(host, "invoiceDetail.createCreditNote.reasonEnHelper")
        }
    }

    /** The confirm button's click handler body: validates the (required German / optional English)
     *  reason, closes the dialog, and reports the outcome. Split out of
     *  [createCreditNoteAndNavigate] purely to stay under the module's LongMethod budget — no
     *  behavioural change. */
    private fun submitCreditNote(
        host: Component,
        dialog: Dialog,
        current: InvoiceDetailDto,
        reasonFields: CreditNoteReasonFields,
    ) {
        val invoiceNumber = current.invoiceNumber.orPlaceholder(current.id.toString())
        val reason = reasonFields.reasonField.value.trim()
        if (reason.isBlank()) {
            UiNotifications.errorOn(reasonFields.reasonField, tr(host, "invoiceDetail.createCreditNote.reasonRequired"))
            return
        }
        // The storno document is born terminal and printed from this text, so characters the PDF
        // fonts lack must be rejected here, while the dialog is still open.
        val fieldsToCheck = listOfNotNull(reasonFields.reasonField, reasonFields.reasonEnField)
        if (!PdfTextValidation.checkAll(reasonFields.tr, *fieldsToCheck.toTypedArray())) return
        // Blank/absent falls back to the German reason verbatim on the write side too
        // (InvoiceStornoService.createCreditNote) — this is only the UI half of that rule: an
        // empty English field must reach the service as null, never as "".
        val reasonEn = reasonFields.reasonEnField?.value?.trim()?.takeIf { it.isNotBlank() }
        dialog.close()
        try {
            val creditNote = changeInvoiceStatusUseCase.createCreditNote(current.id, reason, reasonEn)
            UiNotifications.success(
                tr(
                    host,
                    "invoiceDetail.createCreditNote.success",
                    invoiceNumber,
                    creditNote.invoiceNumber.orPlaceholder(creditNote.id.toString()),
                ),
            )
            UI.getCurrent().navigate(InvoiceDetailView::class.java, RouteParameters("id", creditNote.id.toString()))
        } catch (e: InvoiceStornoNotAllowedException) {
            UiNotifications.error(UiNotifications.resolveMessage(host, e))
        } catch (e: Exception) {
            // Confirm-dialog click listener: must not let an exception escape (this class' own
            // KDoc already notes the catches here are a defensive fallback for a race, not the
            // primary path). Logged with the invoice id so a failed Storno is discoverable.
            logDomainRejection(log, "Failed to create credit note for invoiceId=${current.id}", e)
            UiNotifications.error(tr(host, "invoiceDetail.error.saveFailed", UiNotifications.resolveMessage(host, e)))
        }
    }
}

internal fun allowedTargetStatuses(status: InvoiceStatus): Set<InvoiceStatus> =
    InvoiceStateMachine.successors(status)

internal data class StatusAction(
    val target: InvoiceStatus,
    val labelKey: String,
    val icon: VaadinIcon,
    val cssClass: String,
    val order: Int,
)

internal val STATUS_ACTIONS: List<StatusAction> = listOf(
    StatusAction(InvoiceStatus.IN_REVIEW,  "invoiceDetail.review",       ActionIcons.VIEW,          "ch-btn--review",   10),
    StatusAction(InvoiceStatus.DRAFT,      "invoiceDetail.backToDraft",   VaadinIcon.ARROW_BACKWARD, "ch-btn--draft",    15),
    StatusAction(InvoiceStatus.SENT,  "invoiceDetail.finalize",      VaadinIcon.CHECK_CIRCLE,   "ch-btn--finalize", 20),
    StatusAction(InvoiceStatus.VOIDED,     "invoiceDetail.voidInvoice",   VaadinIcon.CLOSE_CIRCLE,   "ch-btn--void",     30),
)

internal fun visibleStatusActionsFor(status: InvoiceStatus): List<StatusAction> {
    val allowed = allowedTargetStatuses(status)
    return STATUS_ACTIONS.filter { it.target in allowed }.sortedBy { it.order }
}
