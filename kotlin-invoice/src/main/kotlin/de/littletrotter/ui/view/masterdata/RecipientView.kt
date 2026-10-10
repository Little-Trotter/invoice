package de.littletrotter.ui.view.masterdata

import de.littletrotter.ui.component.SCHEME_ID_MAX_LENGTH
import de.littletrotter.ui.i18n.UiTranslations
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientDraft
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientB2bFieldsQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.component.B2bProfileFieldsSection
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.PartyExtensionsSection
import de.littletrotter.ui.component.VatIdField
import de.littletrotter.ui.component.countryDisplayName
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.AbstractCrudView
import de.littletrotter.spi.party.PartyKind
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import de.littletrotter.application.error.ApplicationConflictException
import de.littletrotter.application.error.isOptimisticLockFailure

private const val RECIPIENT_OPTIMISTIC_LOCK_KEY = "recipient.error.optimisticLock"

private const val RECIPIENT_DIALOG_NEW_KEY = "recipient.dialog.new"
private const val MASTERDATA_LOAD_FAILED_KEY = "masterdata.load.failed"

/** [RecipientView]'s own two collaborators for the per-row "Rechnung erstellen" action
 *  ([RecipientGridComposer.openInvoiceCreateDialogFor]) — bundled into one CDI bean (a plain data
 *  class has no bean-defining annotation, so ARC cannot resolve it as a constructor injection
 *  point, same idiom as [de.littletrotter.application.invoice.InvoiceLifecycleSupport]) purely to
 *  stay under detekt's `LongParameterList` threshold; the grid/list section itself never uses
 *  either field on its own. */
@Dependent
class InvoiceCreationDependencies @Inject constructor(
    val senderQuery: SenderQueryPort,
    val createInvoiceUseCase: CreateInvoiceUseCase,
)

