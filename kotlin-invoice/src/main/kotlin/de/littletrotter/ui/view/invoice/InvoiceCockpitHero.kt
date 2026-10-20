package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.CancellationRelationDto
import de.littletrotter.application.dto.CancellationRole
import de.littletrotter.application.mail.port.inbound.MailSendGate
import de.littletrotter.application.export.port.inbound.InvoiceWorkbookExportQueryPort
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.tax.ServiceType
import de.littletrotter.shared.tax.TaxRuleResolution
import de.littletrotter.shared.tax.TaxRuleResolver
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.lifecycleHeroClass
import de.littletrotter.ui.component.orPlaceholder
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.isConcurrencyConflict
import de.littletrotter.ui.view.logRejection
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal const val CH_ACTION_BTN = "ch-action-btn"
private const val CH_FACT = "ch-fact"
private const val CH_FACT_LEAD = "ch-fact--lead"
private const val CH_FACT_WARN = "ch-fact--warn"
private const val ATTR_TITLE = "title"
private const val CH_BTN_DANGER = "ch-btn--danger"

/**
 * The cockpit hero — invoice number/status header, parties row, action bar, tax-rule status strip,
 * validation chip and Nachkalkulation — extracted out of `InvoiceDetailView`/
 * `InvoiceDetailViewCockpit.kt` into a `@Dependent` CDI bean of its own, the same shape as
 * [InvoiceExportPanel]/[InvoiceReceiptsTab]: injected once into [InvoiceDetailView] for its whole
 * lifetime instead of a `Div` rebuilt (and swapped via `replace`) per invoice. [render]/[refresh]
 * both rebuild this component's content wholesale — the header, parties, action bar and tax-rule
 * strip all depend on invoice state a background refresh can change, same reasoning as
 * [InvoicePrintControls.render]'s own KDoc.
 *
 * Composes [InvoiceStatusActions] (status transitions, Stornieren) and [InvoiceMailActions]
 * (E-Mail senden, send history) into its own actions row/footer instead of duplicating either
 * concern here. A write no longer reaches back into `InvoiceDetailView` directly: [bind] registers
 * the [InvoiceDetailState] this bean (and [InvoiceStatusActions]) report through; a successful
 * Nachkalkulation applies its result via [InvoiceDetailState.applyChange], the same shape
 * [InvoiceStatusActions] itself uses.
 */
/** [InvoiceStatusActions] (status transitions, Stornieren) and [InvoiceMailActions] (E-Mail
 *  senden, send history) composed into [InvoiceCockpitHero]'s own actions row/footer — bundled
 *  into one CDI bean (a plain data class has no bean-defining annotation, so ARC cannot resolve
 *  it as a constructor injection point, same idiom as [de.littletrotter.application.invoice
 *  .InvoiceLifecycleSupport]) purely to keep that constructor under detekt's `LongParameterList`
 *  threshold; each is otherwise unrelated to the other. */
@Dependent
class InvoiceCockpitActions @Inject constructor(
    val statusActions: InvoiceStatusActions,
    val mailActions: InvoiceMailActions,
    /** Null in component tests that do not consider the failed-generation banner. */
    val failureBanner: DocumentGenerationFailureBanner? = null,
)

