package de.littletrotter.application.dto

import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.spi.party.PartyPairState
import java.time.OffsetDateTime

/**
 * Read model for the "Empfänger bearbeiten" dialog ([de.littletrotter.ui.view.masterdata
 * .RecipientView]): every field the view's form population used to read off the
 * `Recipient` entity directly, plus the extension pairs (SPI) and the
 * billing/delivery addresses the view used to fetch itself
 * ([de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort.findDeliveryAddress]).
 * Value objects ([de.littletrotter.domain.value.CustomerNumber], [de.littletrotter.domain.value
 * .VatId], [de.littletrotter.domain.value.Iban], [de.littletrotter.domain.value.Email]) are
 * unwrapped to `String`; the plain domain enums ([CustomerType], [InvoiceFormat],
 * [ReverseChargePreference], [VatIdVerificationStatus]) are kept as-is — [RecipientView]'s own
 * Vaadin fields are already typed with them (same precedent as [InvoiceOverviewDto]'s
 * `InvoiceStatus`/`ValidationStatus`), so unwrapping them to an ordinal here would only add a
 * round-trip conversion neither side needs.
 *
 * Writing goes through [de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase]
 * with a [RecipientDraft] built from the form.
 */
data class RecipientDetailDto(
    val id: Long,
    val version: Long?,
    val customerNumber: String?,
    val shortHash: String?,
    val name: String,
    val vatId: String?,
    val routingId: String?,
    val email: String?,
    val phone: String?,
    val contactPerson: String?,
    val iban: String?,
    val customerType: CustomerType?,
    val invoiceFormat: InvoiceFormat,
    /** `de` or `en`, see `Recipient.documentLanguage`. */
    val documentLanguage: String,
    val reverseChargePreference: ReverseChargePreference,
    val buyerIdentifierValue: String?,
    val buyerIdentifierSchemeId: String?,
    val legalRegistrationValue: String?,
    val legalRegistrationSchemeId: String?,
    val b2bProfileId: Long?,
    /** The buyer's extension pairs (e.g. the electronic address, BT-49), keyed by
     *  [de.littletrotter.spi.party.PartyIdentifierSpec.id]. */
    val partyPairs: Map<String, PartyPairState>,
    val billingAddress: AddressDto?,
    val deliveryAddress: AddressDto?,
    val vatIdVerificationStatus: VatIdVerificationStatus?,
    val vatIdVerifiedAt: OffsetDateTime?,
    val vatIdVerificationNote: String?,
)
