package de.littletrotter.application.export

import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * One fully-mapped row of the `Rechnungen` sheet: every enum stays a real Kotlin enum
 * (never an ordinal, never a `.name()` string — see [de.littletrotter.infrastructure.export
 * .workbook.InvoiceHeaderSheetWriter] for where these are resolved to i18n labels), every amount
 * is [Money], every count a plain `Int`. Produced by [InvoiceHeaderRowMapper] from the raw
 * projection [de.littletrotter.infrastructure.persistence.InvoiceExportReadRepositoryImpl] reads;
 * turned into Excel cells by [de.littletrotter.infrastructure.export.workbook
 * .InvoiceHeaderSheetWriter].
 *
 * Field order here follows the sheet's own column order (§3) so the two stay easy to read
 * side by side; it has no bearing on how Excel lays out the columns — that is entirely
 * [de.littletrotter.infrastructure.export.workbook.InvoiceHeaderSheetWriter]'s job. Every field but
 * [invoiceId] and [invoiceDate] carries a default matching the equivalent
 * [de.littletrotter.domain.invoice.Invoice] property's own default (mirrors [de.littletrotter
 * .application.dto.InvoiceDetailDto]'s shape, including detekt's `LongParameterList
 * .ignoreDefaultParameters` exemption for a defaulted constructor).
 */
data class InvoiceHeaderExportRow(
    // ── Identification ──────────────────────────────────────────────────────
    val invoiceId: Long,
    /** The raw, as-stored invoice number. Never blank-checked against [de.littletrotter.domain
     *  .invoice.InvoiceNumber]'s format on a managed entity — read straight off the column so one
     *  malformed row can never take the whole export down (§1 trap 2). Empty for a draft. */
    val invoiceNumber: String = "",
    /** `true` when [invoiceNumber] is non-blank but does not match any of the three accepted
     *  [de.littletrotter.domain.invoice.InvoiceNumber] formats. */
    val invoiceNumberMalformed: Boolean = false,
    val invoiceNumberPrefix: String? = null,
    val invoiceNumberSequence: Long? = null,
    val typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
    val status: InvoiceStatus = InvoiceStatus.DRAFT,
    val validationStatus: ValidationStatus = ValidationStatus.NOT_VALIDATED,
    val invoiceFormat: InvoiceFormat = InvoiceFormat.GERMAN,
    val documentLanguage: String = "de",
    val documentEngine: DocumentEngine = DocumentEngine.MUSTANG,

    // ── Dates ───────────────────────────────────────────────────────────────
    val invoiceDate: LocalDate,
    val deliveryDate: LocalDate? = null,
    val servicePeriodFrom: LocalDate? = null,
    val servicePeriodTo: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val paymentTermsDays: Int = 14,

    // ── Payment terms (frozen snapshot only) ───────────────────────────────
    val paymentTermSnapshotName: String? = null,
    val paymentTermSnapshotDiscountPercent: BigDecimal? = null,
    val paymentTermSnapshotDiscountDays: Int? = null,

    // ── Seller ──────────────────────────────────────────────────────────────
    val sellerName: String? = null,
    val sellerVatId: String? = null,
    val sellerTaxNumber: String? = null,
    val sellerIdentifierValue: String? = null,
    val sellerIdentifierSchemeId: String? = null,
    val sellerRegisterNumber: String? = null,
    val sellerRegisterSchemeId: String? = null,
    val sellerElectronicAddressValue: String? = null,
    val sellerElectronicAddressSchemeId: String? = null,
    val sellerVatRegistrationCountry: String? = null,

    // ── Buyer ───────────────────────────────────────────────────────────────
    val buyerName: String? = null,
    val buyerVatId: String? = null,
    val buyerIdentifierValue: String? = null,
    val buyerIdentifierSchemeId: String? = null,
    val buyerElectronicAddressValue: String? = null,
    val buyerElectronicAddressSchemeId: String? = null,
    val partySource: PartySource = PartySource.MASTER_DATA,

    // ── References (BT block) ──────────────────────────────────────────────
    val buyerReference: String? = null,
    val purchaseOrderReference: String? = null,
    val orderReference: String? = null,
    val orderReferenceDate: LocalDate? = null,
    val offerReference: String? = null,
    val offerReferenceDate: LocalDate? = null,
    val projectNumber: String? = null,
    val costCenter: String? = null,

    // ── Storno reference ────────────────────────────────────────────────────
    val originalInvoiceNumber: String? = null,
    val originalInvoiceDate: LocalDate? = null,

    // ── Tax and amounts ─────────────────────────────────────────────────────
    val currency: Currency = Currency.EUR,
    val reverseCharge: Boolean = false,
    val netAmount: Money = Money.ZERO,
    val vatAmount: Money = Money.ZERO,
    val grossAmount: Money = Money.ZERO,
    val itemsCount: Int = 0,
    val receiptsCount: Int = 0,
    val needsRecalculation: Boolean = false,

    // ── Audit ───────────────────────────────────────────────────────────────
    val createdAt: OffsetDateTime? = null,
    val createdBy: String? = null,
    val updatedAt: OffsetDateTime? = null,
    val updatedBy: String? = null,
    val version: Long? = null,
    val freeText: String? = null,
)
