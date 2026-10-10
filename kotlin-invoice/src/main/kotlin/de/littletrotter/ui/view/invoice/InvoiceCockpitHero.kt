package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.export.InvoiceWorkbookExportPort
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.tax.ServiceType
import de.littletrotter.shared.tax.TaxRuleResolver
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.orPlaceholder
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.isConcurrencyConflict
import de.littletrotter.ui.view.logRejection
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import org.jboss.logging.Logger

internal const val CH_ACTION_BTN = "CH_ACTION_BTN"
private const val ATTR_TITLE = "title"
private const val CSS_CLASS_FS_08 = "fs-08"

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
    private val invoiceWorkbookExportService: InvoiceWorkbookExportPort?,
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

    /** Opens the mail draft of [current] — the continuation of a send started via "E-Mail senden". */
    internal fun openMailCompose(current: InvoiceDetailDto): Boolean = mailActions.openMailCompose(this, current)

    fun render(current: InvoiceDetailDto) {
        removeAll()
        classNames.clear()
        val status = current.statusEnum()
        val headerRow = buildCockpitHeaderRow(current, status)
        val partiesDiv = buildCockpitPartiesRow(current)

        cockpitGrossSpan = Span(formatCurrency((current.grossAmount ?: Money.ZERO).amount, current.currencyEnum())).apply { addClassName("ch-gross") }

        val actionsDiv = buildCockpitActions(current)
        val footerRow = Div(partiesDiv, actionsDiv).apply { addClassName("ch-footer") }

        add(headerRow, footerRow)
        actions.failureBanner?.let { banner ->
            banner.render(current) { reloadAfterRegeneration(current.id) }
            add(banner)
        }
        setWidthFull()
        addClassNames("cockpit-hero", cockpitHeroStatusClass(status))
        buildTaxRuleStatusStrip(current)?.let { add(it) }
        // Send history from the local projection only; no call into the mail
        // component on this render path (see InvoiceMailActions.buildHistory).
        mailActions.buildHistory(this, current)?.let { add(it) }
    }

    private fun reloadAfterRegeneration(invoiceId: Long) {
        invoiceDetailQueryPort.findInvoiceDetailDto(invoiceId)?.let { state?.applyChange(it) }
    }

    /** Same rebuild as [render] — no persistent slot to preserve here (see [render]'s own KDoc). */
    fun refresh(current: InvoiceDetailDto) = render(current)

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
        // Resolves the storno pair by type code (both halves are CANCELLED): this is the very
        // page where telling the cancelled original from the storno document matters most.
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
        }
        return Div(identityDiv, statusBadge).apply { addClassName("ch-header") }
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
     * under the module's complexity budget — the branching here (validation chip, status actions,
     * Storno, mail, Nachkalkulation, Excel export) is inherent to the feature set, not accidental
     * complexity worth flattening further.
     */
    private fun buildCockpitActions(current: InvoiceDetailDto): Div {
        val actionsDiv = Div().apply { addClassName("ch-actions") }

        val validationStatus = current.validationStatusEnum()
        if (validationStatus == ValidationStatus.INVALID) {
            actionsDiv.add(buildValidationChip(this, validationStatus))
        }
        val canCreateCreditNote = invoiceDetailQueryPort.canCreateCreditNote(current.id)
        statusActions.buildButtons(this, current, canCreateCreditNote).forEach { actionsDiv.add(it) }
        // Nachkalkulation: recomputes and persists all amounts (net/vat/gross) from the items.
        // Hidden for finalized/cancelled invoices — recalculateInvoice rejects those anyway.
        if (!current.isReadOnly()) {
            actionsDiv.add(
                AppButtons.secondary(tr("invoiceDetail.recalculate"), ActionIcons.REFRESH) {
                    recalculateAndRefresh(current)
                }.apply { addClassNames(CH_ACTION_BTN, "ch-btn--recalculate") },
            )
        }
        // The per-invoice Excel export button (icon-only, workbook only). `null`
        // omits the button.
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
        actionsDiv.add(cockpitGrossSpan)
        return actionsDiv
    }

    /**
     * The invoice-level Kundentyp/Empfängerland/USt-Id-Nachweis/Vorschlag strip,
     * plus a stale badge when an already-snapshotted item's rule
     * no longer matches what [TaxRuleResolver] would propose for the invoice's *current* recipient
     * ([isProposalStale]). `null` before enough recipient master data exists (no recipient, or
     * Kundentyp/Land not filled in yet) — same "not enough data" case [buildTaxRuleResolutionRequest]
     * already handles.
     */
    private fun buildTaxRuleStatusStrip(current: InvoiceDetailDto): Div? {
        val recipientId = current.recipient?.id ?: return null
        val recipient = recipientQueryPort.findRecipientById(recipientId) ?: return null
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

        val summaryLine = Span(
            "${tr("invoiceDetail.taxRule.customerType")}: $customerTypeLabel  ·  " +
                "${tr("invoiceDetail.taxRule.recipientCountry")}: ${countryAlpha2.orPlaceholder("—")}  ·  " +
                "${tr("invoiceDetail.taxRule.vatIdProof")}: $vatIdProofLabel",
        ).apply { addClassNames(CSS_CLASS_FS_08, "text-muted") }
        val hintLine = Span(separatedTaxRuleHint(taxRuleHintText(this, resolution, proposedRate))).apply { addClassName(CSS_CLASS_FS_08) }

        val strip = Div(summaryLine, hintLine).apply { addClassName("ch-taxrule-strip") }
        if (current.items.any { isProposalStale(it, resolution) }) {
            strip.add(Span(tr("invoiceDetail.taxRule.stale")).apply { addClassNames(CSS_CLASS_FS_08, "taxrule-hint--blocked") })
        }
        return strip
    }

    /**
     * Joins the hint to the summary line with the same middle-dot separator its entries use; empty stays empty.
     * The spaces are non-breaking because the hint is its own flex item, whose leading whitespace the browser drops.
     */
    internal fun separatedTaxRuleHint(hint: String): String = if (hint.isBlank()) hint else "\u00A0·\u00A0$hint"

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

internal fun cockpitHeroStatusClass(status: InvoiceStatus): String = when (status) {
    InvoiceStatus.SENT -> "cockpit-hero--sent"
    InvoiceStatus.CANCELLED -> "cockpit-hero--cancelled"
    InvoiceStatus.VOIDED -> "cockpit-hero--voided"
    InvoiceStatus.IN_REVIEW -> "cockpit-hero--review"
    InvoiceStatus.DRAFT     -> "cockpit-hero--draft"
}

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
