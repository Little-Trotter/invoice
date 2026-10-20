package de.littletrotter.fixture

import de.littletrotter.domain.invoice.replaceReceipts
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceDraft
import de.littletrotter.domain.invoice.InvoiceHeaderChange
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNoteOrigin
import de.littletrotter.domain.invoice.InvoiceNoteSnapshot
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceParties
import de.littletrotter.domain.invoice.InvoicePaymentTermSnapshot
import de.littletrotter.domain.invoice.InvoiceReferences
import de.littletrotter.domain.invoice.InvoiceReferencesChange
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTexts
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.headerChange
import de.littletrotter.domain.invoice.parties
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Currency
import de.littletrotter.shared.country.Country
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** The invoice date a freshly created entity carries before any creation path sets a real one. */
val ENTITY_DEFAULT_INVOICE_DATE: LocalDate = LocalDate.of(1970, 1, 1)

private val EMPTY_NOTE = InvoiceNoteSnapshot(
    value = null,
    origin = InvoiceNoteOrigin.SELLER_DEFAULT,
    sourceSenderProfileId = null,
    sourceLanguage = "de",
    sourceDefaultValue = null,
)

/**
 * An invoice built through the domain operations only (`Invoice.draft`, `assignNumber`,
 * `references.update`, `freezePaymentTermSnapshot`, `addItem`, `recalculateTotals`,
 * `recordMailSent`). Every default is the entity default, so a test that omits a value sees what a
 * freshly created invoice holds.
 *
 * - [status] is the state the document is born in (`Invoice.withInitialStatus`).
 * - [invoiceNumber] other than unassigned is applied with [invoiceNumberPrefix] and
 *   [invoiceNumberSequence] through `assignNumber`.
 * - [frozenPaymentTerm] is the term the payment-term snapshot is frozen from; [paymentTerm] is the
 *   live association afterwards, so the two may differ like after a master-data change.
 * - [items] are added before identity and number are assigned, so building the fixture raises no
 *   item events; [recalculate] runs `recalculateTotals` once the items are in.
 * - [selectedSeller], [totals], [originalInvoiceDate], [languageId] and [documentStatusReasonCode]
 *   are hydrated through [PersistenceEmulation] because no operation sets them independently.
 * - [receipts] are the invoice-level attachments, added to the collection the invoice owns.
 */
