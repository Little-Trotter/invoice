package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.Route
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Money
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.ui.MainLayout
import jakarta.inject.Inject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.shared.Currency
import de.littletrotter.ui.format.MoneyFormatter

/** The 2-second live-update contract covers [InvoiceListView] and [PrintJobListView]; this view's
 *  poll is only a fallback behind the job-finished push, so it deliberately stays at 5 seconds
 *  instead of sharing [LIVE_UPDATE_POLL_INTERVAL_MS]. */
internal const val DETAIL_POLL_INTERVAL_MS = 5_000

@Route(value = "invoice/:id", layout = MainLayout::class)
@PageTitle("Rechnung")
class InvoiceDetailView
@Inject
constructor(
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    private val generationJobQueryService: GenerationJobQueryService,
    private val uiEventBus: UiEventBus,
    internal val invoiceCockpitHero: InvoiceCockpitHero,
    internal val detailTabs: InvoiceDetailTabs,
    // Built afresh on every "Edit" round trip (the entry workspace is never persistent), but the
    // bean itself and its ports are injected once, like every other component here.
    internal val invoiceEntryPane: InvoiceEntryPane,
) : VerticalLayout(), BeforeEnterObserver {

    private var invoiceId: Long? = null
    // Loaded invoice, entry-mode and deferred-change flags plus the component callbacks; every
    // component binds to it once, for this view's whole lifetime.
    private val state = InvoiceDetailState().also {
        it.onInvoiceChanged(::refreshAfterDataChange)
        it.onValidationFailure { ex -> validationBanner?.show(UiNotifications.resolveMessage(this, ex)) }
        it.onPrintScheduled {
            validationBanner?.isVisible = false
            sawActiveGenerationJobs = true
        }
        it.onReceiptsChanged { current ->
            invoice = current
            detailTabs.refreshReceipts(current)
        }
        it.onReloadRequested(::reloadAfterConflict)
        invoiceCockpitHero.bind(it)
        detailTabs.bind(it, onEdit = ::enterEntryMode, onReload = ::refreshAfterDataChange)
    }
    internal var invoice: InvoiceDetailDto?
        get() = state.dto
        set(value) { state.dto = value }
    internal var currentQueryParameters: QueryParameters = QueryParameters.empty()
    private val pollingLifecycle = PollingViewLifecycle()

    // Whether the previous poll tick still saw an active generation job, so the tick on which the
    // last job finishes still refreshes once (see shouldRefreshOnPollTick). Also set when a print
    // is scheduled from this view, so even a job that finishes between two ticks self-heals.
    @Volatile
    private var sawActiveGenerationJobs = false

    /** Null until entry mode builds the context bar; a session that never enters entry mode keeps
     *  it null, and [refreshTotals] then has nothing to update. */
    internal var entryGrossSpan: Span? = null

    /** True while the overview tab shows the entry (edit) pane instead of the read-only document pane.
     *  Background refreshes (poll, job-finished events) must not clobber in-progress, unsaved edits. */
    internal var overviewEditMode: Boolean
        get() = state.editMode
        set(value) { state.editMode = value }

    /** Set when a save elsewhere (item, recalculation, status transition) was deferred because
     *  the overview was mid-edit. Consumed on leaving entry mode. */
    internal var pendingRefreshAfterEdit: Boolean
        get() = state.pendingChange
        set(value) { state.pendingChange = value }

    /** Persistent banner for the last failed print validation; null in entry mode. */
    private var validationBanner: ValidationFailureBanner? = null

    /** Incremental refresh without [removeAll] on the root layout: the tab sheet persists, only
     *  tab content is rebuilt. Only ever fires outside entry mode ([InvoiceDetailState.applyChange]
     *  gates it), so the cockpit hero is always present here. */
    internal fun refreshAfterDataChange(current: InvoiceDetailDto) {
        invoice = current
        refreshTotals(current)
        invoiceCockpitHero.refresh(current)
        detailTabs.refresh(current)
    }

    /** Updates the entry context bar's gross total; hero and overview rebuild their own. */
    internal fun refreshTotals(current: InvoiceDetailDto) {
        val gross = (current.grossAmount ?: Money.ZERO).amount
        entryGrossSpan?.text = formatCurrency(gross)
    }

    /** Enters/leaves the full-bleed entry workspace through a full [renderInvoice] — a deliberate
     *  mode switch, not a background refresh. */
    internal fun enterEntryMode(current: InvoiceDetailDto) {
        state.enterEditMode()
        renderInvoice(current)
    }

    /** The invoice changed under this user: re-reads the committed state and shows it, leaving
     *  entry mode (unsaved form input cannot be applied to the new version). A vanished invoice
     *  keeps the current screen — the notification already told the user what happened. */
    internal fun reloadAfterConflict() {
        val id = invoiceId ?: return
        val fresh = invoiceDetailQueryPort.findInvoiceDetailDto(id) ?: return
        invoice = fresh
        if (overviewEditMode) {
            state.exitEditMode()
            renderInvoice(fresh)
        } else {
            refreshAfterDataChange(fresh)
        }
    }

    internal fun exitEntryMode() {
        state.exitEditMode()
        invoice?.let { renderInvoice(it) }
    }

    // Non-null: Vaadin's only caller always passes a freshly constructed AttachEvent.
    override fun onAttach(attachEvent: com.vaadin.flow.component.AttachEvent) {
        super.onAttach(attachEvent)
        val ui = attachEvent.ui
        pollingLifecycle.attach(
            ui,
            DETAIL_POLL_INTERVAL_MS,
            register = { u ->
                uiEventBus.registerOffLock(
                    u,
                    eventTick(u, capture = ::captureEventState, load = ::loadJobFinished) { dto, event -> applyJobFinished(dto, event, u) },
                )
            },
            tick = pollTick(load = ::loadDetailPollTick, apply = ::applyDetailPollTick),
        )
    }

    /** What the event load needs from the components, read under the session lock. */
    private data class EventSnapshot(val invoiceId: Long?, val editing: Boolean)

    private fun captureEventState() = EventSnapshot(invoiceId, overviewEditMode)

    /** Event load phase, off the session lock: only the detail read for an event of this invoice.
     *  Null means nothing to render. */
    private fun loadJobFinished(snapshot: EventSnapshot, event: UiEventBus.JobFinishedEvent): InvoiceDetailDto? {
        val id = snapshot.invoiceId ?: return null
        if (event.invoiceId != id || snapshot.editing) return null
        return invoiceDetailQueryPort.findInvoiceDetailDto(id)
    }

    // A push queued before this view detached is not cancelled by unregistering; the UI can stay
    // attached while this view no longer is. Edit mode may also have started while loading.
    private fun applyJobFinished(dto: InvoiceDetailDto, event: UiEventBus.JobFinishedEvent, ui: UI) {
        if (!isAttached || !ui.isAttached || overviewEditMode) return
        refreshAfterDataChange(dto)
        continueWithPendingMail(dto, validationSettled = false, jobFailed = !event.success)
    }

    /**
     * Finishes a send started via "E-Mail senden": the mail draft can only be composed once the
     * invoice is actually issued (`SENT`). The issuing job's completion and the invoice's own send
     * transition are two separate commits, so the job-finished push (and even the last poll tick
     * without an active job) can arrive while the invoice is still in review. The wish therefore
     * survives that gap and is only dropped when the send resolved negatively: the job failed
     * ([jobFailed]) or the validation ended `INVALID`/`UNAVAILABLE` with no job left running. The
     * validation verdict lands in its own commit after the job's completion push, so only the poll,
     * which keeps running while the draft is pending, reads a [validationSettled] verdict; the push
     * decides on `SENT` or failure alone.
     * Otherwise the poll keeps refreshing (see [loadDetailPollTick]) until `SENT` shows up.
     */
    private fun continueWithPendingMail(dto: InvoiceDetailDto, validationSettled: Boolean, jobFailed: Boolean = false) {
        val pending = state.pendingMailComposeInvoiceId ?: return
        if (dto.id != pending) return
        when {
            dto.statusEnum() == InvoiceStatus.SENT -> {
                // Kept while the document is still being generated: the next push or poll tick retries.
                if (invoiceCockpitHero.openMailCompose(dto)) state.pendingMailComposeInvoiceId = null
            }
            jobFailed -> state.pendingMailComposeInvoiceId = null
            validationSettled && dto.validationStatusEnum() in setOf(ValidationStatus.INVALID, ValidationStatus.UNAVAILABLE) ->
                state.pendingMailComposeInvoiceId = null
        }
    }

    /** Poll load phase, run off the session lock: the job-state query and, only when the job state
     *  changed, the detail read. Returns null for "nothing to render". [invoiceId] is captured once
     *  because the component tree must not be consulted from the tick thread. */
    private fun loadDetailPollTick(): InvoiceDetailDto? {
        val id = invoiceId ?: return null
        if (overviewEditMode || invoice == null) return null
        val hadActiveJobs = sawActiveGenerationJobs
        val hasActiveJobs = hasActiveGenerationJobs(id)
        sawActiveGenerationJobs = hasActiveJobs
        if (!shouldRefreshOnPollTick(hadActiveJobs, hasActiveJobs, state.pendingMailComposeInvoiceId != null)) return null
        return invoiceDetailQueryPort.findInvoiceDetailDto(id)
    }

    /** Poll apply phase, inside the short UI access: render only. Edit mode may have been entered
     *  while the load was running, so it is checked again. */
    private fun applyDetailPollTick(dto: InvoiceDetailDto?) {
        if (dto == null || overviewEditMode || !isAttached) return
        refreshAfterDataChange(dto)
        continueWithPendingMail(dto, validationSettled = !sawActiveGenerationJobs)
    }

    override fun onDetach(detachEvent: com.vaadin.flow.component.DetachEvent) {
        super.onDetach(detachEvent)
        pollingLifecycle.detach(detachEvent.ui)
    }

    private fun hasActiveGenerationJobs(invoiceId: Long): Boolean =
        generationJobQueryService.findByInvoiceId(invoiceId).any {
            it.status == GenerationJobStatus.PENDING ||
                it.status == GenerationJobStatus.DELEGATED ||
                it.status == GenerationJobStatus.RUNNING
        }

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        currentQueryParameters = event.location.queryParameters
        invoiceCockpitHero.queryParameters = currentQueryParameters
        invoiceId = event.routeParameters.get("id").map { it.toLongOrNull() }.orElse(null)
        if (invoiceId == null) { renderError(tr("invoiceDetail.error.invalidId")); return }

        val dto = invoiceDetailQueryPort.findInvoiceDetailDto(invoiceId!!)
            ?: return renderError(tr("invoiceDetail.error.notFound", invoiceId.toString()))
        invoice = dto
        renderInvoice(dto)

        // The list's validation-chip click adds `focus=validation`, opening the tab with the
        // validation results directly. No-op in entry mode (no tab sheet there).
        if (currentQueryParameters.parameters["focus"]?.firstOrNull() == "validation") {
            detailTabs.selectVersionsTab()
        }
    }

    internal fun renderInvoice(current: InvoiceDetailDto) {
        removeAll()
        setSpacing(false)
        addClassName("lt-gap-035")

        if (overviewEditMode && !current.isReadOnly()) {
            // Entry mode: full-bleed workspace, only the context bar stays visible — no cockpit
            // hero, no items/versions tabs while editing.
            detailTabs.clear()
            validationBanner = null
            val dirtyDot = Span("●").apply { addClassName("entry-dirty-dot"); isVisible = false }
            val entryResult = invoiceEntryPane.build(
                this,
                current,
                onInvoiceUpdated = { saved -> invoice = saved },
                onConflict = ::reloadAfterConflict,
                onDone = { exitEntryMode() },
                onDirtyChanged = { dirty -> dirtyDot.isVisible = dirty },
            )
            add(buildEntryContextBar(current, dirtyDot))
            add(entryResult.component)
            setFlexGrow(1.0, entryResult.component)
        } else {
            overviewEditMode = false
            invoiceCockpitHero.render(current)
            add(invoiceCockpitHero)

            // Persistent validation banner between hero and tabs; survives tab rebuilds
            // (refreshAfterDataChange keeps the root layout), reset on every invoice load.
            validationBanner = ValidationFailureBanner().also { add(it) }

            add(detailTabs.build(current))
        }
        updatePageTitle()
    }

    companion object {
        /** Delegates to [InvoiceItem.calculateAmounts], so the item dialogs' amount preview always
         *  matches what a save persists; BigDecimal in/out because the dialogs work in BigDecimal. */
        internal fun calculateAmounts(qty: BigDecimal, unitPrice: BigDecimal, taxRate: BigDecimal): Triple<BigDecimal, BigDecimal, BigDecimal> {
            val (net, vat, gross) = InvoiceItem.calculateAmounts(qty, Money.of(unitPrice), taxRate)
            return Triple(net.amount, vat.amount, gross.amount)
        }
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    internal fun formatDate(date: LocalDate?): String =
        date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.GERMAN)) ?: "-"

    internal fun formatCurrency(amount: BigDecimal?): String =
        formatCurrency(amount, invoice?.currencyEnum() ?: Currency.EUR)

    internal fun formatDecimal(value: BigDecimal?): String =
        (value ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString()

    internal fun unitLabel(unitName: String): String {
        return InvoiceUnitLabels.label(this, unitName)
    }

    internal fun tr(key: String, vararg params: Any): String = UiTranslations.tr(this, key, *params)

    // ── Error rendering ────────────────────────────────────────────────────────

    private fun renderError(message: String) {
        removeAll()
        val backBtn = AppButtons.tertiary(tr("invoiceDetail.backToList"), ActionIcons.BACK) {
            UI.getCurrent().navigate(InvoiceListView::class.java, currentQueryParameters)
        }
        add(PageShell(tr("nav.invoices")).narrow().apply {
            actions(backBtn)
            content(Paragraph(message))
        })
        updatePageTitle()
    }

    private fun updatePageTitle() {
        val ui = UI.getCurrent() ?: return
        val invoicesLabel = tr("nav.invoices")
        val detailLabel = tr("invoiceDetail.title")
        val suffix = invoice?.invoiceNumber.blankToNull()
            ?: invoiceId?.toString().blankToNull()
        val title = when {
            suffix != null -> "$invoicesLabel / $detailLabel $suffix | ${tr("app.title")}"
            else -> "$invoicesLabel | ${tr("app.title")}"
        }
        ui.page.setTitle(title)
    }
}

