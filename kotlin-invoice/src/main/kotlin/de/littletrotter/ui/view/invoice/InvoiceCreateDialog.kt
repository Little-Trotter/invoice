package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.exception.RecipientNotFoundException
import de.littletrotter.domain.exception.SenderProfileNotFoundException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.invoice.RecipientVatInfo
import de.littletrotter.domain.invoice.ReverseChargeCreationDecision
import de.littletrotter.domain.invoice.resolveReverseChargeCreationDecision
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.component.recipientItems
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.logDomainRejection
import org.jboss.logging.Logger

/**
 * The "Neue Rechnung" dialog, extracted out of [InvoiceListView] so
 * both it and [de.littletrotter.ui.view.masterdata.RecipientView]'s per-row "Rechnung erstellen"
 * action can open the same dialog instead of two divergent copies.
 *
 * [preselectedRecipient] pins the recipient ComboBox's value once the lazy data provider is
 * wired — the combo's own [de.littletrotter.ui.component.lazyItems] id-getter compares by
 * [RecipientSummaryDto.id], so a value fetched separately (e.g. a customer-grid row) still
 * selects the matching combo item correctly. Seller preselection is unaffected by this and
 * follows [resolvePreselectedSeller] exactly as before.
 *
 * Navigation after a successful create is entirely the caller's concern: [onCreated] fires with
 * the new invoice's id only after this dialog has already closed itself.
 */
