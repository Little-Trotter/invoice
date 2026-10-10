package de.littletrotter.application.dto

import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Money
import java.time.LocalDate

/**
 * One row of the hotel-rate catalogue: what the catalogue grid shows and what its editor saves.
 * [id] `null` marks a new row; [version] is the row's optimistic-lock revision, checked on save.
 */
data class HotelRateDto(
    val id: Long? = null,
    val version: Long? = null,
    val country: Country? = null,
    val region: String? = null,
    val ratePerNight: Money = Money.ZERO,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val isActive: Boolean = true,
    val source: String? = null,
)
