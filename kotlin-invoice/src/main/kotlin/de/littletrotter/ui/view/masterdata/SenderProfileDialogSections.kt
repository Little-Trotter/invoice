package de.littletrotter.ui.view.masterdata

import de.littletrotter.ui.component.AppDialog
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.component.PartyExtensionsSection
import de.littletrotter.ui.component.ScopedB2bProfileFieldsSection
import de.littletrotter.ui.component.Section
import de.littletrotter.ui.component.SellerVatRegistrationsSection
import de.littletrotter.ui.component.VatIdField

private const val FS_08 = "fs-08"
private const val TEXT_MUTED = "text-muted"
private const val FORM_RESPONSIVE_WIDTH = "520px"
private const val SP_SECTION_BAR_AMBER = "sp-section-bar--amber"
private const val SENDER_PROFILE_DIALOG_NEW_KEY = "senderProfile.dialog.new"

/** Under detekt's `LongParameterList` threshold — the Identity tab's widgets (name, prefix, seller number, short hash,
 *  default flag). */
class IdentitySectionFields(
    val nameField: TextField,
    val invoicePrefixField: TextField,
    val sellerNumberField: TextField,
    val shortHashField: TextField,
    val defaultSellerField: Checkbox,
)

/** Under detekt's `LongParameterList` threshold — the Address tab's widgets (Z3 address group + the phone/email/
 *  website/seat contact fields folded into it, the Adresse tab). */
class AddressContactSectionFields(
    val addressFormSection: AddressFormSection,
    val phoneField: TextField,
    val emailField: TextField,
    val websiteField: TextField,
    val registeredSeatField: TextField,
)

/** Under detekt's `LongParameterList` threshold — the Tax tab's widgets (tax number, VAT id, additional EU VAT
 *  registrations). */
class TaxSectionFields(
    val taxNumberField: TextField,
    val vatIdField: VatIdField,
    val sellerVatSection: SellerVatRegistrationsSection,
)

/** Under detekt's `LongParameterList` threshold — the Firmierung tab's widgets (legal form, Handwerkskammer
 *  flag, commercial register details). */
class FirmierungSectionFields(
    val legalFormCombo: ComboBox<LegalForm>,
    val craftsChamberField: Checkbox,
    val commercialRegisterField: TextField,
    val legalRegistrationSchemeIdField: TextField,
    val registerCourtField: TextField,
    val managingDirectorField: TextField,
    val courtOfJurisdictionField: TextField,
)

/** Under detekt's `LongParameterList` threshold — the E-Rechnung tab's widgets (BT-29 identifier pair, party
 *  extensions, country-scoped B2B fields). */
class EinvoiceSectionFields(
    val sellerIdentifierValueField: TextField,
    val sellerIdentifierSchemeField: TextField,
    val partySection: PartyExtensionsSection,
    val scopedB2bSection: ScopedB2bProfileFieldsSection,
)

/** Under detekt's `LongParameterList` threshold — the Bank and Notes tabs' widgets; grouped together
 *  as the two smallest, trailing tabs rather than growing the composer's own constructor past
 *  its LongParameterList threshold. */
class BankAndNotesSectionFields(
    val ibanField: TextField,
    val bicField: TextField,
    val bankNameField: TextField,
    val defaultPaymentTermField: ComboBox<PaymentTermDto>,
    val invoiceNoteFieldsByLanguage: Map<LanguageDto, TextField>,
    val consumerNote: SenderConsumerNoteFields,
)

/**
 * The dialog-tab section builders extracted from [SenderProfileView], which otherwise
 * mixes CRUD/persistence logic with pure widget layout in one class. This composer owns nothing
 * that survives across records (no service dependency, no entity state); it only lays the view's
 * own widgets (passed in by reference, same shape as [AddressFormSection]/[PartyExtensionsSection]
 * elsewhere in this package, grouped into small per-tab parameter objects to stay under the
 * LongParameterList threshold) into the [Section]s the dialog's [TabSheet] shows, plus the two
 * hero-card spans and the Firmierung hint span whose visibility only this class's own sections
 * ever toggle.
 *
 * Built once by [SenderProfileView]'s constructor, like every other composer/component it uses —
 * this is a plain class, not a CDI bean (UI parts are constructed by the view, not injected).
 */
