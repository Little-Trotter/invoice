package de.littletrotter.application.dto

import de.littletrotter.application.command.CommandResult
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.isIssued
import de.littletrotter.shared.InvoiceCashDiscountDto
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.LocalDate

data class PartyDto(
    val id: Long?,
    val name: String?,
    val customerNumber: String? = null,
    val taxNumber: String? = null,
    val vatId: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val contactPerson: String? = null,
    val routingId: String? = null,
    val commercialRegisterNumber: String? = null,
    val courtOfJurisdiction: String? = null,
    val registerCourt: String? = null,
    val iban: String? = null,
    val managingDirector: String? = null,
    val bankName: String? = null,
    val bic: String? = null,
    /** The sender's additional (non-German) VAT registrations, ISO 3166-1
     *  alpha-2 country codes only (no vatId, the UI only needs the country set for the seller-VAT-
     *  registration ComboBox and [de.littletrotter.domain.compliance.FacturXEligibility]). Always
     *  empty for a recipient [PartyDto]. */
    val additionalVatRegistrationCountries: List<String> = emptyList(),
    /** The recipient's standing B2B profile id (`Recipient.b2bProfile`), needed by the
     *  print-time template ComboBox ([de.littletrotter.ui.view.invoice.InvoiceExportPanel]) to
     *  scope its profile-specific template choices. Always null for a sender [PartyDto]. */
    val b2bProfileId: Long? = null,
    /** PO 04.09.2026 — ordinal of the recipient's own `Recipient.invoiceFormat` ("deutsche" vs.
     *  "internationale Rechnung"), which drives the print-time template choice
     *  ([de.littletrotter.ui.view.invoice.InvoiceExportPanel]). Always null for a sender
     *  [PartyDto]. */
    val invoiceFormat: Int? = null,
)

data class AddressDto(
    val id: Long?,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val countryAlpha2: String?,
)

data class TravelExpenseDto(
    val id: Long,
    val invoiceItemId: Long,
    val travelDate: LocalDate?,
    val countryAlpha2: String?,
    val isArrivalDeparture: Boolean,
    val breakfastDeduction: Boolean,
    val lunchDeduction: Boolean,
    val dinnerDeduction: Boolean,
    val calculatedAmount: Money?,
    val version: Long? = 0,
    val fullDayRateSnapshot: Money = Money.ZERO,
    val arrivalDepartureRateSnapshot: Money = Money.ZERO,
    val employerContribution: Money = Money.ZERO,
    /** The whole per-diem block's own mandatory description, denormalized onto every row
     *  (the same pattern [employerContribution] already uses); defaults to "" only for read-side/
     *  test construction convenience — a real, persisted entry always carries a non-blank value
     *  ([de.littletrotter.domain.reference.TravelExpenseEntry.validate]). */
    val description: String = "",
    /** The traveler this row's per-diem entitlement belongs to (renamed from the previous
     *  [description], which meant this before the block-level [description] was introduced);
     *  defaults to "" for the same read-side/test construction convenience as [description]. */
    val participant: String = "",
    val perDiemRateId: Long? = null,
    val shortName: String? = null,
)

/** One [de.littletrotter.domain.invoice.TripExpense] line of a trip, read side. */
data class TripExpenseDto(
    val id: Long,
    val invoiceItemId: Long,
    val kind: String,
    val description: String,
    val travelDate: LocalDate?,
    val quantity: BigDecimal,
    val unit: String?,
    val unitPriceNet: Money,
    val netAmount: Money,
    val receiptId: Long? = null,
    val sortOrder: Int = 0,
    val version: Long? = 0,
    val rateProvided: Boolean = true,
)

/** The trip detail of an [ItemDto] whose [ItemDto.category] is `"TRIP"`; `null` for every
 *  other category. [TravelExpenseDto] (the per-diem days) moved under here from [ItemDto]'s own
 *  top-level `perDiemEntries` — a trip is the only category per-diem days ever occur on. */
data class TripDto(
    val detailedOnInvoice: Boolean,
    val fixedNetPrice: Money?,
    val calculatedNet: Money,
    val expenses: List<TripExpenseDto> = emptyList(),
    val perDiemDays: List<TravelExpenseDto> = emptyList(),
)

