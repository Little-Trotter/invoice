package de.littletrotter.ui.view.masterdata

import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.value.EmailValidator
import de.littletrotter.domain.value.IbanValidator
import de.littletrotter.domain.value.PhoneValidator
import de.littletrotter.domain.value.PostalCodeValidator
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.component.PdfTextValidation

/**
 * [RecipientView]'s save-time field validation (billing/delivery address, VAT id, IBAN,
 * email, phone), extracted so the view's own body stays about wiring, not the individual field
 * rules. Every sub-check always runs and refreshes its own field's error state — never
 * short-circuited — so [validate] combines the results with the non-short-circuiting `and`, not
 * `&&`, preserving that every field's UI error state is refreshed on every call.
 */
class RecipientFormValidator(
    private val identity: RecipientIdentityFields,
    private val address: RecipientAddressFields,
    private val tax: RecipientTaxFields,
    private val tr: (String) -> String,
) {

    fun validate(): Boolean {
        val nameValid = requireField(identity.nameField, "validation.recipient.name.required")
        val customerNumberValid = requireField(identity.customerNumberField, "validation.recipient.customerNumber.required")
        val billingAddressValid = validateBillingAddressComplete()
        val addressPostalValid = gatePostalCode(address.addressFormSection, clearWhenValid = false)
        val deliveryPostalValid = gateDeliveryPostalCode()
        val phoneValid = validateOptionalField(address.phoneField, PhoneValidator::validate)
        val vatIdValid = validateVatIdField()
        val ibanValid = validateIbanField()
        val emailValid = validateEmailField()
        val pdfTextValid = PdfTextValidation.checkAll(
            tr,
            identity.nameField, identity.contactPersonField, address.phoneField,
            address.addressFormSection.street, address.addressFormSection.city,
            address.deliveryAddressFormSection.street, address.deliveryAddressFormSection.city,
        )

        return pdfTextValid and nameValid and customerNumberValid and billingAddressValid and addressPostalValid and
            deliveryPostalValid and phoneValid and vatIdValid and ibanValid and emailValid
    }

    /** The first field [validate] left invalid, in tab order (identity -> address -> tax ->
     *  delivery address) — `null` once nothing is wrong. */
    fun firstInvalidField(): TextField? = listOf(
        identity.nameField, identity.customerNumberField,
        address.addressFormSection.street, address.addressFormSection.postalCode, address.addressFormSection.city,
        address.phoneField, address.emailField,
        tax.vatIdField, tax.ibanField,
        address.deliveryAddressFormSection.postalCode,
    ).firstOrNull { it.isInvalid }

    /** L20 — the German five-digit rule applies only when the address's own country is DE; a
     *  foreign address gets the lenient length rule instead. `clearWhenValid` stays false for the
     *  billing PLZ so a billing-incomplete mark set by [validateBillingAddressComplete] survives a
     *  format-valid blank value. */
    private fun gatePostalCode(section: AddressFormSection, clearWhenValid: Boolean): Boolean {
        val violation = PostalCodeValidator.check(section.country.value?.alpha2, section.postalCode.value)
        if (violation == null) {
            if (clearWhenValid) section.postalCode.isInvalid = false
            return true
        }
        section.postalCode.isInvalid = true
        section.postalCode.errorMessage = when (violation) {
            PostalCodeValidator.Violation.GERMAN_FORMAT -> tr("validation.postalCode.germanFormat")
            PostalCodeValidator.Violation.TOO_LONG -> tr("validation.postalCode.tooLong")
        }
        return false
    }

    /** The billing address is mandatory as a whole: a missing street/PLZ/city marks all three
     *  fields with the shared billing-incomplete message. */
    private fun validateBillingAddressComplete(): Boolean {
        val section = address.addressFormSection
        val complete = section.street.value.trim().isNotBlank() &&
            section.postalCode.value.trim().isNotBlank() &&
            section.city.value.trim().isNotBlank()
        if (!complete) {
            val msg = tr("validation.recipient.billingAddress.required")
            listOf(section.street, section.postalCode, section.city).forEach {
                it.isInvalid = true
                it.errorMessage = msg
            }
            return false
        }
        section.street.isInvalid = false
        section.postalCode.isInvalid = false
        section.city.isInvalid = false
        return true
    }

    /** L20 — the delivery PLZ is gated only while a delivery address is being entered; once the
     *  delivery fields are emptied again, a stale error mark must not survive. */
    private fun gateDeliveryPostalCode(): Boolean {
        if (!address.deliveryAddressEntered()) {
            address.deliveryAddressFormSection.postalCode.isInvalid = false
            return true
        }
        return gatePostalCode(address.deliveryAddressFormSection, clearWhenValid = true)
    }

    private fun requireField(field: TextField, msgKey: String): Boolean {
        val valid = field.value.isNotBlank()
        field.isInvalid = !valid
        if (!valid) field.errorMessage = tr(msgKey)
        return valid
    }

    private fun validateOptionalField(field: TextField, check: (String?) -> String?): Boolean {
        val msg = check(field.value.ifBlank { null })
        field.isInvalid = msg != null
        if (msg != null) field.errorMessage = msg
        return msg == null
    }

    // CONSUMER: VAT-ID not required. Buyer's IBAN never required (seller's IBAN is used).
    private fun validateVatIdField(): Boolean {
        val isConsumer = tax.customerTypeField.value == CustomerType.CONSUMER
        val requiredValid = if (!isConsumer) {
            requireField(tax.vatIdField, "validation.recipient.vatId.required")
        } else {
            tax.vatIdField.isInvalid = false
            true
        }
        val formatValid = tax.vatIdField.value.isBlank() || tax.vatIdField.validateNow()
        return requiredValid and formatValid
    }

    private fun validateIbanField(): Boolean {
        tax.ibanField.isInvalid = false
        if (tax.ibanField.value.isBlank()) return true
        val msg = IbanValidator.validate(tax.ibanField.value)
        tax.ibanField.isInvalid = msg != null
        if (msg != null) {
            tax.ibanField.errorMessage = msg
            return false
        }
        return true
    }

    private fun validateEmailField(): Boolean {
        val requiredValid = requireField(address.emailField, "validation.recipient.email.required")
        if (address.emailField.value.isBlank()) return requiredValid
        val msg = EmailValidator.validate(address.emailField.value)
        if (msg != null) {
            address.emailField.isInvalid = true
            address.emailField.errorMessage = msg
            return false
        }
        return requiredValid
    }
}