internal fun checklistStateIcon(ok: Boolean): VaadinIcon = if (ok) VaadinIcon.CHECK_CIRCLE else VaadinIcon.CLOSE_CIRCLE

internal fun checklistStateTextClass(ok: Boolean): String? = if (ok) null else "text-error"

internal fun buildCheckRow(label: String, ok: Boolean): HorizontalLayout = HorizontalLayout().apply {
    val icon = checklistStateIcon(ok).create().apply {
        setSize("1rem")
        addClassName(if (ok) "checklist-icon--ok" else "checklist-icon--missing")
    }
    add(icon)
    add(Span(label).apply { checklistStateTextClass(ok)?.let(::addClassName) })
    defaultVerticalComponentAlignment = Alignment.CENTER
    addClassName("lt-gap-040")
}

internal fun formatCurrency(amount: BigDecimal?, currency: Currency): String =
    MoneyFormatter.format(amount, currency)

/**
 * Poll-tick refresh decision for the generation-job area: refresh while a job is active AND once
 * more on the tick where the last job left PENDING/DELEGATED/RUNNING — that final tick is what
 * carries the DELEGATED/RUNNING → COMPLETED result into the view. Jobs complete in the separate
 * worker process, so this poll tick is a fallback, not the primary channel:
 * `GenerationJobResultConsumer` runs in the web process and pushes the result through
 * [de.littletrotter.ui.UiEventBus] the moment the completing transaction commits (see
 * `GenerationJobEventDispatcher.fireAsyncAfterCommit`'s own KDoc) — that push is what normally updates the
 * chip within about a second. Polling only has to catch what the push misses: a job whose entire
 * lifetime falls between two ticks (short jobs, `DETAIL_POLL_INTERVAL_MS` is 5s) or a push that
 * genuinely got lost. Skipping the completion tick here left the status chip stuck on
 * "Weitergeleitet" until a manual reload for exactly that fallback case.
 */
internal fun shouldRefreshOnPollTick(hadActiveJobs: Boolean, hasActiveJobs: Boolean, mailComposePending: Boolean = false): Boolean =
    hasActiveJobs || hadActiveJobs || mailComposePending
