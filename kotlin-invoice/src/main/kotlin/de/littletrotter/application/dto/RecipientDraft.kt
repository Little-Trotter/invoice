package de.littletrotter.application.dto

import de.littletrotter.application.command.Command
import de.littletrotter.application.masterdata.RecipientAddressInput
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.spi.party.PartyPairState
import java.time.OffsetDateTime

/**
 * A VAT-id check the dialog itself performed this session (VIES, or a failed VIES answer),
 * carried into [RecipientDraft] so [de.littletrotter.application.masterdata.ManageRecipient] can
 * reconcile it onto the saved recipient even though the check itself ran in the UI tier. Matched
 * against the vat id actually being saved by [vatId], so a later edit of the field cannot carry a
 * stale verification onto a different id.
 */
data class PendingVatIdVerification(
    val vatId: String,
    val status: VatIdVerificationStatus,
    val verifiedAt: OffsetDateTime,
)

/**
 * The recipient maintenance dialog's write command: every field the form collects, built by
 * [de.littletrotter.ui.view.masterdata.RecipientView] from its own widgets and handed to
 * [de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
 * .saveRecipientWithAddresses], which owns turning it into the persisted `Recipient` — the view
 * itself never constructs or mutates that entity.
 *
 * Carries the VAT-id verification status/timestamp only indirectly, via [vatIdValidatedByUser]/
 * [pendingVatIdVerification]: those two columns are never set directly by the form, only computed
 * by reconciling the manual checkbox and any in-session VIES result against what is already
 * persisted — the same reconciliation the dialog used to run on the entity it held locally, now
 * run by [de.littletrotter.application.masterdata.ManageRecipient] against the row it loads.
 */
data class RecipientDraft(
    val id: Long?,
    val version: Long?,
    val customerNumber: String?,
    val name: String,
    val vatId: String?,
    val vatIdValidationNote: String?,
    val routingId: String?,
    val email: String?,
    val phone: String?,
    val contactPerson: String?,
    val iban: String?,
    val customerType: CustomerType?,
    val invoiceFormat: InvoiceFormat,
    val documentLanguage: String,
    val reverseChargePreference: ReverseChargePreference,
    val buyerIdentifierValue: String?,
    val buyerIdentifierSchemeId: String?,
    val legalRegistrationValue: String?,
    val legalRegistrationSchemeId: String?,
    val b2bProfileId: Long?,
    val partyPairs: Map<String, PartyPairState>,
    val addresses: RecipientAddressInput,
    val proposedCustomerNumber: String?,
    /** The manual attestation checkbox's state at save time. */
    val vatIdValidatedByUser: Boolean,
    /** A fresh VIES outcome recorded during this dialog session, if any. */
    val pendingVatIdVerification: PendingVatIdVerification?,
) : Command {
    companion object {
        /** The draft of an already-persisted recipient, every field as [detail] loaded it,
         *  unedited — the maintenance form's starting point for an edit. The manual VAT-id
         *  checkbox starts checked exactly when the stored verification is `VERIFIED`, so saving
         *  the draft unchanged never touches the stored verification. */
        fun from(detail: RecipientDetailDto): RecipientDraft = RecipientDraft(
            id = detail.id,
            version = detail.version,
            customerNumber = detail.customerNumber,
            name = detail.name,
            vatId = detail.vatId,
            vatIdValidationNote = detail.vatIdVerificationNote,
            routingId = detail.routingId,
            email = detail.email,
            phone = detail.phone,
            contactPerson = detail.contactPerson,
            iban = detail.iban,
            customerType = detail.customerType,
            invoiceFormat = detail.invoiceFormat,
            documentLanguage = detail.documentLanguage,
            reverseChargePreference = detail.reverseChargePreference,
            buyerIdentifierValue = detail.buyerIdentifierValue,
            buyerIdentifierSchemeId = detail.buyerIdentifierSchemeId,
            legalRegistrationValue = detail.legalRegistrationValue,
            legalRegistrationSchemeId = detail.legalRegistrationSchemeId,
            b2bProfileId = detail.b2bProfileId,
            partyPairs = detail.partyPairs,
            addresses = RecipientAddressInput(
                billingStreet = detail.billingAddress?.street.orEmpty(),
                billingPostalCode = detail.billingAddress?.postalCode.orEmpty(),
                billingCity = detail.billingAddress?.city.orEmpty(),
                billingCountry = detail.billingAddress?.countryAlpha2 ?: "DE",
            ),
            proposedCustomerNumber = null,
            vatIdValidatedByUser = detail.vatIdVerificationStatus == VatIdVerificationStatus.VERIFIED,
            pendingVatIdVerification = null,
        )

        /** Blank starting point for a not-yet-persisted recipient — the `Recipient` entity only
         *  comes into being once [de.littletrotter.application.masterdata.ManageRecipient] saves
         *  the draft. */
        fun blank(id: Long? = null, version: Long? = null): RecipientDraft = RecipientDraft(
            id = id,
            version = version,
            customerNumber = null,
            name = "",
            vatId = null,
            vatIdValidationNote = null,
            routingId = null,
            email = null,
            phone = null,
            contactPerson = null,
            iban = null,
            customerType = null,
            invoiceFormat = InvoiceFormat.GERMAN,
            documentLanguage = DocumentLanguage.DE.code,
            reverseChargePreference = ReverseChargePreference.ASK,
            buyerIdentifierValue = null,
            buyerIdentifierSchemeId = null,
            legalRegistrationValue = null,
            legalRegistrationSchemeId = null,
            b2bProfileId = null,
            partyPairs = emptyMap(),
            addresses = RecipientAddressInput(billingStreet = "", billingPostalCode = "", billingCity = ""),
            proposedCustomerNumber = null,
            vatIdValidatedByUser = false,
            pendingVatIdVerification = null,
        )
    }
}
