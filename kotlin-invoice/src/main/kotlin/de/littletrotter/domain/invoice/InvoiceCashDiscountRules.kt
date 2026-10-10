package de.littletrotter.domain.invoice

import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.shared.InvoiceCashDiscountDto
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pure computation over already-resolved inputs — no entity graph, no snapshot-vs-live decision
 * (that is [Invoice.cashDiscount]'s job). `null` in every case where there is nothing to offer a
 * discount on: a zero rate, no day-count maintained ([discountDays] `null`, the master-data
 * invariant that a non-zero rate always pairs with a day-count — see [DiscountPercent]'s own KDoc),
 * or a non-positive gross amount (a nil invoice or a credit note).
 *
 * [CashDiscount.amount] rounds once, at the end ([Money.of]'s HALF_UP-to-cents), never on an
 * intermediate value — the spec is explicit that a second rounding step would drift from the
 * printed figure.
 */
fun cashDiscountFor(
    invoiceDate: LocalDate,
    discountPercent: DiscountPercent,
    discountDays: Int?,
    grossAmount: Money,
): CashDiscount? {
    if (discountPercent.value.signum() == 0 || discountDays == null || !grossAmount.isPositive()) {
        return null
    }
    val amount = Money.of(grossAmount.amount.multiply(discountPercent.value).divide(BigDecimal(100)))
    return CashDiscount(
        percent = discountPercent,
        days = discountDays,
        deadline = invoiceDate.plusDays(discountDays.toLong()),
        amount = amount,
        payableAmount = grossAmount - amount,
    )
}

/**
 * The discount condition source follows the same snapshot-wins-over-live rule as the payment
 * term's own name/due-in-days ([InvoicePaymentTermSnapshot]): a sent invoice's frozen
 * [Invoice.paymentTermSnapshot] is authoritative when present (a later edit to the master-data
 * [de.littletrotter.domain.masterdata.PaymentTerm] row must never leak into an already-sent
 * document); a draft without a snapshot falls back to the live [Invoice.paymentTerm]; no term at
 * all means no discount to offer. A storno ([InvoiceTypeCode.CORRECTED_INVOICE]) never carries a
 * discount — it has no payment block to begin with.
 */
fun Invoice.cashDiscount(): CashDiscount? {
    if (typeCode == InvoiceTypeCode.CORRECTED_INVOICE) return null
    val snapshot = paymentTermSnapshot
    val term = paymentTerm
    val (percent, days) = when {
        snapshot != null -> snapshot.discountPercent to snapshot.discountDays
        term != null -> term.discountPercent to term.discountDays
        else -> return null
    }
    return cashDiscountFor(invoiceDate, percent, days, totals.grossAmount)
}

/**
 * Early-payment discount (Skonto), computed once from a payment term's discount rate/day-count and
 * the invoice's own dates/gross amount — the domain-side counterpart of
 * [de.littletrotter.shared.InvoiceCashDiscountDto], which [InvoiceDtoMapper] fills from
 * [Invoice.cashDiscount].
 */
data class CashDiscount(
    val percent: DiscountPercent,
    val days: Int,
    val deadline: LocalDate,
    val amount: Money,
    val payableAmount: Money,
)

/** Reshapes this onto the wire/read-model DTO's plain [BigDecimal]/ISO-string fields, no further
 *  decision-making — shared by [de.littletrotter.application.invoice.InvoiceDtoMapper] (the NATS
 *  wire DTO) and the invoice detail read model, so the conversion exists exactly once. */
fun CashDiscount.toDto(): InvoiceCashDiscountDto = InvoiceCashDiscountDto(
    percent = percent.value,
    days = days,
    deadline = deadline.toString(),
    amount = amount,
    payableAmount = payableAmount,
)
