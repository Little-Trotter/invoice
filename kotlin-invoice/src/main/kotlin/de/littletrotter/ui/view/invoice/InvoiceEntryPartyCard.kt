package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartySnapshotDiffDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.application.invoice.InvoiceHeaderFormState
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Currency
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.orPlaceholder
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import org.jboss.logging.Logger

private val log = Logger.getLogger(InvoiceEntryPartyCard::class.java)

/**
 * Party card of the entry pane's Tab 1 ("Rechnung"): recipient and sender selection, the seller
 * VAT-registration country that depends on both, the reverse-charge decision whose visibility
 * follows them, and the "refresh master data" header action. Owns its widgets and their
 * listeners; [InvoiceEntryPane] only decides when each wiring step runs relative to the binder's
 * first `readBean`.
 *
 * Reads recipients/senders as [RecipientSummaryDto]/[SenderSummaryDto] (`RecipientQueryPort`/
 * `SenderQueryPort`) and addresses as [AddressDto] (`RecipientQueryPort.findBillingAddress`) —
 * never the `Recipient`/`SenderProfile`/`Address` entities.
 */
internal class InvoiceEntryPartyCard(
    private val host: Component,
    private val recipientQueryPort: RecipientQueryPort,
    private val senderQueryPort: SenderQueryPort,
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase,
    current: InvoiceDetailDto,
    formState: InvoiceHeaderFormState,
) {
    private fun tr(key: String, vararg params: Any): String = UiTranslations.tr(host, key, *params)

    val recipientBox: ComboBox<RecipientSummaryDto> = buildRecipientBox()
    val senderBox: ComboBox<SenderSummaryDto> = buildSenderBox()
    val sellerVatCountryBox: ComboBox<Country> = buildSellerVatCountryBox(formState, senderBox)
    val sellerVat = SellerVatCountrySelection(recipientQueryPort, formState, sellerVatCountryBox, senderBox, recipientBox)

    // Reverse-charge decision: binary, default No, visible only while seller/recipient VAT countries differ
    // (ReverseChargeField.applyVisibility); a client-driven change to any of the three boxes
    // resets it to No — never an implicit stale Yes — before re-evaluating visibility.
    val reverseChargeDecisionBox: ReverseChargeField = buildReverseChargeDecisionBox(current)
    private val reverseCharge = ReverseChargeVisibilityController(recipientQueryPort, current, reverseChargeDecisionBox, sellerVatCountryBox, recipientBox)

    // "Stammdaten erneut übernehmen": only on a mutable invoice (GoBD; the service itself
    // also blocks this, matching InvoiceLifecycleService.updateHeader's identical guard), never
    // on a finalized/cancelled one.
    private val refreshSnapshotButton: Button = buildRefreshPartySnapshotButton(current)

    init {
        sellerVatCountryBox.addValueChangeListener { event -> sellerVat.onValueChangedByUser(event.isFromClient, event.value) }
        sellerVat.refreshOptions()
    }

    /** Recipient/sender listeners that must be registered before the binder's first `readBean`;
     *  [loading] and [buyerReferenceOverridden] are read live on every change. */
    fun wireSelectionListeners(buyerReferenceField: TextField, buyerReferenceOverridden: () -> Boolean, loading: () -> Boolean) {
        recipientBox.addValueChangeListener {
            // BT-10 — while the field is still showing the inherited suggestion (not manually
            // overridden via the pencil button), a recipient change re-suggests that recipient's
            // own routingId instead of silently keeping the previous one's value on screen.
            recipientBox.isInvalid = false
            if (!buyerReferenceOverridden()) buyerReferenceField.value = recipientBox.value?.routingId ?: ""
            sellerVat.onRecipientChanged(loading())
        }
        senderBox.addValueChangeListener { onSenderBoxChanged(senderBox, loading(), sellerVat) }
    }

    /** Initial reverse-charge visibility plus its reset listeners; wired after `readBean` so the
     *  programmatic value load never counts as a client change. */
    fun wireReverseCharge() {
        reverseCharge.refresh()
        recipientBox.addValueChangeListener { event -> reverseCharge.resetIfFromClient(event.isFromClient) }
        senderBox.addValueChangeListener { event -> reverseCharge.resetIfFromClient(event.isFromClient) }
        sellerVatCountryBox.addValueChangeListener { event -> reverseCharge.resetIfFromClient(event.isFromClient) }
    }

    fun buildSection(currencyBox: ComboBox<Currency>): Div = entrySection(
        tr("invoiceDetail.entry.section.parties"),
        Div().apply {
            add(
                FormLayout(recipientBox, senderBox, currencyBox).apply {
                    setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("700px", 2))
                },
            )
            add(reverseChargeDecisionBox)
        },
        headerAction = refreshSnapshotButton,
    )

    private fun buildRecipientBox(): ComboBox<RecipientSummaryDto> =
        ComboBox<RecipientSummaryDto>(tr("invoiceDetail.summary.name")).apply {
            setDataProvider(
                { filter: String?, offset: Int, limit: Int ->
                    val pageIndex = if (limit == 0) 0 else offset / limit
                    recipientQueryPort.findRecipients(pageIndex, limit, filter?.ifBlank { null }).stream()
                },
                { filter: String? -> recipientQueryPort.countRecipients(filter?.ifBlank { null }).toInt() },
            )
            setItemLabelGenerator { "${it.name} (${it.customerNumber})" }
            isClearButtonVisible = true; setWidthFull()
        }

    private fun buildSenderBox(): ComboBox<SenderSummaryDto> =
        ComboBox<SenderSummaryDto>(tr("invoiceDetail.card.sender")).apply {
            setItems(senderQueryPort.findAllSenders())
            setItemLabelGenerator { it.name }
            isClearButtonVisible = true; setWidthFull()
        }

    // Which of the seller's VAT registrations this invoice is billed under;
    // `null` = German default. Hand-built like senderBox itself rather than routed through the
    // generic InvoiceFormModule/FieldSpec mechanism: that mechanism renders a *static* option list
    // from FormContext (InvoiceDto only, no sender), while this ComboBox's options depend on which
    // sender is currently chosen and must refresh live when senderBox changes — a dynamic,
    // entity-dependent shape the generic ENUM FieldSpec doesn't cover. Always visible (showing it is more
    // transparent) — a sender without additional registrations simply shows the
    // lone DE entry, so which VAT ID the invoice is billed under is legible on every invoice, not
    // only cross-border ones.
    // The field is never empty: Country.DE is a presentation-only sentinel for
    // the German default, structurally collision-free because
    // Country.SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES excludes DE, so no real additional
    // registration row can ever carry it. [countryPresentationToModel]/[countryModelToPresentation]
    // map the sentinel back to null, so Invoice.sellerVatRegistrationCountry's null semantics
    // (reverse-charge eligibility, item tax derivation, XML export) stay untouched — null remains the
    // persisted encoding of "billed under the seller's German USt-IdNr".
    private fun buildSellerVatCountryBox(
        formState: InvoiceHeaderFormState,
        senderBox: ComboBox<SenderSummaryDto>,
    ): ComboBox<Country> = ComboBox<Country>(tr("invoiceDetail.sellerVatRegistrationCountry")).apply {
        // No clear button: returning to the German default is now an explicit pick of the DE entry,
        // not a blanked combo.
        isClearButtonVisible = false; setWidthFull()
        setItemLabelGenerator { country -> sellerVatCountryItemLabel(country, senderBox.value ?: formState.senderProfile) }
        helperText = tr("invoiceDetail.sellerVatRegistrationCountry.helper")
    }

    private fun buildReverseChargeDecisionBox(current: InvoiceDetailDto): ReverseChargeField =
        ReverseChargeField(
            labelText = tr("invoiceDetail.reverseCharge"),
            yesLabel = tr("invoiceDetail.reverseCharge.yes"),
            noLabel = tr("invoiceDetail.reverseCharge.no"),
            helper = tr("invoiceDetail.reverseCharge.explicit.helper"),
        ).apply { value = current.reverseCharge }

    private fun buildRefreshPartySnapshotButton(current: InvoiceDetailDto): Button {
        lateinit var button: Button
        button = AppButtons.secondary(tr("invoiceDetail.entry.refreshPartySnapshot"), ActionIcons.REFRESH) {
            onRefreshPartySnapshotClicked(current, button)
        }
        button.addClassNames("ch-action-btn", "ch-btn--refresh-master-data")
        button.isVisible = !current.isReadOnly()
        return button
    }

    // Click listener: must not let ANY exception through, sole catch-all of this flow.
    private fun onRefreshPartySnapshotClicked(current: InvoiceDetailDto, button: Button) {
        val invoiceId = current.id
        val preview = try {
            invoiceDetailQueryPort.previewPartySnapshotRefresh(invoiceId)
        } catch (e: Exception) {
            // Click listener: must not let an exception escape. Logged with the invoice id so a
            // failed preview fetch is discoverable.
            log.error("Failed to preview party snapshot refresh for invoiceId=$invoiceId", e)
            UiNotifications.error(button, e)
            return
        }
        if (!preview.hasChanges) {
            UiNotifications.info(tr("invoiceDetail.entry.refreshPartySnapshot.noChanges"))
            return
        }
        ConfirmDialog(
            tr("invoiceDetail.entry.refreshPartySnapshot.confirm.title"),
            formatPartySnapshotDiff(preview),
            tr("invoiceDetail.entry.refreshPartySnapshot.confirm.confirm"),
        ) { confirmRefreshPartySnapshot(invoiceId, button) }.apply {
            setCancelable(true)
            setCancelText(tr("app.cancel"))
            open()
        }
    }

    private fun formatPartySnapshotDiff(preview: PartySnapshotDiffDto): String =
        (preview.sellerChanges.map { "${tr("invoiceDetail.card.sender")}: ${it.field}: '${it.oldValue.orPlaceholder("—")}' → '${it.newValue.orPlaceholder("—")}'" } +
            preview.buyerChanges.map { "${tr("invoiceDetail.summary.name")}: ${it.field}: '${it.oldValue.orPlaceholder("—")}' → '${it.newValue.orPlaceholder("—")}'" })
            .joinToString("\n")

    /** [onRefreshed] hands the caller the saved invoice so it can update its own held reference
     *  (the pane never mutates [InvoiceDetailView.invoice] itself). */
    var onRefreshed: (InvoiceDetailDto) -> Unit = {}

    // Confirm-dialog listener: must not let ANY exception through, sole catch-all of
    // this flow.
    private fun confirmRefreshPartySnapshot(invoiceId: Long, button: Button) {
        try {
            val savedDto = editInvoiceHeaderUseCase.refreshPartySnapshots(invoiceId)
            onRefreshed(savedDto)
            UiNotifications.confirmOn(button)
        } catch (e: Exception) {
            // Confirm-dialog click listener: must not let an exception escape. Logged with the
            // invoice id so a failed party-snapshot refresh is discoverable.
            log.error("Failed to refresh party snapshots for invoiceId=$invoiceId", e)
            UiNotifications.error(button, e)
        }
    }
}