fun anInvoice(
    id: Long? = null,
    version: Long? = 0,
    status: InvoiceStatus = InvoiceStatus.DRAFT,
    invoiceNumber: InvoiceNumber = InvoiceNumber.UNASSIGNED,
    invoiceNumberPrefix: String? = null,
    invoiceNumberSequence: Long? = null,
    currency: Currency = Currency.EUR,
    senderProfile: SenderProfile? = null,
    recipient: Recipient? = null,
    billingAddress: Address? = null,
    deliveryAddress: Address? = null,
    selectedSeller: SenderProfile? = null,
    paymentTerm: PaymentTerm? = null,
    frozenPaymentTerm: PaymentTerm? = null,
    b2bProfile: B2bProfile? = null,
    references: InvoiceReferencesChange = InvoiceReferencesChange(),
    sellerVatRegistrationCountry: Country? = null,
    invoiceFormat: InvoiceFormat = InvoiceFormat.GERMAN,
    documentLanguage: String = "de",
    invoiceDate: LocalDate = ENTITY_DEFAULT_INVOICE_DATE,
    servicePeriodFrom: LocalDate? = null,
    servicePeriodTo: LocalDate? = null,
    deliveryDate: LocalDate? = null,
    dueDate: LocalDate? = null,
    paymentTermsDays: Int = Invoice.DEFAULT_PAYMENT_TERMS_DAYS,
    freeText: String? = null,
    invoiceNote: String? = null,
    consumerNote: String? = null,
    typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
    reverseCharge: Boolean = false,
    originalInvoiceNumber: String? = null,
    validationStatus: ValidationStatus = ValidationStatus.NOT_VALIDATED,
    mailSentAt: OffsetDateTime? = null,
    sourceQuoteId: UUID? = null,
    items: List<InvoiceItem> = emptyList(),
    recalculate: Boolean = false,
    totals: InvoiceTotals? = null,
    originalInvoiceDate: LocalDate? = null,
    languageId: String? = null,
    documentStatusReasonCode: String? = null,
    receipts: List<Receipt> = emptyList(),
): Invoice {
    val invoice = Invoice.draft(
        InvoiceDraft(
            parties = InvoiceParties(
                senderProfile = senderProfile,
                recipient = recipient,
                billingAddress = billingAddress,
                deliveryAddress = deliveryAddress,
                b2bProfile = b2bProfile,
                paymentTerm = frozenPaymentTerm ?: paymentTerm,
                documentLanguage = documentLanguage,
            ),
            header = InvoiceHeaderChange(
                invoiceDate = invoiceDate,
                servicePeriodFrom = servicePeriodFrom,
                servicePeriodTo = servicePeriodTo,
                deliveryDate = deliveryDate,
                dueDate = dueDate,
                paymentTermsDays = paymentTermsDays,
                currency = currency,
                invoiceFormat = invoiceFormat,
                sellerVatRegistrationCountry = sellerVatRegistrationCountry,
            ),
            invoiceNote = EMPTY_NOTE.copy(value = invoiceNote),
            consumerNote = EMPTY_NOTE.copy(value = consumerNote),
            freeText = freeText,
            reverseCharge = reverseCharge,
            status = status,
            typeCode = typeCode,
            originalInvoiceNumber = originalInvoiceNumber,
            validationStatus = validationStatus,
        ),
    )
    invoice.references.update(references)
    PersistenceEmulation.whileEditable(invoice) {
        if (frozenPaymentTerm != null) {
            invoice.freezePaymentTermSnapshot()
            invoice.assignParties(invoice.parties().copy(paymentTerm = paymentTerm))
        }
        items.forEach(invoice::addItem)
        if (recalculate) invoice.recalculateTotals()
    }
    if (invoiceNumber.isAssigned() || invoiceNumberPrefix != null || invoiceNumberSequence != null) {
        invoice.assignNumber(invoiceNumber, invoiceNumberPrefix, invoiceNumberSequence)
    }
    if (mailSentAt != null) invoice.recordMailSent(mailSentAt)
    if (sourceQuoteId != null) PersistenceEmulation.whileEditable(invoice) { invoice.linkToQuote(sourceQuoteId) }
    if (selectedSeller != null) PersistenceEmulation.assignSelectedSeller(invoice, selectedSeller)
    if (totals != null) PersistenceEmulation.assignTotals(invoice, totals)
    if (originalInvoiceDate != null) PersistenceEmulation.assignOriginalInvoiceDate(invoice, originalInvoiceDate)
    if (languageId != null) PersistenceEmulation.assignLanguageId(invoice, languageId)
    if (documentStatusReasonCode != null) PersistenceEmulation.assignDocumentStatusReasonCode(invoice, documentStatusReasonCode)
    PersistenceEmulation.whileEditable(invoice) { invoice.replaceReceipts(receipts) }
    if (id != null) PersistenceEmulation.assignId(invoice, id)
    if (version != 0L) PersistenceEmulation.assignVersion(invoice, version)
    return invoice
}

/**
 * Edits an existing invoice in place through the domain operations. The header values and the
 * parties each move as one group, so the editor reads the current group, changes the touched value
 * and writes the group back.
 */
fun Invoice.edit(block: InvoiceEditor.() -> Unit): Invoice = also { InvoiceEditor(it).block() }

/** Property-style access to the editable groups of an [Invoice]; each write applies at once. */
class InvoiceEditor internal constructor(internal val invoice: Invoice) {
    private fun header(change: InvoiceHeaderChange.() -> InvoiceHeaderChange) =
        PersistenceEmulation.whileEditable(invoice) { invoice.updateHeader(invoice.headerChange().change()) }

    private fun parties(change: InvoiceParties.() -> InvoiceParties) =
        PersistenceEmulation.whileEditable(invoice) { invoice.assignParties(invoice.parties().change()) }

