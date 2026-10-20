package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.InvoiceDocumentSizeExceededException
import de.littletrotter.application.invoice.port.inbound.DocumentResolution
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.RestoreInvoiceDocumentUseCase
import de.littletrotter.application.invoice.port.inbound.SchedulePrintJobUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.exception.InvoiceValidationException
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
 * Extracted from `InvoiceExportPanel`: the LLM-Check checkbox
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
    private val schedulePrintJobUseCase: SchedulePrintJobUseCase,
    private val llmCheckAvailability: LlmCheckAvailability,
    /** Null in component tests that do not consider document recovery; then no restore action shows. */
    private val restoreInvoiceDocumentUseCase: RestoreInvoiceDocumentUseCase? = null,
    private val appRoleProvider: AppRoleQueryPort? = null,
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
     * Rebuilds the print/refresh button row, replacing whatever
     * was there before. Rebuilt on every call — deliberately: `blockReason` depends on invoice state a background refresh can change
     * (e.g. an invoice becomes IMMUTABLE once its first job succeeds), these are plain form
     * controls with no scroll/selection state worth preserving, and rebuilding them is cheap.
     */
    fun render(current: InvoiceDetailDto) {
        removeAll()

        val refreshJobs = buildRefreshButton()
        val llmStatus = llmCheckAvailability.isAvailable()
        val llmCheck = buildLlmCheckbox(llmStatus)
        val printJob = buildPrintButton(current, llmCheck)
        val restore = buildRestoreButton(current)

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

    /** A voided invoice or an already-generated-and-locked invoice can no longer place an
     *  E-Rechnungs-Auftrag at all (GenerationJobCreationService.createJob rejects it server-side);
     *  the button itself is disabled here so the rejection is never actually reached through
     *  normal use, matching the "control stays but names the reason" idiom [buildLlmCheckbox]
     *  uses rather than hiding it outright. */
    private fun buildPrintButton(current: InvoiceDetailDto, llmCheck: Checkbox): Button {
        val blockReason = invoiceDetailQueryPort.generationBlockReason(current.id)
        return AppButtons.secondary(tr("invoiceDetail.jobs.print"), ActionIcons.E_DOCUMENT_JOB) {
            schedulePrintJob(current, llmCheck)
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
    private fun schedulePrintJob(current: InvoiceDetailDto, llmCheck: Checkbox) {
        try {
            // llmOptimize is persisted onto the job at creation time and the job is published
            // from that persisted state, so a racing fast-path or poller republish sends the
            // same payload. The invoice put on the wire is the one loaded while creating the job,
            // not this panel's own partial view of it.
            val newJobId = schedulePrintJobUseCase.schedulePrintJob(current.id, llmCheck.value)
            UiNotifications.success(tr("invoiceDetail.jobs.print.scheduled", newJobId ?: "n/a"))
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

private val NOT_ISSUED = setOf(InvoiceStatus.DRAFT, InvoiceStatus.IN_REVIEW)
