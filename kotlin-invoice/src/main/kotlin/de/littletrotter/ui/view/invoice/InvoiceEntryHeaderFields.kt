package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.InvoiceHeaderFormState
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.Currency
import de.littletrotter.shared.b2b.B2bTermLevel
import de.littletrotter.shared.documents.FreeTextMarkup
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.B2bProfileFieldsSection
import de.littletrotter.ui.component.PdfTextValidation
import de.littletrotter.ui.component.ReplaceableChipSource
import de.littletrotter.ui.components.freetext.FreeTextChips
import de.littletrotter.ui.components.freetext.HtmlFreeTextEditor
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.component.ActionIcons

private const val BUYER_REFERENCE_I18N_KEY = "invoiceDetail.buyerReference"
private const val INVOICE_NOTE_MAX_LENGTH = 4000

internal fun buyerReferenceI18nKey(): String = BUYER_REFERENCE_I18N_KEY

/** Free-text editor (the mail editor's surface) and its chip row, plus the plain invoice-note TextArea that shares
 *  the notes tab with them. [chipSource] is the indirection [chips] was built against
 *  ([InvoiceEntryPane]'s `wireFreeTextChips` assigns the real source once the header's other
 *  live widgets exist and calls [FreeTextChips.refresh]). */
internal class EntryFreeTextFields(
    val editor: HtmlFreeTextEditor,
    val chips: FreeTextChips,
    val chipSource: ReplaceableChipSource,
    val block: Div,
    val manuallyImportedB2bCodes: MutableSet<String>,
    val invoiceNoteField: TextArea,
    val consumerNote: EntryConsumerNote,
)

/**
 * The private-customer notice of the invoice: a text area plus an explicit way back to the
 * sender's default. [resetRequested] makes the next save drop the edited copy and re-derive it
 * from the sender, language and recipient type instead of submitting the typed text.
 */
internal class EntryConsumerNote(val field: TextArea, val resetButton: Button) {
    var resetRequested = false
        private set

    val component: Div = Div(field, resetButton)

    init {
        resetButton.addClickListener {
            resetRequested = true
            field.value = ""
            field.isReadOnly = true
            resetButton.isEnabled = false
        }
    }

    /** Shown only when the invoice carries a notice or the user decided its (empty) content. */
    fun applyVisibility(current: InvoiceDetailDto) {
        component.isVisible = !current.consumerNote.isNullOrBlank() || current.consumerNoteOrigin == MANUAL_ORIGIN
    }

    private companion object {
        const val MANUAL_ORIGIN = "MANUAL"
    }
}

/** BT-10/BT-13/BT-14/BT-15 reference fields shared by Tab 1's dates section and Tab 2's
 *  references step ([InvoiceEinvoiceEntryTab]). */
internal class EntryReferenceFields(
    val buyerReferenceField: TextField,
    val purchaseOrderReferenceField: TextField,
    val orderReferenceField: TextField,
    val orderReferenceDateField: DatePicker,
)

/** Which of the seller's VAT registrations this invoice is billed under; `null` =
 *  German default. See [SellerVatCountrySelection] for the live pre-selection/override rules. */
internal class EntryPartyFields(
    val recipientBox: ComboBox<de.littletrotter.application.dto.RecipientSummaryDto>,
    val senderBox: ComboBox<de.littletrotter.application.dto.SenderSummaryDto>,
    val sellerVatCountryBox: ComboBox<Country>,
    val paymentTermBox: ComboBox<PaymentTermDto>,
    val sellerVat: SellerVatCountrySelection,
)

/** The invoice's own B2B profile, resolved server-side from the invoice's sender and
 *  freely overridable here while the invoice stays a draft (never built for a read-only
 *  invoice, see [InvoiceEntryPane.build]'s own call site). */
internal class B2bProfileSelection(val profileId: Long?, val profile: B2bProfileDto?, val box: ComboBox<B2bProfileDto>)

/** Invoice-level B2B fields: the union of INVOICE-placed header fields,
 *  SELLER-placed HEADER/SELLER_PARTY fields and RECIPIENT-placed fields, grouped visibly by
 *  origin ("Profilfelder" / "Profilfelder · Anbieter" / "Profilfelder · Kunde"). */
