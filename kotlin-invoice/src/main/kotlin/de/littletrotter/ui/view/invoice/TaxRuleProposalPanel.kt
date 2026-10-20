package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.tax.TaxRuleResolution
import de.littletrotter.shared.tax.TaxRuleResolutionRequest
import de.littletrotter.shared.tax.TaxRuleResolver
import de.littletrotter.ui.component.orPlaceholder
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import java.time.LocalDate

private const val CSS_CLASS_FS_08 = "fs-08"
private const val TAXRULE_HINT_BLOCKED = "taxrule-hint--blocked"
private const val TAXRULE_HINT_PROPOSED = "taxrule-hint--proposed"

/** Shared Proposed/Blocked rendering — used by [TaxRuleProposalPanel]'s inline hint and by
 *  [InvoiceCockpitHero]'s own invoice-level status strip, so the two don't drift apart. Takes
 *  [host] (rather than an `InvoiceDetailView` receiver) so either caller can use it without
 *  depending on the view itself — the same idiom [buildValidationChip] already uses. */
internal fun taxRuleHintText(host: Component?, resolution: TaxRuleResolution?, matchedRate: TaxRateDto?): String = when (resolution) {
    is TaxRuleResolution.Proposed -> matchedRate?.let(::selectedTaxRateLabel).orEmpty()
    is TaxRuleResolution.Blocked -> UiTranslations.tr(host, "invoiceDetail.taxRule.blocked", resolution.detail)
    null -> ""
}

/** The proposal row names only the effective positive country rate. Zero-rate special
 * categories use their category name because a country-prefixed `0 %` would imply a regular
 * country rate that does not exist. */
internal fun selectedTaxRateLabel(rate: TaxRateDto): String =
    if (rate.rate.value.signum() > 0) {
        "${rate.country.alpha2} ${rate.rate.value.stripTrailingZeros().toPlainString()} %"
    } else {
        rate.categoryEnum.shortReasonFallback()
    }

/**
 * The resolver-facing part of a tax-rate ComboBox: a proposal/blocked hint, the
 * Reverse-Charge disclosure (both VAT ids, verification status, mandatory text), and the E/O
 * reason+legal-basis capture the resolver requires before it treats an exemption as complete
 * ([de.littletrotter.shared.tax.ExplicitExemption]). One instance per dialog open —
 * [invoice]/[category] don't change while the dialog is open. Not itself unit-tested (thin
 * Vaadin glue, same as the rest of this package — Karibu-Testing is currently skipped in this
 * repo); the logic it calls into ([requiresExplicitExemptionDetails], [exemptionDetailsIncomplete],
 * [TaxRuleResolver.resolve] itself) is pure and covered in `TaxRuleUiSupportTest`.
 */