class SenderProfileDialogSections(
    private val tr: (String) -> String,
    private val identity: IdentitySectionFields,
    private val addressContact: AddressContactSectionFields,
    private val tax: TaxSectionFields,
    private val firmierung: FirmierungSectionFields,
    private val einvoice: EinvoiceSectionFields,
    private val bankAndNotes: BankAndNotesSectionFields,
) {

    val heroNameSpan = Span()
    val heroAvatarSpan = Span()

    /** The dialog title/hero placeholder for a not-yet-named profile; exposed so
     *  [SenderProfileFormPopulator] needs no `tr` reference of its own. */
    fun newProfilePlaceholder(): String = tr(SENDER_PROFILE_DIALOG_NEW_KEY)

    /** Shown only when legalForm == SOLE_PROPRIETOR and craftsChamberField is checked
     *  (see [de.littletrotter.domain.masterdata.SenderProfile.showsCommercialRegister]); toggled
     *  by [updateFirmierungHintVisibility]. */
    val firmierungHintSpan = Span().apply { addClassNames(FS_08, TEXT_MUTED) }

    /** Mirrors `SenderProfile.showsCommercialRegister` on the still-unsaved form fields:
     *  the hint appears exactly when that domain rule would suppress the HR data on an invoice,
     *  so a user sees the consequence of the combination before saving, not after. Wired to both
     *  fields' change listeners (the view's init block) and called explicitly once more after a
     *  record finishes loading (the view's readFormData), the same pattern as the view's own
     *  updatePartySectionVisibility. */
    fun updateFirmierungHintVisibility() {
        firmierungHintSpan.isVisible = firmierung.legalFormCombo.value == LegalForm.SOLE_PROPRIETOR && firmierung.craftsChamberField.value
    }

    // ── Hero card ──────────────────────────────────────────────────
    fun buildHeroCard(): Div {
        heroAvatarSpan.addClassName("sp-hero-avatar")
        heroNameSpan.addClassName("sp-hero-name")
        val heroSubSpan = Span(tr("nav.senderProfiles")).also { it.addClassName("sp-hero-sub") }
        val heroTextDiv = Div().also { d ->
            d.addClassName("sp-hero-text")
            d.add(heroNameSpan, heroSubSpan)
        }
        val heroDiv = Div().also { d ->
            d.addClassName("sp-dialog-hero")
            d.add(heroAvatarSpan, heroTextDiv)
        }

        // live-update avatar as user types the name
        identity.nameField.addValueChangeListener { e ->
            val v = e.value.trim()
            heroNameSpan.text = v.ifBlank { tr(SENDER_PROFILE_DIALOG_NEW_KEY) }
            heroAvatarSpan.text = v.take(1).uppercase().ifEmpty { "+" }
        }
        return heroDiv
    }

    // Build FormLayouts and wrap with shared Section component
    fun buildIdentitySection(): Section {
        val identityForm = FormLayout(
            identity.nameField, identity.invoicePrefixField, identity.sellerNumberField,
            identity.shortHashField, identity.defaultSellerField,
        ).also {
            it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(FORM_RESPONSIVE_WIDTH, 2))
            it.setColspan(identity.defaultSellerField, 2)
        }
        return Section(tr("senderProfile.section.identity"), "sp-section-bar--red", identityForm)
    }

    // Kontakt (phone/email) folds into Adresse: both describe
    // "how to reach this seller", one tab fewer to click through, same eyebrow-heading pattern
    // the E-Rechnung tab already established for grouping several concerns in one Section.
    fun buildAddressSection(): Section {
        val contactForm = FormLayout(
            addressContact.phoneField, addressContact.emailField,
            addressContact.websiteField, addressContact.registeredSeatField,
        ).also {
            it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(FORM_RESPONSIVE_WIDTH, 2))
        }
        val addressContent = VerticalLayout(
            addressContact.addressFormSection,
            einvoiceGroupHeading(tr("senderProfile.section.contact")), contactForm,
        ).also {
            it.isPadding = false
            it.isSpacing = true
            it.setWidthFull()
        }
        return Section(tr("senderProfile.section.address"), "sp-section-bar--blue", addressContent)
    }

    // Commercial register details moved to Firmierung tab; tax identifiers (BT-29, BT-34) remain here.
    fun buildTaxSection(): Section {
        val taxForm = FormLayout(tax.taxNumberField, tax.vatIdField).also {
            it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(FORM_RESPONSIVE_WIDTH, 2))
        }
        // EU VAT registrations are tax data, not e-invoice data.
        val taxContent = VerticalLayout(taxForm).also {
            it.isPadding = false
            it.isSpacing = true
            it.setWidthFull()
            if (tax.sellerVatSection.hasContent) it.add(einvoiceGroupHeading(tr("senderProfile.section.euVat")), tax.sellerVatSection)
        }
        return Section(tr("senderProfile.section.tax"), SP_SECTION_BAR_AMBER, taxContent)
    }

    // Legal form tab with hint shown only when combination actually affects invoice.
    fun buildFirmierungSection(): Section {
        val firmierungForm = FormLayout(
            firmierung.legalFormCombo, firmierung.craftsChamberField,
            firmierung.commercialRegisterField, firmierung.legalRegistrationSchemeIdField,
            firmierung.registerCourtField, firmierung.managingDirectorField, firmierung.courtOfJurisdictionField,
        ).also {
            it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(FORM_RESPONSIVE_WIDTH, 2))
        }
        val firmierungContent = VerticalLayout(firmierungForm, firmierungHintSpan).also {
            it.isPadding = false
            it.isSpacing = true
            it.setWidthFull()
        }
        val firmierungSection = Section(tr("senderProfile.section.firmierung"), SP_SECTION_BAR_AMBER, firmierungContent)
        // The dialog is built once in the constructor (like every other Section here),
        // but the hint's visibility listeners only fire on a later *change*; without this initial
        // call the hint would default to Vaadin's Span visibility (true) until the first edit.
        updateFirmierungHintVisibility()
        return firmierungSection
    }

    // Three e-invoice tabs merged into one section with conditional groups.
    fun buildEinvoiceSection(): Section {
        val einvoiceContent = VerticalLayout().also { content ->
            content.isPadding = false
            content.isSpacing = true
            content.setWidthFull()
            content.add(einvoiceGroupHeading(tr("senderProfile.section.einvoiceIdentity")), buildEinvoiceIdentityContent())
            einvoice.scopedB2bSection.takeIf { it.hasContent }?.let { content.add(it) }
            // Hint explains why country-scoped B2B fields may be absent above.
            content.add(Span(tr("senderProfile.info.newBehaviorHint")).apply { addClassNames(FS_08, TEXT_MUTED) })
        }
        return Section(tr("senderProfile.section.einvoice"), SP_SECTION_BAR_AMBER, einvoiceContent)
    }

    fun buildBankSection(): Section {
        val bankForm = FormLayout(
            bankAndNotes.ibanField, bankAndNotes.bicField, bankAndNotes.bankNameField, bankAndNotes.defaultPaymentTermField,
        ).also {
            it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(FORM_RESPONSIVE_WIDTH, 2))
            it.setColspan(bankAndNotes.ibanField, 2)
        }
        return Section(tr("senderProfile.section.bank"), "sp-section-bar--green", bankForm)
    }

    // Invoice hint is separate tab, not hidden in bank section. — one field per
    // language present in invoiceNoteFieldsByLanguage (fallback always, others only with the
    // flag on), in the map's own fallback-first insertion order.
    fun buildNotesSection(): Section {
        val notesForm = FormLayout(*bankAndNotes.invoiceNoteFieldsByLanguage.values.toTypedArray()).also {
            it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1))
        }
        return Section(tr("senderProfile.section.notes"), "sp-section-bar--violet", notesForm)
    }

    /** The private-customer tab; hidden until [updateConsumerTabVisibility] shows it. */
    var consumerTab: Tab? = null
        private set

    /** The tab belongs to the crafts wording profile only; the dialog re-evaluates it per open. */
    fun updateConsumerTabVisibility(visible: Boolean) {
        consumerTab?.isVisible = visible
    }

    fun buildTabs(): TabSheet {
        val identitySection = buildIdentitySection()
        val addressSection = buildAddressSection()
        val taxSection = buildTaxSection()
        val firmierungSection = buildFirmierungSection()
        val bankSection = buildBankSection()
        val einvoiceSection = buildEinvoiceSection()
        val notesSection = buildNotesSection()

        return TabSheet().also {
            it.setWidthFull()
            it.add(tr("senderProfile.tab.identity"), identitySection)
            it.add(tr("senderProfile.tab.address"), addressSection)
            it.add(tr("senderProfile.tab.tax"), taxSection)
            it.add(tr("senderProfile.tab.firmierung"), firmierungSection)
            it.add(tr("senderProfile.tab.bank"), bankSection)
            it.add(tr("senderProfile.tab.einvoiceIdentity"), einvoiceSection)
            it.add(tr("senderProfile.tab.notes"), notesSection)
            consumerTab = it.add(tr("senderProfile.tab.consumer"), bankAndNotes.consumerNote.buildSection()).also { tab -> tab.isVisible = false }
        }
    }

    /** The caller owns the action footer (saveButton/deleteButton/cancelButton live in its AbstractCrudView base). */
    fun finalizeDialog(editorDialog: AppDialog, heroDiv: Div, tabs: TabSheet) {
        editorDialog.width = AppDialog.Size.L.width
        editorDialog.height = "auto"
        editorDialog.isCloseOnEsc = true
        editorDialog.isCloseOnOutsideClick = false
        editorDialog.add(VerticalLayout(heroDiv, tabs).also { outer ->
            outer.isPadding = false
            outer.isSpacing = false
            outer.setWidthFull()
        })
    }

    /** E-invoice identifiers: seller ID (BT-29) and electronic delivery address (BT-34) as value+scheme pairs
     *  (inseparable). Commercial register, VAT ID, tax number live in the tax/legal section. */
    fun buildEinvoiceIdentityContent(): VerticalLayout {
        fun btPairGroup(heading: String, valueField: TextField, schemeField: TextField): Div = Div().apply {
            addClassName("sp-bt-pair-group")
            add(Span(heading).apply { addClassNames(FS_08, "font-weight-semibold", TEXT_MUTED) })
            add(FormLayout(valueField, schemeField).also {
                it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("360px", 2))
            })
        }

        return VerticalLayout(
            btPairGroup(tr("senderProfile.section.einvoiceIdentity.bt29"), einvoice.sellerIdentifierValueField, einvoice.sellerIdentifierSchemeField),
        ).also {
            it.isPadding = false
            it.isSpacing = true
            it.setWidthFull()
            // Extension pairs (electronic address BT-34) dock below BT-29. With no extension
            // registered the section is empty and adds nothing.
            if (einvoice.partySection.hasContent) it.add(einvoice.partySection)
        }
    }

    /** The eyebrow-style group heading used inside the consolidated E-Rechnung tab (same
     *  classes as the existing `sp-bt-pair-group` headings, e.g. [buildEinvoiceIdentityContent]'s
     *  BT-29 group — no new CSS). */
    private fun einvoiceGroupHeading(text: String): Span =
        Span(text).apply { addClassNames(FS_08, "font-weight-semibold", TEXT_MUTED) }
}