internal class EntryB2bHeaderFields(
    val sellerHeaderFields: List<B2bProfileFieldDto>,
    val invoiceHeaderFields: List<B2bProfileFieldDto>,
    val recipientHeaderFields: List<B2bProfileFieldDto>,
    val section: B2bProfileFieldsSection,
)

internal fun blankToEmpty(value: String?): String = value ?: ""
internal fun emptyToNull(value: String): String? = value.ifEmpty { null }

// VAT — the DE↔null sentinel mapping: the model side (InvoiceHeaderFormState, and through it the
// UpdateInvoiceHeaderCommand and Invoice.sellerVatRegistrationCountry) never sees Country.DE, only
// null — the presentation side never shows an empty field for it.
internal fun countryPresentationToModel(presentation: Country?): Country? = presentation?.takeIf { it != Country.DE }
internal fun countryModelToPresentation(model: Country?): Country = model ?: Country.DE

/**
 * Builds and binds the entry pane's plain (non-party, non-dates) header fields: free text,
 * plain references, currency, B2B profile selection and the invoice-level B2B header fields
 * section. Reads [de.littletrotter.application.dto.RecipientSummaryDto]/
 * [de.littletrotter.application.dto.SenderSummaryDto] to build [InvoiceHeaderFormState]'s
 * `recipient`/`senderProfile`, never the `Recipient`/`SenderProfile` entities.
 */
