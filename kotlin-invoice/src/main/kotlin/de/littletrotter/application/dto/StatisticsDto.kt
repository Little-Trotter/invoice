package de.littletrotter.application.dto

import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money

/**
 * money-currency-display R6 — every DTO below carries the [currency] its amounts are actually
 * in, trailing with a `Currency.EUR` default so a caller/test that never mixes currencies (the
 * common case) reads and constructs exactly as before. A single row here is only ever a sum
 * across invoices sharing that one currency: [de.littletrotter.infrastructure.persistence.stats
 * .InvoiceStatisticsQueryServiceImpl] groups by currency at the query level, so a period with two
 * invoice currencies now comes back as two rows instead of one row falsely labeled EUR.
 */
data class YearlyTotals(
    val year: Int,
    val netAmount: Money,
    val vatAmount: Money,
    val grossAmount: Money,
    val invoiceCount: Long,
    val avgInvoiceAmount: Money,
    val currency: Currency,
)

data class MonthlyTotals(
    val year: Int,
    val month: Int,
    val netAmount: Money,
    val vatAmount: Money,
    val grossAmount: Money,
    val invoiceCount: Long,
    val currency: Currency,
)

data class QuarterlyTotals(
    val year: Int,
    val quarter: Int,
    val netAmount: Money,
    val vatAmount: Money,
    val grossAmount: Money,
    val invoiceCount: Long,
    val currency: Currency,
)

data class RecipientTotals(
    val recipientId: Long,
    val recipientName: String,
    val grossAmount: Money,
    val invoiceCount: Long,
    val netAmount: Money,
    val currency: Currency = Currency.EUR,
)

data class SenderTotals(
    val senderId: Long,
    val senderName: String,
    val grossAmount: Money,
    val invoiceCount: Long,
    val currency: Currency = Currency.EUR,
)

data class ArticleTotals(
    val description: String,
    val netAmount: Money,
    val itemCount: Long,
    val avgPrice: Money,
    val currency: Currency = Currency.EUR,
)

data class MonthlyTrend(
    val year: Int,
    val month: Int,
    val netAmount: Money,
    val grossAmount: Money,
    val currency: Currency = Currency.EUR,
)

data class DailyTrend(
    val year: Int,
    val month: Int,
    val day: Int,
    val netAmount: Money,
    val grossAmount: Money,
    val currency: Currency = Currency.EUR,
)

data class StatusDistribution(
    val status: InvoiceStatus,
    val count: Long,
)
