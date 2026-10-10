package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.details.Details
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.Currency
import de.littletrotter.shared.InvoiceCashDiscountDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.sum
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.Section
import de.littletrotter.ui.component.addressLines
import de.littletrotter.ui.component.overviewAddress
import de.littletrotter.ui.component.overviewField
import de.littletrotter.ui.component.overviewFieldGrid
import de.littletrotter.ui.component.overviewLead
import de.littletrotter.ui.components.freetext.freeTextHtml
import de.littletrotter.ui.component.orPlaceholder
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import de.littletrotter.ui.component.ActionIcons

private const val INH_SUB_VALUE = "inh-sub-value"
private const val INH_SUB_ROW = "inh-sub-row"
private const val INH_SUB_LABEL = "inh-sub-label"
private const val INH_SUB_DOTS = "inh-sub-dots"
private const val INO_CARD = "ino-card"
private const val INO_CARD_PADDED = "ino-card--padded"

// ── Pure functions ──────────────────────────────────────────────────────────

/**
 * The recipient addresses the overview shows, as display-only [AddressDto]s (street, postal code,
 * city, country) — exactly what a read-only pane needs, and never a domain address, which only
 * exists together with its owning party.
 */
internal data class ResolvedRecipientAddresses(
    val billing: AddressDto?,
    val deliveryForDisplay: AddressDto?,
)

/** The two address lookups [resolveRecipientAddresses] needs, bundled so the function's own "current vs. resolved" addresses (both already the
 *  [ResolvedRecipientAddresses] shape) aren't crowded out by how they're fetched. */
internal class RecipientAddressLookup(
    val findBilling: (Long) -> AddressDto?,
    val findDelivery: (Long) -> AddressDto?,
)

internal fun resolveRecipientAddresses(
    recipientId: Long?,
    current: ResolvedRecipientAddresses,
    lookup: RecipientAddressLookup,
): ResolvedRecipientAddresses {
    val billing = recipientId?.let(lookup.findBilling) ?: current.billing
    val delivery = recipientId?.let(lookup.findDelivery) ?: current.deliveryForDisplay
    return ResolvedRecipientAddresses(
        billing = billing,
        deliveryForDisplay = delivery ?: billing,
    )
}

/**
 * The label for one B2B compliance finding row: every finding is a violation (the rule set only
 * ever reports what's missing), so the label is always the profile's own anwenderdefinierte Definition — the actual value-add over a bare
 * "BT-19 fehlt" — prefixed with the position number when the finding hangs off a line item.
 *
 * Shared by [InvoiceOverviewPanel]'s read-only checklist and the entry pane's E-Rechnung
 * PRÜFUNG step so the two never derive this text differently. [lineFindingLabel] builds the position-prefixed variant — passed in rather than a
 * bare `tr` here so this stays independent of which `tr` overload the caller has in scope.
 */
internal fun b2bComplianceFindingLabel(
    finding: B2bFindingDto,
    ifEmptySuffix: (conditionBtCode: String) -> String,
    lineFindingLabel: (positionId: String, definition: String) -> String,
): String {
    val definition = finding.definition
        ?: finding.businessTermCodes.firstOrNull()
        ?: finding.ruleId
    val text = finding.businessTermCodes.getOrNull(1)
        ?.let { conditionBtCode -> "$definition ${ifEmptySuffix(conditionBtCode)}" }
        ?: definition
    return finding.positionId?.let { positionId -> lineFindingLabel(positionId, text) } ?: text
}

private fun AddressDto?.isCompleteForChecklist(): Boolean =
    this != null && !street.isNullOrBlank() && !postalCode.isNullOrBlank() && !city.isNullOrBlank()

internal fun applyRecipientAddressChecks(
    checks: List<InvoiceCompletenessCheck>,
    resolved: ResolvedRecipientAddresses,
): List<InvoiceCompletenessCheck> {
    val billingComplete = resolved.billing.isCompleteForChecklist()
    val deliveryComplete = resolved.deliveryForDisplay.isCompleteForChecklist()
    return checks.map { check ->
        when (check.translationKey) {
            "invoiceDetail.summary.checklist.recipientAddress" -> check.copy(isComplete = billingComplete || deliveryComplete)
            "invoiceDetail.summary.checklist.recipientBillingAddress" -> check.copy(isComplete = billingComplete)
            "invoiceDetail.summary.checklist.recipientDeliveryAddress" -> check.copy(isComplete = deliveryComplete)
            else -> check
        }
    }
}

