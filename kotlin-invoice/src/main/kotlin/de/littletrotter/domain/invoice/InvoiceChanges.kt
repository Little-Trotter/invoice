package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Currency
import de.littletrotter.shared.country.Country
import java.time.LocalDate

/**
 * The header values of an invoice that move together: the dates, the service period, the payment
 * terms in days, the currency, the document format and the seller VAT registration. The parameter
 * object of [Invoice.updateHeader]; a caller that changes one value starts from
 * [Invoice.headerChange] and copies the rest unchanged.
 *
 * [invoiceDate] has no default because every invoice carries one from the moment it exists.
 */
data class InvoiceHeaderChange(
    val invoiceDate: LocalDate,
    val servicePeriodFrom: LocalDate? = null,
    val servicePeriodTo: LocalDate? = null,
    val deliveryDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val paymentTermsDays: Int = Invoice.DEFAULT_PAYMENT_TERMS_DAYS,
    val currency: Currency = Currency.EUR,
    val invoiceFormat: InvoiceFormat = InvoiceFormat.GERMAN,
    val sellerVatRegistrationCountry: Country? = null,
)

/**
 * The parties and party-derived choices of an invoice: who sends, who receives, the addresses,
 * the B2B field-set profile, the payment term and the document language. The parameter object of
 * [Invoice.assignParties]; read the current state with [Invoice.parties].
 */
data class InvoiceParties(
    val senderProfile: SenderProfile?,
    val recipient: Recipient?,
    val billingAddress: Address? = null,
    val deliveryAddress: Address? = null,
    val b2bProfile: B2bProfile? = null,
    val paymentTerm: PaymentTerm? = null,
    val documentLanguage: String = "de",
)

/**
 * Everything [Invoice.draft] needs to create an invoice that is complete from its first moment:
 * header values, parties, the two note snapshots and the optional starting values a fixture or a
 * special document type sets. [status] is the state the document is born in; only synthetic data
 * is born anywhere but `DRAFT`.
 */
data class InvoiceDraft(
    val parties: InvoiceParties,
    val header: InvoiceHeaderChange,
    val invoiceNote: InvoiceNoteSnapshot,
    val consumerNote: InvoiceNoteSnapshot,
    val buyerReference: String? = null,
    val freeText: String? = null,
    val reverseCharge: Boolean = false,
    val status: InvoiceStatus = InvoiceStatus.DRAFT,
    val typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
    val originalInvoiceNumber: String? = null,
    val validationStatus: ValidationStatus = ValidationStatus.NOT_VALIDATED,
)
