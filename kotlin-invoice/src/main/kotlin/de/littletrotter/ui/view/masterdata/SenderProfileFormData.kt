package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.dto.SaveSenderProfileCommand
import de.littletrotter.application.dto.SenderProfileDetailDto
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.shared.country.Country
import de.littletrotter.spi.party.PartyPairState
import de.littletrotter.spi.party.SellerVatRegistrationRow

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
    // plain field-mirror of the command — the save resolves it against the stored languages.
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
    // Loaded with the profile, read-only for the dialog: the seller number and short hash are
    // display-only, the extension state is read back into its own sections.
    val sellerNumber: String = "",
    val shortHash: String = "",
    val partyPairs: Map<String, PartyPairState> = emptyMap(),
    val vatRegistrations: List<SellerVatRegistrationRow> = emptyList(),
    val version: Long? = 0,
) {
    /**
     * The save command for this form. [inputs] carries what the widgets hold outside the Binder
     * (the address as typed, extension pairs, VAT registrations, notes per rendered language and
     * the proposed seller number).
     */
    fun toCommand(inputs: SenderProfileSaveInputs): SaveSenderProfileCommand = SaveSenderProfileCommand(
        id = id,
        name = name,
        street = inputs.address.street.orEmpty(),
        postalCode = inputs.address.postalCode.orEmpty(),
        city = inputs.address.city.orEmpty(),
        countryAlpha2 = inputs.address.countryAlpha2 ?: Country.DE.alpha2,
        taxNumber = taxNumber,
        vatId = vatId,
        iban = iban,
        bic = bic,
        bankName = bankName,
        defaultPaymentTermId = defaultPaymentTerm?.id,
        invoicePrefix = invoicePrefix,
        invoiceNotesByLanguage = inputs.invoiceNotesByLanguage,
        phone = phone,
        email = email,
        website = website,
        registeredSeat = registeredSeat,
        managingDirector = managingDirector,
        courtOfJurisdiction = courtOfJurisdiction,
        registerCourt = registerCourt,
        commercialRegisterNumber = commercialRegisterNumber,
        sellerIdentifierValue = sellerIdentifierValue,
        sellerIdentifierSchemeId = sellerIdentifierScheme,
        legalRegistrationSchemeId = legalRegistrationSchemeId,
        isDefault = isDefault,
        legalForm = legalForm,
        craftsChamberRegistered = craftsChamberRegistered,
        partyPairs = inputs.partyPairs,
        vatRegistrations = inputs.vatRegistrations,
        proposedSellerNumber = inputs.proposedSellerNumber,
    )

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
        fun from(profile: SenderProfileDetailDto?): SenderProfileFormData {
            if (profile == null) return SenderProfileFormData()
            return SenderProfileFormData(
                id = profile.id,
                version = profile.version,
                name = profile.name,
                street = profile.billingAddress?.street.orEmpty(),
                postalCode = profile.billingAddress?.postalCode.orEmpty(),
                city = profile.billingAddress?.city.orEmpty(),
                country = Country.fromAlpha2(profile.billingAddress?.countryAlpha2) ?: Country.DE,
                taxNumber = profile.taxNumber.orEmpty(),
                vatId = profile.vatId.orEmpty(),
                iban = profile.iban.orEmpty(),
                bic = profile.bic.orEmpty(),
                bankName = profile.bankName.orEmpty(),
                defaultPaymentTerm = profile.defaultPaymentTerm,
                invoicePrefix = profile.invoicePrefix.ifBlank { "RE-" },
                invoiceNotesByLanguageCode = profile.invoiceNotesByLanguageCode.toMutableMap(),
                phone = profile.phone.orEmpty(),
                email = profile.email.orEmpty(),
                website = profile.website.orEmpty(),
                registeredSeat = profile.registeredSeat.orEmpty(),
                managingDirector = profile.managingDirector.orEmpty(),
                courtOfJurisdiction = profile.courtOfJurisdiction.orEmpty(),
                registerCourt = profile.registerCourt.orEmpty(),
                commercialRegisterNumber = profile.commercialRegisterNumber.orEmpty(),
                sellerIdentifierValue = profile.sellerIdentifierValue.orEmpty(),
                sellerIdentifierScheme = profile.sellerIdentifierSchemeId.orEmpty(),
                legalRegistrationSchemeId = profile.legalRegistrationSchemeId.orEmpty(),
                isDefault = profile.isDefault,
                legalForm = profile.legalForm,
                craftsChamberRegistered = profile.craftsChamberRegistered,
                sellerNumber = profile.sellerNumber.orEmpty(),
                shortHash = profile.shortHash.orEmpty(),
                partyPairs = profile.partyPairs,
                vatRegistrations = profile.vatRegistrations,
            )
        }
    }
}

/** What [SenderProfileFormData.toCommand] takes from the widgets outside the Binder. */
class SenderProfileSaveInputs(
    val address: AddressDto,
    val invoiceNotesByLanguage: Map<String, String?>,
    val partyPairs: Map<String, PartyPairState>,
    val vatRegistrations: List<SellerVatRegistrationRow>,
    val proposedSellerNumber: String?,
)
