package de.littletrotter.ui.view.masterdata

import com.vaadin.flow.component.HasValue
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.value.EmailValidator
import de.littletrotter.domain.value.GermanTaxNumberValidator
import de.littletrotter.domain.value.IbanValidator
import de.littletrotter.domain.value.PhoneValidator
import de.littletrotter.domain.value.PostalCodeValidator
import de.littletrotter.domain.value.VatIdValidator
import de.littletrotter.shared.pdf.fitFontSize
import de.littletrotter.shared.pdf.normalizeWebsiteForDisplay
import de.littletrotter.ui.component.PdfTextValidation
import kotlin.reflect.KMutableProperty1

/** ST-B3 — mirrors `SenderProfile.bic`'s `@Size(max = 11)`/`VARCHAR(11)`. */
private const val BIC_MAX_LENGTH = 11

/** ST-B3 — mirrors `SenderProfile.legalRegistrationSchemeId`'s `@Size(max = 10)`/`VARCHAR(10)`. */
private const val LEGAL_REGISTRATION_SCHEME_ID_MAX_LENGTH = 10

/**
 * The exact prefix the invoice footer prints in front of the phone number/registered seat (spec
 * C.1 — `invoice-labels_de.properties`' `footer.phone`/`footer.seat`, kept as a plain literal here
 * rather than a cross-module dependency on the xpdf label bundle: this is a UI-side approximation
 * of the printed line's width, and [de.littletrotter.shared.pdf.fitFontSize]'s own fallback to its
 * minimum size — never a hard failure — is what actually protects a document whose own document
 * language ends up using a longer label than this).
 */
private const val FOOTER_PHONE_PREFIX = "Tel. "
private const val FOOTER_SEAT_PREFIX = "Sitz: "

private const val TITLE_ATTR = "title"
private const val VALIDATION_CORRECT_MARKED_FIELDS_KEY = "validation.correctMarkedFields"

/**
 * [SenderProfileView]'s field-label configuration and Binder wiring, extracted so the
 * view itself stays under the LargeClass threshold. Reuses the same per-tab field-group
 * parameter objects [SenderProfileDialogSections] lays out (built once by the view and passed to
 * both composers) — labels/validators and layout are two different reasons to change over the
 * same widgets, so they live in two classes rather than one.
 *
 * Built once by [SenderProfileView]'s constructor; a plain class, not a CDI bean.
 */
