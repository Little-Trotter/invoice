package de.littletrotter.spi.form

import de.littletrotter.shared.InvoiceDto
import java.util.Locale

/**
 * The minimal context a field-[InvoiceFormModule] needs to bind its fields, without exposing the
 * Vaadin view itself. It carries the shared message model ([InvoiceDto]) rather than a domain
 * entity, so every module works against the same contract.
 *
 * @param invoice the invoice currently on screen, in the shared wire format.
 * @param readOnly whether the invoice is finalized/cancelled and must not be edited.
 * @param locale the UI locale, for date/number formatting.
 */
data class FormContext(
    val invoice: InvoiceDto,
    val readOnly: Boolean,
    val locale: Locale,
)