@Dependent
class InvoiceCockpitHero
@Inject
constructor(
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    private val recipientQueryPort: RecipientQueryPort,
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase,
    private val taxRateQueries: TaxRateQueryPort,
    private val invoiceWorkbookExportService: InvoiceWorkbookExportQueryPort?,
    private val actions: InvoiceCockpitActions,
    private val time: TimeSource,
) : Div(), TranslationProvider {

    private val statusActions get() = actions.statusActions
    private val mailActions get() = actions.mailActions

    internal val log = Logger.getLogger(javaClass)
    private var state: InvoiceDetailState? = null

    /** Held for in-place updates by [de.littletrotter.ui.view.invoice
     *  .InvoiceDetailView.refreshTotals] — set fresh on every [render]/[refresh]. */
    internal lateinit var cockpitGrossSpan: Span

    /** The route's own incoming query parameters (e.g. list filters), carried through the back
     *  button's navigation to [InvoiceListView] — set once by [InvoiceDetailView.beforeEnter],
     *  mirroring its own `currentQueryParameters` field. */
    internal var queryParameters: QueryParameters = QueryParameters.empty()

    init { setWidthFull() }

    override fun getComponent(): Component = this

    /** Registers the [InvoiceDetailState] this bean (and [InvoiceStatusActions]) report write
     *  outcomes through — called once by [InvoiceDetailView]. */
    internal fun bind(state: InvoiceDetailState) {
        this.state = state
        statusActions.bind(state)
    }

    /** What the last [render] showed; [refresh] skips a rebuild that would show the same again. */
    private var lastRenderKey: HeroRenderKey? = null

    fun render(current: InvoiceDetailDto) = render(current, renderKeyOf(current))

    private fun render(current: InvoiceDetailDto, key: HeroRenderKey) {
        removeAll()
        classNames.clear()
        val status = current.statusEnum()
        cockpitGrossSpan = Span(formatCurrency((current.grossAmount ?: Money.ZERO).amount, current.currencyEnum())).apply { addClassName("ch-gross") }
        val headerRow = buildCockpitHeaderRow(current, status)
        val partiesDiv = buildCockpitPartiesRow(current)

        val actionsDiv = buildCockpitActions(current, key.canCreateCreditNote)
        val footerRow = Div(partiesDiv, actionsDiv).apply { addClassName("ch-footer") }

        add(headerRow, footerRow)
        actions.failureBanner?.let { banner ->
            banner.render(current) { reloadAfterRegeneration(current.id) }
            add(banner)
        }
        setWidthFull()
        addClassNames("cockpit-hero", cockpitHeroStatusClass(status))
        buildFactRail(current, key.cancellationRelation)?.let { add(it) }
        lastRenderKey = key
    }

    private fun reloadAfterRegeneration(invoiceId: Long) {
        invoiceDetailQueryPort.findInvoiceDetailDto(invoiceId)?.let { state?.applyChange(it) }
    }

    /**
     * Rebuilds the hero only when something it shows has changed. Finalizing an invoice arrives as
     * several pushes and poll ticks (job finished, status issued, job no longer active) that all
     * carry the same committed row; a rebuild on each of them replaces the action buttons while the
     * user may be clicking one, and a click on a node the server has already discarded is dropped
     * without a trace. While a generation job is running the hero is always rebuilt, because the
     * finalize action shows the seconds elapsed since the job was created.
     */
    fun refresh(current: InvoiceDetailDto) {
        val key = renderKeyOf(current)
        if (key == lastRenderKey && !current.hasActiveGenerationJob()) return
        render(current, key)
    }

    private fun renderKeyOf(current: InvoiceDetailDto) =
        HeroRenderKey(
            current,
            invoiceDetailQueryPort.canCreateCreditNote(current.id),
            mailActions.sendGate(current),
            if (current.statusEnum() == InvoiceStatus.CANCELLED) invoiceDetailQueryPort.findCancellationRelation(current.id) else null,
        )

    private fun buildCockpitBackButton(): Button =
        AppButtons.icon(ActionIcons.BACK, tr("invoiceDetail.backToList")) {
            UI.getCurrent().navigate(InvoiceListView::class.java, queryParameters)
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
            addClassName("ch-back-btn")
        }

    // ── Row 1: invoice number (left, hero) + status badge (right) ────────────
    private fun buildCockpitHeaderRow(current: InvoiceDetailDto, status: InvoiceStatus): Div {
        val invNumber = current.invoiceNumber.orPlaceholder("—")
        // Resolves the cancellation pair by type code (both halves are CANCELLED): this is the very
        // page where telling the cancelled original from the cancellation document matters most.
        // The detail cockpit is outside the table sweep (space exists here), so this stays a
        // written chip; only the hand-built Span+class-list is replaced by the shared StatusChip.
        val statusBadge = StatusChip(tr(statusBadgeLabelKey(status, current.typeCodeEnum())), invoiceStatusTone(status)).apply {
            addClassName("ch-status-badge")
        }
        val identityDiv = Div().apply {
            addClassName("ch-identity")
            add(buildCockpitBackButton())
            // .ch-number now ellipsizes on a narrow header instead of overflowing;
            // the title carries the full number so it's still readable when clipped.
            add(Span(invNumber).apply { addClassName("ch-number"); element.setAttribute(ATTR_TITLE, invNumber) })
            add(statusBadge)
        }
        val kpi = Div(Span(tr("invoiceDetail.kpi.gross")).apply { addClassName("ch-kpi-label") }, cockpitGrossSpan)
            .apply { addClassName("ch-kpi") }
        return Div(identityDiv, kpi).apply { addClassName("ch-header") }
    }

    // ── Row 2: parties (left) + actions (right) ───────────────────────────────
    private fun buildCockpitPartiesRow(current: InvoiceDetailDto): Div {
        val senderName = current.sender?.name.orPlaceholder("—")
        val recipientName = current.recipient?.name.orPlaceholder("—")

        // .ch-party-name already ellipsizes at max-width: 24rem (CSS); the title carries
        // the full sender/recipient name so a long one stays reachable when clipped.
        val senderParty = Div().apply {
            addClassName("ch-party")
            add(Span(tr("invoiceDetail.party.from")).apply { addClassName("ch-party-role") })
            add(Span(senderName).apply { addClassName("ch-party-name"); element.setAttribute(ATTR_TITLE, senderName) })
        }
        val recipientParty = Div().apply {
            addClassName("ch-party")
            add(Span(tr("invoiceDetail.party.to")).apply { addClassName("ch-party-role") })
            add(Span(recipientName).apply { addClassName("ch-party-name"); element.setAttribute(ATTR_TITLE, recipientName) })
        }
        return Div(senderParty, Span("→").apply { addClassName("ch-arrow") }, recipientParty).apply {
            addClassName("ch-parties")
        }
    }

    /**
     * The cockpit hero's action button row, split out of [render] purely to keep that function
     * under the module's complexity budget — the branching here (status actions,
     * Cancellation, mail, Nachkalkulation, Excel export) is inherent to the feature set, not accidental
     * complexity worth flattening further.
     */
    private fun buildCockpitActions(current: InvoiceDetailDto, canCreateCreditNote: Boolean): Div {
        val actionsDiv = Div().apply { addClassName("ch-actions") }

        val statusButtons = statusActions.buildButtons(this, current, canCreateCreditNote)
        val danger = statusButtons.filter { it.hasClassName(CH_BTN_DANGER) }
        val primary = statusButtons.filter { it is Button && it.element.themeList.contains("primary") }
        val secondary = statusButtons - danger.toSet() - primary.toSet()
        danger.forEach { actionsDiv.add(it) }
        if (danger.isNotEmpty()) actionsDiv.add(Span().apply { addClassName("ch-actions-sep") })
        // The per-invoice Excel export button (icon-only, workbook only). `null` omits the button.
        invoiceWorkbookExportService?.let { service ->
            actionsDiv.add(
                buildInvoiceExportButton(
                    current,
                    service,
                    tr = { key -> tr(key) },
                    trWithArg = { key, arg -> tr(key, arg) },
                ),
            )
        }
        secondary.forEach { actionsDiv.add(it) }
        // Nachkalkulation: recomputes and persists all amounts (net/vat/gross) from the items.
        // Hidden for finalized/cancelled invoices — recalculateInvoice rejects those anyway.
        if (!current.isReadOnly()) {
            actionsDiv.add(
                AppButtons.secondary(tr("invoiceDetail.recalculate"), ActionIcons.REFRESH) {
                    recalculateAndRefresh(current)
                }.apply { addClassNames(CH_ACTION_BTN, "ch-btn--recalculate") },
            )
        }
        primary.forEach { actionsDiv.add(it) }
        return actionsDiv
    }

    /**
     * The fact rail under the hero: the mail lead fact (sent + journal link, local projection only —
     * no call into the mail component on this render path, see [InvoiceMailActions.buildHistory])
     * followed by the invoice-level Empfänger/Land/USt-Id/Steuersatz facts and a stale warning when an
     * already-snapshotted item's rule no longer matches what [TaxRuleResolver] would propose for the
     * invoice's *current* recipient ([isProposalStale]). `null` when there is no fact to show.
     */
    private fun buildFactRail(current: InvoiceDetailDto, cancellationRelation: CancellationRelationDto?): Div? {
        val status = current.statusEnum()
        val terminal = status == InvoiceStatus.CANCELLED || status == InvoiceStatus.VOIDED
        val leads = listOfNotNull(buildProgressLead(current)) + when (status) {
            InvoiceStatus.DRAFT -> listOf(buildCreatedFact(current))
            InvoiceStatus.CANCELLED -> listOfNotNull(buildCancellationLead(cancellationRelation), mailActions.buildHistory(this, current))
            InvoiceStatus.VOIDED -> listOf(buildVoidedFact(), buildCreatedFact(current))
            else -> listOfNotNull(mailActions.buildHistory(this, current))
        }
        val facts = if (terminal) emptyList() else listOfNotNull<Component>(buildValidationFact(current)) + buildTaxFacts(current)
        return chFactRail(leads, facts)
    }

    /** "Angelegt <date>" from the existing creation timestamp. */
    private fun buildCreatedFact(current: InvoiceDetailDto): Span =
        chLeadFact(VaadinIcon.CLOCK, tr("invoiceDetail.rail.createdAt")).also { fact ->
            current.createdAt?.let { fact.add(Span(createdDateFormat().format(it)).apply { addClassName("ch-fact-value") }) }
        }

    private fun createdDateFormat(): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(UI.getCurrent()?.locale ?: java.util.Locale.getDefault())

    private fun buildVoidedFact(): Span = chLeadFact(VaadinIcon.CLOSE_CIRCLE, tr("invoiceDetail.rail.voided"))

    /** "Storniert durch <credit note>" / "Cancellation zu <original>", linking to the counterpart; `null` when unresolved. */
    private fun buildCancellationLead(relation: CancellationRelationDto?): Span? {
        relation ?: return null
        val key = if (relation.role == CancellationRole.CREDIT_NOTE) "invoiceDetail.rail.cancelledBy" else "invoiceDetail.rail.stornoOf"
        return chLeadFact(invoiceStatusGlyphIcon(InvoiceStatus.CANCELLED), tr(key)).also { fact ->
            fact.add(
                RouterLink(relation.counterpartNumber, InvoiceDetailView::class.java, RouteParameters("id", relation.counterpartId.toString()))
                    .apply { addClassName("ch-fact-link") },
            )
        }
    }

    /** Lead fact while the finalize (`SEND`) job runs; the DTO check avoids a job query on every render. */
    private fun buildProgressLead(current: InvoiceDetailDto): Span? {
        if (!current.hasActiveGenerationJob()) return null
        val seconds = statusActions.inFlightSendSeconds(current.id) ?: return null
        return Span(Span().apply { addClassName("ch-fact-dot") }, Span(tr("invoiceDetail.finalize.inProgress", seconds)))
            .apply { addClassNames(CH_FACT, CH_FACT_LEAD, "ch-fact--progress") }
    }

    /** Validation status as a rail fact; only for the editable statuses (DRAFT, IN_REVIEW). */
    private fun buildValidationFact(current: InvoiceDetailDto): Span? {
        if (current.statusEnum() !in setOf(InvoiceStatus.DRAFT, InvoiceStatus.IN_REVIEW)) return null
        val status = current.validationStatusEnum()
        val fact = chFact(tr("invoiceDetail.validation.label"), validationStatusLabel(status) { tr(it) })
        when (status) {
            ValidationStatus.INVALID -> fact.addClassName("ch-fact--bad")
            ValidationStatus.UNAVAILABLE -> fact.addClassName(CH_FACT_WARN)
            ValidationStatus.PENDING -> {
                fact.addClassName("ch-fact--progress")
                fact.addComponentAsFirst(Span().apply { addClassName("ch-fact-dot") })
            }
            ValidationStatus.VALID -> {
                fact.addClassName("ch-fact--ok")
                fact.addComponentAsFirst(VaadinIcon.CHECK.create())
            }
            ValidationStatus.NOT_VALIDATED -> Unit
        }
        when (status) {
            ValidationStatus.UNAVAILABLE -> fact.element.setAttribute(ATTR_TITLE, tr("invoice.validation.unavailable.tooltip"))
            ValidationStatus.PENDING -> fact.element.setAttribute(ATTR_TITLE, tr("invoice.validation.pending.tooltip"))
            else -> Unit
        }
        return fact
    }

    /** Tax facts; empty before enough recipient master data exists (no recipient, or not resolvable). */
    private fun buildTaxFacts(current: InvoiceDetailDto): List<Component> {
        val recipientId = current.recipient?.id ?: return emptyList()
        val recipient = recipientQueryPort.findRecipientById(recipientId) ?: return emptyList()
        val countryAlpha2 = recipientQueryPort.findBillingAddress(recipientId)?.countryAlpha2

        val today = time.today()
        val request = buildTaxRuleResolutionRequest(recipientQueryPort, current, today, ServiceType.GENERAL_SERVICE)
        val resolution = request?.let { TaxRuleResolver.resolve(it, today) }
        val proposedRate = resolution?.matchingRate(taxRateQueries.findAllTaxRates())

        val customerTypeLabel = when (recipient.customerType) {
            CustomerType.BUSINESS -> tr("invoiceDetail.taxRule.customerType.business")
            CustomerType.CONSUMER -> tr("invoiceDetail.taxRule.customerType.consumer")
            null -> "—"
        }
        val vatIdProofLabel = when (recipient.vatIdVerificationStatus) {
            VatIdVerificationStatus.VERIFIED -> tr("invoiceDetail.taxRule.verificationStatus.verified")
            VatIdVerificationStatus.FAILED -> tr("invoiceDetail.taxRule.verificationStatus.failed")
            VatIdVerificationStatus.UNVERIFIED, null -> tr("invoiceDetail.taxRule.verificationStatus.unverified")
        }
        val facts = mutableListOf(
            chFact(tr("invoiceDetail.rail.customerType"), customerTypeLabel),
            chFact(tr("invoiceDetail.rail.country"), countryAlpha2.orPlaceholder("—")),
            chFact(tr("invoiceDetail.rail.vatId"), vatIdProofLabel),
        )
        if (resolution != null) {
            // The rate fact carries the value only; a blocked resolution shows the placeholder and
            // its explanation as a separate warning fact.
            facts.add(chFact(tr("invoiceDetail.rail.rate"), proposedRate?.let(::selectedTaxRateLabel) ?: "—"))
            if (resolution is TaxRuleResolution.Blocked) {
                facts.add(Span(taxRuleHintText(this, resolution, proposedRate)).apply { addClassNames(CH_FACT, CH_FACT_WARN) })
            }
        }
        if (current.items.any { isProposalStale(it, resolution) }) {
            facts.add(Span(tr("invoiceDetail.taxRule.stale")).apply { addClassNames(CH_FACT, CH_FACT_WARN) })
        }
        return facts
    }

    /**
     * Recalculates the invoice totals through the dedicated command instead of a full save.
     *
     * [EditInvoiceHeaderUseCase.recalculateInvoice] is the one operation that expresses this
     * intent: it recomputes and persists totals without running
     * [de.littletrotter.domain.invoice.Invoice.validate], and it rejects finalized or cancelled
     * invoices. Wired to the "Nachkalkulation" action button in [buildCockpitActions], which is
     * only rendered for editable invoices (DRAFT / IN_REVIEW) — matching the read-only guard the
     * command enforces.
     */
    internal fun recalculateAndRefresh(current: InvoiceDetailDto) {
        val invoiceId = current.id
        try {
            val savedDto = editInvoiceHeaderUseCase.recalculateInvoice(invoiceId)
            state?.applyChange(savedDto)
            UiNotifications.success(tr("invoiceDetail.recalculate.success"))
        } catch (e: Exception) {
            // Click listener ("Nachkalkulation" button): must not let an exception escape. Logged
            // with the invoice id so a failed recalculation is discoverable.
            logRejection(log, "Failed to recalculate invoiceId=$invoiceId", e)
            if (isConcurrencyConflict(e)) {
                UiNotifications.error(this, e)
                state.reloadOnConflict(e)
            } else {
                UiNotifications.error(tr("invoiceDetail.error.saveFailed", e.message ?: ""))
            }
        }
    }
}