data class ItemDto(
    val id: Long,
    val positionId: String,
    val description: String,
    // No longer mutable: the item grid's inline editor (Vaadin Grid Editor) used to bind
    // quantity/unit-price/tax-rate directly onto this row; the editor now buffers edits
    // separately (InvoiceDetailViewItemGrid's ItemGridEditingContext) and folds them into a
    // fresh copy() on save, so ItemDto stays a genuine immutable value object.
    val quantity: BigDecimal,
    val unit: String?,
    val unitPriceNet: Money?,
    val taxRate: BigDecimal?,
    val taxRateRefId: Long?,
    val netAmount: Money?,
    val vatAmount: Money?,
    val grossAmount: Money?,
    val receiptsCount: Int = 0,
    val receiptIds: List<Long> = emptyList(),
    /** Non-null only for a TRIP position ([category] == `"TRIP"`). */
    val trip: TripDto? = null,
    val version: Long? = 0,
    val category: String? = null,
    val sortOrder: Int = 0,
    val deliveryNoteReference: String? = null,
    /** BT-154 — the optional, longer item description alongside [description] (BT-153). */
    val itemDescription: String? = null,
    /** Buyer's item description — recipient-specific label for this position. */
    val buyerItemDescription: String? = null,
    /** The bound `Article.id`, if this position was created/edited from one. */
    val articleRefId: Long? = null,
    val exemptionReasonText: String? = null,
    val taxLegalBasis: String? = null,
    /** R4 — the frozen VAT category code (`S`/`AE`/`E`/`O`/`Z`), see
     *  `InvoiceItem.taxCategoryEnum`. */
    val taxCategory: String? = null,
    /** BT-134 — line-item service period start; exposed here so a TRIP's period (BG-26,
     *  `InvoiceItem.servicePeriodFrom`) round-trips through the read/write DTOs. Meaningful for
     *  every category, not only TRIP, but unused (`null`) for most today. */
    val servicePeriodFrom: LocalDate? = null,
    /** BT-135 — see [servicePeriodFrom]. */
    val servicePeriodTo: LocalDate? = null,
)

data class JobSummaryDto(
    val id: Long,
    val status: String?,
    val createdAt: java.time.OffsetDateTime?,
    val resultRefs: String?,
    val kositReport: String?,
    val lastError: String? = null,
)

/** The latest generation job of an issued invoice ended FAILED: [reason] is short and user-safe. */
data class DocumentGenerationFailure(val reason: String)

/** Short, single-line, user-safe failure text from a job's recorded error (`null` = no usable text). */
fun userSafeFailureReason(lastError: String?, resultRefs: String?): String? {
    val raw = lastError?.takeIf { it.isNotBlank() } ?: errorFromRefs(resultRefs)
    val line = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    return if (line.length <= MAX_REASON_LENGTH) line else line.take(MAX_REASON_LENGTH - 1) + "\u2026"
}

/**
 * The failure to surface for an invoice: only an issued invoice (sent or cancelled) can be missing
 * its document of record, and only when its latest job ended FAILED. A reason-less failure still
 * counts, with an empty [DocumentGenerationFailure.reason] the UI replaces by a generic text.
 */
fun documentGenerationFailureOf(invoiceStatus: Int, latestJob: JobSummaryDto?): DocumentGenerationFailure? {
    val issued = InvoiceStatus.entries.getOrNull(invoiceStatus)?.isIssued == true
    if (!issued || latestJob?.status != GenerationJobStatus.FAILED.name) return null
    return DocumentGenerationFailure(userSafeFailureReason(latestJob.lastError, latestJob.resultRefs).orEmpty())
}

private fun errorFromRefs(resultRefs: String?): String? =
    try {
        resultRefs?.let { REFS_READER.readTree(it)?.get("error")?.takeIf { node -> node.isTextual }?.asText() }
    } catch (_: JsonProcessingException) {
        null
    }

private const val MAX_REASON_LENGTH = 160
private val REFS_READER = ObjectMapper()

