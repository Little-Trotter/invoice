package de.littletrotter.domain.masterdata

import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.shared.tax.CustomerType

/**
 * The fields of a [Recipient] that the customer form edits as one unit; see [Recipient.update].
 * Defaults equal the values of a freshly created recipient.
 */
data class RecipientDetails(
    val name: String,
    val customerNumber: CustomerNumber? = null,
    val vatId: VatId? = null,
    val vatIdVerificationNote: String? = null,
    val iban: Iban? = null,
    val routingId: String? = null,
    val email: Email? = null,
    val phone: String? = null,
    val contactPerson: String? = null,
    val customerType: CustomerType? = null,
    val invoiceFormat: InvoiceFormat = InvoiceFormat.GERMAN,
    val documentLanguage: String = DocumentLanguage.DE.code,
    val reverseChargePreference: ReverseChargePreference = ReverseChargePreference.ASK,
    val buyerIdentifier: PartyIdentifier? = null,
    val legalRegistrationIdentifier: LegalRegistrationIdentifier? = null,
    val b2bProfile: B2bProfile? = null,
)
