/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.application.invoice

import de.littletrotter.application.reference.NumberRangeService
import de.littletrotter.domain.compliance.NumberJournalEntities
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.AllocatedNumber
import de.littletrotter.domain.reference.NumberContext
import de.littletrotter.domain.reference.NumberRangeSubject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.LocalDate

/**
 * Invoice numbers come from the `INVOICE` number range ([numberRangeService]).
 *
 * The return type is deliberately [AllocatedNumber], not just `InvoiceNumber`: the caller must
 * persist prefix and counter separately on the invoice.
 *
 * [generateNextNumber] takes the date the number is drawn for instead of reading a clock: it is
 * the invoice date, so the year in the number and the year the range counts in always match the
 * date printed on the invoice — even for a document sent in the last second of a year.
 */
@ApplicationScoped
class InvoiceNumberService @Inject constructor(
    private val numberRangeService: NumberRangeService,
) {

    fun generateNextNumber(sender: SenderProfile, date: LocalDate, invoiceId: Long? = null): AllocatedNumber {
        val senderId = sender.id ?: throw IllegalArgumentException("SenderProfile must be persisted to allocate a number.")
        val prefix = sender.invoicePrefix.trim().trimEnd('-')

        val context = NumberContext(
            date = date, sellerId = senderId, sellerPrefix = prefix,
            entityType = NumberJournalEntities.INVOICE, entityId = invoiceId,
        )
        return numberRangeService.allocate(NumberRangeSubject.INVOICE, context)
    }
}
