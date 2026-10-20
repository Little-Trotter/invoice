package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.InvoiceHeaderFormState
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.b2bFreeHeaderChipTerms
import de.littletrotter.ui.component.InvoiceChipFieldSources
import de.littletrotter.ui.component.wireFreeTextChips
import de.littletrotter.ui.i18n.UiTranslations
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/** Result of building the entry form: the component itself, plus a guarded exit function the
 *  frozen context bar's "Done" button calls (dirty-check + discard confirmation live here,
 *  next to the dirty flag itself, not duplicated at the call site). */
internal data class EntryPaneResult(val component: Div, val requestExit: () -> Unit)

/** The TabSheet plus both its tabs, the E-Rechnung tab's own reveal/status contract, and
 *  the validation summary panel: everything a validation issue needs in order to switch to the
 *  right tab/step and show itself. */
internal class EntryTabNavigation(
    val tabSheet: TabSheet,
    val invoiceTabRef: Tab,
    val einvoiceTabRef: Tab,
    val einvoiceTab: EInvoiceTabResult,
    val validationSummary: Div,
    val notesTabRef: Tab,
    private val notesContent: Component,
) {
    /** A flashed/focused field on the currently invisible tab (or guided step
     *  within it) must be revealed first, or the shake/flash animation nobody sees is pointless.
     *  Switch by tab identity, not index, so tab reordering never silently lands on the wrong
     *  field. */
    fun reveal(field: Component) {
        tabSheet.selectedTab = tabFor(field)
    }

    /** The tab a field lives on: the E-Rechnung tab (or its guided step) if it claims the field,
     *  the notes tab if the field sits inside its content, the invoice tab otherwise. */
    fun tabFor(field: Component): Tab = when {
        einvoiceTab.revealField(field) -> einvoiceTabRef
        generateSequence(field) { it.parent.orElse(null) }.any { it === notesContent } -> notesTabRef
        else -> invoiceTabRef
    }
}

/** Result of [InvoiceEntryPane.buildEntryPaneTabs]: the TabSheet/validation-summary bundle plus
 *  the guided-mode bits [InvoiceEntryPane.build]'s save flow and hint wiring both still need. */
private class EntryPaneTabsResult(
    val nav: EntryTabNavigation,
    val guidedTrigger: EInvoiceGuidedTrigger,
    val mandatoryHeaderFields: List<MandatoryB2bHeaderField>,
    val b2bHeaderSection: de.littletrotter.ui.component.B2bProfileFieldsSection,
)

private class EntryPaneSections(val parties: Div, val dates: Div, val notes: Div)

/**
 * Entry mode (Erfassen-Modus): the editable meta form, entered explicitly via "Edit" and left via
 * the context bar's "Done" (see [InvoiceDetailView.renderInvoice]). Two flat tabs — Tab 1
 * "Rechnung" (parties/dates/notes) and Tab 2 "E-Rechnung" (references/format/B2B, built by
 * [InvoiceEinvoiceEntryTab]) — only one level deep, so this doesn't reintroduce nested tab bars.
 *
 * Composes [InvoiceEntryHeaderFields] (plain header fields), [InvoiceEntryPartyCard] (parties/VAT/
 * reverse charge), [InvoiceEntryDatesSection] (dates/payment term), [InvoiceEntrySaveFlow]
 * (validation + save) and [InvoiceEinvoiceEntryTab] (the "E-Rechnung" tab). Reads
 * [RecipientQueryPort]/[SenderQueryPort]/[InvoiceDetailQueryPort]/[EditInvoiceHeaderUseCase] —
 * one narrow input port per use case it serves.
 */
