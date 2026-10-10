package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.reference.toDto
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * [SenderProfileView]'s form-bean, extracted to a top-level class (was nested) so the
 * view's own LargeClass finding disappears rather than moving to yet another god-class shape.
 *
 * A plain class, not a `data class`: the Binder wiring in [SenderProfileView.bindFormFields]
 * uses `Property::set` setter references, which need `var` fields; an immutable `val` form would
 * need per-field `copy()` plumbing that Binder's per-field `bind(getter, setter)` API does not
 * support. Nothing in this codebase compares two instances, prints one, or copies one —
 * `equals`/`hashCode`/`copy`/`toString` were never used, only the auto-generated presence of
 * `equals`/`hashCode` on 28 mutable `var` fields (DataClassShouldBeImmutable).
 */
class SenderProfileFormData(
    var id: Long? = null,
    var name: String = "",
    var street: String = "",
    var postalCode: String = "",
    var city: String = "",
    var country: Country = Country.DE,
    var taxNumber: String = "",
    var vatId: String = "",
    var iban: String = "",
    var bic: String = "",
    var bankName: String = "",
    var defaultPaymentTerm: PaymentTermDto? = null,
    var invoicePrefix: String = "RE-",
    // One raw text per active language, keyed by Language.code; German is always
    // present, any other active language only when the multilingual-texts feature flag is on
    // and its field was rendered. Deliberately not part of applyTo/copyEditableFieldsFrom's
    // plain field-mirror (see SenderProfile.copyEditableFieldsFrom's own KDoc) —
    // SellerProfileService.save resolves it against Language entities and
    // SenderProfile.setInvoiceNote.
    var invoiceNotesByLanguageCode: MutableMap<String, String> = mutableMapOf(),
    var phone: String = "",
    var email: String = "",
    var website: String = "",
    var registeredSeat: String = "",
    var managingDirector: String = "",
    var courtOfJurisdiction: String = "",
    var registerCourt: String = "",
    var commercialRegisterNumber: String = "",
    var sellerIdentifierValue: String = "",
    var sellerIdentifierScheme: String = "",
    var legalRegistrationSchemeId: String = "",
    var isDefault: Boolean = false,
    // Legal form (null = not specified) + commercial register flag.
    var legalForm: LegalForm? = null,
    var craftsChamberRegistered: Boolean = false,
    val version: Long? = 0,
) {
    fun applyTo(profile: SenderProfile) {
        profile.name = name.trim()
        // Address fields are no longer directly writable on SenderProfile; persistEntity
        // writes them through sellerProfileService.saveAddress.
        profile.taxNumber = TaxNumber.ofNullable(taxNumber)
        profile.vatId = VatId.ofNullable(vatId)
        profile.iban = Iban.ofNullable(iban.trim().uppercase().ifBlank { null })
        profile.bic = Bic.ofNullable(bic)
        profile.bankName = bankName.trim().ifBlank { null }
        // defaultPaymentTerm is deliberately not written here: this form only ever holds the
        // read-model row ([PaymentTermDto]); [SellerProfileService.save] resolves its id against
        // the real entity itself.
        profile.invoicePrefix = invoicePrefix.trim().ifBlank { "RE-" }
        // InvoiceNote is deliberately not written here; see the field's own KDoc.
        profile.phone = phone.trim().ifBlank { null }
        profile.email = Email.ofNullable(email.trim().ifBlank { null })
        // Stored as entered; the renderer normalises for display (strips the
        // scheme prefix and a trailing slash), never this write path.
        profile.website = website.trim().ifBlank { null }
        profile.registeredSeat = registeredSeat.trim().ifBlank { null }
        profile.managingDirector = managingDirector.trim().ifBlank { null }
        profile.courtOfJurisdiction = courtOfJurisdiction.trim().ifBlank { null }
        profile.registerCourt = registerCourt.trim().ifBlank { null }
        profile.commercialRegisterNumber = commercialRegisterNumber.trim().ifBlank { null }
        // BT-29/30/34 — value and scheme always travel together; leaving both blank
        // means "not set", leaving exactly one filled is a validation error surfaced to the
        // user, never silently dropped.
        profile.sellerIdentifier = toPartyIdentifier(sellerIdentifierValue, sellerIdentifierScheme)
        profile.legalRegistrationSchemeId = legalRegistrationSchemeId.trim().ifBlank { null }
        if (isDefault) profile.markAsDefault() else profile.revokeDefault()
        // Firmierung.
        profile.legalForm = legalForm
        profile.craftsChamberRegistered = craftsChamberRegistered
    }

    companion object {
        /**
         * anbieter-improve A1.2 — builds the form from a **fully loaded** profile.
         *
         * The former `try/catch` here returned a reduced form when a lazy relation could not
         * be initialized — and its fallback branch read the very same lazy relation
         * (`defaultPaymentTerm`), so it threw too and the caller's own catch produced a fully
         * empty form over an existing record. The catch was not the safety net, it was the
         * reason nobody noticed the broken load. Loading correctly is the caller's job
         * ([SenderProfileView.formDataFrom]); failing here is honest and visible.
         */
        fun from(profile: SenderProfile?): SenderProfileFormData {
            if (profile == null) return SenderProfileFormData()
            return SenderProfileFormData(
                id = profile.id,
                version = profile.version,
                name = profile.name,
                street = profile.billingAddress?.street.orEmpty(),
                postalCode = profile.billingAddress?.postalCode?.value.orEmpty(),
                city = profile.billingAddress?.city.orEmpty(),
                country = profile.billingAddress?.country ?: Country.DE,
                taxNumber = profile.taxNumber?.value.orEmpty(),
                vatId = profile.vatId?.value.orEmpty(),
                iban = profile.iban?.value.orEmpty(),
                bic = profile.bic?.value.orEmpty(),
                bankName = profile.bankName.orEmpty(),
                defaultPaymentTerm = profile.defaultPaymentTerm?.toDto(),
                invoicePrefix = profile.invoicePrefix.ifBlank { "RE-" },
                invoiceNotesByLanguageCode = profile.invoiceNote?.entries
                    ?.mapNotNull { entry -> entry.language?.code?.let { it to entry.value } }
                    ?.toMap()
                    ?.toMutableMap()
                    ?: mutableMapOf(),
                phone = profile.phone.orEmpty(),
                email = profile.email?.value.orEmpty(),
                website = profile.website.orEmpty(),
                registeredSeat = profile.registeredSeat.orEmpty(),
                managingDirector = profile.managingDirector.orEmpty(),
                courtOfJurisdiction = profile.courtOfJurisdiction.orEmpty(),
                registerCourt = profile.registerCourt.orEmpty(),
                commercialRegisterNumber = profile.commercialRegisterNumber.orEmpty(),
                sellerIdentifierValue = profile.sellerIdentifier?.value.orEmpty(),
                sellerIdentifierScheme = profile.sellerIdentifier?.schemeId.orEmpty(),
                legalRegistrationSchemeId = profile.legalRegistrationSchemeId.orEmpty(),
                isDefault = profile.isDefault,
                legalForm = profile.legalForm,
                craftsChamberRegistered = profile.craftsChamberRegistered,
            )
        }
    }
}