    var id: Long?
        get() = invoice.id
        set(value) { PersistenceEmulation.assignId(invoice, value) }
    var version: Long?
        get() = invoice.version
        set(value) { PersistenceEmulation.assignVersion(invoice, value) }

    /** The number as a loaded row carries it; assigning a number is `Invoice.assignNumber`'s job. */
    var invoiceNumber: InvoiceNumber
        get() = invoice.invoiceNumber
        set(value) { PersistenceEmulation.hydrate(invoice, "invoiceNumber", value) }

    var invoiceDate: LocalDate
        get() = invoice.invoiceDate
        set(value) = header { copy(invoiceDate = value) }
    var servicePeriodFrom: LocalDate?
        get() = invoice.servicePeriodFrom
        set(value) = header { copy(servicePeriodFrom = value) }
    var servicePeriodTo: LocalDate?
        get() = invoice.servicePeriodTo
        set(value) = header { copy(servicePeriodTo = value) }
    var deliveryDate: LocalDate?
        get() = invoice.deliveryDate
        set(value) = header { copy(deliveryDate = value) }
    var dueDate: LocalDate?
        get() = invoice.dueDate
        set(value) = header { copy(dueDate = value) }
    var paymentTermsDays: Int
        get() = invoice.paymentTermsDays
        set(value) = header { copy(paymentTermsDays = value) }
    var currency: Currency
        get() = invoice.currency
        set(value) = header { copy(currency = value) }
    var invoiceFormat: InvoiceFormat
        get() = invoice.invoiceFormat
        set(value) = header { copy(invoiceFormat = value) }
    var sellerVatRegistrationCountry: Country?
        get() = invoice.sellerVatRegistrationCountry
        set(value) = header { copy(sellerVatRegistrationCountry = value) }

    var senderProfile: SenderProfile?
        get() = invoice.senderProfile
        set(value) = parties { copy(senderProfile = value) }
    var recipient: Recipient?
        get() = invoice.recipient
        set(value) = parties { copy(recipient = value) }
    var billingAddress: Address?
        get() = invoice.billingAddress
        set(value) = parties { copy(billingAddress = value) }
    var deliveryAddress: Address?
        get() = invoice.deliveryAddress
        set(value) = parties { copy(deliveryAddress = value) }
    var b2bProfile: B2bProfile?
        get() = invoice.b2bProfile
        set(value) = parties { copy(b2bProfile = value) }
    var paymentTerm: PaymentTerm?
        get() = invoice.paymentTerm
        set(value) = parties { copy(paymentTerm = value) }
    var documentLanguage: String
        get() = invoice.documentLanguage.code
        set(value) = parties { copy(documentLanguage = value) }

    var reverseCharge: Boolean
        get() = invoice.reverseCharge
        set(value) = PersistenceEmulation.whileEditable(invoice) { invoice.decideReverseCharge(value) }
    var freeText: String?
        get() = invoice.texts.freeText
        set(value) = invoice.texts.changeFreeText(value)
    var invoiceNote: String?
        get() = invoice.texts.invoiceNote
        set(value) = invoice.texts.applyInvoiceNote(invoice.texts.invoiceNoteSnapshot().copy(value = value))
    /** The reference fields, edited as one group like `InvoiceReferences.update` does. */
    val references: InvoiceReferencesEditor = InvoiceReferencesEditor(invoice)

    val itemList: List<InvoiceItem> get() = invoice.itemList

    fun addItem(item: InvoiceItem) = PersistenceEmulation.whileEditable(invoice) { invoice.addItem(item) }

    fun recalculateTotals() = PersistenceEmulation.whileEditable(invoice) { invoice.recalculateTotals() }

    val texts: InvoiceTexts get() = invoice.texts

    val documents: List<InvoiceDocument> get() = invoice.documentList

    fun forceStatusForTest(status: InvoiceStatus) {
        PersistenceEmulation.forceStatus(invoice, status)
    }

    fun transitionTo(target: InvoiceStatus) = invoice.transitionTo(target)