/** One label/value fact of the hero's fact rail. */
internal fun chFact(label: String, value: String): Span =
    Span(Span(label).apply { addClassName("ch-fact-label") }, Span(value).apply { addClassName("ch-fact-value") })
        .apply { addClassName(CH_FACT) }

/** One lead of the hero's fact rail: glyph and label; the caller appends a value where there is one. */
internal fun chLeadFact(icon: VaadinIcon, label: String): Span =
    Span(icon.create(), Span(label).apply { addClassName("ch-fact-label") }).apply { addClassNames(CH_FACT, CH_FACT_LEAD) }

/** Leads first, then facts, a separator only between both groups; `null` when both are empty, so no empty strip is drawn. */
internal fun chFactRail(leads: List<Component>, facts: List<Component>): Div? {
    if (leads.isEmpty() && facts.isEmpty()) return null
    val rail = Div().apply { addClassName("ch-rail") }
    leads.forEach { rail.add(it) }
    if (leads.isNotEmpty() && facts.isNotEmpty()) rail.add(Span().apply { addClassName("ch-rail-sep") })
    facts.forEach { rail.add(it) }
    return rail
}

internal fun cockpitHeroStatusClass(status: InvoiceStatus): String = lifecycleHeroClass(invoiceLifecycleMeaning(status))