@Dependent
class InvoiceEntryPane
@Inject
constructor(
    private val recipientQueryPort: RecipientQueryPort,
    private val senderQueryPort: SenderQueryPort,
    private val paymentTermQueries: PaymentTermQueryPort,
    private val b2bProfileQueries: B2bProfileQueryPort,
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase,
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    internal val einvoiceEntryTab: InvoiceEinvoiceEntryTab,
) {
    private val saveFlow = InvoiceEntrySaveFlow(editInvoiceHeaderUseCase)

    // Set once at the top of [build] and read by its private helpers below — a translation anchor
    // and the header-field builder are per-build state, not per-call parameters, the same way
    // [InvoiceDetailView] itself holds its own per-render state as fields rather than threading it
    // through every extension function (keeps the helpers under detekt's function-parameter
    // threshold without a parameter-bag object).
    private lateinit var host: Component
    private lateinit var headerFields: InvoiceEntryHeaderFields

    /**
     * [onInvoiceUpdated] is called with the freshly saved invoice both by a successful header
     * save (before [onDone], mirroring the pre-extraction "set `invoice` then leave entry mode"
     * order [InvoiceDetailView.exitEntryMode] relies on) and by "Stammdaten erneut übernehmen"
     * (which does not leave entry mode) — the pane never holds [InvoiceDetailView]'s own `invoice`
     * property itself.
     */
    internal fun build(
        host: Component,
        current: InvoiceDetailDto,
        onInvoiceUpdated: (InvoiceDetailDto) -> Unit,
        onDone: () -> Unit,
        onDirtyChanged: (Boolean) -> Unit = {},
        onConflict: () -> Unit = {},
    ): EntryPaneResult {
        this.host = host
        this.headerFields = InvoiceEntryHeaderFields(
            host,
            recipientQueryPort,
            senderQueryPort,
            paymentTermQueries,
            b2bProfileQueries,
            einvoiceEntryTab.b2bFieldDefinitionQueries,
            einvoiceEntryTab.b2bFieldValueQueries,
        )
        fun tr(key: String, vararg params: Any) = UiTranslations.tr(host, key, *params)

        // The Binder's bean is a dedicated, mutable form-bean ([InvoiceHeaderFormState]), not
        // a hand-reconstructed `Invoice` (`dtoToInvoice`, removed). `recipient`/`senderProfile`/
        // `paymentTerm` are read-model projections/entities the field construction below already
        // selects from, never constructed here.
        val formState = headerFields.buildInvoiceHeaderFormState(current)
        val binder = Binder(InvoiceHeaderFormState::class.java)

        var buyerReferenceOverridden = false
        val widgets = buildEntryPaneWidgets(current, formState) { buyerReferenceOverridden = true }
        val references = widgets.referenceFields
        val party = widgets.party
        val sellerVat = party.sellerVat
        widgets.partyCard.onRefreshed = onInvoiceUpdated

        headerFields.bindEntryPaneFields(binder, widgets)

        var loading = true
        widgets.dates.wireListeners { loading }
        widgets.partyCard.wireSelectionListeners(references.buyerReferenceField, { buyerReferenceOverridden }) { loading }

        binder.addStatusChangeListener { onDirtyChanged(binder.hasChanges()) }
        binder.readBean(formState)
        loading = false

        sellerVat.initializeAfterFirstReadBean()
        buyerReferenceOverridden = computeInitialBuyerReferenceOverridden(current)
        references.buyerReferenceField.isReadOnly = !buyerReferenceOverridden

        val tabs = buildEntryPaneTabs(current, widgets, binder, formState)

        val saveContext = EntrySaveContext(
            current = current,
            binder = binder,
            formState = formState,
            fields = EntrySaveFields(widgets.dates.fields, party, references.buyerReferenceField, widgets.partyCard.reverseChargeDecisionBox, widgets.freeText.consumerNote),
            nav = tabs.nav,
            b2b = EntrySaveB2bContext(
                tabs.guidedTrigger, tabs.mandatoryHeaderFields, tabs.b2bHeaderSection, widgets.b2bProfile.box, widgets.freeText.manuallyImportedB2bCodes,
            ),
            onDone = onDone,
            onConflict = onConflict,
        )
        val saveBtn = saveFlow.buildSaveButton(host, saveContext, onInvoiceUpdated)

        // The single Binder and save flow stay exactly as they are: backBtn/saveBtn remain below the
        // TabSheet, outside both tabs, and save all fields of both tabs at once.
        val requestExit: () -> Unit = { saveFlow.performRequestExit(host, binder, onDone) }
        val backBtn = AppButtons.tertiary(tr("invoiceDetail.overview.done"), ActionIcons.BACK) { requestExit() }
        val component = Div().apply {
            addClassName("entry-workspace")
            setSizeFull()
            add(tabs.nav.validationSummary, tabs.nav.tabSheet)
            add(ActionBar(listOf(backBtn), saveBtn).apply { isPadding = false })
        }
        return EntryPaneResult(component, requestExit)
    }

    private fun buildEntryPaneWidgets(
        current: InvoiceDetailDto,
        formState: InvoiceHeaderFormState,
        onBuyerReferenceOverridden: () -> Unit,
    ): EntryPaneWidgets {
        val dates = InvoiceEntryDatesSection(host, paymentTermQueries, current)
        val freeText = headerFields.buildFreeTextFields(current)
        val references = EntryReferenceFields(
            buyerReferenceField = headerFields.buildBuyerReferenceField(onBuyerReferenceOverridden),
            purchaseOrderReferenceField = headerFields.buildPlainReferenceField(
                UiTranslations.tr(host, "invoiceDetail.purchaseOrderReference"), UiTranslations.tr(host, "invoiceDetail.purchaseOrderReference.helper"),
            ),
            orderReferenceField = headerFields.buildPlainReferenceField(
                UiTranslations.tr(host, "invoiceDetail.orderReference"), UiTranslations.tr(host, "invoiceDetail.orderReference.helper"),
            ),
            orderReferenceDateField = com.vaadin.flow.component.datepicker.DatePicker(UiTranslations.tr(host, "invoiceDetail.orderReferenceDate"))
                .apply { isClearButtonVisible = true },
        )
        val currencyBox = headerFields.buildCurrencyBox()
        val partyCard = InvoiceEntryPartyCard(host, recipientQueryPort, senderQueryPort, invoiceDetailQueryPort, editInvoiceHeaderUseCase, current, formState)
        val party = EntryPartyFields(partyCard.recipientBox, partyCard.senderBox, partyCard.sellerVatCountryBox, dates.paymentTermBox, partyCard.sellerVat)
        return EntryPaneWidgets(dates, freeText, references, currencyBox, party, partyCard, headerFields.buildB2bProfileSelection(current))
    }

    /** Builds Tab 1 "Rechnung"/Tab 2 "E-Rechnung"'s [EntryTabNavigation] plus the guided-mode bits
     *  the save flow (b2b context) and the hint wiring both need. */
    private fun buildEntryPaneTabs(
        current: InvoiceDetailDto,
        widgets: EntryPaneWidgets,
        binder: Binder<InvoiceHeaderFormState>,
        formState: InvoiceHeaderFormState,
    ): EntryPaneTabsResult {
        fun tr(key: String, vararg params: Any) = UiTranslations.tr(host, key, *params)

        val dateFields = widgets.dates.fields
        val freeText = widgets.freeText
        val party = widgets.party
        val references = widgets.referenceFields
        val effectiveB2bProfileId = widgets.b2bProfile.profileId

        val b2bHeaderFields = headerFields.buildEntryB2bHeaderFields(current, effectiveB2bProfileId)
        val b2bHeaderSection = b2bHeaderFields.section
        // Reused by wireFreeTextChips below (after einvoiceTab exists).
        val freeTextChipTerms = b2bFreeHeaderChipTerms(
            b2bHeaderFields.invoiceHeaderFields, b2bHeaderFields.sellerHeaderFields, b2bHeaderFields.recipientHeaderFields,
        ) { key -> tr(key) }

        widgets.partyCard.wireReverseCharge()

        val sections = buildEntryPaneSections(tr("invoiceDetail.entry.section.notes"), freeText, widgets.partyCard.buildSection(widgets.currencyBox), widgets.dates.buildSection())

        // ── Tab 2 "E-Rechnung" — guided B2B stepper or plain flat form, built by einvoiceEntryTab. ──
        val einvoiceTab = einvoiceEntryTab.build(
            host,
            EinvoiceTabParameters(
                current = current,
                party = party,
                b2bProfile = widgets.b2bProfile,
                binder = binder,
                references = references,
                b2bHeaderSection = b2bHeaderSection,
            ),
        )
        // build(...) is what registers the InvoiceFormModule (ModuleFieldSection)
        // bindings, e.g. the format ComboBox, and it only runs after the one-shot binder.readBean(formState)
        // above: Binder.readBean copies the bean's values into whatever fields are already bound at
        // that instant — re-reading here (same bean, a pure no-op for every already-bound field) is
        // what makes a preselected value (e.g. the reverse-charge INTERNATIONAL format default) actually show up on open.
        binder.readBean(formState)

        // Chip candidates read live off the actual widgets (never a stale snapshot); see
        // InvoiceFreeTextChipSource.kt's wireFreeTextChips KDoc.
        wireFreeTextChips(
            freeText.chips,
            freeText.chipSource,
            { key -> tr(key) },
            InvoiceChipFieldSources(dateFields.serviceFromField, dateFields.serviceToField, einvoiceTab.projectNumberField, b2bHeaderSection, freeTextChipTerms),
        )

        val guidedTrigger = einvoiceGuidedModeTrigger(effectiveB2bProfileId, current.recipient?.routingId)
        // Shared with einvoiceEntryTab's own copy of this computation; fed from the
        // INVOICE+SELLER+RECIPIENT union so a mandatory seller/customer field counts here too.
        val mandatoryHeaderFields: List<MandatoryB2bHeaderField> =
            mandatoryB2bHeaderFields(einvoiceEntryTab.b2bFieldDefinitionQueries, effectiveB2bProfileId, b2bHeaderSection.currentValues().keys)
        val guidedHint = GuidedHintController(host, guidedTrigger, references.buyerReferenceField, mandatoryHeaderFields, b2bHeaderSection)

        val invoiceTabContent = Div().apply {
            addClassName("entry-tab-content")
            guidedHint.build()?.let { add(it) }
            add(sections.parties, sections.dates)
        }

        // TabSheet.add always returns a freshly created, non-null Tab.
        val tabSheet = TabSheet().apply { setWidthFull() }
        val invoiceTabRef: Tab = tabSheet.add(tr("invoiceDetail.entry.tab.invoice"), invoiceTabContent)
        val einvoiceTabRef: Tab = tabSheet.add(einvoiceTab.captionComponent, einvoiceTab.content)
        val notesContent = Div(sections.notes).apply { addClassName("entry-tab-content") }
        val notesTabRef: Tab = tabSheet.add(tr("invoiceDetail.entry.tab.notes"), notesContent)
        val validationSummary = buildValidationSummaryContainer()
        val nav = EntryTabNavigation(tabSheet, invoiceTabRef, einvoiceTabRef, einvoiceTab, validationSummary, notesTabRef, notesContent)

        // Hook the hint refresh to field changes (same trigger as the E-Rechnung tab caption)
        references.buyerReferenceField.addValueChangeListener { guidedHint.refresh() }
        b2bHeaderSection.addValueChangeListener { guidedHint.refresh() }

        return EntryPaneTabsResult(nav, guidedTrigger, mandatoryHeaderFields, b2bHeaderSection)
    }

    // ── Tab 1 "Rechnung" — the actual invoice header: parties/dates/notes. ──
    private fun buildEntryPaneSections(
        notesTitle: String,
        freeText: EntryFreeTextFields,
        partiesSection: Div,
        datesSection: Div,
    ): EntryPaneSections {
        val notesSection = entrySection(
            notesTitle,
            FormLayout(freeText.block, freeText.invoiceNoteField, freeText.consumerNote.component).apply {
                setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("700px", 2))
                setColspan(freeText.block, 2)
                setColspan(freeText.invoiceNoteField, 2)
                setColspan(freeText.consumerNote.component, 2)
            },
        )
        return EntryPaneSections(partiesSection, datesSection, notesSection)
    }
}

