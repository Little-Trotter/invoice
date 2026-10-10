package de.littletrotter.ui.view.masterdata
import de.littletrotter.ui.component.SCHEME_ID_MAX_LENGTH
import de.littletrotter.ui.notification.UiNotifications

import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.application.invoice.MultilingualTextsFeatureConfig
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.sellerRegistrationCountries
import de.littletrotter.domain.reference.LocalizedTextPurpose
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.PartyExtensionsSection
import de.littletrotter.ui.component.ScopedB2bProfileFieldsSection
import de.littletrotter.ui.component.SellerVatRegistrationsSection
import de.littletrotter.ui.component.VatIdField
import de.littletrotter.ui.view.AbstractCrudView
import de.littletrotter.spi.party.PartyKind
import jakarta.inject.Inject

private const val TITLE_ATTR = "title"
private const val VALIDATION_CORRECT_MARKED_FIELDS_KEY = "validation.correctMarkedFields"
private const val SENDER_PROFILE_DIALOG_NEW_KEY = "senderProfile.dialog.new"

@Route(value = "sender-profiles", layout = MainLayout::class)
@PageTitle("Anbieterprofile")
class SenderProfileView
@Inject
constructor(
    private val sellerProfileService: SellerProfileService,
    private val b2bSectionComposer: SenderB2bSectionComposer,
    private val defaultsComposer: SenderProfileDefaultsComposer,
    private val consumerNoteComposer: SenderConsumerNoteComposer,
    numberRangeQueries: NumberRangeQueryPort,
    partyFormRegistry: PartyFormExtensionRegistry,
    sellerVatRegistrationsRegistry: SellerVatRegistrationsExtensionRegistry,
    private val multilingualTextsFeatureConfig: MultilingualTextsFeatureConfig = MultilingualTextsFeatureConfig(false),
) : AbstractCrudView<SenderProfile, SenderProfileFormData>() {

    /** The extension pairs of the seller (electronic address BT-34); empty when no
     *  [de.littletrotter.spi.party.PartyFormExtension] contributes any. */
    private val partySection: PartyExtensionsSection =
        PartyExtensionsSection(PartyKind.SELLER, partyFormRegistry.enabledAspects(PartyKind.SELLER)) { key -> tr(key) }

    /** The additional VAT registrations list; empty/absent when no extension
     *  contributes any. */
    private val sellerVatSection: SellerVatRegistrationsSection =
        SellerVatRegistrationsSection(sellerVatRegistrationsRegistry.enabledExtension()) { key -> tr(key) }

    /** The seller's own SELLER/BOTH-placed B2B fields: the union across every active profile
     *  (the seller's own profile selector was removed, see [de.littletrotter.application.reference.port.inbound
     *  .B2bFieldDefinitionQueryPort.findB2bSellerFieldsUnion]), split into an always-visible
     *  unscoped group and one group per country a scoped profile names, toggled reactively by
     *  [updateEinvoiceFieldVisibility]. Built once from constructor params only (no dependency on
     *  other properties), so it is initialized directly here rather than in `init`, and needs no
     *  nullability: the widget set itself never changes after construction, only
     *  [updateEinvoiceFieldVisibility]'s per-group visibility does. */
    private val scopedB2bSection: ScopedB2bProfileFieldsSection = b2bSectionComposer.buildSection { key -> tr(key) }

    private val comboSupport = SenderProfileComboSupport(defaultsComposer, numberRangeQueries)

    internal val grid = DataTable(SenderProfile::class.java, false)
    private val binder = Binder(SenderProfileFormData::class.java)

    private val nameField = TextField()
    /** Street/postalCode/city/country, previously four hand-built fields. */
    private val addressFormSection: AddressFormSection = AddressFormSection("senderProfile", { key -> tr(key) })
    private val taxNumberField = TextField()
    private val vatIdField = VatIdField({ key -> tr(key) }, { addressFormSection.country.value }, mismatchIsError = false)
    private val ibanField = TextField()
    private val bicField = TextField()
    private val bankNameField = TextField()
    internal val defaultPaymentTermField = com.vaadin.flow.component.combobox.ComboBox<PaymentTermDto>().apply {
        setItems(defaultsComposer.activePaymentTerms())
        // A seller's own default payment-term choice is master data, not tied to any one
        // invoice's document language; the admin grid's own German (fallback) display convention
        // applies here too.
        setItemLabelGenerator { it.nameFor("de") ?: it.nameFor("en").orEmpty() }
        isClearButtonVisible = true
    }
    private val invoicePrefixField = TextField()

    /** Seller number preview from SELLER circle; never editable, no collision risk. */
    private val sellerNumberField = TextField()

    /** Proposed seller number on dialog open; passed to save handler for collision detection. */
    private var proposedSellerNumber: String? = null

    /** Short hash display-only; assigned on save by [SenderProfile.ensureShortHash]. */
    private val shortHashField = TextField()

    /**
     * Active languages, fallback (`de`) first, then every other active language in sort order.
     * Explicitly fallback-first rather than trusting seed ordering: the spec requires the German
     * field to keep its position regardless of how the language sort order happens to be seeded.
     */
    private val invoiceNoteLanguagesOrdered: List<LanguageDto> = consumerNoteComposer.activeLanguages().let { languages ->
        val fallback = languages.filter { it.isFallback }
        fallback + languages.filterNot { it.isFallback }
    }

    /** One field per active language, never hard-coded. The fallback language's field is
     *  unconditional; every other one is built only when [multilingualTextsFeatureConfig] is
     *  enabled — not added and hidden, so a disabled feature is unreachable via the DOM. */
    internal val invoiceNoteFieldsByLanguage: Map<LanguageDto, TextField> =
        invoiceNoteLanguagesOrdered
            .filter { it.isFallback || multilingualTextsFeatureConfig.enabled }
            .associateWith { language ->
                TextField().apply {
                    maxLength = LocalizedTextPurpose.SELLER_INVOICE_NOTE.maxLength
                    setWidthFull()
                    if (language.isFallback) {
                        label = tr("senderProfile.footerNote")
                        helperText = tr("senderProfile.footerNote.helper")
                    } else {
                        label = tr("senderProfile.invoiceNote.additional.label", language.code.uppercase())
                        helperText = tr("senderProfile.invoiceNote.additional.helper")
                    }
                }
            }

    /** Bridges [applyFormData] (which sees the just-written [SenderProfileFormData]) to
     *  [persistForm] (which only sees the target entity), the same shape as [proposedSellerNumber].
     *  Keyed by language code, not [LanguageDto] itself: [SellerProfileService.save] resolves each
     *  code against the active [de.littletrotter.domain.reference.Language] rows itself. */
    internal var pendingInvoiceNotesByLanguage: Map<String, String?> = emptyMap()

    private val phoneField = TextField()
    private val emailField = TextField()
    private val websiteField = TextField()
    private val registeredSeatField = TextField()
    private val commercialRegisterField = TextField()
    // ── BT-29/30/34 identity fields ────────────────────────────────────────────────────────
    private val sellerIdentifierValueField = TextField()
    private val sellerIdentifierSchemeField = TextField().apply { maxLength = SCHEME_ID_MAX_LENGTH }
    private val legalRegistrationSchemeIdField = TextField().apply { maxLength = SCHEME_ID_MAX_LENGTH }
    private val managingDirectorField = TextField()
    private val courtOfJurisdictionField = TextField()
    private val registerCourtField = TextField()
    private val defaultSellerField = Checkbox()

    // ── Tab "Firmierung": Rechtsform + Handwerkskammer ──────────────────────────
    internal val legalFormCombo = com.vaadin.flow.component.combobox.ComboBox<LegalForm>().apply {
        setItems(LegalForm.UI_ORDER)
        setItemLabelGenerator { tr(it.labelKey) }
        isClearButtonVisible = true
    }
    private val craftsChamberField = Checkbox()

    // One field-group instance per tab, shared by the dialog composers below (layout,
    // labels/Binder wiring and load/save mapping are three different reasons to change over the
    // same widgets).
    private val identityFields = IdentitySectionFields(nameField, invoicePrefixField, sellerNumberField, shortHashField, defaultSellerField)
    private val addressContactFields =
        AddressContactSectionFields(addressFormSection, phoneField, emailField, websiteField, registeredSeatField)
    private val taxFields = TaxSectionFields(taxNumberField, vatIdField, sellerVatSection)
    private val firmierungFields = FirmierungSectionFields(
        legalFormCombo, craftsChamberField, commercialRegisterField, legalRegistrationSchemeIdField,
        registerCourtField, managingDirectorField, courtOfJurisdictionField,
    )
    private val einvoiceFields = EinvoiceSectionFields(sellerIdentifierValueField, sellerIdentifierSchemeField, partySection, scopedB2bSection)
    private val consumerNoteFields = SenderConsumerNoteFields(invoiceNoteFieldsByLanguage.keys.toList()) { key -> tr(key) }
    private val bankAndNotesFields = BankAndNotesSectionFields(
        ibanField, bicField, bankNameField, defaultPaymentTermField, invoiceNoteFieldsByLanguage, consumerNoteFields,
    )

    /** Whether the open dialog shows (and therefore saves) the private-customer tab. */
    private var consumerTabActive = false

    /** The dialog's tab-section builders, hero-card spans and the Firmierung hint span
     *  live in this composer; see [SenderProfileDialogSections]'s own KDoc. */
    private val sections = SenderProfileDialogSections(
        tr = { key -> tr(key) },
        identity = identityFields,
        addressContact = addressContactFields,
        tax = taxFields,
        firmierung = firmierungFields,
        einvoice = einvoiceFields,
        bankAndNotes = bankAndNotesFields,
    )

    /** Field labels and Binder wiring for the same tabs; see [SenderProfileFormBinder]'s
     *  own KDoc. */
    private val formBinder = SenderProfileFormBinder(
        tr = { key -> tr(key) },
        identity = identityFields,
        addressContact = addressContactFields,
        tax = taxFields,
        firmierung = firmierungFields,
        einvoice = einvoiceFields,
        bankAndNotes = bankAndNotesFields,
    )

    /** The dialog's load/save mapping for everything [binder] does not cover; see
     *  [SenderProfileFormPopulator]'s own KDoc. */
    private val formPopulator = SenderProfileFormPopulator(
        binder, SenderProfileLoadFields(identityFields, taxFields, einvoiceFields, bankAndNotesFields), sections, comboSupport, ::findEntityById,
    )

    /** The grid/toolbar; see [SenderProfileGridComposer]'s own KDoc. */
    private val gridComposer = SenderProfileGridComposer(grid, sellerProfileService, { key -> tr(key) }, ::nameCell) { openEditor(it) }

    override var currentFormData = SenderProfileFormData()

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true
        // No seller pairs are declared today (no seller SIRET), so this
        // is a no-op in practice, but the mechanism is generic over PartyKind.
        addressFormSection.onCountryChange {
            updatePartySectionVisibility()
            vatIdField.onLinkedCountryChanged()
        }
        // The country-scoped B2B field groups react to the same
        // two inputs sellerRegistrationCountries() is built from: the VAT id and the additional
        // EU VAT registrations.
        vatIdField.addValueChangeListener { updatePartySectionVisibility(); updateEinvoiceFieldVisibility() }
        // The "HR data omitted" hint reacts to both inputs of the suppression rule.
        legalFormCombo.addValueChangeListener { sections.updateFirmierungHintVisibility() }
        craftsChamberField.addValueChangeListener { sections.updateFirmierungHintVisibility() }
        sellerVatSection.onChange { updateEinvoiceFieldVisibility() }
        initTexts()
        gridComposer.configureGrid()
        configureDialog()
        add(
            PageShell(tr("senderProfile.title"), tr("senderProfile.validationInfo"))
                .actions(gridComposer.buildNewButton())
                .filters(gridComposer.buildFilter())
                .content(GridWrapper(grid)),
        )
        updatePageTitle()
    }

    private fun initTexts() {
        saveButton.text = tr("app.save")
        deleteButton.text = tr("app.delete")
        cancelButton.text = tr("app.cancel")
    }

    /** Name cell with default-profile star marker. Kept directly on the view (not on
     *  [SenderProfileGridComposer]) because the Karibu tests reflect it by name. */
    private fun nameCell(sender: SenderProfile): Span = Span(sender.name).apply {
        if (sender.isDefault) {
            add(
                Icon(VaadinIcon.STAR).apply {
                    addClassName("lt-default-star")
                    element.setAttribute(TITLE_ATTR, tr("senderProfile.grid.defaultMarker"))
                    element.setAttribute("aria-label", tr("senderProfile.grid.defaultMarker"))
                },
            )
        }
    }

    private fun configureDialog() {
        formBinder.configureFieldLabels(sections.firmierungHintSpan)
        formBinder.bind(binder)
        initCrudDialogButtons()
        val heroDiv = sections.buildHeroCard()
        val tabs = sections.buildTabs()
        installCrudFooter()
        sections.finalizeDialog(editorDialog, heroDiv, tabs)
    }

    override fun idOf(formData: SenderProfileFormData): Long? = formData.id

    /** The grid row is a detached entity whose `defaultPaymentTerm` is an uninitialized lazy
     *  proxy, so an existing profile is re-loaded completely here before the form is built. No
     *  fallback: if the record cannot be loaded, [openEditor] shows the error without opening the
     *  dialog — an empty form over an existing record invites saving blank values over it. */
    override fun formDataFrom(entity: SenderProfile?): SenderProfileFormData {
        if (entity == null) return createWritableForm(null)
        val id = entity.id ?: return SenderProfileFormData.from(entity)
        val loaded = sellerProfileService.findByIdForEdit(id)
            ?: throw IllegalStateException(tr("senderProfile.load.failed", id.toString()))
        return SenderProfileFormData.from(loaded)
    }

    override fun createWritableForm(id: Long?): SenderProfileFormData = SenderProfileFormData(
        id = id,
        version = if (id == null) null else currentFormData.version,
        // New profiles default to immediate payment (dueInDays=0); existing profiles preserve their setting.
        defaultPaymentTerm = if (id == null) defaultsComposer.defaultPaymentTermForNewProfile() else null,
    )

    override fun expectedRevision(formData: SenderProfileFormData): Long? = formData.version

    override fun currentRevision(entity: SenderProfile): Long? = entity.version

    /** Field-by-field mapping onto the widgets lives on [formPopulator]; a failed load propagates
     *  to [openEditor], which reports it and leaves the dialog closed rather than falling back to
     *  an empty form. */
    override fun readFormData(formData: SenderProfileFormData) {
        proposedSellerNumber = formPopulator.populate(formData)
        consumerTabActive = consumerNoteComposer.tabAvailable()
        sections.updateConsumerTabVisibility(consumerTabActive)
        if (consumerTabActive) consumerNoteFields.populate(formData.id?.let(consumerNoteComposer::load))
        updatePartySectionVisibility()
        vatIdField.onLinkedCountryChanged()
        sections.updateFirmierungHintVisibility()
        updateEinvoiceFieldVisibility()
    }

    override fun writeValidatedForm(targetFormData: SenderProfileFormData): Boolean {
        return formPopulator.writeValidated(targetFormData) {
            UiNotifications.error(tr(VALIDATION_CORRECT_MARKED_FIELDS_KEY))
        }
    }

    override fun findEntityById(id: Long): SenderProfile? = sellerProfileService.findById(id)

    private fun applyFormData(target: SenderProfile, formData: SenderProfileFormData) {
        formData.applyTo(target)
        // Write the extension pairs (electronic address) onto the persisted entity.
        partySection.writeTo(target)
        // Likewise for the additional VAT registrations list.
        sellerVatSection.writeTo(target)
        // invoiceNote is not part of formData.applyTo's plain field mirror; it is captured here
        // for the save call below.
        pendingInvoiceNotesByLanguage = invoiceNoteFieldsByLanguage.keys.associate { language ->
            language.code to formData.invoiceNotesByLanguageCode[language.code]
        }
    }

    override fun persistForm(formData: SenderProfileFormData, current: SenderProfile?) {
        val target = current ?: SenderProfile()
        applyFormData(target, formData)
        sellerProfileService.save(
            target,
            proposedSellerNumber,
            pendingInvoiceNotesByLanguage,
            SellerDefaultSelections(
                paymentTermId = formData.defaultPaymentTerm?.id,
            ),
        )
        val id = checkNotNull(target.id) { "Sender must have ID after save" }
        if (consumerTabActive) consumerNoteComposer.save(id, consumerNoteFields.enabledField.value, consumerNoteFields.textsForSave())
        sellerProfileService.saveAddress(
            id,
            Address.AddressType.BILLING,
            addressFormSection.street.value,
            addressFormSection.postalCode.value,
            addressFormSection.city.value,
            addressFormSection.country.value?.alpha2 ?: "DE",
        )
        // Persisted after the sender has an id; a term left off this profile's
        // current field set is simply not among currentValues() and stays untouched.
        // unconditional on current group visibility: a hidden country group's typed value is
        // written back exactly like a visible one.
        b2bSectionComposer.persist(id, scopedB2bSection)
    }

    override fun deleteEntityById(id: Long) {
        sellerProfileService.delete(id)
    }

    override fun refreshGridData() {
        gridComposer.refreshGrid()
    }

    override fun newHeaderTitle(): String = tr(SENDER_PROFILE_DIALOG_NEW_KEY)

    override fun editHeaderTitle(): String = tr("senderProfile.dialog.edit")

    private fun updatePageTitle() {
        val ui = UI.getCurrent() ?: return
        ui.page.setTitle("${tr("senderProfile.title")} | ${tr("app.title")}")
    }

    /** The seller has a single address (no billing/general split), so the country context
     *  is just [addressFormSection]'s current country value. */
    private fun updatePartySectionVisibility() {
        partySection.applyCountryContext(addressFormSection.country.value, vatIdField.value)
    }

    /** The seller's current VAT registration countries, built
     *  from the same two inputs [updateEinvoiceFieldVisibility] reacts to. */
    private fun currentSellerCountries(): Set<de.littletrotter.shared.country.Country> =
        sellerRegistrationCountries(vatIdField.value, sellerVatSection.currentCountries())

    /** Recomputes which of [scopedB2bSection]'s country groups are visible for the
     *  current seller; wired to vatIdField changes and [sellerVatSection]'s own change hook (init
     *  block), and called explicitly once more after both finish loading a record ([readFormData]). */
    private fun updateEinvoiceFieldVisibility() {
        scopedB2bSection.applySellerCountries(currentSellerCountries())
    }
}