internal class TaxRuleProposalPanel(
    recipientQueryPort: RecipientQueryPort,
    allTaxRates: List<TaxRateDto>,
    invoice: InvoiceDetailDto,
    category: InvoiceItemCategory,
    today: LocalDate,
) : VerticalLayout(), TranslationProvider {

    override fun getComponent(): Component = this

    /** Captured at construction (the dialog's tax rule is locked to the Reverse-Charge
     *  rule for the whole time it's open when this is `true`); read by [renderHint] and
     *  [onSelectedRateChanged] so the panel states the decision instead of fighting the lock with
     *  its own proposal/deviation text. */
    private val reverseChargeDecision: Boolean = invoice.reverseCharge

    private val request: TaxRuleResolutionRequest? =
        buildTaxRuleResolutionRequest(recipientQueryPort, invoice, today, category.toServiceType())

    /** The tax rule resolver's verdict for this invoice's recipient context — read by the dialogs through
     *  [effectiveTaxRuleFor] so the article can never silently cancel it. */
    val resolution: TaxRuleResolution? = request?.let { TaxRuleResolver.resolve(it, today) }

    /** The [TaxRateDto] row behind a [TaxRuleResolution.Proposed] — `null` when the resolver has
     *  nothing to say yet (missing master data) or blocked the case outright. */
    val proposedRate: TaxRateDto? = resolution?.matchingRate(allTaxRates)

    private val hintLine = Span().apply { addClassName(CSS_CLASS_FS_08) }
    /** Shown when the currently selected rule's category contradicts the proposal — the state
     *  that used to appear silently when picking a 19 % article on a Reverse-Charge invoice. */
    private val deviationLine = Span().apply { addClassNames(CSS_CLASS_FS_08, TAXRULE_HINT_BLOCKED); isVisible = false }
    private val rcBlock = Div().apply { addClassNames(CSS_CLASS_FS_08, "text-muted"); isVisible = false }
    val exemptionReasonField: TextField = TextField(tr("invoiceDetail.taxRule.exemption.reason")).apply {
        setWidthFull(); isVisible = false
    }
    val exemptionLegalBasisField: TextField = TextField(tr("invoiceDetail.taxRule.exemption.legalBasis")).apply {
        setWidthFull(); isVisible = false
    }

    init {
        isPadding = false; isSpacing = false; setWidthFull()
        addClassName("lt-gap-025")
        renderHint()
        add(hintLine, deviationLine, rcBlock, exemptionReasonField, exemptionLegalBasisField)
    }

    private fun renderHint() {
        // The decision, once set, is a fact about this invoice, not a proposal to weigh
        // against an article choice; stating it (instead of the resolver's own proposed/blocked
        // text, which would just repeat the resolver's case underneath the already-fixed selection) is
        // what "the panel states the decision instead of proposing" means.
        if (reverseChargeDecision) {
            hintLine.text = tr("invoiceDetail.taxRule.reverseChargeDecision")
            hintLine.isVisible = true
            hintLine.removeClassNames(TAXRULE_HINT_BLOCKED, TAXRULE_HINT_PROPOSED)
            hintLine.addClassName(TAXRULE_HINT_PROPOSED)
            return
        }
        val res = resolution
        hintLine.text = taxRuleHintText(this, res, proposedRate)
        hintLine.isVisible = res != null
        hintLine.removeClassNames(TAXRULE_HINT_BLOCKED, TAXRULE_HINT_PROPOSED)
        hintLine.addClassName(if (res is TaxRuleResolution.Blocked) TAXRULE_HINT_BLOCKED else TAXRULE_HINT_PROPOSED)
    }

    /** Wire this to the dialog's own `taxGroup.addValueChangeListener` — the panel doesn't own
     *  the ComboBox itself (dialogs already have their own selection/preview wiring). */
    fun onSelectedRateChanged(rate: TaxRateDto?) {
        val needsExemption = requiresExplicitExemptionDetails(rate)
        exemptionReasonField.isVisible = needsExemption
        exemptionLegalBasisField.isVisible = needsExemption

        // Deviation from the proposal is now stated instead of left to the (static) hint line
        // contradicting the ComboBox — the exact situation that made a 19 % article silently
        // cancel a Reverse-Charge proposal look fine on screen.
        // With the decision ON, the selection is locked to the Reverse-Charge rule
        // itself, so there is nothing left to deviate from; the deviation line would only fight
        // renderHint's "decision" text with a second, contradicting message.
        val proposedCategory = (resolution as? TaxRuleResolution.Proposed)?.category
        val deviates = !reverseChargeDecision && proposedCategory != null &&
            rate != null && rate.categoryEnum != proposedCategory
        deviationLine.isVisible = deviates
        if (deviates) {
            deviationLine.text = tr(
                "invoiceDetail.taxRule.deviation",
                proposedRate?.let(::taxRuleLabel) ?: proposedCategory.code,
            )
        }

        val req = request
        rcBlock.isVisible = rate?.categoryEnum == VatCategory.REVERSE_CHARGE && req != null
        if (rcBlock.isVisible && req != null) {
            rcBlock.removeAll()
            rcBlock.add(
                Div(Span("${tr("invoiceDetail.taxRule.reverseCharge.sellerVatId")}: ${req.sellerVatId.orPlaceholder("—")}")),
                Div(Span("${tr("invoiceDetail.taxRule.reverseCharge.buyerVatId")}: ${req.buyerVatId.orPlaceholder("—")}")),
                Div(Span("${tr("invoiceDetail.taxRule.reverseCharge.verificationStatus")}: ${verificationStatusLabel(req.buyerVatIdVerificationStatus)}")),
                Div(Span("${tr("invoiceDetail.taxRule.reverseCharge.mandatoryText")}: ${rate?.exemptionReasonText.orPlaceholder("—")}")),
            )
        }
    }

    private fun verificationStatusLabel(status: VatIdVerificationStatus?): String = when (status) {
        VatIdVerificationStatus.VERIFIED -> tr("invoiceDetail.taxRule.verificationStatus.verified")
        VatIdVerificationStatus.FAILED -> tr("invoiceDetail.taxRule.verificationStatus.failed")
        VatIdVerificationStatus.UNVERIFIED, null -> tr("invoiceDetail.taxRule.verificationStatus.unverified")
    }

    /** Blocks Save when the selected rate is E/O and reason/legal basis are blank. */
    fun validate(selectedRate: TaxRateDto?): Boolean {
        if (!requiresExplicitExemptionDetails(selectedRate)) return true
        val incomplete = exemptionDetailsIncomplete(exemptionReasonField.value, exemptionLegalBasisField.value)
        if (incomplete) {
            val message = tr("invoiceDetail.taxRule.exemption.required")
            // Marked bottom-up so the focus call made last lands on the first blank field.
            if (exemptionLegalBasisField.value.isBlank()) UiNotifications.errorOn(exemptionLegalBasisField, message)
            if (exemptionReasonField.value.isBlank()) UiNotifications.errorOn(exemptionReasonField, message)
        }
        return !incomplete
    }
}
