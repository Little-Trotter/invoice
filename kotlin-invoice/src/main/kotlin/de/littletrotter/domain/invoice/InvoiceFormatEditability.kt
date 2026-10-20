package de.littletrotter.domain.invoice

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.InvoiceFormat

/**
 * R-CB — whether [Invoice.invoiceFormat] may currently be freely chosen: either a
 * non-German seller VAT registration is active, or the invoice is itself a
 * cross-border EU B2B Reverse-Charge invoice (R-CB) — both are contexts where an English document
 * is exactly as sensible a choice as the German default. Any other combination forces
 * [InvoiceFormat.GERMAN].
 *
 * Stays in `domain.invoice` while the enum itself lives in `domain.shared`: the
 * predicate needs [Country], and pulling masterdata into `domain.shared` would close a
 * shared↔masterdata loop the enum move was meant to avoid.
 *
 * The single source of truth for this predicate: `InvoiceLifecycleService.updateHeader` enforces
 * it server-side on every save, and `InvoiceFormatModule` (the entry pane's `ComboBox`) calls this
 * exact function to decide whether that field is enabled — the two must never diverge, so neither
 * re-derives the rule locally.
 */
fun isInvoiceFormatEditable(sellerVatRegistrationCountry: Country?, reverseCharge: Boolean): Boolean =
    sellerVatRegistrationCountry != null || reverseCharge
