package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.isReadOnly
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Currency

/**
 * Enum-ordinal decoders for the read model the views now work on throughout
 * ([InvoiceDetailDto]/[ItemDto], `dtoToInvoice` removed). These are plain enum lookups, never a
 * domain `Invoice`/`SenderProfile`/`Recipient` construction — the same "ordinal → enum, with a
 * safe fallback" idiom already used at every other DTO boundary in this codebase (e.g.
 * `InvoiceStatus.entries.getOrNull(dto.status) ?: InvoiceStatus.DRAFT` in
 * [InvoiceCockpitHero]).
 */

internal fun InvoiceDetailDto.statusEnum(): InvoiceStatus = InvoiceStatus.entries.getOrNull(status) ?: InvoiceStatus.DRAFT

internal fun InvoiceDetailDto.typeCodeEnum(): InvoiceTypeCode = typeCode?.let { InvoiceTypeCode.entries.getOrNull(it) } ?: InvoiceTypeCode.COMMERCIAL_INVOICE

// PO order 2026-08-31 — mirrors Invoice.isReadOnly(): a storno document is read-only regardless of
// its own status, same reasoning as there. ST-1 — the status half now shares InvoiceStatus
// .isReadOnly with Invoice.isReadOnly() and PanacheReceiptRepository instead of its own copy of
// the SENT/CANCELLED/VOIDED set.
//
// PO decision 1 (2026-09-02) — moved from CREDIT_NOTE (`381`) to CORRECTED_INVOICE (`384`) along
// with the storno flow itself; see Invoice.isReadOnly()'s own comment.
internal fun InvoiceDetailDto.isReadOnly(): Boolean =
    statusEnum().isReadOnly || typeCodeEnum() == InvoiceTypeCode.CORRECTED_INVOICE

/**
 * The i18n key for a status badge, resolving the storno pair by type code: both halves sit
 * in [InvoiceStatus.CANCELLED], so the plain status label alone cannot tell the cancelled original
 * ("Storniert (Original)") from the storno document itself, type code 384 ("Korrektur-Storno").
 * Every other status maps to its own [InvoiceStatus.labelKey] unchanged.
 *
 * [typeCode] `null` means "no type-code context available" (e.g. the overview's status quick-filter
 * chips, which label a status, not a document) — those keep the generic `invoice.status.cancelled`.
 * Shared by [InvoiceListView]'s grid badge and the detail page's own cockpit badges so the two can
 * never drift apart.
 */
internal fun statusBadgeLabelKey(status: InvoiceStatus, typeCode: InvoiceTypeCode? = null): String = when (status) {
    InvoiceStatus.CANCELLED -> when (typeCode) {
        InvoiceTypeCode.CORRECTED_INVOICE -> "invoice.typeCode.correctedInvoice"
        null -> status.labelKey
        else -> "invoice.status.cancelledOriginal"
    }
    else -> status.labelKey
}

internal fun InvoiceDetailDto.currencyEnum(): Currency = currency?.let { Currency.entries.getOrNull(it) } ?: Currency.EUR

internal fun InvoiceDetailDto.documentEngineEnum(): DocumentEngine = documentEngine?.let { DocumentEngine.entries.getOrNull(it) } ?: DocumentEngine.MUSTANG

internal fun InvoiceDetailDto.validationStatusEnum(): ValidationStatus = validationStatus?.let { ValidationStatus.entries.getOrNull(it) } ?: ValidationStatus.NOT_VALIDATED

internal fun InvoiceDetailDto.invoiceFormatEnum(): InvoiceFormat = invoiceFormat?.let { InvoiceFormat.entries.getOrNull(it) } ?: InvoiceFormat.GERMAN

internal fun ItemDto.categoryEnum(): InvoiceItemCategory =
    category?.let { name -> InvoiceItemCategory.entries.find { it.name == name } } ?: InvoiceItemCategory.SERVICE

internal fun ItemDto.unitEnum(): InvoiceItemUnit =
    unit?.let { name -> InvoiceItemUnit.entries.find { it.name == name } } ?: InvoiceItemUnit.PIECE

internal fun ItemDto.taxCategoryEnum(): VatCategory? = taxCategory?.let { VatCategory.fromCodeOrNull(it) }