/** Same relabel as [de.littletrotter.ui.view.invoice.InvoiceListView]'s
 *  own `buildValidationChip`: revises the 2026-08-24 PO decision that made UNAVAILABLE read
 *  exactly like INVALID. The domain enum keeps its five values (`FinalizationPolicy` still
 *  needs UNAVAILABLE vs. INVALID), and now so does the chip — UNAVAILABLE gets its own amber
 *  `unavailable` css modifier, red stays exclusively INVALID.
 *
 *  PENDING is now truthful ("a job is running right
 *  now"), so NOT_VALIDATED ("no run has ever started") gets its own neutral chip instead of also
 *  reading as PENDING. Takes [host] (rather than an [InvoiceCockpitHero] receiver) so any caller
 *  in this package can build the chip without depending on the cockpit itself. */
internal fun buildValidationChip(host: Component?, validationStatus: ValidationStatus): Span {
    val label = when (validationStatus) {
        ValidationStatus.VALID -> UiTranslations.tr(host, "invoice.validation.valid")
        ValidationStatus.INVALID -> UiTranslations.tr(host, "invoice.validation.invalid")
        ValidationStatus.UNAVAILABLE -> UiTranslations.tr(host, "invoice.validation.unavailable")
        ValidationStatus.NOT_VALIDATED -> UiTranslations.tr(host, "invoice.validation.notValidated")
        ValidationStatus.PENDING -> UiTranslations.tr(host, "invoice.validation.pending")
    }
    val tone = validationStatusTone(validationStatus)
    val tooltip = when (validationStatus) {
        ValidationStatus.UNAVAILABLE -> UiTranslations.tr(host, "invoice.validation.unavailable.tooltip")
        ValidationStatus.PENDING -> UiTranslations.tr(host, "invoice.validation.pending.tooltip")
        else -> null
    }
    // Outside the table sweep (this badge sits in the cockpit header's action row, not a
    // grid cell), so it stays a written chip; only the hand-built Span+class-list is replaced.
    return StatusChip(label, tone, pulsing = validationStatus == ValidationStatus.PENDING).apply {
        tooltip?.let { element.setAttribute(ATTR_TITLE, it) }
    }
}

/**
 * Everything a [InvoiceCockpitHero.render] shows that is not derived from the current time: the
 * committed invoice row plus the two per-render queries (credit-note eligibility, mail readiness
 * gate). Two refreshes with the same key would render the same hero.
 */
private data class HeroRenderKey(
    val invoice: InvoiceDetailDto,
    val canCreateCreditNote: Boolean,
    val mailGate: MailSendGate?,
    val cancellationRelation: CancellationRelationDto?,
)

/** True while the latest generation job of the invoice has not reached a terminal state. */
private fun InvoiceDetailDto.hasActiveGenerationJob(): Boolean =
    latestGenerationJob?.status in ACTIVE_JOB_STATUSES

private val ACTIVE_JOB_STATUSES = setOf(GenerationJobStatus.PENDING, GenerationJobStatus.DELEGATED, GenerationJobStatus.RUNNING).map { it.name }.toSet()
