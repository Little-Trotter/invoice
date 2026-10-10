package de.littletrotter.application.dto

import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.Currency
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Money
import java.time.LocalDate

data class InvoiceOverviewDto(
    val id: Long?,
    val invoiceNumber: String,
    val typeCode: InvoiceTypeCode?,
    val sellerName: String?,
    val sellerCity: String?,
    val recipientName: String?,
    val grossAmount: Money?,
    val paymentLabel: String?,
    val freeText: String?,
    val itemsCount: Int,
    val invoiceDate: LocalDate?,
    val status: InvoiceStatus,
    val validationStatus: ValidationStatus,
    /** Task O currency; defaults to EUR when a projection row predates this column (AR5). */
    val currency: Currency = Currency.EUR,
    /** Reverse charge flag for EU invoices. */
    val reverseCharge: Boolean = false,
    /** The latest document job of this issued invoice ended FAILED: the document of record is missing. */
    val documentGenerationFailed: Boolean = false,
)
