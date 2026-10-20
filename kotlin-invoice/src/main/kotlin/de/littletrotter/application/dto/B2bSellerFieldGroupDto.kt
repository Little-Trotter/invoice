package de.littletrotter.application.dto

import de.littletrotter.shared.country.Country

/**
 * One seller-form B2B field after merging the fields of every active profile: the [field] itself,
 * the country [group] it renders under (`null` = the unscoped group shown to every seller) and the
 * [triggerCountries] of every profile that contributes it, which decide whether a country group is
 * visible for the current seller.
 */
data class B2bSellerFieldGroupDto(
    val field: B2bProfileFieldDto,
    val group: Country?,
    val triggerCountries: Set<Country>,
)
