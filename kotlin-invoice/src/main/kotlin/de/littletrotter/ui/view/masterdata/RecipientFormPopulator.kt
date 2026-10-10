package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientDraft
import de.littletrotter.application.masterdata.RecipientAddressInput
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.domain.masterdata.defaultInvoiceFormatFor
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.ui.notification.UiNotifications

/**
 * [RecipientView]'s dialog load/save mapping: writing a loaded [RecipientDetailDto] onto
 * the widgets ([populate]), resetting them for a new recipient ([clear]), and reading them back
 * into a [RecipientDraft] ([buildDraft]) for the pending save. Extracted so the view's own body
 * stays about wiring, not the field-by-field mapping.
 *
 * [RecipientView] still owns every widget instance (so the existing field-name reflection in the
 * Karibu tests keeps working); this class only owns the behaviour around them.
 */
internal class RecipientFormPopulator(
    private val identity: RecipientIdentityFields,
    private val address: RecipientAddressFields,
    private val tax: RecipientTaxFields,
    private val einvoiceIdentity: RecipientEinvoiceIdentityFields,
    private val b2b: RecipientB2bFields,
    private val vatIdVerification: RecipientVatIdVerificationComposer,
    private val b2bProfileComposer: RecipientB2bProfileComposer,
) {

    /** True once the user picked an invoice format themselves in the open dialog, or once a
     *  stored customer was loaded — both mean the country preselection must not overwrite the
     *  field any more. */
    private var invoiceFormatTouched = false

    /** Same rule for the document language: country preselection until the user chose or a stored
     *  recipient was loaded. */
    private var documentLanguageTouched = false

    fun applyDocumentLanguagePreselection() {
        if (documentLanguageTouched) return
        tax.documentLanguageField.value = DocumentLanguage.fromCountry(address.addressFormSection.country.value?.alpha2)
    }

    fun touchDocumentLanguageIfFromClient(fromClient: Boolean) {
        if (fromClient) documentLanguageTouched = true
    }

    /** PO 04.09.2026 — seeds the "deutsche/internationale Rechnung" field from the billing country
     *  while the user has not decided for themselves, and stops the moment they have. */
    fun applyInvoiceFormatPreselection() {
        if (invoiceFormatTouched) return
        tax.invoiceFormatField.value = defaultInvoiceFormatFor(address.addressFormSection.country.value)
    }

    fun touchInvoiceFormatIfFromClient(fromClient: Boolean) {
        if (fromClient) invoiceFormatTouched = true
    }

    /**
     * Reads display fields off [detail] ([RecipientDetailDto]), the dialog's
     * own read model; [b2bProfile] is resolved by the caller (needs [de.littletrotter.application
     * .masterdata.port.inbound.RecipientB2bFieldsQueryPort], which this class has no reason to depend on otherwise).
     */
    fun populate(detail: RecipientDetailDto, b2bProfile: B2bProfileDto?) {
        identity.customerNumberField.value = detail.customerNumber.orEmpty()
        identity.shortHashField.value = UiNotifications.orEmpty(detail.shortHash)
        identity.nameField.value = detail.name
        address.addressFormSection.populate(detail.billingAddress)
        tax.vatIdField.value = UiNotifications.orEmpty(detail.vatId)
        tax.vatIdField.onLinkedCountryChanged()
        tax.ibanField.value = UiNotifications.orEmpty(detail.iban)
        einvoiceIdentity.routingIdField.value = UiNotifications.orEmpty(detail.routingId)
        address.emailField.value = UiNotifications.orEmpty(detail.email)
        address.phoneField.value = UiNotifications.orEmpty(detail.phone)
        identity.contactPersonField.value = UiNotifications.orEmpty(detail.contactPerson)
        b2bProfileComposer.prepare(b2bProfile)
        b2b.b2bProfileCombo.value = b2bProfile
        // explicit, not just the ComboBox's own value-change listener: Vaadin does not
        // fire that listener when the assigned value is unchanged from the dialog's previous state
        // (e.g. reopening the same recipient), and the "B2B-Felder" section must still reload this
        // recipient's own stored values every time the dialog opens.
        b2bProfileComposer.refresh()
        populateIdentityFields(detail)
        if (detail.deliveryAddress != null) {
            address.deliveryAddressFormSection.populate(detail.deliveryAddress)
        } else {
            address.deliveryAddressFormSection.clear()
        }
    }

    private fun populateIdentityFields(detail: RecipientDetailDto) {
        tax.customerTypeField.value = detail.customerType ?: CustomerType.BUSINESS
        // A stored customer keeps exactly what was saved — never re-derived from the country.
        tax.invoiceFormatField.value = detail.invoiceFormat
        invoiceFormatTouched = true
        tax.documentLanguageField.value = DocumentLanguage.fromCode(detail.documentLanguage)
        documentLanguageTouched = true
        // the persisted value, not the field's own class default.
        tax.reverseChargePreferenceField.value = detail.reverseChargePreference
        einvoiceIdentity.buyerIdentifierValueField.value = UiNotifications.orEmpty(detail.buyerIdentifierValue)
        einvoiceIdentity.buyerIdentifierSchemeField.value = UiNotifications.orEmpty(detail.buyerIdentifierSchemeId)
        einvoiceIdentity.legalRegistrationValueField.value = UiNotifications.orEmpty(detail.legalRegistrationValue)
        einvoiceIdentity.legalRegistrationSchemeField.value = UiNotifications.orEmpty(detail.legalRegistrationSchemeId)
        einvoiceIdentity.partySection.readFrom(detail.partyPairs)
        vatIdVerification.populate(detail)
    }

    /** Resets every dialog widget for a not-yet-persisted recipient. */
    fun clear() {
        listOf(
            identity.customerNumberField, identity.shortHashField, identity.nameField,
            tax.vatIdField, tax.ibanField, einvoiceIdentity.routingIdField,
            address.emailField, address.phoneField, identity.contactPersonField,
            einvoiceIdentity.buyerIdentifierValueField, einvoiceIdentity.buyerIdentifierSchemeField,
            einvoiceIdentity.legalRegistrationValueField, einvoiceIdentity.legalRegistrationSchemeField,
            tax.vatIdValidationNoteField,
        ).forEach { it.clear() }
        vatIdVerification.clear()
        address.addressFormSection.clear()
        tax.vatIdField.onLinkedCountryChanged()
        tax.customerTypeField.value = CustomerType.BUSINESS
        invoiceFormatTouched = false
        applyInvoiceFormatPreselection()
        documentLanguageTouched = false
        applyDocumentLanguagePreselection()
        // matches the entity default; regression guard for stale UI state carrying over
        // between dialog openings (a prior NEVER/ALWAYS recipient must not leak into "new recipient").
        tax.reverseChargePreferenceField.value = ReverseChargePreference.ASK
        b2bProfileComposer.prepare(null)
        b2b.b2bProfileCombo.clear()
        b2bProfileComposer.refresh() // see the matching comment in populate().
        address.deliveryAddressFormSection.clear()
    }

    /**
     * [RecipientDraft] read back off every widget for the save currently in flight.
     * [proposedCustomerNumber] is [RecipientView]'s own view-scoped state (collision
     * detection), passed in rather than duplicated here.
     */
    fun buildDraft(formData: RecipientDraft, proposedCustomerNumber: String?): RecipientDraft {
        val billingStreet = address.addressFormSection.street.value.trim()
        val billingPostalCode = address.addressFormSection.postalCode.value.trim()
        val billingCity = address.addressFormSection.city.value.trim()
        val billingCountry = address.addressFormSection.country.value?.alpha2 ?: "DE"
        val hasDelivery = address.deliveryAddressEntered()
        return RecipientDraft(
            id = formData.id,
            version = formData.version,
            customerNumber = identity.customerNumberField.value,
            name = identity.nameField.value,
            vatId = tax.vatIdField.value,
            // Always saved regardless of the checkbox's state (task spec) — never gated by the
            // VAT-id reconciliation, which only ever touches status/verifiedAt.
            vatIdValidationNote = tax.vatIdValidationNoteField.value.trim().ifBlank { null },
            routingId = einvoiceIdentity.routingIdField.value.ifBlank { null },
            email = address.emailField.value.ifBlank { null },
            phone = address.phoneField.value.ifBlank { null },
            contactPerson = identity.contactPersonField.value.ifBlank { null },
            iban = tax.ibanField.value.ifBlank { null },
            customerType = tax.customerTypeField.value,
            // PO 04.09.2026 — never null: the ComboBox is seeded with the country preselection and
            // offers no empty item, but a cleared value must still land on the German default
            // rather than on a NOT NULL violation.
            invoiceFormat = tax.invoiceFormatField.value ?: InvoiceFormat.GERMAN,
            documentLanguage = (tax.documentLanguageField.value ?: DocumentLanguage.DE).code,
            // always has a value, but a defensive fallback to the entity/migration default.
            reverseChargePreference = tax.reverseChargePreferenceField.value ?: ReverseChargePreference.ASK,
            buyerIdentifierValue = einvoiceIdentity.buyerIdentifierValueField.value,
            buyerIdentifierSchemeId = einvoiceIdentity.buyerIdentifierSchemeField.value,
            legalRegistrationValue = einvoiceIdentity.legalRegistrationValueField.value,
            legalRegistrationSchemeId = einvoiceIdentity.legalRegistrationSchemeField.value,
            // The standing B2B profile default; `null` = no profile (Community behavior).
            b2bProfileId = b2b.b2bProfileCombo.value?.id,
            // The extension pairs (electronic address), for ManageRecipient to write onto the
            // persisted entity via the same SPI the view used to call directly.
            partyPairs = einvoiceIdentity.partySection.writeToPairs(),
            addresses = RecipientAddressInput(
                billingStreet = billingStreet,
                billingPostalCode = billingPostalCode,
                billingCity = billingCity,
                billingCountry = billingCountry,
                deliveryStreet = if (hasDelivery) address.deliveryAddressFormSection.street.value else null,
                deliveryPostalCode = if (hasDelivery) address.deliveryAddressFormSection.postalCode.value else null,
                deliveryCity = if (hasDelivery) address.deliveryAddressFormSection.city.value else null,
                deliveryCountry = if (hasDelivery) address.deliveryAddressFormSection.country.value?.alpha2 ?: billingCountry else null,
            ),
            proposedCustomerNumber = proposedCustomerNumber,
            vatIdValidatedByUser = vatIdVerification.validatedByUser(),
            pendingVatIdVerification = vatIdVerification.pendingVerification(),
        )
    }
}
