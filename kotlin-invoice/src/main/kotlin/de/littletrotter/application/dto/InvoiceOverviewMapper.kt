package de.littletrotter.application.dto

import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.domain.shared.ValidationStatus
import java.math.BigDecimal
import java.time.LocalDate

object InvoiceOverviewMapper {

    // Row layout (17 columns, index 0–16):
    private const val COL_ID = 0
    private const val COL_INVOICE_NUMBER = 1
    private const val COL_TYPE_CODE = 2
    private const val COL_SELLER_NAME_1 = 3 // selectedSeller
    private const val COL_SELLER_NAME_2 = 4 // senderProfile
    private const val COL_RECIPIENT_NAME = 5
    private const val COL_GROSS_AMOUNT = 6
    private const val COL_PAYMENT_TERM_NAME = 7
    private const val COL_FREE_TEXT = 8
    private const val COL_ITEMS_COUNT = 9
    private const val COL_INVOICE_DATE = 10
    private const val COL_STATUS = 11
    private const val COL_VALIDATION_STATUS = 12
    private const val COL_PAYMENT_TERMS_DAYS = 13
    private const val COL_CURRENCY = 14
    private const val COL_REVERSE_CHARGE = 15
    private const val COL_DOCUMENT_GENERATION_FAILED = 16

    fun fromRow(row: Array<Any?>): InvoiceOverviewDto {
        val id = (row.getOrNull(COL_ID) as? Number)?.toLong()
        val invoiceNumber = when (val raw = row.getOrNull(COL_INVOICE_NUMBER)) {
            is String -> raw
            is InvoiceNumber -> raw.value
            else -> ""
        }
        val typeCode = row.getOrNull(COL_TYPE_CODE) as? InvoiceTypeCode
        val sellerName1 = row.getOrNull(COL_SELLER_NAME_1) as? String
        val sellerName2 = row.getOrNull(COL_SELLER_NAME_2) as? String
        val recipientName = row.getOrNull(COL_RECIPIENT_NAME) as? String
        val grossAmount = when (val raw = row.getOrNull(COL_GROSS_AMOUNT)) {
            is BigDecimal -> Money(raw)
            is Money -> raw
            else -> null
        }
        val paymentTermName = row.getOrNull(COL_PAYMENT_TERM_NAME) as? String
        val freeText = row.getOrNull(COL_FREE_TEXT) as? String
        val itemsCount = (row.getOrNull(COL_ITEMS_COUNT) as? Number)?.toInt() ?: 0
        val invoiceDate = row.getOrNull(COL_INVOICE_DATE) as? LocalDate
        val statusVal = (row.getOrNull(COL_STATUS) as? InvoiceStatus) ?: InvoiceStatus.DRAFT
        val validationStatus = (row.getOrNull(COL_VALIDATION_STATUS) as? ValidationStatus) ?: ValidationStatus.UNAVAILABLE
        val paymentTermsDays = (row.getOrNull(COL_PAYMENT_TERMS_DAYS) as? Number)?.toInt()
        val currency = (row.getOrNull(COL_CURRENCY) as? Currency) ?: Currency.EUR
        val reverseCharge = (row.getOrNull(COL_REVERSE_CHARGE) as? Boolean) ?: false

        val documentGenerationFailed = (row.getOrNull(COL_DOCUMENT_GENERATION_FAILED) as? Boolean) ?: false

        return InvoiceOverviewDto(
            id = id,
            invoiceNumber = invoiceNumber.ifBlank { "" },
            typeCode = typeCode,
            sellerName = sellerName1 ?: sellerName2,
            sellerCity = null,
            recipientName = recipientName,
            grossAmount = grossAmount,
            paymentLabel = paymentTermName ?: paymentTermsDays?.toString(),
            freeText = freeText,
            itemsCount = itemsCount,
            invoiceDate = invoiceDate,
            status = statusVal,
            validationStatus = validationStatus,
            currency = currency,
            reverseCharge = reverseCharge,
            documentGenerationFailed = documentGenerationFailed,
        )
    }
}
