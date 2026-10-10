package de.littletrotter.domain.invoice

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRateTerms
import java.time.LocalDate

/** The invoice-level facts [selectTripTaxRate] decides a trip item's tax rule from — bundled so
 *  that function takes one parameter for them instead of four (LongParameterList). */
data class TripTaxContext<R : TaxRateTerms>(
    val reverseCharge: Boolean,
    val sellerCountry: Country?,
    val invoiceDate: LocalDate?,
    val reverseChargeRate: R?,
)