@Route(value = "recipients", layout = MainLayout::class)
@PageTitle("Kunden")
class RecipientView
@Inject
constructor(
    // Writes go through [manageRecipient]; reads go through [recipientQuery].
    private val manageRecipient: ManageRecipientUseCase,
    private val recipientQuery: RecipientQueryPort,
    partyFormRegistry: PartyFormExtensionRegistry,
    vatIdVerificationPort: de.littletrotter.application.masterdata.VatIdVerificationPort,
    // the B2B profile selector on the customer relationship.
    private val recipientB2bFields: RecipientB2bFieldsQueryPort,
    // Needed only to open InvoiceCreateDialog for the per-row "Rechnung erstellen" action.
    invoiceCreation: InvoiceCreationDependencies,
    time: de.littletrotter.shared.time.TimeSource,
) : AbstractCrudView<RecipientSummaryDto, RecipientDraft>() {

    /** The extension pairs of the buyer (electronic address BT-49); empty when no
     *  [de.littletrotter.spi.party.PartyFormExtension] contributes any. */
    private val partySection: PartyExtensionsSection =
        PartyExtensionsSection(PartyKind.BUYER, partyFormRegistry.enabledAspects(PartyKind.BUYER)) { key -> tr(key) }

    // internal, not private: Karibu tests drive and assert these fields with their real generic
    // item type. Reflection erases that type, so a reflected field can only be cast back to it
    // unchecked; same-module access keeps the type and needs no cast at all.
    internal val grid = DataTable(RecipientSummaryDto::class.java, false)

    /** Proposed number on dialog open; passed to save handler for collision detection. */
    private var proposedCustomerNumber: String? = null

    private val customerNumberField = TextField()

    /** Display-only mirror of `Recipient.ensureShortHash`'s assigned value. */
    private val shortHashField = TextField()
    private val nameField = TextField()
    /** Street/postalCode/city/country, previously four hand-built fields per address. */
    private val addressFormSection: AddressFormSection =
        AddressFormSection("recipient", { key -> tr(key) }, ::countryItemLabelGenerator)
    private val vatIdField = VatIdField({ key -> tr(key) }, { addressFormSection.country.value }, mismatchIsError = false)
    /** Enabled only while [vatIdField] is non-blank and format-valid; wiring lives on [vatIdVerification]. */
    private val vatIdVerifyButton = AppButtons.tertiary(tr("recipient.vatId.verify.action"), ActionIcons.VERIFY) {}.apply {
        addThemeVariants(ButtonVariant.LUMO_SMALL)
    }
    private val ibanField = TextField()
    private val routingIdField = TextField()
    private val emailField = TextField()
    private val phoneField = TextField()
    private val contactPersonField = TextField()

    // ── BT-46/47/49 identity + customer type (previously SQL-only) ──
    internal val customerTypeField = buildRecipientCustomerTypeField()
    internal val invoiceFormatField = buildRecipientInvoiceFormatField()
    internal val documentLanguageField = buildRecipientDocumentLanguageField()

    /** The recipient's standing default for how Reverse Charge is decided at invoice
     *  creation; see [buildRecipientReverseChargePreferenceField]'s own KDoc. */
    internal val reverseChargePreferenceField = buildRecipientReverseChargePreferenceField()

    private val buyerIdentifierValueField = TextField()
    // The *_scheme_id columns are VARCHAR(10); a longer entry would only fail at the flush.
    private val buyerIdentifierSchemeField = TextField().apply { maxLength = SCHEME_ID_MAX_LENGTH }
    private val legalRegistrationValueField = TextField()
    private val legalRegistrationSchemeField = TextField().apply { maxLength = SCHEME_ID_MAX_LENGTH }

    /** The manual VAT validation checkbox is the sole surface for verification status: checked
     *  = VERIFIED. FAILED renders as [vatIdFailedBadge] next to the unchecked box instead — wiring
     *  and reconciliation onto the persisted entity live on [vatIdVerification]. */
    private val vatIdValidatedCheckbox = Checkbox()
    private val vatIdFailedBadge = buildVatIdFailedBadge()
    private val vatIdValidatedRow = buildVatIdValidatedRow(vatIdValidatedCheckbox, vatIdFailedBadge)

    /** Optional free-text detail for the manual attestation above; always editable and saved
     *  regardless of the checkbox's state (unlike the persisted verification status/timestamp,
     *  which the checkbox/automated check drive). */
    private val vatIdValidationNoteField = TextField().apply { maxLength = 255 }

    /** Owns the manual-attestation state and the on-demand VIES check; see
     *  [RecipientVatIdVerificationComposer]'s own KDoc. */
    private val vatIdVerification = RecipientVatIdVerificationComposer(
        VatIdFormFields(vatIdField, vatIdVerifyButton, vatIdValidatedCheckbox, vatIdFailedBadge, vatIdValidationNoteField),
        vatIdVerificationPort,
        time,
    ) { key, params -> tr(key, *params) }

    /** The standing B2B profile default for this customer; see
     *  [buildRecipientB2bProfileCombo]'s own KDoc. */
    internal val b2bProfileCombo = buildRecipientB2bProfileCombo(recipientB2bFields)

    /** Per-customer values for [b2bProfileCombo]'s currently assigned profile's
     *  `RECIPIENT`-placed fields (`recipient_b2b_field_value`). `ownerType = null` — reused via the
     *  `Map`-based [B2bProfileFieldsSection.readFrom] overload, never the generic one. */
    private val b2bFieldsSection = B2bProfileFieldsSection { key -> tr(key) }
    private val b2bFieldsSectionTitle = Span()
    private val b2bFieldsContainer = buildRecipientB2bFieldsContainer(b2bFieldsSectionTitle, b2bFieldsSection)

    /** Owns prepare/refresh/persist for the section above; see [RecipientB2bProfileComposer]'s own KDoc. */
    private val b2bProfileComposer = RecipientB2bProfileComposer(
        b2bProfileCombo, b2bFieldsSection, b2bFieldsContainer, recipientB2bFields,
    ) { currentRecipientDetail?.id }

    /** Delivery address (optional); billing address is mandatory. */
    private val deliveryAddressFormSection: AddressFormSection =
        AddressFormSection("recipient", { key -> tr(key) }, ::countryItemLabelGenerator)

    // The dialog's per-tab field groups, shared by [dialogSections] (layout/labels),
    // [formPopulator] (load/save mapping) and [formValidator] (save-time rules): one instance per
    // section, layout/mapping/validation are three different reasons to change over the same
    // widgets.
    private val identityFields = RecipientIdentityFields(customerNumberField, nameField, contactPersonField, shortHashField)
    private val addressFields = RecipientAddressFields(addressFormSection, deliveryAddressFormSection, emailField, phoneField)
    private val taxFields = RecipientTaxFields(
        customerTypeField, invoiceFormatField, documentLanguageField, reverseChargePreferenceField, vatIdField, vatIdValidationNoteField, ibanField,
    )
    private val vatIdLayoutFields = RecipientVatIdLayoutFields(vatIdVerifyButton, vatIdValidatedRow)
    private val einvoiceIdentityFields = RecipientEinvoiceIdentityFields(
        routingIdField, buyerIdentifierValueField, buyerIdentifierSchemeField,
        legalRegistrationValueField, legalRegistrationSchemeField, partySection,
    )
    private val b2bFields = RecipientB2bFields(b2bProfileCombo, b2bFieldsContainer, b2bFieldsSectionTitle)

    private val dialogSections = RecipientDialogSections(
        { key -> tr(key) }, identityFields, addressFields, taxFields, vatIdLayoutFields, einvoiceIdentityFields, b2bFields,
    )
    private val formPopulator = RecipientFormPopulator(
        identityFields, addressFields, taxFields, einvoiceIdentityFields, b2bFields, vatIdVerification, b2bProfileComposer,
    )
    private val formValidator = RecipientFormValidator(identityFields, addressFields, taxFields) { key -> tr(key) }

    /** The dialog's six tabs, built by [dialogSections]; held as a field so [revealField] can
     *  switch tabs long after the dialog was built. */
    private lateinit var tabs: TabSheet

    /** Which tab each field lives on, populated by [dialogSections]'s `buildTabs`; backs [revealField]. */
    internal val fieldTabs = mutableMapOf<Component, Tab>()

    private fun countryItemLabelGenerator(country: Country): String = countryDisplayName(country.alpha2)

    private fun updatePartySectionVisibility() {
        partySection.applyCountryContext(addressFormSection.country.value, vatIdField.value)
    }

    /** The read model [populateForm] displays from; `null` for a not-yet-persisted recipient. */
    private var currentRecipientDetail: RecipientDetailDto? = null

    override var currentFormData: RecipientDraft = RecipientDraft.blank()

    /** Owns the grid/toolbar/per-row "Rechnung erstellen" action; see [RecipientGridComposer]. */
    private val gridComposer = RecipientGridComposer(grid, recipientQuery, invoiceCreation.senderQuery, invoiceCreation.createInvoiceUseCase, { key -> tr(key) }, ::openEditor)

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true
        // Country-scoped extension pairs toggle visibility on country/VAT-id change.
        addressFormSection.onCountryChange {
            updatePartySectionVisibility()
            formPopulator.applyInvoiceFormatPreselection()
            formPopulator.applyDocumentLanguagePreselection()
            vatIdField.onLinkedCountryChanged()
        }
        invoiceFormatField.addValueChangeListener { formPopulator.touchInvoiceFormatIfFromClient(it.isFromClient) }
        documentLanguageField.addValueChangeListener { formPopulator.touchDocumentLanguageIfFromClient(it.isFromClient) }
        vatIdField.addValueChangeListener {
            updatePartySectionVisibility()
            vatIdVerification.updateVatIdVerifyButtonState()
        }
        vatIdValidatedCheckbox.label = tr("recipient.vatIdValidated")
        vatIdValidatedCheckbox.helperText = tr("recipient.vatIdValidated.helper")
        dialogSections.configureLabels()
        buildDialog()
        gridComposer.configureGrid()
        add(
            PageShell(tr("recipient.title"))
                .actions(gridComposer.buildNewButton())
                .filters(gridComposer.buildFilter())
                .content(GridWrapper(grid)),
        )
        gridComposer.refreshGrid()
        updatePageTitle()
        vatIdVerification.updateVatIdVerifyButtonState()
    }

    private fun updatePageTitle() {
        val ui = UI.getCurrent() ?: return
        ui.page.setTitle("${tr("recipient.title")} | ${tr("app.title")}")
    }

    private fun buildDialog() {
        useDialogSize(AppDialog.Size.L)
        editorDialog.height = "auto"
        editorDialog.isCloseOnEsc = true
        editorDialog.isCloseOnOutsideClick = false

        tabs = dialogSections.buildTabs(fieldTabs)
        initCrudDialogButtons()
        installCrudFooter()
        val hero = dialogSections.buildDialogHero(::refreshProposedCustomerNumber)

        editorDialog.add(
            VerticalLayout(hero, tabs).also { outer ->
                outer.isPadding = false
                outer.isSpacing = false
                outer.setWidthFull()
            },
        )
    }

    /** A failed load is reported, never disguised as an empty address — see [formDataFrom]'s own
     *  KDoc. Field-by-field mapping onto the widgets lives on [formPopulator]. */
    private fun populateForm(detail: RecipientDetailDto) {
        // Kept in sync here too (not only in formDataFrom), so a caller that populates the form
        // directly off an already-loaded detail still gets a correct currentRecipientDetail.
        currentRecipientDetail = detail
        proposedCustomerNumber = null // An existing recipient never gets a proposal.
        val b2bProfile = detail.b2bProfileId?.let { recipientB2bFields.findB2bProfileById(it) }
        formPopulator.populate(detail, b2bProfile)
        updatePartySectionVisibility() // Recompute once every loaded field is at its final value.
    }

    private fun clearForm() {
        currentRecipientDetail = null
        grid.asSingleSelect().clear()
        formPopulator.clear()
        // Peek at open (a proposal never touches the counter itself).
        proposedCustomerNumber = recipientQuery.proposeCustomerNumber(null)
        customerNumberField.value = proposedCustomerNumber ?: ""
        updatePartySectionVisibility()
        vatIdVerification.updateVatIdVerifyButtonState()
    }

    /** Name-derived prefix; refreshed on name change but only while the field still shows the
     *  unchanged proposal (never overwrites a manual edit). */
    private fun refreshProposedCustomerNumber(name: String) {
        if (currentFormData.id != null) return // No proposal in edit mode.
        if (customerNumberField.value != (proposedCustomerNumber ?: "")) return
        proposedCustomerNumber = recipientQuery.proposeCustomerNumber(name)
        customerNumberField.value = proposedCustomerNumber ?: ""
    }

    private fun validateForm(): Boolean = formValidator.validate()

    override fun idOf(formData: RecipientDraft): Long? = formData.id

    /** The grid row is a detached entity, so an existing recipient's read model is re-loaded
     *  through [recipientQuery] (own transaction, initializes lazy address collections) rather
     *  than read off the grid row. No fallback: a record that cannot be loaded must not appear as
     *  an empty form — [openEditor] reports the failure and leaves the dialog closed. */
    override fun formDataFrom(entity: RecipientSummaryDto?): RecipientDraft {
        val id = entity?.id ?: return RecipientDraft.blank()
        val detail = recipientQuery.findRecipientDetail(id)
            ?: throw IllegalStateException(tr(MASTERDATA_LOAD_FAILED_KEY, id.toString()))
        currentRecipientDetail = detail
        return RecipientDraft.from(detail)
    }

    /** Carries [currentFormData]'s loaded version forward onto the shell `saveCurrentForm`
     *  validates and applies onto; without it, [expectedRevision] always saw `null` and a
     *  concurrent edit was never detected before the entity got overwritten. */
    override fun createWritableForm(id: Long?): RecipientDraft = RecipientDraft.blank(id = id, version = currentFormData.version)

    override fun expectedRevision(formData: RecipientDraft): Long? = formData.version

    override fun optimisticLockMessageKey(): String = RECIPIENT_OPTIMISTIC_LOCK_KEY

    /** The freshly reloaded entity's own current version; [RecipientSummaryDto] carries no
     *  version, so this re-reads it through [RecipientQueryPort.findRecipientDetail]. */
    override fun currentRevision(entity: RecipientSummaryDto): Long? = recipientQuery.findRecipientDetail(entity.id)?.version

    /** A failed load propagates to [openEditor], which reports it and keeps the dialog shut, rather
     *  than silently showing an empty form over an existing record. */
    override fun readFormData(formData: RecipientDraft) {
        if (formData.id == null) {
            clearForm()
        } else {
            populateForm(currentRecipientDetail ?: throw IllegalStateException(tr(MASTERDATA_LOAD_FAILED_KEY, formData.id.toString())))
        }
    }

    /** Switches to the tab holding [field] and focuses it — PO report 04.09.2026 ("Speichern-Knopf
     *  tut sichtbar nichts"): five of the six tabs are hidden at any given time, so an invalid
     *  field on a tab not currently shown looked exactly like a dead save button. */
    private fun revealField(field: TextField) {
        fieldTabs[field]?.let { tabs.selectedTab = it }
        field.focus()
        UiNotifications.flash(field)
    }

    override fun writeValidatedForm(targetFormData: RecipientDraft): Boolean {
        if (validateForm()) return true
        val invalid = formValidator.firstInvalidField()
        if (invalid == null) UiNotifications.error(tr("validation.correctMarkedFields")) else revealField(invalid)
        return false
    }

    override fun findEntityById(id: Long): RecipientSummaryDto? = recipientQuery.findRecipientById(id)

    /** The draft the save hands to the use case: [formData] completed off the live widgets. */
    internal fun buildDraft(formData: RecipientDraft = currentFormData): RecipientDraft =
        formPopulator.buildDraft(formData, proposedCustomerNumber)

    override fun persistForm(formData: RecipientDraft, current: RecipientSummaryDto?) {
        try {
            val saved = manageRecipient.saveRecipientWithAddresses(buildDraft(formData))
            currentRecipientDetail = saved
            // Per-customer B2B field values reference recipientId, so this runs after save.
            b2bProfileComposer.persist(saved.id)
        } catch (e: jakarta.persistence.PersistenceException) {
            throw conflictOrRethrow(e, RECIPIENT_OPTIMISTIC_LOCK_KEY)
        } catch (e: jakarta.transaction.RollbackException) {
            // Commit-time conflicts arrive wrapped (ARJUNA016053), see isOptimisticLockFailure.
            throw conflictOrRethrow(e, RECIPIENT_OPTIMISTIC_LOCK_KEY)
        }
    }

    override fun deleteEntityById(id: Long) {
        // WP2 (0.9.10) — the version the editor dialog captured on open, so the service rejects a
        // delete against a row already edited concurrently since then (see [CustomerManagementService.delete]).
        manageRecipient.deleteRecipient(id, currentFormData.version)
    }

    override fun refreshGridData() {
        gridComposer.refreshGrid()
    }

    override fun onAfterDelete() {
        clearForm()
    }

    override fun newHeaderTitle(): String = tr(RECIPIENT_DIALOG_NEW_KEY)

    override fun editHeaderTitle(): String = tr("recipient.dialog.edit")
}

private fun Component.conflictOrRethrow(e: Exception, key: String): Exception =
    if (e.isOptimisticLockFailure()) ApplicationConflictException(UiTranslations.tr(this, key), e) else e