internal class InvoiceEntryHeaderFields(
    private val host: Component,
    private val recipientQueryPort: RecipientQueryPort,
    private val senderQueryPort: SenderQueryPort,
    private val paymentTermQueries: PaymentTermQueryPort,
    private val b2bProfileQueries: B2bProfileQueryPort,
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    private val b2bFieldValueQueries: B2bFieldValueQueryPort,
) {
    private fun tr(key: String, vararg params: Any): String = UiTranslations.tr(host, key, *params)

    fun buildInvoiceHeaderFormState(current: InvoiceDetailDto): InvoiceHeaderFormState = InvoiceHeaderFormState(
        invoiceDate = current.invoiceDate,
        servicePeriodFrom = current.servicePeriodFrom,
        servicePeriodTo = current.servicePeriodTo,
        dueDate = current.dueDate,
        freeText = current.freeText,
        invoiceNote = current.invoiceNote,
        consumerNote = current.consumerNote,
        buyerReference = current.buyerReference,
        purchaseOrderReference = current.purchaseOrderReference,
        orderReference = current.orderReference,
        orderReferenceDate = current.orderReferenceDate,
        projectNumber = current.projectNumber,
        currency = current.currencyEnum(),
        recipient = current.recipient?.id?.let { recipientQueryPort.findRecipientById(it) },
        senderProfile = current.sender?.id?.let { senderQueryPort.findSenderById(it) },
        sellerVatRegistrationCountry = current.sellerVatRegistrationCountry?.let { Country.fromAlpha2(it) },
        paymentTerm = current.paymentTermId?.let { paymentTermQueries.findPaymentTermById(it) },
        invoiceFormat = current.invoiceFormatEnum(),
    )

    fun buildFreeTextFields(current: InvoiceDetailDto): EntryFreeTextFields {
        // The invoice free text is edited on exactly the surface the mail bodies use; it stores the
        // same markup the PDF and XML render, and the header binder reads it through
        // [FreeTextEditorField].
        val editor = HtmlFreeTextEditor(tr("invoiceDetail.freeText"), FreeTextMarkup.MAX_LENGTH)
        // Chips for currently-filled non-standard values (service period, project number, FREE
        // header B2B term values); click inserts the bare value at the editor's caret. A term chip's key
        // (the B2B term code) is also remembered in manuallyImportedB2bCodes so the automatic
        // silent merge (B2bHeaderFreeTextMerge, InvoiceLifecycleService.mergeUnmappedB2bHeaderData)
        // never appends the same value again on save. A repeated click on the same chip still
        // appends again each time (no de-duplication of the manual action itself).
        // chipSource is a placeholder until InvoiceEntryPane.wireFreeTextChips assigns the real
        // one — the header's other live widgets it reads do not exist yet at this point.
        val manuallyImportedB2bCodes = mutableSetOf<String>()
        val chipSource = ReplaceableChipSource()
        val chips = FreeTextChips(chipSource.source) { candidate ->
            editor.insertAtCursor(candidate.insertText)
            candidate.key?.let { manuallyImportedB2bCodes += it }
        }
        val block = Div(chips, editor).apply { addClassName("lt-freetext-block") }
        val invoiceNoteField = TextArea(tr("invoiceDetail.invoiceNote")).apply {
            isClearButtonVisible = true; maxLength = INVOICE_NOTE_MAX_LENGTH; setWidthFull()
            helperText = tr("invoiceDetail.invoiceNote.helper")
        }
        val consumerNoteField = TextArea(tr("invoice.consumerNote")).apply {
            isClearButtonVisible = true; maxLength = INVOICE_NOTE_MAX_LENGTH; setWidthFull()
            helperText = tr("invoice.consumerNote.helper")
        }
        // The click handler is wired by EntryConsumerNote, which owns the reset state.
        val resetButton = AppButtons.tertiary(tr("invoice.consumerNote.reset")) {}
        val consumerNote = EntryConsumerNote(consumerNoteField, resetButton).apply { applyVisibility(current) }
        return EntryFreeTextFields(editor, chips, chipSource, block, manuallyImportedB2bCodes, invoiceNoteField, consumerNote)
    }

    // BT-10 — starts read-only (visually greyed out by the Lumo theme) with the recipient's
    // routingId as a live-tracked suggestion, not a second "Leitweg-ID"-labelled field next to this
    // one: BT-10 is exactly one XML element, so a combo-label ("Leitweg-ID / Käuferreferenz") or two
    // editable fields for it would both be redundant. The pencil button unlocks manual editing once,
    // permanently for this session — see [computeInitialBuyerReferenceOverridden].
    fun buildBuyerReferenceField(onOverridden: () -> Unit): TextField =
        TextField(tr(BUYER_REFERENCE_I18N_KEY)).apply buyerRef@{
            isClearButtonVisible = true; maxLength = 100; setWidthFull()
            helperText = tr("$BUYER_REFERENCE_I18N_KEY.helper")
            isReadOnly = true
            suffixComponent = AppButtons.icon(ActionIcons.EDIT, tr("$BUYER_REFERENCE_I18N_KEY.override")) {
                onOverridden()
                this@buyerRef.isReadOnly = false
                this@buyerRef.focus()
            }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) }
        }

    // BT-13/BT-14 — the buyer's purchase order number / order reference. A plain, always-editable
    // field: unlike buyerReference (BT-10) there is no master-data source to suggest it from, and
    // unlike that one it is optional in EN 16931, so nothing has to be forced in before finalization.
    fun buildPlainReferenceField(label: String, helper: String): TextField =
        TextField(label).apply {
            isClearButtonVisible = true; maxLength = 100; setWidthFull()
            helperText = helper
        }

    fun buildCurrencyBox(): ComboBox<Currency> =
        ComboBox<Currency>(tr("invoiceDetail.currency")).apply {
            setItems(Currency.entries)
            setItemLabelGenerator { it.code }
            isClearButtonVisible = false
            setWidthFull()
            helperText = tr("invoiceDetail.currency.helper")
        }

    // The invoice's own B2B profile: derived from the recipient on assignment/change
    // (InvoiceLifecycleService), then freely overridable here while the invoice stays a draft. Not
    // bound through `binder`/`formState` like the fields above: the entry form's bean carries no
    // `b2bProfile` field — the current value is resolved server-side instead, the same way
    // b2bHeaderSection's profile id already is below. Only ever built for a non-finalized invoice
    // ([InvoiceEntryPane.build] is called from [InvoiceDetailView.renderInvoice] only when
    // `!current.isReadOnly()`), so "editable only while draft" holds structurally without a separate
    // read-only flag here.
    fun buildB2bProfileSelection(current: InvoiceDetailDto): B2bProfileSelection {
        // Resolved once and reused below (b2bHeaderSection, the E-Rechnung tab's
        // guided-mode trigger and its "effective profile" header text) instead of asking the facade
        // again for what is, within one entry-pane build, an unchanging answer.
        val effectiveB2bProfileId = b2bFieldValueQueries.findB2bProfileIdForInvoice(current.id)
        val effectiveB2bProfile = effectiveB2bProfileId?.let { b2bProfileQueries.findB2bProfileById(it) }
        val items = mergeEffectiveB2bProfileIntoActiveList(b2bProfileQueries.findAllActiveB2bProfiles(), effectiveB2bProfile)
        val box = ComboBox<B2bProfileDto>(tr("invoiceDetail.b2bProfile")).apply {
            setItems(items)
            setItemLabelGenerator { it.name }
            isClearButtonVisible = true; setWidthFull()
            helperText = tr("invoiceDetail.b2bProfile.helper")
            value = effectiveB2bProfile
        }
        return B2bProfileSelection(effectiveB2bProfileId, effectiveB2bProfile, box)
    }

    fun buildEntryB2bHeaderFields(current: InvoiceDetailDto, effectiveB2bProfileId: Long?): EntryB2bHeaderFields {
        val sellerHeaderFields = effectiveB2bProfileId?.let { b2bFieldDefinitionQueries.findB2bSellerFields(it) }.orEmpty()
            .filter { isHeaderOrSellerPartyField(it) }
        val invoiceHeaderFields = b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(effectiveB2bProfileId)
        val recipientHeaderFields = b2bFieldDefinitionQueries.findB2bRecipientFields(effectiveB2bProfileId)
        val section = B2bProfileFieldsSection { tr(it) }.apply {
            setGroupedFields(
                listOf(
                    tr("invoiceDetail.entry.einvoice.profileFields") to invoiceHeaderFields,
                    tr("invoiceDetail.entry.einvoice.profileFields.seller") to sellerHeaderFields,
                    tr("invoiceDetail.entry.einvoice.profileFields.recipient") to recipientHeaderFields,
                ),
            )
            // One resolution chain (invoice value > party value > profile default), the same one
            // B2bFieldValueService.resolveB2bFieldChain feeds the export and the compliance check from.
            readResolved(
                b2bFieldValueQueries.findB2bGuidedFieldValues(current.id),
                tr("invoiceDetail.entry.einvoice.profileFields.inheritedHint"),
            )
        }
        return EntryB2bHeaderFields(sellerHeaderFields, invoiceHeaderFields, recipientHeaderFields, section)
    }

    fun bindEntryPaneFields(binder: Binder<InvoiceHeaderFormState>, widgets: EntryPaneWidgets) {
        val dateFields = widgets.dates.fields
        val pdfText = PdfTextValidation.validator { key -> tr(key) }
        val freeText = widgets.freeText
        val references = widgets.referenceFields
        val currencyBox = widgets.currencyBox
        val party = widgets.party
        binder.forField(dateFields.invoiceDateField)
            .asRequired(tr("validation.invoice.invoiceDate.required"))
            .bind(InvoiceHeaderFormState::invoiceDate.getter, InvoiceHeaderFormState::invoiceDate.setter)
        binder.forField(dateFields.serviceFromField).bind(InvoiceHeaderFormState::servicePeriodFrom.getter, InvoiceHeaderFormState::servicePeriodFrom.setter)
        binder.forField(dateFields.serviceToField).bind(InvoiceHeaderFormState::servicePeriodTo.getter, InvoiceHeaderFormState::servicePeriodTo.setter)
        binder.forField(dateFields.dueDateField).bind(InvoiceHeaderFormState::dueDate.getter, InvoiceHeaderFormState::dueDate.setter)
        binder.forField(FreeTextEditorField(freeText.editor)).withValidator(pdfText).withConverter({ it }, ::blankToEmpty).bind(InvoiceHeaderFormState::freeText.getter, InvoiceHeaderFormState::freeText.setter)
        binder.forField(freeText.invoiceNoteField).withValidator(pdfText).withConverter(::emptyToNull, ::blankToEmpty)
            .bind(InvoiceHeaderFormState::invoiceNote.getter, InvoiceHeaderFormState::invoiceNote.setter)
        binder.forField(freeText.consumerNote.field).withValidator(pdfText).withConverter(::emptyToNull, ::blankToEmpty)
            .bind(InvoiceHeaderFormState::consumerNote.getter, InvoiceHeaderFormState::consumerNote.setter)
        binder.forField(references.buyerReferenceField).withValidator(pdfText).withConverter({ it }, ::blankToEmpty)
            .bind(InvoiceHeaderFormState::buyerReference.getter, InvoiceHeaderFormState::buyerReference.setter)
        binder.forField(references.purchaseOrderReferenceField).withValidator(pdfText).withConverter({ it }, ::blankToEmpty)
            .bind(InvoiceHeaderFormState::purchaseOrderReference.getter, InvoiceHeaderFormState::purchaseOrderReference.setter)
        binder.forField(references.orderReferenceField).withValidator(pdfText).withConverter({ it }, ::blankToEmpty)
            .bind(InvoiceHeaderFormState::orderReference.getter, InvoiceHeaderFormState::orderReference.setter)
        binder.forField(references.orderReferenceDateField)
            .bind(InvoiceHeaderFormState::orderReferenceDate.getter, InvoiceHeaderFormState::orderReferenceDate.setter)
        binder.forField(currencyBox).bind(InvoiceHeaderFormState::currency.getter, InvoiceHeaderFormState::currency.setter)
        binder.forField(party.recipientBox).bind(InvoiceHeaderFormState::recipient.getter, InvoiceHeaderFormState::recipient.setter)
        binder.forField(party.senderBox).bind(InvoiceHeaderFormState::senderProfile.getter, InvoiceHeaderFormState::senderProfile.setter)
        binder.forField(party.sellerVatCountryBox)
            .withConverter(::countryPresentationToModel, ::countryModelToPresentation)
            .bind(InvoiceHeaderFormState::sellerVatRegistrationCountry.getter, InvoiceHeaderFormState::sellerVatRegistrationCountry.setter)
        binder.forField(party.paymentTermBox)
            .withConverter({ presentation -> resolvePaymentTerm(presentation, paymentTermQueries.findDefaultPaymentTerm()) }, { it })
            .bind(InvoiceHeaderFormState::paymentTerm.getter, InvoiceHeaderFormState::paymentTerm.setter)
    }
}