class SenderProfileFormBinder(
    private val tr: (String) -> String,
    private val identity: IdentitySectionFields,
    private val addressContact: AddressContactSectionFields,
    private val tax: TaxSectionFields,
    private val firmierung: FirmierungSectionFields,
    private val einvoice: EinvoiceSectionFields,
    private val bankAndNotes: BankAndNotesSectionFields,
) {

    /** L20 — the profile address's PLZ violation (or null) for the currently selected country. */
    private fun postalCodeViolation(value: String?): PostalCodeValidator.Violation? =
        PostalCodeValidator.check(addressContact.addressFormSection.country.value?.alpha2, value)

    fun configureFieldLabels(firmierungHintSpan: Span) {
        identity.sellerNumberField.label = tr("senderProfile.grid.sellerNumber")
        identity.sellerNumberField.isReadOnly = true
        // Field always visible; shows placeholder in empty state instead of disappearing.
        identity.sellerNumberField.placeholder = tr("senderProfile.sellerNumber.pending")

        identity.shortHashField.label = tr("senderProfile.shortHash")
        identity.shortHashField.helperText = tr("senderProfile.shortHash.helper")
        identity.shortHashField.isReadOnly = true
        identity.shortHashField.setWidthFull()

        identity.nameField.label = tr("senderProfile.name")
        tax.taxNumberField.label = tr("senderProfile.taxNumber")
        tax.vatIdField.label = tr("senderProfile.vatId")
        bankAndNotes.ibanField.label = tr("senderProfile.iban")
        bankAndNotes.bicField.label = tr("senderProfile.bic")
        // ST-B3 — hard UI cap mirroring SenderProfile.bic's @Size/VARCHAR bound: the finding was
        // a raw Postgres "value too long" error for an over-long BIC.
        bankAndNotes.bicField.maxLength = BIC_MAX_LENGTH
        bankAndNotes.bankNameField.label = tr("senderProfile.bankName")
        bankAndNotes.defaultPaymentTermField.label = tr("senderProfile.defaultPaymentTerm")
        bankAndNotes.defaultPaymentTermField.helperText = tr("senderProfile.defaultPaymentTerm.helper")
        identity.invoicePrefixField.label = tr("senderProfile.invoicePrefix")
        identity.invoicePrefixField.helperText = tr("senderProfile.invoicePrefix.helper")
        // invoiceNoteFieldsByLanguage's own labels/helper texts are set once at field
        // construction (they never change per-record), unlike every other field's texts here.
        addressContact.phoneField.label = tr("senderProfile.phone")
        addressContact.emailField.label = tr("senderProfile.email")
        addressContact.websiteField.label = tr("senderProfile.website")
        addressContact.registeredSeatField.label = tr("senderProfile.registeredSeat")
        addressContact.registeredSeatField.helperText = tr("senderProfile.registeredSeat.helper")
        firmierung.commercialRegisterField.label = tr("senderProfile.commercialRegisterNumber")
        einvoice.sellerIdentifierValueField.label = tr("senderProfile.sellerIdentifier.value")
        einvoice.sellerIdentifierValueField.helperText = tr("senderProfile.sellerIdentifier.helper")
        einvoice.sellerIdentifierSchemeField.label = tr("senderProfile.sellerIdentifier.scheme")
        einvoice.sellerIdentifierSchemeField.placeholder = "0088"
        firmierung.legalRegistrationSchemeIdField.label = tr("senderProfile.legalRegistrationSchemeId")
        firmierung.legalRegistrationSchemeIdField.placeholder = "0002"
        // ST-B3 — hard UI cap mirroring SenderProfile.legalRegistrationSchemeId's @Size/VARCHAR
        // bound.
        firmierung.legalRegistrationSchemeIdField.maxLength = LEGAL_REGISTRATION_SCHEME_ID_MAX_LENGTH
        // Identifier explanations moved to native hover titles instead of separate tab.
        einvoice.sellerIdentifierValueField.element.setAttribute(TITLE_ATTR, tr("senderProfile.info.bt29.text"))
        firmierung.commercialRegisterField.element.setAttribute(TITLE_ATTR, tr("senderProfile.info.bt30.text"))
        tax.vatIdField.element.setAttribute(TITLE_ATTR, tr("senderProfile.info.bt31.text"))
        tax.taxNumberField.element.setAttribute(TITLE_ATTR, tr("senderProfile.info.bt32.text"))
        firmierung.managingDirectorField.label = tr("senderProfile.managingDirector")
        firmierung.courtOfJurisdictionField.label = tr("senderProfile.courtOfJurisdiction")
        firmierung.registerCourtField.label = tr("senderProfile.registerCourt")
        identity.defaultSellerField.label = tr("senderProfile.defaultSeller")
        firmierung.legalFormCombo.label = tr("senderProfile.legalForm")
        firmierung.craftsChamberField.label = tr("senderProfile.craftsChamberRegistered")
        firmierungHintSpan.text = tr("senderProfile.firmierung.hrSuppressedHint")
    }

    private val pdfText = PdfTextValidation.validator(tr)

    fun bind(binder: Binder<SenderProfileFormData>) {
        bindIdentityAndAddress(binder)
        bindFooterTexts(binder)
        bindTaxAndBank(binder)
        bindFirmierungAndEinvoice(binder)
    }

    /** A free-text field whose value is printed on the PDF: it must only hold characters the PDF fonts can render. */
    private fun Binder<SenderProfileFormData>.bindPdfText(
        field: HasValue<*, String>,
        property: KMutableProperty1<SenderProfileFormData, String>,
    ) {
        forField(field).withValidator(pdfText).bind(property, property::set)
    }

    private fun bindIdentityAndAddress(binder: Binder<SenderProfileFormData>) {
        binder.forField(identity.nameField)
            .asRequired(tr("validation.senderProfile.name.required"))
            .withValidator(pdfText)
            .bind(SenderProfileFormData::name, SenderProfileFormData::name::set)
        binder.bindPdfText(addressContact.addressFormSection.street, SenderProfileFormData::street)
        // L20 — country-gated PLZ rule: strict five-digit shape for DE, lenient length rule
        // for every other country. Two validators so each violation carries its own message.
        binder.forField(addressContact.addressFormSection.postalCode)
            .withValidator(
                { value -> postalCodeViolation(value) != PostalCodeValidator.Violation.GERMAN_FORMAT },
                tr("validation.postalCode.germanFormat"),
            )
            .withValidator(
                { value -> postalCodeViolation(value) != PostalCodeValidator.Violation.TOO_LONG },
                tr("validation.postalCode.tooLong"),
            )
            .bind(SenderProfileFormData::postalCode, SenderProfileFormData::postalCode::set)
        binder.bindPdfText(addressContact.addressFormSection.city, SenderProfileFormData::city)
        binder.forField(addressContact.addressFormSection.country)
            .bind(SenderProfileFormData::country, SenderProfileFormData::country::set)
        binder.forField(addressContact.phoneField)
            .withValidator(pdfText)
            .withValidator({ value -> PhoneValidator.isValid(value) }, tr(VALIDATION_CORRECT_MARKED_FIELDS_KEY))
            .withValidator(
                { value -> value.isNullOrBlank() || fitFontSize(FOOTER_PHONE_PREFIX + value.trim()) != null },
                tr("validation.senderProfile.phone.tooLongForFooter"),
            )
            .bind(SenderProfileFormData::phone, SenderProfileFormData::phone::set)
        binder.forField(addressContact.emailField)
            .asRequired(tr("validation.senderProfile.email.required"))
            .withValidator({ value -> EmailValidator.validate(value) == null }, tr(VALIDATION_CORRECT_MARKED_FIELDS_KEY))
            .withValidator(
                { value -> value.isNullOrBlank() || fitFontSize(value.trim()) != null },
                tr("validation.senderProfile.email.tooLongForFooter"),
            )
            .bind(SenderProfileFormData::email, SenderProfileFormData::email::set)
        binder.forField(identity.invoicePrefixField)
            .withValidator(
                { value -> InvoiceNumber.isValidPrefix(value.orEmpty()) },
                tr("validation.senderProfile.invoicePrefix.format"),
            )
            .bind(SenderProfileFormData::invoicePrefix, SenderProfileFormData::invoicePrefix::set)
        binder.forField(identity.defaultSellerField).bind(SenderProfileFormData::isDefault, SenderProfileFormData::isDefault::set)
    }

    private fun bindFooterTexts(binder: Binder<SenderProfileFormData>) {
        binder.forField(addressContact.websiteField)
            .withValidator(pdfText)
            .withValidator(
                { value -> value.isNullOrBlank() || fitFontSize(normalizeWebsiteForDisplay(value)) != null },
                tr("validation.senderProfile.website.tooLongForFooter"),
            )
            .bind(SenderProfileFormData::website, SenderProfileFormData::website::set)
        binder.forField(addressContact.registeredSeatField)
            .withValidator(pdfText)
            .withValidator(
                { value -> value.isNullOrBlank() || fitFontSize(FOOTER_SEAT_PREFIX + value.trim()) != null },
                tr("validation.senderProfile.registeredSeat.tooLongForFooter"),
            )
            .bind(SenderProfileFormData::registeredSeat, SenderProfileFormData::registeredSeat::set)
    }

    private fun bindTaxAndBank(binder: Binder<SenderProfileFormData>) {
        binder.forField(tax.taxNumberField)
            .asRequired(tr("validation.senderProfile.taxNumber.required"))
            .bind(SenderProfileFormData::taxNumber, SenderProfileFormData::taxNumber::set)
        tax.taxNumberField.addValueChangeListener { event ->
            GermanTaxNumberValidator.validate(event.value).ifPresent {
                tax.taxNumberField.helperText = "${tr("validation.senderProfile.hint")}: $it"
            }
            if (GermanTaxNumberValidator.validate(event.value).isEmpty) {
                tax.taxNumberField.helperText = ""
            }
        }

        // VatIdField already renders its own live format validation (isInvalid/errorMessage) on
        // blur; this validator is the save-time safety net for a value entered without a blur
        // (e.g. programmatic set), matching the pattern ibanField/emailField/phoneField already
        // use below.
        binder.forField(tax.vatIdField)
            .asRequired(tr("validation.senderProfile.vatId.required"))
            .withValidator({ value -> VatIdValidator.isValid(value) }, tr(VALIDATION_CORRECT_MARKED_FIELDS_KEY))
            .bind(SenderProfileFormData::vatId, SenderProfileFormData::vatId::set)
        binder.forField(bankAndNotes.ibanField)
            .asRequired(tr("validation.senderProfile.iban.required"))
            .withValidator({ value -> IbanValidator.validate(value) == null }, tr(VALIDATION_CORRECT_MARKED_FIELDS_KEY))
            .bind(SenderProfileFormData::iban, SenderProfileFormData::iban::set)
        binder.forField(bankAndNotes.bicField).bind(SenderProfileFormData::bic, SenderProfileFormData::bic::set)
        // The per-language notes are persisted outside this binder (see the populator); binding them
        // with a pass-through getter and no setter only gives them the same validation and error
        // display, without readBean/writeBean ever changing their value.
        bankAndNotes.invoiceNoteFieldsByLanguage.values.forEach { field ->
            binder.forField(field).withValidator(pdfText).bind({ field.value }, { _, _ -> })
        }
        binder.forField(bankAndNotes.bankNameField).withValidator(pdfText).bind(SenderProfileFormData::bankName, SenderProfileFormData::bankName::set)
        binder.forField(bankAndNotes.defaultPaymentTermField)
            .bind(SenderProfileFormData::defaultPaymentTerm, SenderProfileFormData::defaultPaymentTerm::set)
        // invoiceNoteFieldsByLanguage is read/written outside the main binder entirely
        // (readFormData/applyFormData), the same shape as partySection/sellerVatSection/
        // scopedB2bSection: a Map-shaped value has no single POJO property Binder.forField can
        // bind to.
    }

    private fun bindFirmierungAndEinvoice(binder: Binder<SenderProfileFormData>) {
        // P2-T07 — deliberately no Leitweg-ID field here: BT-10/routingId identifies the public
        // *buyer* (see Recipient.routingId), never the seller. SenderProfile no longer carries
        // the field at all (see the P4 removal in ).
        binder.bindPdfText(firmierung.commercialRegisterField, SenderProfileFormData::commercialRegisterNumber)
        binder.forField(einvoice.sellerIdentifierValueField)
            .bind(SenderProfileFormData::sellerIdentifierValue, SenderProfileFormData::sellerIdentifierValue::set)
        binder.forField(einvoice.sellerIdentifierSchemeField)
            .bind(SenderProfileFormData::sellerIdentifierScheme, SenderProfileFormData::sellerIdentifierScheme::set)
        binder.forField(firmierung.legalRegistrationSchemeIdField)
            .bind(SenderProfileFormData::legalRegistrationSchemeId, SenderProfileFormData::legalRegistrationSchemeId::set)
        binder.bindPdfText(firmierung.managingDirectorField, SenderProfileFormData::managingDirector)
        binder.bindPdfText(firmierung.courtOfJurisdictionField, SenderProfileFormData::courtOfJurisdiction)
        binder.bindPdfText(firmierung.registerCourtField, SenderProfileFormData::registerCourt)
        binder.forField(firmierung.legalFormCombo).bind(SenderProfileFormData::legalForm, SenderProfileFormData::legalForm::set)
        binder.forField(firmierung.craftsChamberField)
            .bind(SenderProfileFormData::craftsChamberRegistered, SenderProfileFormData::craftsChamberRegistered::set)
    }
}
