package de.littletrotter.application.export

import de.littletrotter.application.export.InvoiceHeaderRowIndex as Idx
import de.littletrotter.application.export.RowProjectionSupport.bigDecimal
import de.littletrotter.application.export.RowProjectionSupport.code
import de.littletrotter.application.export.RowProjectionSupport.date
import de.littletrotter.application.export.RowProjectionSupport.enumOrDefault
import de.littletrotter.application.export.RowProjectionSupport.int
import de.littletrotter.application.export.RowProjectionSupport.long
import de.littletrotter.application.export.RowProjectionSupport.str
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.ValidationStatus

/**
 * The "header" cohesive group of [InvoiceHeaderRowMapper.fromRow]'s 75-column projection:
 * identification, status/format enums, dates and the frozen payment-term snapshot. Builds the seed
 * [InvoiceHeaderExportRow] that [InvoicePartyRowMapper], [InvoiceTaxRowMapper],
 * [InvoiceTotalsRowMapper] and [InvoiceTextRowMapper] then each `.copy()` their own group's columns
 * onto — every field outside this group keeps [InvoiceHeaderExportRow]'s own default until its
 * group mapper runs.
 */
internal object InvoiceHeaderCoreRowMapper {

    fun map(
        row: Array<Any?>,
        rawInvoiceNumber: String?,
        invoiceNumberMalformed: Boolean,
    ): InvoiceHeaderExportRow =
        InvoiceHeaderExportRow(
            invoiceId = row.long(Idx.ID) ?: 0L,
            invoiceNumber = rawInvoiceNumber.orEmpty(),
            invoiceNumberMalformed = invoiceNumberMalformed,
            invoiceNumberPrefix = row.str(Idx.INVOICE_NUMBER_PREFIX),
            invoiceNumberSequence = row.long(Idx.INVOICE_NUMBER_SEQUENCE),
            typeCode = row.enumOrDefault(Idx.TYPE_CODE, InvoiceTypeCode.COMMERCIAL_INVOICE),
            status = row.enumOrDefault(Idx.STATUS, InvoiceStatus.DRAFT),
            validationStatus = row.enumOrDefault(Idx.VALIDATION_STATUS, ValidationStatus.NOT_VALIDATED),
            invoiceFormat = row.enumOrDefault(Idx.INVOICE_FORMAT, InvoiceFormat.GERMAN),
            documentLanguage = row.code(Idx.DOCUMENT_LANGUAGE) ?: "de",
            // NOT NULL at the DB level (invoice.invoice_date); a NULL here means the projection's
            // column index/select list drifted out of sync with InvoiceHeaderRowIndex, which a
            // silent fallback date would hide as a wrong-but-plausible 1970 row instead of failing
            // the export outright.
            invoiceDate = requireNotNull(row.date(Idx.INVOICE_DATE)) {
                "invoice_date column was NULL for invoiceId=${row.long(Idx.ID)} — projection/column-index mismatch?"
            },
            deliveryDate = row.date(Idx.DELIVERY_DATE),
            servicePeriodFrom = row.date(Idx.SERVICE_PERIOD_FROM),
            servicePeriodTo = row.date(Idx.SERVICE_PERIOD_TO),
            dueDate = row.date(Idx.DUE_DATE),
            paymentTermsDays = row.int(Idx.PAYMENT_TERMS_DAYS) ?: 14,
            paymentTermSnapshotName = row.str(Idx.PAYMENT_TERM_SNAPSHOT_NAME),
            paymentTermSnapshotDiscountPercent = row.bigDecimal(Idx.PAYMENT_TERM_SNAPSHOT_DISCOUNT_PERCENT),
            paymentTermSnapshotDiscountDays = row.int(Idx.PAYMENT_TERM_SNAPSHOT_DISCOUNT_DAYS),
        )
}
