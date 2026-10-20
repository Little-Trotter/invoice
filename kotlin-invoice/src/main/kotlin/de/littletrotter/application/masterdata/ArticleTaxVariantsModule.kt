package de.littletrotter.application.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.spi.masterdata.ArticleTaxVariantsExtension
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * An article's per-country MWST variants (0..n rows: country + tax rate), curated to
 * [Country.supportedSellerVatRegistrationCountries]. The rows themselves travel on the
 * article's read model and write command; this module only contributes the country set.
 */
@ApplicationScoped
class ArticleTaxVariantsModule @Inject constructor(time: TimeSource) : ArticleTaxVariantsExtension {

    override val id: String = "articleTaxVariants"
    override val supportedCountries: List<String> =
        Country.supportedSellerVatRegistrationCountries(time.today()).map { it.alpha2 }.sorted()
}