class InvoiceCreateDialog(
    private val recipientQuery: RecipientQueryPort,
    private val senderQuery: SenderQueryPort,
    private val createInvoiceUseCase: CreateInvoiceUseCase,
    private val preselectedRecipient: RecipientSummaryDto? = null,
    private val onCreated: (createdInvoiceId: Long) -> Unit,
) : AppDialog("", Size.S), TranslationProvider {

    private val log = Logger.getLogger(javaClass)

    override fun getComponent(): Component = this

    /** Builds the dialog content lazily — the blocking no-seller message, or the full form — then
     *  opens it, same lazy-build-on-open shape [InvoiceListView.openInvoiceCreateDialog] used before
     *  this extraction. */
    override fun open() {
        headerTitle = tr("invoice.dialog.title")

        val allSenders = senderQuery.findAllSenders()
        if (allSenders.isEmpty()) {
            buildNoSellerContent()
        } else {
            buildFormContent(allSenders)
        }
        super.open()
    }

    /** Shown instead of the form when no seller exists yet — there is nothing to create against. */
    private fun buildNoSellerContent() {
        add(Paragraph(tr("invoice.dialog.noSeller")))
        footer(AppButtons.tertiary(tr("app.cancel")) { close() })
    }

    private fun buildFormContent(allSenders: List<SenderSummaryDto>) {
        // The true count for the hint text and the combo/create-button enable check must come from
        // [RecipientQueryPort.countRecipients], never from the length of a page-capped list.
        val recipientCount = recipientQuery.countRecipients(null)
        val sellerCombo = buildSellerCombo(allSenders)
        val recipientCombo = buildRecipientCombo(recipientCount)
        val hint = buildRecipientHint(recipientCount)
        val reverseChargeDecision = buildReverseChargeDecision()

        val form = FormLayout(sellerCombo, recipientCombo, reverseChargeDecision, hint)
        val createBtn = buildCreateButton(sellerCombo, recipientCombo, reverseChargeDecision, recipientCount)
        val widgets = CreateDialogFormWidgets(sellerCombo, recipientCombo, reverseChargeDecision, createBtn)
        val refresh = { refreshReverseChargeDecision(widgets, recipientCount) }
        sellerCombo.addValueChangeListener { refresh() }
        recipientCombo.addValueChangeListener { refresh() }
        reverseChargeDecision.addValueChangeListener { refresh() }
        refresh()
        val cancelBtn = AppButtons.tertiary(tr("app.cancel")) { close() }

        add(form)
        footer(cancelBtn, createBtn)
    }

    /** LongParameterList — the four widgets [buildFormContent] wires together in one
     *  seller/recipient/reverse-charge/create-button form; [refreshReverseChargeDecision] reacts to
     *  any of their value changes and reads/mutates all four as a unit. */
    private class CreateDialogFormWidgets(
        val sellerCombo: ComboBox<SenderSummaryDto>,
        val recipientCombo: ComboBox<RecipientSummaryDto>,
        val reverseChargeDecision: RadioButtonGroup<Boolean>,
        val createBtn: Button,
    )

    private fun refreshReverseChargeDecision(widgets: CreateDialogFormWidgets, recipientCount: Long) {
        val sellerCombo = widgets.sellerCombo
        val recipientCombo = widgets.recipientCombo
        val reverseChargeDecision = widgets.reverseChargeDecision
        val createBtn = widgets.createBtn
        val seller = sellerCombo.value
        val recipient = recipientCombo.value
        if (seller == null || recipient == null) {
            reverseChargeDecision.isVisible = false
            reverseChargeDecision.clear()
        } else {
            // The recipient's standing ReverseChargePreference decides whether this
            // dialog asks at all; NEVER/ALWAYS pre-supply the answer without a visible radio
            // click, exactly as handleCreateClick already just reads
            // reverseChargeDecision.value unconditionally. wasAskVisible is captured before
            // mutating isVisible below, so a freshly entered Ask state (coming from a
            // Resolved recipient/seller combination, where .value just got overwritten with a
            // preference-supplied answer) always starts unanswered rather than silently
            // keeping that unrelated answer.
            val wasAskVisible = reverseChargeDecision.isVisible
            when (
                val decision = resolveReverseChargeCreationDecision(
                    recipient.reverseChargePreference,
                    seller.vatId,
                    RecipientVatInfo(recipient.vatId, recipientCountry(recipient)),
                    seller.additionalVatRegistrations.mapNotNull { Country.fromAlpha2(it.countryAlpha2) }.toSet(),
                )
            ) {
                is ReverseChargeCreationDecision.Resolved -> {
                    reverseChargeDecision.isVisible = false
                    reverseChargeDecision.value = decision.reverseChargeDecision
                }
                is ReverseChargeCreationDecision.Ask -> {
                    reverseChargeDecision.isVisible = true
                    // item == true is "Yes": enabled only when allowYes; "No" (false) is
                    // always selectable.
                    reverseChargeDecision.setItemEnabledProvider { item -> !item || decision.allowYes }
                    // Never leave "Yes" selected once it becomes the guaranteed-ineligible
                    // option, and never carry over an answer that wasn't actually
                    // given for this Ask state.
                    if (!wasAskVisible || (reverseChargeDecision.value == true && !decision.allowYes)) {
                        reverseChargeDecision.clear()
                    }
                }
            }
        }
        createBtn.isEnabled = recipientCount > 0 && (!reverseChargeDecision.isVisible || reverseChargeDecision.value != null)
    }

    private fun buildReverseChargeDecision(): RadioButtonGroup<Boolean> = RadioButtonGroup<Boolean>().apply {
        label = tr("invoice.dialog.reverseCharge.label")
        setItems(true, false)
        setItemLabelGenerator { decision ->
            tr(if (decision) "invoice.dialog.reverseCharge.yes" else "invoice.dialog.reverseCharge.no")
        }
        isRequiredIndicatorVisible = true
        isVisible = false
        element.setAttribute("data-testid", "invoice-create-reverse-charge-decision")
    }

    /** Every active [SenderSummaryDto] is selectable,
     *  [resolvePreselectedSeller] decides what's preselected. */
    private fun buildSellerCombo(allSenders: List<SenderSummaryDto>): ComboBox<SenderSummaryDto> {
        val sellerCombo = ComboBox<SenderSummaryDto>(tr("invoice.dialog.seller"))
        sellerCombo.setWidthFull()
        sellerCombo.setItems(allSenders)
        sellerCombo.setItemLabelGenerator { s ->
            val sellerCity = s.billingAddressCity.blankToNull()
            "${s.name}${sellerCity?.let { ", $it" }.orEmpty()}"
        }
        sellerCombo.value = resolvePreselectedSeller(allSenders)
        sellerCombo.isRequiredIndicatorVisible = true
        return sellerCombo
    }

    private fun buildRecipientCombo(recipientCount: Long): ComboBox<RecipientSummaryDto> {
        val recipientCombo = ComboBox<RecipientSummaryDto>(tr("invoice.dialog.recipient"))
        recipientCombo.setWidthFull()
        recipientCombo.recipientItems(recipientQuery)
        recipientCombo.setItemLabelGenerator(::recipientItemLabel)
        recipientCombo.placeholder = tr("invoice.dialog.recipient.placeholder")
        recipientCombo.element.setAttribute("aria-describedby", RECIPIENT_HINT_ID)
        recipientCombo.element.setAttribute("aria-label", tr("invoice.dialog.recipient"))
        recipientCombo.isEnabled = recipientCount > 0
        // preselectedRecipient may come from a separately fetched RecipientSummaryDto (e.g. a
        // customer-grid row); the combo's own lazy provider compares by id (recipientItems'
        // idGetter), so this still selects correctly once the data provider is wired.
        if (preselectedRecipient != null) {
            recipientCombo.value = preselectedRecipient
        }
        return recipientCombo
    }

    /** The recipient ComboBox's item label — name, billing city, customer number, plus the short
     *  id ([RecipientSummaryDto.shortHash]) appended when present, giving the improved backend
     *  search a visible anchor. */
    private fun recipientItemLabel(recipient: RecipientSummaryDto): String {
        val city = recipient.billingCity.orEmpty()
        val base = tr("invoice.dialog.recipient.itemLabel", recipient.name, city, recipient.customerNumber.orEmpty())
        val shortHash = recipient.shortHash.blankToNull()
        return if (shortHash != null) "$base · $shortHash" else base
    }

    /** The same "recipient's own resolved country" signal
     *  [de.littletrotter.application.invoice.InvoiceLifecycleService.create] uses for seller-VAT
     *  derivation (billing address, falling back to delivery), needed here so
     *  [resolveReverseChargeCreationDecision]'s matching-registration check stays address-based,
     *  not a VAT-ID guess — [RecipientSummaryDto.billingCountryAlpha2]/`deliveryCountryAlpha2`
     *  are already read directly elsewhere in this dialog (see [recipientItemLabel]), so no
     *  extra service dependency is needed for this UI-side hint. */
    private fun recipientCountry(recipient: RecipientSummaryDto): Country? =
        Country.fromAlpha2(recipient.billingCountryAlpha2 ?: recipient.deliveryCountryAlpha2)

    private fun buildRecipientHint(recipientCount: Long): Paragraph {
        val hintText = if (recipientCount <= 0) {
            tr("invoice.dialog.recipient.none")
        } else {
            tr("invoice.dialog.recipient.available", recipientCount)
        }
        return Paragraph(hintText).also {
            it.element.setAttribute("id", RECIPIENT_HINT_ID)
            it.addClassName("hint-text")
        }
    }

    private fun buildCreateButton(
        sellerCombo: ComboBox<SenderSummaryDto>,
        recipientCombo: ComboBox<RecipientSummaryDto>,
        reverseChargeDecision: RadioButtonGroup<Boolean>,
        recipientCount: Long,
    ): Button = AppButtons.primary(tr("invoice.dialog.create")) {
        handleCreateClick(sellerCombo, recipientCombo, reverseChargeDecision)
    }.apply { isEnabled = recipientCount > 0 }

    private fun handleCreateClick(
        sellerCombo: ComboBox<SenderSummaryDto>,
        recipientCombo: ComboBox<RecipientSummaryDto>,
        reverseChargeDecision: RadioButtonGroup<Boolean>,
    ) {
        val seller = sellerCombo.value
        if (seller == null) {
            UiNotifications.errorOn(sellerCombo, tr("invoice.dialog.seller.required"))
            return
        }
        val recipient = recipientCombo.value
        if (recipient == null) {
            UiNotifications.errorOn(recipientCombo, tr("invoice.dialog.recipient.required"))
            return
        }
        try {
            val created = createInvoiceUseCase.createInvoiceWithRecipient(seller.id, recipient.id, reverseChargeDecision.value)
            close()
            onCreated(created.id)
        } catch (e: Exception) {
            // Click listener: an exception must not escape (undefined UI state for the rest of
            // the session), so this stays broad. Logged with the seller/recipient ids involved so
            // a failed invoice creation is discoverable, not just a toast.
            logDomainRejection(log, "Failed to create invoice for sellerId=${seller.id}, recipientId=${recipient.id}", e)
            reportCreateError(e)
        }
    }

    private fun reportCreateError(e: Exception) {
        when (e) {
            is RecipientNotFoundException,
            is SenderProfileNotFoundException,
            is IllegalArgumentException -> UiNotifications.error(e.message ?: tr("invoice.error.create"))
            // An ALWAYS-implied "Yes" (or a matching-registration-blocked ASK "Yes")
            // that turns out ineligible must surface the resolver's own reason, not a generic
            // toast. UiNotifications already resolves this exception's i18n key + args (the same
            // path the header "Reverse Charge" toggle uses), so this reuses it rather than
            // re-deriving the human-readable text from e.message (which is a developer hint only).
            is InvoiceValidationException -> UiNotifications.error(this, e)
            else -> UiNotifications.error(tr("invoice.error.create", UiNotifications.resolveMessage(this, e)))
        }
    }

    companion object {
        private const val RECIPIENT_HINT_ID = "recipient-hint"
    }
}