// The effective profile must be among the box's own items even when it is deactivated: a
// "deactivated but still referenced" profile is not in findAllActiveB2bProfiles at all, so without
// this the box would render empty for exactly that state. B2bProfileDto compares by value, so a
// profile row loaded separately still matches its twin in the item list for `value =` above.
private fun mergeEffectiveB2bProfileIntoActiveList(activeProfiles: List<B2bProfileDto>, effectiveB2bProfile: B2bProfileDto?): List<B2bProfileDto> =
    if (effectiveB2bProfile != null && activeProfiles.none { it.id == effectiveB2bProfile.id }) {
        activeProfiles + effectiveB2bProfile
    } else {
        activeProfiles
    }

private fun isHeaderOrSellerPartyField(field: B2bProfileFieldDto): Boolean =
    field.term?.level == B2bTermLevel.HEADER || field.term?.level == B2bTermLevel.SELLER_PARTY

// BT-10 — an invoice whose buyerReference already diverges from its recipient's routingId
// (a deliberate earlier override, or a value entered before a recipient's routingId existed)
// opens already unlocked; one that still matches (or is blank) stays in the greyed-out "inherited
// suggestion" state so a later recipient change keeps tracking it.
internal fun computeInitialBuyerReferenceOverridden(current: InvoiceDetailDto): Boolean =
    !current.buyerReference.isNullOrBlank() && current.buyerReference != current.recipient?.routingId

/** Everything [InvoiceEntryPane]'s field-construction step builds before any binder binding or
 *  listener wiring. */
internal class EntryPaneWidgets(
    val dates: InvoiceEntryDatesSection,
    val freeText: EntryFreeTextFields,
    val referenceFields: EntryReferenceFields,
    val currencyBox: ComboBox<Currency>,
    val party: EntryPartyFields,
    val partyCard: InvoiceEntryPartyCard,
    val b2bProfile: B2bProfileSelection,
)
