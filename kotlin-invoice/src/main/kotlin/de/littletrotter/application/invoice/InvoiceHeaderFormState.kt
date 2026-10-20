package de.littletrotter.application.invoice

import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Currency
import java.time.LocalDate

/**
 * The entry pane's (Erfassen-Formular) mutable Vaadin `Binder` bean. Replaces binding
 * directly onto a detached, hand-reconstructed `Invoice` (`dtoToInvoice`, removed): the pane now
 * starts from the read model (`InvoiceDetailDto`) plus whichever master-data read models its
 * ComboBoxes need ([recipient]/[senderProfile]/[paymentTerm] — loaded via the query ports, never
 * constructed here), and [UpdateInvoiceHeaderCommand] is built straight from this bean's fields
 * after a successful `Binder.writeBeanIfValid`.
 *
 * Plain fields, no Vaadin/JPA imports of its own — every `var` is a JavaBean property so the
 * `Binder` can bind to it both reflectively (by name) and via the property-reference idiom
 * (`InvoiceHeaderFormState::invoiceDate.getter`/`.setter`) the entry pane already used against
 * `Invoice`. [InvoiceFormatModule] binds [invoiceFormat] through this same bean via its own
 * getter/setter [de.littletrotter.spi.form.FieldSpec] lambdas.
 *
 * [recipient]/[senderProfile]/[paymentTerm] are read-model projections
 * ([RecipientSummaryDto]/[SenderSummaryDto]/[PaymentTermDto], from
 * `RecipientQueryPort`/`SenderQueryPort`/`PaymentTermQueryPort`), never the
 * `Recipient`/`SenderProfile`/`PaymentTerm` entities — the entry pane's ComboBoxes never need the
 * lazy master-data collections, only the summary fields these DTOs already carry.
 */
class InvoiceHeaderFormState(
    var invoiceDate: LocalDate? = null,
    var servicePeriodFrom: LocalDate? = null,
    var servicePeriodTo: LocalDate? = null,
    var dueDate: LocalDate? = null,
    var freeText: String? = null,
    var invoiceNote: String? = null,
    var consumerNote: String? = null,
    var buyerReference: String? = null,
    var purchaseOrderReference: String? = null,
    var orderReference: String? = null,
    var orderReferenceDate: LocalDate? = null,
    /** BT-11 — bound by ProjectNumberModule's FieldSpec "projectNumber"; missing here used to
     *  leave a dangling forField binding and crash the whole detail view at readBean
     *  (PO-Bugreport 2026-08-30, invoice/627). */
    var projectNumber: String? = null,
    var currency: Currency = Currency.EUR,
    /** Read-model projection (or `null`) — never constructed by the UI. */
    var recipient: RecipientSummaryDto? = null,
    /** Read-model projection (or `null`) — never constructed by the UI. */
    var senderProfile: SenderSummaryDto? = null,
    var sellerVatRegistrationCountry: Country? = null,
    /** Read-model projection (or `null`) — never constructed by the UI. */
    var paymentTerm: PaymentTermDto? = null,
    var invoiceFormat: InvoiceFormat = InvoiceFormat.GERMAN,
)
