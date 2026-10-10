package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.FacturXDeployment
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.InvoiceDocumentSizeExceededException
import de.littletrotter.application.invoice.port.inbound.DocumentResolution
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.RestoreInvoiceDocumentUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.compliance.FacturXEligibility
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import de.littletrotter.ui.view.logDomainRejection
import org.jboss.logging.Logger

private const val TITLE_ATTR = "title"

/**
 * Extracted from `InvoiceExportPanel`: engine/template choice, the LLM-Check checkbox
 * and the print action (create + publish a generation job). [InvoiceExportPanel] composes this
 * alongside [InvoiceGenerationJobsPanel] into the same card.
 *
 * The panel's callbacks to the surrounding view ([bind]'s own KDoc) now go through
 * [InvoiceDetailState] instead of constructor lambdas, since this class is a CDI-injected bean
 * shared for the whole view lifetime rather than rebuilt per invoice.
 */
@Dependent
class InvoicePrintControls
@Inject
constructor(
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    private val recipientQueryPort: RecipientQueryPort,
    private val generationJobCreationService: GenerationJobCreationService,
    private val documentJobPublisher: DocumentJobPublisher,
    private val llmCheckAvailability: LlmCheckAvailability,
    /** Gates the engine choice on the engine actually being deployed. Null in component
     *  tests that don't consider it, then the stack counts as deployed (the pre-gate behavior). */
    private val facturXDeployment: FacturXDeployment? = null,
    /** T8 (C2) — the reference-data switch for the engine choice; independent of the LLM-Check
     *  switch wrapped inside [llmCheckAvailability]. Null in component tests that don't consider
     *  it, then the engine choice counts as switched off (pre-T8 behavior). */
    private val validationSettingsGate: ValidationSettingsGate? = null,
    /** Null in component tests that do not consider document recovery; then no restore action shows. */
    private val restoreInvoiceDocumentUseCase: RestoreInvoiceDocumentUseCase? = null,
    private val appRoleProvider: AppRoleProvider? = null,
) : Div(), TranslationProvider {

    private val log = Logger.getLogger(javaClass)

    /** Called after a job is created or refreshed — [InvoiceExportPanel] forwards the view's
     *  reload handler here. */
    var onReload: () -> Unit = {}

    private var state: InvoiceDetailState? = null

    init { setWidthFull() }

    override fun getComponent(): Component = this

    /** Registers the [InvoiceDetailState] this panel reports print-job outcomes to (see the
     *  class KDoc) — called once by [InvoiceExportPanel.bind]. */
    internal fun bind(state: InvoiceDetailState) {
        this.state = state
    }

    /**
     * Rebuilds the engine/template controls and the print/refresh button row, replacing whatever
     * was there before. Rebuilt on every call — deliberately: `blockReason`, engine eligibility
     * and the template choice list all depend on invoice state a background refresh can change
     * (e.g. an invoice becomes IMMUTABLE once its first job succeeds), these are plain form
     * controls with no scroll/selection state worth preserving, and rebuilding them is cheap.
     */
    fun render(current: InvoiceDetailDto) {
        removeAll()

        val refreshJobs = buildRefreshButton()
        val llmStatus = llmCheckAvailability.isAvailable()
        val llmCheck = buildLlmCheckbox(llmStatus)
        val engineSelectionVisible = resolveEngineSelectionVisible(current)
        val engineSelect = buildEngineSelect(current)
        val printControls = PrintControls(engineSelect, llmCheck)
        val printJob = buildPrintButton(current, engineSelectionVisible, printControls)
        val restore = buildRestoreButton(current)

        if (engineSelectionVisible) add(engineSelect)

        add(HorizontalLayout().apply {
            setWidthFull(); isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            // Not `isVisible = false`, but not adding it in the first place: an invisible
            // component still sits in the tree and still shows up in component tests and
            // accessibility tools.
            add(printJob)
            restore?.let { add(it) }
            if (llmStatus != LlmCheckStatus.SwitchedOff) add(llmCheck, refreshJobs) else add(refreshJobs)
        })
    }

    /** Same rebuild as [render] — no persistent slot to preserve here (see [render]'s own KDoc). */
    fun refresh(current: InvoiceDetailDto) = render(current)

    private fun buildRefreshButton(): Button =
        AppButtons.icon(ActionIcons.REFRESH, tr("invoiceDetail.jobs.refresh.tooltip")) { onReload() }.apply {
            addClassName("refresh-button-animated")
        }

    /** Explicit "restore document" action: only for callers holding the permission and only once the
     *  invoice has been issued — a draft has no document of record to restore. */
    private fun buildRestoreButton(current: InvoiceDetailDto): Button? {
        val useCase = restoreInvoiceDocumentUseCase ?: return null
        if (appRoleProvider?.hasPermission(AppPermission.DOCUMENT_REPRODUCE) != true) return null
        if (current.statusEnum() in NOT_ISSUED) return null
        lateinit var restoreButton: Button
        restoreButton = AppButtons.secondary(tr("document.recovery.action"), VaadinIcon.RECYCLE) {
            restoreDocument(useCase, current.id, restoreButton)
        }
        return restoreButton
    }

    private fun restoreDocument(useCase: RestoreInvoiceDocumentUseCase, invoiceId: Long, button: Button) {
        try {
            when (val resolution = useCase.restore(invoiceId)) {
                is DocumentResolution.Serve, DocumentResolution.NoDocument ->
                    UiNotifications.info(tr("document.recovery.nothingToDo"))
                is DocumentResolution.Restored -> UiNotifications.confirmOn(button)
                is DocumentResolution.RegenerationStarted -> {
                    UiNotifications.info(tr("document.recovery.regenerationStarted", resolution.jobId))
                    onReload()
                }
                DocumentResolution.StorageUnavailable ->
                    UiNotifications.error(tr("document.recovery.storageUnavailable"))
            }
        } catch (e: SecurityException) {
            logDomainRejection(log, "Document restore denied for invoiceId=$invoiceId", e)
            UiNotifications.error(UiNotifications.resolveMessage(this, e))
        } catch (e: Exception) {
            logDomainRejection(log, "Document restore failed for invoiceId=$invoiceId", e)
            UiNotifications.error(UiNotifications.resolveMessage(this, e))
        }
    }

    private fun buildLlmCheckbox(status: LlmCheckStatus): Checkbox =
        Checkbox(tr("invoiceDetail.jobs.llmCheck")).apply {
            // unchecked by default (a local model costs runtime) — no explicit value assignment, the
            // Vaadin default is already off.
            addClassName("lt-text-085")
            when (status) {
                LlmCheckStatus.DisabledByDeployment -> {
                    isEnabled = false
                    helperText = tr("invoiceDetail.jobs.llmCheck.disabledByDeployment.reason")
                }
                LlmCheckStatus.NotConfigured -> {
                    isEnabled = false
                    helperText = tr("invoiceDetail.jobs.llmCheck.disabled.reason")
                }
                LlmCheckStatus.SwitchedOff, LlmCheckStatus.Available ->
                    element.setAttribute(TITLE_ATTR, tr("invoiceDetail.jobs.llmCheck.tooltip"))
            }
        }

    /**
     * T8 (C2) — the engine choice hangs off the T9 reference-data switch; switched off, the
     * selector stays hidden and nothing but Mustang is ever shown.
     *
     * The switch alone is not enough: even with the reference-data
     * capability on, the engine choice only appears when this invoice's parties are actually in
     * Factur-X scope. `FacturXEligibility` is the one place that test lives; see its KDoc.
     *
     * The primitive-values overload: no SenderProfile/Recipient loaded or constructed.
     * The recipient's billing-address country is looked up fresh via the port (the real owning
     * `Recipient.addresses` — not reachable off the read model at all).
     */
    private fun resolveEngineSelectionVisible(current: InvoiceDetailDto): Boolean {
        val facturXEngineEnabled = resolveFacturXEngineEnabled(
            gateEnabled = validationSettingsGate?.isEnabled(ValidationCapabilityId.FACTURX_ENGINE) ?: false,
            deployed = facturXDeployment?.deployed ?: true,
        )
        val recipientBillingCountry = current.recipient?.id
            ?.let { recipientQueryPort.findBillingAddress(it)?.countryAlpha2 }
            ?.let { Country.fromAlpha2(it) }
        return facturXEngineEnabled &&
            FacturXEligibility.isEligible(
                sellerVatId = current.sender?.vatId,
                sellerAdditionalVatRegistrationCountries = current.sender?.additionalVatRegistrationCountries.orEmpty()
                    .mapNotNull { runCatching { Country.fromAlpha2(it) }.getOrNull() },
                recipientBillingCountry = recipientBillingCountry,
                recipientVatId = current.recipient?.vatId,
            )
    }

    private fun buildEngineSelect(current: InvoiceDetailDto): RadioButtonGroup<DocumentEngine> =
        RadioButtonGroup<DocumentEngine>().apply {
            label = tr("invoiceDetail.jobs.engine.label")
            setItems(DocumentEngine.MUSTANG, DocumentEngine.FACTURX)
            setItemLabelGenerator { engine ->
                when (engine) {
                    DocumentEngine.MUSTANG -> tr("invoiceDetail.jobs.engine.mustang")
                    DocumentEngine.FACTURX -> tr("invoiceDetail.jobs.engine.facturx")
                }
            }
            // The invoice's own durable choice (Invoice.documentEngine), not always MUSTANG —
            // reopening the panel must show what this invoice is actually set to.
            value = current.documentEngineEnum()
            addThemeVariants(com.vaadin.flow.component.radiobutton.RadioGroupVariant.LUMO_VERTICAL)
            addClassName("lt-text-085")
            element.setAttribute(TITLE_ATTR, tr("invoiceDetail.jobs.engine.tooltip"))
        }

    /** [engineSelect]/[llmCheck] bundled so [buildPrintButton]/[schedulePrintJob]
     *  take one parameter for them. */
    private data class PrintControls(
        val engineSelect: RadioButtonGroup<DocumentEngine>,
        val llmCheck: Checkbox,
    )

    /** A voided invoice or an already-generated-and-locked invoice can no longer place an
     *  E-Rechnungs-Auftrag at all (GenerationJobCreationService.createJob rejects it server-side);
     *  the button itself is disabled here so the rejection is never actually reached through
     *  normal use, matching the "control stays but names the reason" idiom [buildLlmCheckbox]
     *  uses rather than hiding it outright. */
    private fun buildPrintButton(
        current: InvoiceDetailDto,
        engineSelectionVisible: Boolean,
        controls: PrintControls,
    ): Button {
        val blockReason = invoiceDetailQueryPort.generationBlockReason(current.id)
        return AppButtons.secondary(tr("invoiceDetail.jobs.print"), ActionIcons.E_DOCUMENT_JOB) {
            schedulePrintJob(current, engineSelectionVisible, controls)
            onReload()
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_SUCCESS)
            when (blockReason) {
                de.littletrotter.domain.exception.GenerationBlockReason.VOIDED -> {
                    isEnabled = false
                    element.setAttribute(TITLE_ATTR, tr("invoiceDetail.jobs.print.blockedVoided"))
                }
                de.littletrotter.domain.exception.GenerationBlockReason.IMMUTABLE -> {
                    isEnabled = false
                    element.setAttribute(TITLE_ATTR, tr("invoiceDetail.jobs.print.blockedImmutable"))
                }
                null -> element.setAttribute(TITLE_ATTR, tr("invoiceDetail.jobs.print.tooltip"))
            }
        }
    }

    /**
     * The print button's click action, split out of [buildPrintButton] purely to keep both
     * functions under the module's complexity budget — same single responsibility either way:
     * create the job, publish it, and surface success/failure to the user.
     */
    private fun schedulePrintJob(
        current: InvoiceDetailDto,
        engineSelectionVisible: Boolean,
        controls: PrintControls,
    ) {
        val (engineSelect, llmCheck) = controls
        try {
            // task12-b2b/T8 — only an actually visible selector may change the invoice's durable
            // engine; a hidden selector (either gate off) must never silently touch it, and
            // `null` here means "leave whatever the invoice already has".
            val chosenEngine = if (engineSelectionVisible) engineSelect.value else null
            // llmOptimize is persisted onto the job right here, at creation
            // time, instead of being forwarded to publish() as a separate argument:
            // documentJobPublisher.publish reads it back off `newJob`, the same job the fast
            // path/poller republish would read if either of them won the race to publish this job
            // instead of this synchronous call.
            val newJob = generationJobCreationService.createJob(
                current.id, "PRINT_JOB", DocumentJobLane.INTERACTIVE,
                engine = chosenEngine,
                llmOptimize = llmCheck.value,
            )
            // The invoice actually mapped onto the wire is `newJob.invoice`, the fresh
            // entity `generationJobCreationService.createJob` just loaded through the application
            // layer (one call, no N+1) — not `current`, this panel's own caller-supplied invoice.
            // That closes the class of bug where the UI's own partial view of the invoice
            // (however it was built) could diverge from what the printed document actually
            // contains.
            documentJobPublisher.publish(newJob, skipValidation = false)
            UiNotifications.success(tr("invoiceDetail.jobs.print.scheduled", newJob.id ?: "n/a"))
            state?.notifyPrintScheduled()
        } catch (e: InvoiceValidationException) {
            // Click listener: must not let an exception escape. Logged with the invoice id so a
            // failed print-job scheduling is discoverable, not just a toast.
            logDomainRejection(log, "Failed to schedule print job for invoiceId=${current.id}", e)
            state?.notifyValidationFailure(e)
            UiNotifications.error(UiNotifications.resolveMessage(this, e))
        } catch (e: InvoiceDocumentSizeExceededException) {
            // The hard stop GenerationJobCreationService now owns: an oversized receipt set was
            // allowed to be linked (PO decision), so this is the first point the user actually
            // learns generation was refused — named, not the generic status-change-failed wrapper.
            log.error("Failed to schedule print job for invoiceId=${current.id}: document budget exceeded", e)
            UiNotifications.error(
                tr("receipt.budget.tooLarge.blocked", DocumentSizeFormatter.format(e.estimated), DocumentSizeFormatter.format(e.limit)),
            )
        } catch (e: Exception) {
            logDomainRejection(log, "Failed to schedule print job for invoiceId=${current.id}", e)
            UiNotifications.error(UiNotifications.resolveMessage(this, e))
        }
    }
}

/** The reference-data switch alone is not enough: a DB switch left ON from an earlier
 *  deployment must not surface Factur-X output on a stack without the engine. Pulled out of the
 *  render path so this decision is unit-testable without Vaadin/Karibu plumbing, same reasoning as
 *  [de.littletrotter.ui.view.referencedata.ValidationSettingsTab.applyEngineActivation]. */
internal fun resolveFacturXEngineEnabled(gateEnabled: Boolean, deployed: Boolean): Boolean =
    gateEnabled && deployed

private val NOT_ISSUED = setOf(InvoiceStatus.DRAFT, InvoiceStatus.IN_REVIEW)
