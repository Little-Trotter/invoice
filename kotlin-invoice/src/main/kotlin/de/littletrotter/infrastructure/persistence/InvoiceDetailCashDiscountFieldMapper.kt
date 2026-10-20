package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.cashDiscountFor
import de.littletrotter.domain.invoice.toDto
import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.shared.InvoiceCashDiscountDto
import de.littletrotter.shared.Money
import jakarta.persistence.Tuple
import java.time.LocalDate

/**
 * Reproduces [de.littletrotter.domain.invoice.Invoice.cashDiscount]'s snapshot-wins-over-live rule
 * from the header tuple projection alone, without loading a domain `Invoice` for a read-only
 * detail view: the frozen payment-term snapshot's discount pair when
 * [InvoiceReadRepositoryImpl]'s `hasPaymentTermSnapshot` column says one was frozen, else the live
 * `PaymentTerm` association's pair (`null` when no term at all is chosen, the same "nothing to
 * offer" case [cashDiscountFor] itself returns `null` for). A cancellation
 * ([InvoiceTypeCode.CORRECTED_INVOICE]) never carries a discount — same as the domain rule, it has
 * no payment block to begin with.
 */
internal object InvoiceDetailCashDiscountFieldMapper {

    fun map(header: Tuple, typeCode: Int?, invoiceDate: LocalDate?, grossAmount: Money?): InvoiceCashDiscountDto? {
        if (typeCode == InvoiceTypeCode.CORRECTED_INVOICE.ordinal) return null
        val date = invoiceDate ?: return null
        val gross = grossAmount ?: return null
        val hasSnapshot = header.get("hasPaymentTermSnapshot") as? Boolean ?: false
        val (percentRaw, daysRaw) = if (hasSnapshot) {
            header.get("snapshotDiscountPercent") to header.get("snapshotDiscountDays")
        } else {
            header.get("termDiscountPercent") to header.get("termDiscountDays")
        }
        val percent = InvoiceDetailRowSupport.parseBigDecimal(percentRaw) ?: return null
        val days = (daysRaw as? Number)?.toInt()
        return cashDiscountFor(date, DiscountPercent(percent), days, gross)?.toDto()
    }
}
