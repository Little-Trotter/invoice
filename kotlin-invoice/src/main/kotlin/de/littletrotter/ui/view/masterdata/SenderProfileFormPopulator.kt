package de.littletrotter.ui.view.masterdata

import com.vaadin.flow.data.binder.Binder
import com.vaadin.flow.data.binder.ValidationException
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.component.orPlaceholder

/** LongParameterList — the four per-tab field groups [SenderProfileFormPopulator] reads/writes,
 *  bundled into one constructor parameter. */
class SenderProfileLoadFields(
    val identity: IdentitySectionFields,
    val tax: TaxSectionFields,
    val einvoice: EinvoiceSectionFields,
    val bankAndNotes: BankAndNotesSectionFields,
)

/**
 * [SenderProfileView]'s dialog load/save mapping: writing a loaded [SenderProfileFormData]
 * plus the profile fields the Binder does not cover (invoice notes, extension pairs, additional VAT
 * registrations, B2B fields, PDF templates, seller number/short hash) onto the widgets
 * ([populate]), and reading the same non-Binder fields back for the save in flight
 * ([writeValidated]). Extracted so the view's own body stays about wiring, not the field-by-field
 * mapping.
 */
class SenderProfileFormPopulator(
    private val binder: Binder<SenderProfileFormData>,
    private val fields: SenderProfileLoadFields,
    private val sections: SenderProfileDialogSections,
    private val comboSupport: SenderProfileComboSupport,
) {
    private val identity get() = fields.identity
    private val tax get() = fields.tax
    private val einvoice get() = fields.einvoice
    private val bankAndNotes get() = fields.bankAndNotes

    /** The address fields are already part of [formData] (already loaded
     *  with the profile), so [binder]'s own bind covers them; this only fills what the Binder
     *  cannot reach. Returns the new `proposedSellerNumber` for the view to hold (view-scoped
     *  state, same shape as [de.littletrotter.ui.view.masterdata.RecipientView]'s own field). */
    fun populate(formData: SenderProfileFormData): String? {
        comboSupport.prepareDefaultPaymentTermCombo(bankAndNotes.defaultPaymentTermField, formData.defaultPaymentTerm)
        binder.readBean(formData)

        // — one field per rendered language; a language whose field the feature flag hid
        // simply keeps blank (there is nothing to show, and nothing this field could edit anyway).
        bankAndNotes.invoiceNoteFieldsByLanguage.forEach { (language, field) ->
            field.value = formData.invoiceNotesByLanguageCode[language.code].orEmpty()
        }

        // Load the extension pairs (electronic address) from the loaded profile, not the
        // binder; the pair is not part of the Community form data.
        if (formData.id != null) einvoice.partySection.readFrom(formData.partyPairs)
        // Likewise for the additional VAT registrations list (empty for a new profile).
        tax.sellerVatSection.readRows(formData.vatRegistrations)

        // Loaded through the section itself (the field
        // values live in b2b_field_value, not on the entity/form data); a term never
        // captured on this seller yet starts from the profile's own default instead of blank.
        einvoice.scopedB2bSection.readFrom(formData.id)
        einvoice.scopedB2bSection.applyDefaults()

        val displayName = formData.name.orPlaceholder(sections.newProfilePlaceholder())
        sections.heroNameSpan.text = displayName
        sections.heroAvatarSpan.text = displayName.take(1).uppercase().ifEmpty { "+" }

        // An existing profile keeps its number unchanged (no new proposal); a
        // not-yet-persisted one gets a fresh proposal, and its short hash stays blank until saved.
        val formId = formData.id
        return if (formId == null) {
            val proposed = comboSupport.proposeSellerNumber()
            identity.sellerNumberField.value = proposed ?: ""
            identity.shortHashField.value = ""
            proposed
        } else {
            identity.sellerNumberField.value = formData.sellerNumber
            identity.shortHashField.value = formData.shortHash
            null
        }
    }

    /** `true` once [binder] accepted [targetFormData]. On `false` focus is already on the first
     *  rejected field; [onUnlocatedFailure] runs only when the rejection names no such field. */
    fun writeValidated(targetFormData: SenderProfileFormData, onUnlocatedFailure: () -> Unit): Boolean {
        try {
            binder.writeBean(targetFormData)
        } catch (ex: ValidationException) {
            if (!UiNotifications.focusFirstInvalid(ex)) onUnlocatedFailure()
            return false
        }
        if (!tax.sellerVatSection.validatePairs()) {
            onUnlocatedFailure()
            return false
        }
        // — read back only the rendered fields (see populate()'s own comment on why a
        // flag-hidden language is never represented here at all).
        targetFormData.invoiceNotesByLanguageCode = bankAndNotes.invoiceNoteFieldsByLanguage
            .mapKeys { (language, _) -> language.code }
            .mapValues { (_, field) -> field.value }
            .toMutableMap()
        return true
    }
}
