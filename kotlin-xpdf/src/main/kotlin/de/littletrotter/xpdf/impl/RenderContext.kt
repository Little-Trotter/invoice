package de.littletrotter.xpdf.impl

/**
 * Typed replacement for [InvoiceReportContextBuilder.buildContext]'s former
 * `Map<String, Any?>` render context. The eight unpacking casts in
 * [InvoiceReportModelBuilder]/[InvoiceReportRowBuilders] stayed because the map was the
 * layout's single source of truth plus ~50 test call
 * sites, so removing the casts alone (without changing the map contract they unpack) had no
 * cast-free route. This type is that contract, made concrete: field names and nullability mirror
 * the former map keys 1:1 (a value the map could hold as `null` stays nullable here; the few keys
 * that were always read with a plain, non-`?` cast — `language`/`cancellation`/`reverseCharge`/
 * `hasPaymentTerms`/the row lists — stay non-null). [InvoiceReportModelBuilder]/
 * [InvoiceReportRowBuilders] read these as typed properties; the template itself never sees this
 * shape, only the [InvoiceReportModel] built from it.
 */
internal data class RenderContext(
    val invoice: InvoiceRenderContext,
    val sender: SenderRenderContext,
    val recipient: RecipientRenderContext,
)

/** Formatted invoice-level fields (numbers/dates already applied) plus the item rows. */
internal data class InvoiceRenderContext(
    val number: String = "",
    val reverseCharge: Boolean = false,
    val language: String = "de",
    val wordingProfile: Int? = null,
    val currencyCode: String = "",
    val reverseChargeNoteText: String? = null,
    val reverseChargeExemptionReasonCode: String? = null,
    val date: String? = null,
    val dueDate: String? = null,
    val netTotal: String = "",
    val vatTotal: String = "",
    val grossTotal: String = "",
    val paymentDueText: String? = null,
    val cashDiscountText: String? = null,
    val buyerReference: String? = null,
    val purchaseOrderReference: String? = null,
    val orderReference: String? = null,
    val offerReference: String? = null,
    val offerDate: String? = null,
    val cancellation: Boolean = false,
    val originalInvoiceNumber: String? = null,
    val originalInvoiceDate: String? = null,
    val servicePeriod: String? = null,
    val freeText: String? = null,
    /** Hint for private customers, printed as its own paragraph in the note box. */
    val consumerNote: String? = null,
    val taxBreakdown: RenderTaxBreakdown? = null,
    val hasPaymentTerms: Boolean = false,
    val projectNumber: String? = null,
    val items: List<ItemRenderContext> = emptyList(),
)

/** MwSt.-Aufschlüsselung; `null` (not an empty list) when there is nothing to break down
 *  (single rate), mirroring the former map's `"taxBreakdown" to null` sentinel exactly. */
internal data class RenderTaxBreakdown(val rows: List<TaxBreakdownRowContext> = emptyList())

internal data class TaxBreakdownRowContext(
    val rate: String = "",
    val category: String? = null,
    val net: String = "",
    val vat: String = "",
)

/** One invoice item, in report-template shape (formatted, not the raw [de.littletrotter.shared.InvoiceItemDto]). */
internal data class ItemRenderContext(
    val position: String = "",
    val description: String = "",
    val qty: String = "",
    val unitLabel: String = "",
    val unitPrice: String? = null,
    val lineTotal: String? = null,
    val lineVat: String? = null,
    val vatPercent: String? = null,
    val sellerItemId: String? = null,
    val buyerItemId: String? = null,
    val buyerArticleDescription: String? = null,
    val itemDescription: String? = null,
    val servicePeriodFrom: String? = null,
    val servicePeriodTo: String? = null,
    val category: Int? = null,
    val tripDetailed: Boolean = false,
    val tripExpenses: List<TripExpenseRenderContext> = emptyList(),
    val tripPerDiemDays: Int = 0,
    val tripPerDiemTotal: String? = null,
)

internal data class TripExpenseRenderContext(
    val kindLabelKey: String? = null,
    val description: String? = null,
    val quantity: String? = null,
    val unitLabel: String = "",
    val netAmount: String? = null,
)

internal data class SenderRenderContext(
    val name: String = "",
    val street: String = "",
    val postalCode: String = "",
    val city: String = "",
    val country: String = "",
    val vatId: String? = null,
    val registrationVatId: String? = null,
    val registrationVatCountry: String? = null,
    val registrationTaxNumber: String? = null,
    val registrationTaxNumberLabelKey: String? = null,
    val iban: String? = null,
    val bic: String? = null,
    val taxNumber: String? = null,
    val bankName: String? = null,
    val managingDirector: String? = null,
    val courtOfJurisdiction: String? = null,
    val registerCourt: String? = null,
    val commercialRegisterNumber: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val footerNote: String? = null,
    val website: String? = null,
    val registeredSeat: String? = null,
)

internal data class RecipientRenderContext(
    val name: String = "",
    val street: String = "",
    val postalCode: String = "",
    val city: String = "",
    val country: String? = null,
    val customerNumber: String? = null,
)