internal fun cockpitField(label: String, value: String, icon: VaadinIcon? = null): Div {
    val labelEl = Span(label).apply { addClassName("cockpit-field-label") }
    val valueEl = Span(value).apply { addClassName("cockpit-field-value") }
    return Div().apply {
        addClassName("cockpit-field")
        icon?.let {
            val ico = it.create().apply { setSize("0.85rem"); addClassName("cockpit-field-icon") }
            add(HorizontalLayout(ico, labelEl).apply { setSpacing(false); isPadding = false; defaultVerticalComponentAlignment = Alignment.CENTER; addClassName("lt-gap-030") })
        } ?: add(labelEl)
        add(valueEl)
    }
}

internal fun formatAddress(address: AddressDto?): String =
    listOf(address?.street, address?.postalCode, address?.city, address?.countryAlpha2)
        .filter { !it.isNullOrBlank() }
        .joinToString(", ")
        .ifBlank { "-" }

internal data class CategoryGroupSummary(
    val icon: String,
    val labelKey: String,
    val count: Int,
    val netTotal: Money,
)

// Every trip-related position (travel, expense, per diem) is one TRIP category, and a position can
// belong to only one dashboard tile: trips go to the trip tile, the "goods" tile holds products only.
internal val INVOICE_CATEGORY_GROUPS: List<Triple<String, String, Set<de.littletrotter.shared.catalogue.InvoiceItemCategory>>> = listOf(
    Triple("\u2699",  "invoiceDetail.summary.dashboard.services", setOf(de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE)),
    Triple("\uD83D\uDCE2", "invoiceDetail.summary.dashboard.goods",    setOf(de.littletrotter.shared.catalogue.InvoiceItemCategory.PRODUCT)),
    // Shares the trip tab's label, so every trip-facing text uses the same "trip" wording.
    Triple("\u2708",  "invoiceDetail.tab.trips",   setOf(de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP)),
    Triple("\ud83d\udccf", "invoiceDetail.summary.dashboard.unitPrice", setOf(de.littletrotter.shared.catalogue.InvoiceItemCategory.UNIT_PRICE)),
)

internal fun buildCategorySummaries(items: List<ItemDto>): List<CategoryGroupSummary> =
    INVOICE_CATEGORY_GROUPS.mapNotNull { (icon, labelKey, categories) ->
        val matching = items.filter { it.categoryEnum() in categories }
        if (matching.isEmpty()) null
        else CategoryGroupSummary(
            icon = icon,
            labelKey = labelKey,
            count = matching.size,
            netTotal = matching.map { it.netAmount ?: Money.ZERO }.sum(),
        )
    }

// ── InvoiceOverviewPanel component ───────────────────────────────────────────

/**
 * Overview tab: renders the read-only document pane, including the net/vat/gross total spans,
 * which [render] rebuilds from scratch on every call. Injected once per detail view and re-rendered
 * on every data change; it reads the compliance checks and the recipient's current addresses
 * itself, everything else comes from the invoice passed to [render].
 */
