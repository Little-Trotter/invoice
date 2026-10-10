package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRateTerms
import java.time.LocalDate

/**
 * Every trip uses the seller registration's reference; reverse charge takes priority. Generic over
 * the rule's representation so the stored rows (when saving) and the read rows (the trip dialog's
 * preview) are decided by this one rule.
 */
fun <R : TaxRateTerms> selectTripTaxRate(
    context: TripTaxContext<R>,
    referenceLookup: (Country, LocalDate) -> R,
): R {
    if (context.reverseCharge) {
        return context.reverseChargeRate
            ?: throw InvalidParameterException("Für die Dienstreise fehlt die Reverse-Charge-Steuerregel.")
    }
    val date = context.invoiceDate
        ?: throw InvalidParameterException("Für den Referenzsteuersatz ist ein Rechnungsdatum erforderlich.")
    return referenceLookup(context.sellerCountry ?: Country.DE, date)
}