private fun buildValidationSummaryContainer(): Div = Div().apply {
    isVisible = false
    addClassNames("ino-card", "ino-card--padded", "ino-card--warn")
    element.setAttribute("role", "alert")
    setWidthFull()
}

/** Null exactly when the guided trigger is NONE: [build] always builds the hint card for an
 *  active trigger and only hides it while nothing is missing, so a later edit that empties a
 *  mandatory field has a component to surface (the former known gap where a pane opened complete
 *  could never show the hint again). */
private class GuidedHintController(
    private val host: Component,
    private val trigger: EInvoiceGuidedTrigger,
    private val buyerReferenceField: TextField,
    private val mandatoryHeaderFields: List<MandatoryB2bHeaderField>,
    private val b2bHeaderSection: de.littletrotter.ui.component.B2bProfileFieldsSection,
) {
    private var component: Div? = null
    private var textSpan: Span? = null

    private fun tr(key: String, vararg params: Any) = UiTranslations.tr(host, key, *params)

    private fun currentStatuses(): List<EInvoiceInputStatus> = einvoiceInputStatuses(
        EInvoiceInputStatusQuery(
            guidedModeTriggered = true,
            buyerReferenceLabel = tr(buyerReferenceI18nKey()),
            buyerReference = buyerReferenceField.value,
            mandatoryB2bHeaderFields = mandatoryHeaderFields,
            b2bHeaderFieldValues = b2bHeaderSection.currentValues(),
        ),
    )

    fun refresh() {
        if (trigger == EInvoiceGuidedTrigger.NONE) {
            component?.isVisible = false
            return
        }
        val missingCount = currentStatuses().count { it.missing }
        if (missingCount == 0) {
            component?.isVisible = false
        } else {
            component?.isVisible = true
            textSpan?.text = tr("invoiceDetail.entry.einvoice.guidedHint", missingCount.toString())
        }
    }

    fun build(): Component? {
        if (trigger == EInvoiceGuidedTrigger.NONE) return null
        val missingCount = currentStatuses().count { it.missing }
        val hintContent = Div().apply hintCard@{
            setId("guided-hint")
            isVisible = missingCount > 0
            addClassNames("ino-card", "ino-card--padded", "ino-card--warn")
            textSpan = Span(tr("invoiceDetail.entry.einvoice.guidedHint", missingCount.toString()))
            add(textSpan)
            add(Span(" "))
            val dismissBtn = Span("×").apply {
                addClassNames("line-advice-dismiss", "lt-line-advice-dismiss")
                element.setAttribute("aria-label", tr("invoiceDetail.entry.einvoice.guidedHint.dismiss"))
                addClickListener { this@hintCard.isVisible = false }
            }
            add(dismissBtn)
        }
        component = hintContent
        return hintContent
    }
}