@Dependent
class InvoiceOverviewPanel
@Inject
constructor(
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    private val recipientQueryPort: RecipientQueryPort,
) : VerticalLayout(), TranslationProvider {

    /** Called when the user clicks "Edit" — the parent enters entry mode. */
    var onEdit: () -> Unit = {}

    init {
        isPadding = false; isSpacing = true; setWidthFull()
        alignItems = Alignment.STRETCH
    }

    override fun getComponent(): Component = this

    fun render(current: InvoiceDetailDto) {
        removeAll()
        add(buildOverview(current))
    }

    private fun buildOverview(current: InvoiceDetailDto): Component {
        val recipient = current.recipient
        val recipientAddresses = resolveRecipientAddresses(
            recipientId = recipient?.id,
            // An invoice can override only the delivery address, never the recipient's billing
            // address, so the billing fallback is always empty.
            current = ResolvedRecipientAddresses(billing = null, deliveryForDisplay = current.deliveryAddress),
            lookup = RecipientAddressLookup(
                findBilling = recipientQueryPort::findBillingAddress,
                findDelivery = recipientQueryPort::findDeliveryAddress,
            ),
        )
        val canEdit = !current.isReadOnly()
        return buildDocumentPane(current, recipient, recipientAddresses, canEdit)
    }

    private fun buildDocumentPane(
        current: InvoiceDetailDto,
        recipient: PartyDto?,
        recipientAddresses: ResolvedRecipientAddresses,
        canEdit: Boolean,
    ): Component {
        val leftCol = buildDocumentLeftColumn(current, recipient, recipientAddresses, canEdit)
        val sidePane = buildDocumentSidePane(current, recipientAddresses)
        return HorizontalLayout(leftCol, sidePane).apply {
            setWidthFull(); alignItems = Alignment.STRETCH
            addClassName("lt-document-columns")
        }
    }

    private fun buildDocumentLeftColumn(
        current: InvoiceDetailDto,
        recipient: PartyDto?,
        recipientAddresses: ResolvedRecipientAddresses,
        canEdit: Boolean,
    ): VerticalLayout {
        val leftCol = VerticalLayout().apply {
            isPadding = false; isSpacing = true; setWidthFull()
            alignItems = Alignment.STRETCH
            addClassName("invoice-overview-main")
        }
        if (canEdit) {
            leftCol.add(buildEditButtonRow())
        }
        leftCol.add(buildReadOnlyDatesPanel(current))
        leftCol.add(buildReadOnlyOptionsCard(current))
        leftCol.add(buildPartyCards(current, recipient, recipientAddresses))
        return leftCol
    }

    private fun buildEditButtonRow(): ActionBar =
        ActionBar(AppButtons.tertiary(tr("invoiceDetail.overview.edit"), ActionIcons.EDIT) { onEdit() }).apply {
            // Same un-padded tab slot as the Positionen toolbar: without the shared toolbar class the
            // right-aligned "Bearbeiten" button sits flush against the tab panel's edge.
            addClassName("item-toolbar-row")
        }

    private fun buildPartyCards(
        current: InvoiceDetailDto,
        recipient: PartyDto?,
        recipientAddresses: ResolvedRecipientAddresses,
    ): Div = Div(
        Section(tr("invoiceDetail.tab.recipient"), content = buildRecipientBlock(current, recipient, recipientAddresses)),
        // The sender is the user's own company and rarely of interest on a single invoice, so it
        // stays folded away and the recipient gets the full width.
        Details(tr("invoiceDetail.tab.sender"), buildSenderBlock(current)).apply {
            isOpened = false
            setWidthFull()
            addClassName("overview-sender-details")
        },
    ).apply { addClassName("overview-party-stack") }

    private fun buildRecipientBlock(
        current: InvoiceDetailDto,
        recipient: PartyDto?,
        recipientAddresses: ResolvedRecipientAddresses,
    ): Div {
        val name = recipient?.name
        if (recipient == null || name == null) {
            return Div(overviewLead(tr("invoiceDetail.recipient.noneAssigned")))
        }
        val billing = recipientAddresses.billing
        val address = overviewAddress(addressLines(billing?.street, billing?.postalCode, billing?.city, billing?.countryAlpha2))
        val fields = overviewFieldGrid()
        listOfNotNull(
            overviewField(tr("recipient.customerNumber"), recipient.customerNumber),
            overviewField(tr("recipient.vatId"), recipient.vatId),
            overviewField(tr("recipient.leitwegId"), recipient.routingId),
            overviewField(tr("recipient.contactPerson"), recipient.contactPerson),
            overviewField(tr("recipient.email"), recipient.email),
            overviewField(tr("recipient.phone"), recipient.phone),
            overviewField(tr("recipient.documentLanguage"), tr("recipient.documentLanguage." + current.documentLanguage)),
            overviewField(tr("invoiceDetail.tab.deliveryAddress"), differingDeliveryAddress(recipientAddresses), wide = true),
        ).forEach(fields::add)
        return Div(*listOfNotNull<Component>(overviewLead(name), address, fields).toTypedArray())
    }

    /** The delivery address as text, or `null` when there is none or it equals the billing address. */
    private fun differingDeliveryAddress(addresses: ResolvedRecipientAddresses): String? {
        val delivery = formatAddress(addresses.deliveryForDisplay)
        return delivery.takeIf { it != "-" && it != formatAddress(addresses.billing) }
    }

    // The read model already projects the invoice's effective sender into `dto.sender`, so the
    // panel needs no fallback between a selected seller and the sender profile.
    private fun buildSenderBlock(current: InvoiceDetailDto): Div {
        val sender = current.sender
        val name = sender?.name
        if (sender == null || name == null) {
            return Div(overviewLead(tr("invoiceDetail.sender.noneAssigned")))
        }
        val billing = current.billingAddress
        val address = overviewAddress(addressLines(billing?.street, billing?.postalCode, billing?.city, billing?.countryAlpha2))
        val fields = overviewFieldGrid()
        listOfNotNull(
            overviewField(tr("senderProfile.taxNumber"), sender.taxNumber),
            overviewField(tr("senderProfile.vatId"), sender.vatId),
            overviewField(tr("senderProfile.commercialRegisterNumber"), sender.commercialRegisterNumber),
            overviewField(tr("senderProfile.courtOfJurisdiction"), sender.courtOfJurisdiction),
            overviewField(tr("senderProfile.email"), sender.email),
            overviewField(tr("senderProfile.phone"), sender.phone),
        ).forEach(fields::add)
        return Div(*listOfNotNull<Component>(overviewLead(name), address, fields).toTypedArray())
    }

    private fun buildDocumentSidePane(
        current: InvoiceDetailDto,
        recipientAddresses: ResolvedRecipientAddresses,
    ): VerticalLayout = VerticalLayout(buildTotalCard(current), buildChecklistCard(current, recipientAddresses)).apply {
        isPadding = false; isSpacing = true; setWidthFull()
        addClassName("invoice-overview-sidebar")
    }

    private fun resolveTotalCardStatusClass(status: InvoiceStatus): String = when (status) {
        InvoiceStatus.SENT -> "invoice-total-card--sent"
        InvoiceStatus.CANCELLED -> "invoice-total-card--cancelled"
        InvoiceStatus.VOIDED -> "invoice-total-card--voided"
        InvoiceStatus.IN_REVIEW -> "invoice-total-card--review"
        else -> "invoice-total-card--draft"
    }

    /** One "label ⋯⋯ value" row of [buildTotalCard]'s subtotals block, extracted purely to keep
     *  that function's nesting (and so its detekt cyclomatic-complexity count) under the module's
     *  complexity budget — there is no branching here. */
    private fun buildTotalCardSubtotalRow(label: String, valueSpan: Component, testId: String? = null): Div = Div().apply {
        addClassName(INH_SUB_ROW)
        testId?.let { element.setAttribute("data-testid", it) }
        add(Span(label).apply { addClassName(INH_SUB_LABEL) })
        add(Div().apply { addClassName(INH_SUB_DOTS) })
        add(valueSpan)
    }

    private fun buildTotalCardSubtotals(currency: Currency, netSpan: Span, vatSpan: Span): Div = Div().apply {
        addClassName("inh-subtotals")
        add(buildTotalCardSubtotalRow(tr("invoiceDetail.currency"), Span(currency.code).apply { addClassName(INH_SUB_VALUE) }, "invoice-total-currency"))
        add(buildTotalCardSubtotalRow(tr("invoiceDetail.netAmount"), netSpan))
        add(buildTotalCardSubtotalRow(tr("invoiceDetail.vatAmount"), vatSpan))
    }

    private fun buildTotalCardGrossSection(grossSpan: Span): Div = Div().apply {
        addClassName("inh-gross-section")
        add(grossSpan)
        add(Span(tr("invoiceDetail.grossAmount")).apply { addClassName("inh-gross-label") })
    }

    private fun buildTotalCard(current: InvoiceDetailDto): Div {
        val statusClass = resolveTotalCardStatusClass(current.statusEnum())
        val currency = current.currencyEnum()
        val netSpan = Span(formatCurrency((current.netAmount ?: Money.ZERO).amount, currency)).apply { addClassName(INH_SUB_VALUE) }
        val vatSpan = Span(formatCurrency((current.vatAmount ?: Money.ZERO).amount, currency)).apply { addClassName(INH_SUB_VALUE) }
        val grossSpan = Span(formatCurrency((current.grossAmount ?: Money.ZERO).amount, currency)).apply { addClassName("inh-gross-amount") }

        return Div().apply {
            addClassNames("invoice-total-hero", statusClass)
            add(Span(tr("invoiceDetail.totalCard.header")).apply { addClassName("inh-eyebrow") })
            add(buildTotalCardSubtotals(currency, netSpan, vatSpan))
            add(Div().apply { addClassName("inh-rule") })
            add(buildTotalCardGrossSection(grossSpan))
        }
    }

    private fun buildChecklistCard(current: InvoiceDetailDto, recipientAddresses: ResolvedRecipientAddresses): Div = Div().apply {
        addClassNames(INO_CARD, INO_CARD_PADDED)
        add(H2(tr("invoiceDetail.summary.checklist.title")))
        addCompletenessCheckRows(current, recipientAddresses)
        addB2bComplianceFindingRows(current)
        addLineAdviceRows(current)
    }

    // The completeness checks are evaluated server-side against a freshly loaded,
    // real Invoice (never exposed here); this panel only ever sees the plain result.
    private fun Div.addCompletenessCheckRows(current: InvoiceDetailDto, recipientAddresses: ResolvedRecipientAddresses) {
        val completenessChecks = invoiceDetailQueryPort.findCompletenessChecks(current.id).taxRelevantCompletenessChecks()
        applyRecipientAddressChecks(completenessChecks, recipientAddresses).forEach { check ->
            add(checkRow(tr(check.translationKey), check.isComplete))
        }
    }

    // Missing-mandatory-field findings from the seller's B2B profile: every row here is a
    // violation (the rule set only ever reports what's missing), so each one always renders as an open point, with the
    // profile's own anwenderdefinierte Definition as label — the actual value-add over a
    // bare "BT-19 fehlt".
    private fun Div.addB2bComplianceFindingRows(current: InvoiceDetailDto) {
        invoiceDetailQueryPort.findB2bComplianceFindings(current.id).forEach { finding ->
            val label = b2bComplianceFindingLabel(
                finding,
                ifEmptySuffix = { conditionBtCode -> tr("compliance.b2b.conditionalIfEmptySuffix", conditionBtCode) },
            ) { positionId, definition ->
                tr("invoiceDetail.summary.checklist.b2bLinePrefix", positionId, definition)
            }
            add(checkRow(label, false))
        }
    }

    // Soft, non-blocking line advisors (BT-153 quality, quantity/unit
    // plausibility). Its own heading, deliberately separate from the checklist above:
    // every row up there gates finalization, these never do.
    private fun Div.addLineAdviceRows(current: InvoiceDetailDto) {
        val lineAdvice = current.items.flatMap { item ->
            LineAdvisor.advise(item.description, item.unitEnum(), item.quantity).map { item.positionId to it }
        }
        if (lineAdvice.isNotEmpty()) {
            add(H2(tr("invoiceDetail.summary.advisor.title")).apply { addClassName("lt-mt-100") })
            lineAdvice.forEach { (positionId, advice) ->
                add(checkRow(tr("invoiceDetail.summary.checklist.b2bLinePrefix", positionId, tr(advice.messageKey)), false))
            }
        }
    }

    private fun buildReadOnlyDatesPanel(current: InvoiceDetailDto): Div = Div().apply {
        addClassNames(INO_CARD, INO_CARD_PADDED, "cockpit-dates-card")
        val grid = Div().apply { addClassName("cockpit-dates-grid") }
        // A storno document has no due date and no payment term by design (PO decision 2,
        // 2026-09-02 — the storno flow stamps neither), so both fields are omitted entirely
        // instead of rendering "-" placeholders. The cancelled ORIGINAL keeps showing its real
        // values: it had genuine terms when it was sent.
        val storno = current.typeCodeEnum() == InvoiceTypeCode.CORRECTED_INVOICE
        grid.add(cockpitField(tr("invoiceDetail.invoiceDate"), formatDate(current.invoiceDate), VaadinIcon.CALENDAR))
        if (!storno) {
            grid.add(cockpitField(tr("invoiceDetail.dueDate"), formatDate(current.dueDate), VaadinIcon.CLOCK))
        }
        grid.add(cockpitField(tr("invoiceDetail.servicePeriodFrom"), formatDate(current.servicePeriodFrom), VaadinIcon.CALENDAR_CLOCK))
        grid.add(cockpitField(tr("invoiceDetail.servicePeriodTo"), formatDate(current.servicePeriodTo), VaadinIcon.CALENDAR_CLOCK))
        if (!storno) {
            grid.add(cockpitField(tr("invoiceDetail.paymentTerm"), current.paymentTermName.orPlaceholder(), VaadinIcon.MONEY))
            current.cashDiscount?.let { discount ->
                grid.add(cockpitField(tr("invoiceDetail.cashDiscount"), formatCashDiscount(discount, current.currencyEnum()), VaadinIcon.PIGGY_BANK))
            }
        }
        add(grid)
    }

    /** {0} percent (no trailing zeros, same convention as the tax-rate/VAT displays elsewhere in
     *  this view), {1} deadline, {2} discount amount, {3} amount still payable after taking it. */
    private fun formatCashDiscount(discount: InvoiceCashDiscountDto, currency: Currency): String = tr(
        "invoiceDetail.cashDiscount.value",
        discount.percent.stripTrailingZeros().toPlainString(),
        formatDate(java.time.LocalDate.parse(discount.deadline)),
        formatCurrency(discount.amount.amount, currency),
        formatCurrency(discount.payableAmount.amount, currency),
    )

    private fun buildReadOnlyOptionsCard(current: InvoiceDetailDto): Div = sectionCard(
        tr("invoiceDetail.options.summary"),
        fieldRow(tr("invoiceDetail.reverseCharge"), if (current.reverseCharge) tr("app.yes") else tr("app.no")),
        fieldRow(tr("invoiceDetail.buyerReference"), current.buyerReference?.trim().orPlaceholder()),
        fieldRow(tr("invoiceDetail.purchaseOrderReference"), current.purchaseOrderReference?.trim().orPlaceholder()),
        fieldRow(tr("invoiceDetail.orderReference"), current.orderReference?.trim().orPlaceholder()),
        freeTextRow(current.freeText),
    )

    private fun formatDate(date: java.time.LocalDate?): String =
        date?.let { de.littletrotter.ui.format.DateFormatter.format(it) } ?: "-"

    // Non-nullable on purpose: every caller resolves a missing amount to zero first, so a "-"
    // placeholder branch here could never be reached.
    private fun formatCurrency(amount: java.math.BigDecimal, currency: Currency): String =
        de.littletrotter.ui.format.MoneyFormatter.format(amount, currency)

    private fun sectionCard(title: String, vararg rows: HorizontalLayout): Div = Div().apply {
        addClassNames(INO_CARD, INO_CARD_PADDED)
        add(H2(title))
        rows.forEach { add(it) }
    }

    private fun fieldRow(label: String, value: String): HorizontalLayout =
        fieldRow(label, Span(value).apply { addClassName("detail-field-value") })

    private fun fieldRow(label: String, value: Component): HorizontalLayout = HorizontalLayout().apply {
        setWidthFull(); isSpacing = false
        defaultVerticalComponentAlignment = Alignment.CENTER
        addClassName("lt-gap-050")
        add(Span(label).apply { addClassName("detail-field-label") })
        add(value)
    }

    // The free text is shown formatted, exactly as the editor preview and the print render it.
    private fun freeTextRow(freeText: String?): HorizontalLayout {
        val label = tr("invoiceDetail.freeText")
        val text = freeText?.trimEnd()
        if (text.isNullOrBlank()) return fieldRow(label, text.orPlaceholder())
        return fieldRow(label, Div(freeTextHtml(text)).apply { addClassName("detail-field-value") })
    }

    private fun checkRow(label: String, ok: Boolean): HorizontalLayout = HorizontalLayout().apply {
        setWidthFull(); isSpacing = false
        defaultVerticalComponentAlignment = Alignment.CENTER
        addClassName("lt-gap-040")
        add(Span(if (ok) "\u2713" else "\u25CB").apply {
            addClassName("lt-check-mark"); if (ok) addClassName("lt-check-mark--ok")
        })
        add(Span(label).apply {
            addClassName("lt-check-label"); if (ok) addClassName("lt-check-label--ok")
        })
    }
}
