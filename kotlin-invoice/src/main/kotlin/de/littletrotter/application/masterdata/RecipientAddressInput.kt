package de.littletrotter.application.masterdata

/**
 * The billing and optional delivery address a recipient is saved with, as the UI
 * collects them (ISO alpha-2 country codes, free text). Part of
 * [de.littletrotter.application.dto.RecipientDraft] and command input for
 * [CustomerManagementService.saveWithAddresses].
 *
 * A delivery address exists only when [deliveryStreet] is non-null; its missing postal code/city
 * then persist as empty strings and a missing [deliveryCountry] as `DE` — the same fallbacks the
 * former positional parameters had.
 */
data class RecipientAddressInput(
    val billingStreet: String,
    val billingPostalCode: String,
    val billingCity: String,
    val billingCountry: String = "DE",
    val deliveryStreet: String? = null,
    val deliveryPostalCode: String? = null,
    val deliveryCity: String? = null,
    val deliveryCountry: String? = null,
) {
    /** Street, postal code and city trimmed (billing and delivery); countries are codes and stay
     *  as given. A `null` delivery line stays `null`. */
    fun trimmed(): RecipientAddressInput = copy(
        billingStreet = billingStreet.trim(),
        billingPostalCode = billingPostalCode.trim(),
        billingCity = billingCity.trim(),
        deliveryStreet = deliveryStreet?.trim(),
        deliveryPostalCode = deliveryPostalCode?.trim(),
        deliveryCity = deliveryCity?.trim(),
    )
}
