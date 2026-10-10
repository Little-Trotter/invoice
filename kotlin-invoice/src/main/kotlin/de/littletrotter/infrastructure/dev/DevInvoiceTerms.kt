/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.infrastructure.dev

import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.shared.ValidationStatus
import java.time.LocalDate

/**
 * The optional commercial terms (S107) of a [DevDataCatalogue.invoice] fixture, grouped apart
 * from its identity (status, parties, date) and its items. Every default is the one an ordinary
 * fixture needs; a fixture names only what it deviates in.
 *
 * - [dueDate]: PO decision 2 (2026-09-02) — nullable so the storno fixture can genuinely omit a
 *   due date, matching InvoiceStornoService.createCreditNote's own real behavior, instead of
 *   stamping one no real storno document would carry.
 * - [paymentTermsDays]: PO decision 2 (2026-09-02) — the Invoice-entity default (14) is right for
 *   every ordinary fixture; only the storno fixture overrides it to 0.
 * - [validationStatus]: validation-status-truthful-pending.md — these dev fixtures never run a
 *   real generation job, so the default means "never validated", not "a job is in flight".
 */
data class DevInvoiceTerms(
    val dueDate: LocalDate? = null,
    val reverseCharge: Boolean = false,
    val freeText: String? = null,
    val typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
    val originalInvoiceNumber: String? = null,
    val paymentTermsDays: Int = 14,
    val validationStatus: ValidationStatus = ValidationStatus.NOT_VALIDATED,
)
