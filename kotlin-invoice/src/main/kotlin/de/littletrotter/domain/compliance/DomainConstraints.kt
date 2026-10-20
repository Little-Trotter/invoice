/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.domain.compliance

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.reference.TravelExpenseEntry
import java.time.LocalDate

/**
 * Thin delegation façade kept for backward compatibility.
 * All validation logic now lives on the entities themselves.
 * Prefer calling entity.validate() directly.
 */
object DomainConstraints {

    fun validateInvoice(invoice: de.littletrotter.domain.invoice.Invoice) = invoice.validate()

    fun validateInvoiceItem(item: InvoiceItem) = item.validate()

    fun validatePaymentTerm(term: PaymentTerm) = term.validate()

    fun validateReceipt(receipt: Receipt) = receipt.validate()

    fun validateTaxRate(rate: TaxRate) = rate.validate()

    fun validatePerDiemRate(rate: PerDiemRate) = rate.validate()

    fun validateTravelExpenseEntry(entry: TravelExpenseEntry) = entry.validate()

    fun validateArticle(article: Article, today: LocalDate) = article.validate(today)

    fun validateAddress(address: Address) = address.validate()
}