data class InvoiceDetailDto(
    val id: Long,
    val invoiceNumber: String,
    val status: Int,
    val invoiceDate: LocalDate?,
    val netAmount: Money?,
    val vatAmount: Money?,
    val grossAmount: Money?,
    val sender: PartyDto?,
    val recipient: PartyDto?,
    val items: List<ItemDto>,
    val latestGenerationJob: JobSummaryDto?,
    val version: Long? = null,
    val dueDate: LocalDate? = null,
    val servicePeriodFrom: LocalDate? = null,
    val servicePeriodTo: LocalDate? = null,
    val freeText: String? = null,
    /** INV-B — persisted invoice-owned note snapshot, independent from live sender master data. */
    val invoiceNote: String? = null,
    val invoiceNoteOrigin: String = "SELLER_DEFAULT",
    /** Persisted invoice-owned copy of the private-customer notice; `null` when none applies. */
    val consumerNote: String? = null,
    val consumerNoteOrigin: String = "SELLER_DEFAULT",
    val buyerReference: String? = null,
    /** BT-13 — see [de.littletrotter.domain.invoice.InvoiceReferences.purchaseOrderReference]; neither
     *  [buyerReference] (BT-10) nor [orderReference] (BT-15). */
    val purchaseOrderReference: String? = null,
    val orderReference: String? = null,
    val orderReferenceDate: LocalDate? = null,
    /** BT-11 — the internal project reference (`invoice.project_number`), collected by
     *  ProjectNumberModule's header form field. */
    val projectNumber: String? = null,
    // Task O: ISO 4217 currency ordinal; null/out-of-range reads as EUR by the mapper.
    val currency: Int? = null,
    val reverseCharge: Boolean = false,
    val paymentTermName: String? = null,
    val billingAddress: AddressDto? = null,
    val deliveryAddress: AddressDto? = null,
    /** Ordinal of `Invoice.documentEngine`; decoded with a MUSTANG
     *  fallback by readers (`DocumentEngine.entries.getOrNull(documentEngine)`). */
    val documentEngine: Int? = null,
    /** Ordinal of `Invoice.validationStatus`, for the cockpit's validation chip. */
    val validationStatus: Int? = null,
    /** Ordinal of `Invoice.invoiceFormat`. */
    val invoiceFormat: Int? = null,
    /** ISO 3166-1 alpha-2 of `Invoice.sellerVatRegistrationCountry`, `null`
     *  for the German default. */
    val sellerVatRegistrationCountry: String? = null,
    /** The invoice's live `PaymentTerm` association id (distinct from [paymentTermName],
     *  which may already be the frozen snapshot's name). Needed to preselect the entry pane's
     *  payment-term ComboBox against a freshly loaded, real `PaymentTerm`. */
    val paymentTermId: Long? = null,
    /** Ordinal of `Invoice.typeCode`; `null`/absent reads as `COMMERCIAL_INVOICE`.
     *  Needed so the UI can tell a credit note apart from a normal invoice
     *  without reconstructing a domain `Invoice`. */
    val typeCode: Int? = null,
    /** `Invoice.documentLanguage` (`"de"`/`"en"`), needed so the storno dialog can decide
     *  whether to offer the English reason field without reconstructing a domain `Invoice`. */
    val documentLanguage: String = "de",
    /**
     * The mail send read model (H-7)
     * ([de.littletrotter.domain.invoice.Invoice.mailSent]), so the detail view can render a send
     * history **without any call into the mail component** on its render path. `null` means "never
     * observed", which is also what an instance that never installed the component sees.
     */
    val mailSent: Boolean? = null,
    /** H-7 — when the component recorded the SENT transition; see [mailSent]. */
    val mailSentAt: java.time.OffsetDateTime? = null,
    /** Early-payment discount (Skonto), computed once by the domain rule
     *  ([de.littletrotter.domain.invoice.Invoice.cashDiscount]) from the frozen payment-term
     *  snapshot for an already-`SENT` invoice, or the live payment term for a draft. `null` means
     *  no Skonto applies — no discount on the term, no gross amount yet, or a storno (which never
     *  carries a discount). */
    val cashDiscount: InvoiceCashDiscountDto? = null,
    /** Set when the latest generation job of this issued invoice ended FAILED; see [documentGenerationFailureOf]. */
    val documentGenerationFailure: DocumentGenerationFailure? = null,
) : CommandResult
