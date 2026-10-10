package de.littletrotter.spi.masterdata

/**
 * Extension point for an article's per-country MWST variants: a variable-length
 * (0..n) list of country+tax-rate rows, docked into the article master-data form. Same repeatable-
 * row shape as [de.littletrotter.spi.party.SellerVatRegistrationsExtension], just for
 * a different owning entity — kept as its own interface rather than a shared generic one, so each
 * stays a small, concrete contract instead of a leaky abstraction over two unrelated domain
 * shapes.
 */
interface ArticleTaxVariantsExtension {

    /** Stable identifier of this module (module-scope unique). */
    val id: String

    /** The curated set of country codes (ISO 3166-1 alpha-2) a variant row may use. Informational
     *  for the view (e.g. to populate a country dropdown); the article save re-validates the rows
     *  against the domain rules. */
    val supportedCountries: List<String>
}
