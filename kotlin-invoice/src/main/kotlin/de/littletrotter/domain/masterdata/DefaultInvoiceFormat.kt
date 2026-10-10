package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.InvoiceFormat

/**
 * PO order 04.09.2026 — the country-driven preselection for [Recipient.invoiceFormat]: a customer
 * in a German-business-language country (DE/AT/CH, [Country.GERMAN_BUSINESS_LANGUAGE_COUNTRIES])
 * is billed with the German document family, everyone else with the international one. An unknown
 * country falls back to [InvoiceFormat.GERMAN], the same direction every other default in this
 * code base leans.
 *
 * A preselection, never an invariant: [de.littletrotter.ui.view.masterdata.RecipientView] applies
 * it while the customer dialog is open and the user can override it, and nothing re-derives the
 * stored value afterwards (PO decision 04.09.2026). Lives here rather than inline in the view so
 * the rule has exactly one home for the view, the demo data generator and the tests.
 */
fun defaultInvoiceFormatFor(country: Country?): InvoiceFormat =
    if (country == null || country in Country.GERMAN_BUSINESS_LANGUAGE_COUNTRIES) {
        InvoiceFormat.GERMAN
    } else {
        InvoiceFormat.INTERNATIONAL
    }