    // States a persisted row can carry but no domain operation produces: hydrated the way Hibernate loads them.
    /** A stored status, set without walking the state machine. */
    var status: InvoiceStatus
        get() = invoice.status
        set(value) { PersistenceEmulation.forceStatus(invoice, value) }

    var validationStatus: ValidationStatus
        get() = invoice.validationStatus
        set(value) { PersistenceEmulation.assign(invoice, "validationStatus", value) }

    var typeCode: InvoiceTypeCode
        get() = invoice.typeCode
        set(value) { PersistenceEmulation.hydrate(invoice, "typeCode", value) }

    var originalInvoiceNumber: String?
        get() = invoice.originalInvoiceNumber?.value
        set(value) { PersistenceEmulation.hydrate(invoice, "originalInvoiceNumber", value?.let { InvoiceNumber(it) }) }

    var originalInvoiceDate: LocalDate?
        get() = invoice.originalInvoiceDate
        set(value) { PersistenceEmulation.hydrate(invoice, "originalInvoiceDate", value) }

    var selectedSeller: SenderProfile?
        get() = invoice.selectedSeller
        set(value) { PersistenceEmulation.assignSelectedSeller(invoice, value) }

    var mailSent: Boolean?
        get() = invoice.mailSent
        set(value) { PersistenceEmulation.hydrate(invoice, "mailSent", value) }

    var mailSentAt: OffsetDateTime?
        get() = invoice.mailSentAt
        set(value) {
            if (value != null) invoice.recordMailSent(value) else PersistenceEmulation.hydrate(invoice, "mailSentAt", null)
        }

    var needsRecalculation: Boolean
        get() = invoice.needsRecalculation
        set(value) { PersistenceEmulation.assign(invoice, "needsRecalculation", value) }

    var paymentTermSnapshot: InvoicePaymentTermSnapshot?
        get() = invoice.paymentTermSnapshot
        set(value) { PersistenceEmulation.hydrate(invoice, "paymentTermSnapshot", value) }

    var invoiceNumberSequence: Long?
        get() = invoice.invoiceNumberSequence
        set(value) { PersistenceEmulation.hydrate(invoice, "invoiceNumberSequence", value) }

    var receipts: List<Receipt>
        get() = invoice.receiptList
        set(value) { PersistenceEmulation.hydrate(invoice, "receipts", value.toMutableList()) }

    /** The stored totals, independent of the items. */
    var totals: InvoiceTotals
        get() = invoice.totals
        set(value) { PersistenceEmulation.assign(invoice, "totals", value) }
}

/** Property-style access to the reference fields of an invoice; each write applies at once. */
class InvoiceReferencesEditor internal constructor(private val invoice: Invoice) {
    private fun refs(change: InvoiceReferencesChange.() -> InvoiceReferencesChange) =
        invoice.references.update(invoice.references.asChange().change())

    var buyerReference: String?
        get() = invoice.references.buyerReference
        set(value) = refs { copy(buyerReference = value) }
    var purchaseOrderReference: String?
        get() = invoice.references.purchaseOrderReference
        set(value) = refs { copy(purchaseOrderReference = value) }
    var orderReference: String?
        get() = invoice.references.orderReference
        set(value) = refs { copy(orderReference = value) }
    var projectNumber: String?
        get() = invoice.references.projectNumber
        set(value) = refs { copy(projectNumber = value) }
}

/** A standalone reference value object with the given fields, set through `InvoiceReferences.update`. */
fun someReferences(
    buyerReference: String? = null,
    purchaseOrderReference: String? = null,
    orderReference: String? = null,
    orderReferenceDate: LocalDate? = null,
    offerReference: String? = null,
    offerReferenceDate: LocalDate? = null,
    projectNumber: String? = null,
    costCenter: String? = null,
): InvoiceReferences = InvoiceReferences().apply {
    update(
        InvoiceReferencesChange(
            buyerReference = buyerReference,
            purchaseOrderReference = purchaseOrderReference,
            orderReference = orderReference,
            orderReferenceDate = orderReferenceDate,
            offerReference = offerReference,
            offerReferenceDate = offerReferenceDate,
            projectNumber = projectNumber,
            costCenter = costCenter,
        ),
    )
}