private fun sellerVatCountryItemLabel(country: Country, sender: SenderSummaryDto?): String {
    val vatId = if (country == Country.DE) {
        sender?.vatId
    } else {
        sender?.additionalVatRegistrations?.firstOrNull { it.countryAlpha2.equals(country.alpha2, ignoreCase = true) }?.vatId
    }
    return if (vatId != null) "${country.alpha2} ($vatId)" else country.alpha2
}

// ─── Seller VAT-registration-country live selection ───

/**
 * VAT: whether the user (or the persisted invoice itself, see [initializeAfterFirstReadBean]) has
 * made an explicit choice for [box] — as opposed to a value set by [refreshOptions]'s safety-clear
 * or [refreshPreselection]'s own default. `isFromClient` is false for both of those programmatic
 * sets, so [onValueChangedByUser] only ever marks [overridden] true for a real pick/clear at the
 * keyboard or mouse. [onRecipientChanged]/[onSenderChanged] read it: a sender change must not
 * silently overwrite an explicit choice while the recipient stays the same; a recipient change
 * always may.
 */
internal class SellerVatCountrySelection(
    private val recipientQueryPort: RecipientQueryPort,
    private val formState: InvoiceHeaderFormState,
    private val box: ComboBox<Country>,
    private val senderBox: ComboBox<SenderSummaryDto>,
    private val recipientBox: ComboBox<RecipientSummaryDto>,
) {
    var overridden: Boolean = false
        private set

    fun onValueChangedByUser(isFromClient: Boolean, value: Country?) {
        if (isFromClient) overridden = true
        // Never-empty guard: a client-side blanking (typing over the value and erasing it) falls
        // back to the visible DE default entry instead of an empty combo. Programmatic nulls
        // (setItems' transient clear in refreshOptions) are not client events and are restored by
        // their own callers.
        if (isFromClient && value == null) box.value = Country.DE
    }

    fun refreshOptions() {
        // senderBox.value is the source of truth once the user picks a different sender live;
        // formState.senderProfile (the persisted sender, already resolved above) is the initial
        // fallback before that first pick. The previous value is captured before setItems: Vaadin's
        // ComboBox.setItems replaces the data provider and, as a side effect, always clears the
        // current selection first — restoring it explicitly afterward (only when it is still among
        // the new registrations) is what actually implements "an invalid selection is cleared, a
        // still-valid one survives", not the setItems call itself.
        val registrations = (senderBox.value ?: formState.senderProfile)?.additionalVatRegistrations.orEmpty()
        val registrationCountries = registrations.mapNotNull { Country.fromAlpha2(it.countryAlpha2) }
        val previousValue = box.value
        // The DE default entry always heads the list; a previous foreign selection survives only
        // while the (possibly new) sender still holds that registration, everything else falls
        // back to the visible DE default — never to an empty combo.
        box.setItems(listOf(Country.DE) + registrationCountries)
        box.value = previousValue?.takeIf { v -> v == Country.DE || v in registrationCountries } ?: Country.DE
    }

    // VAT — the pre-selection rule itself: resolveSellerVatRegistrationCountry is pure, and this
    // closes it over the *current* recipient and sender ComboBox values (not `current`/`formState`,
    // which only reflect what was true when the pane was opened) so it keeps producing the right
    // default as either changes live. findBillingAddress is the same buyerCountry source
    // TaxRuleUiSupport.buildTaxRuleResolutionRequest already uses — deliberately not a second
    // notion of "the recipient's country". Writes formState directly, not just the field:
    // buildEinvoiceTab's own binder.readBean(formState) call (after this pane's Tab 2 fields are
    // bound) would otherwise clobber a live-computed value back to whatever formState still held.
    fun refreshPreselection() {
        val registrations = (senderBox.value ?: formState.senderProfile)?.additionalVatRegistrations.orEmpty()
        val recipientCountry = recipientBox.value?.id?.let { recipientQueryPort.findBillingAddress(it)?.countryAlpha2?.let(Country::fromAlpha2) }
        val resolved = resolveSellerVatRegistrationCountryFromDto(registrations, recipientCountry)
        formState.sellerVatRegistrationCountry = resolved
        // null (the domestic answer) renders as the DE sentinel entry — the form state above
        // keeps the real null so persistence and reverse-charge eligibility are unaffected.
        box.value = resolved ?: Country.DE
    }

    // VAT — a changed recipient always recomputes the pre-selection, even over an explicit prior
    // choice: keeping e.g. a Luxembourg registration selected on an invoice that now goes to
    // France would be worse than re-deriving it (spec's own reasoning). Guarded by `loading` so
    // binder.readBean's own programmatic recipientBox set doesn't run this twice; the "no value
    // yet" case is handled explicitly once loading finishes.
    fun onRecipientChanged(loading: Boolean) {
        if (loading) return
        overridden = false
        refreshPreselection()
    }

    // A different sender has different (or no) VAT registrations available.
    fun onSenderChanged(loading: Boolean) {
        refreshOptions()
        // VAT — the recipient hasn't changed here, so an explicit choice must survive ("not
        // silently overwritten while the recipient stays the same"); only recompute the default
        // while none has been made yet.
        if (!loading && !overridden) refreshPreselection()
    }

    // VAT — "on opening an invoice that has no value set yet": readBean already applied a
    // persisted, non-null value onto the box, which is itself the kind of explicit choice a later
    // sender change must not silently overwrite. Only a genuinely empty field gets the live
    // pre-selection run once, up front.
    fun initializeAfterFirstReadBean() {
        if (formState.sellerVatRegistrationCountry == null) {
            refreshPreselection()
        } else {
            overridden = true
        }
    }
}

private fun onSenderBoxChanged(senderBox: ComboBox<SenderSummaryDto>, loading: Boolean, sellerVat: SellerVatCountrySelection) {
    senderBox.isInvalid = false
    sellerVat.onSenderChanged(loading)
}

/** Visibility + reset for the reverse-charge decision: kept alongside the boxes it reads so a
 *  client-driven change to any of them resets the decision to No before re-evaluating whether it
 *  should even be shown. */
private class ReverseChargeVisibilityController(
    private val recipientQueryPort: RecipientQueryPort,
    private val current: InvoiceDetailDto,
    private val box: ReverseChargeField,
    private val sellerVatCountryBox: ComboBox<Country>,
    private val recipientBox: ComboBox<RecipientSummaryDto>,
) {
    fun refresh() {
        box.applyVisibility(
            sellerVatCountryBox.value ?: Country.DE,
            recipientBox.value?.id?.let { recipientQueryPort.findBillingAddress(it)?.countryAlpha2?.let(Country::fromAlpha2) },
            current.isReadOnly(),
        )
    }

    fun resetIfFromClient(isFromClient: Boolean) {
        if (!isFromClient) return
        box.value = false
        refresh()
    }
}
