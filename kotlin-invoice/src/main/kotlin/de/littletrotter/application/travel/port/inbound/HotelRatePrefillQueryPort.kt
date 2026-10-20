package de.littletrotter.application.travel.port.inbound

import de.littletrotter.shared.Money
import de.littletrotter.shared.country.Country
import java.time.LocalDate

/** Looks up the hotel rate per night that pre-fills a trip expense row. */
fun interface HotelRatePrefillQueryPort {

    /** The active per-night rate for [country] on [date], `null` when none is configured. */
    fun ratePerNight(country: Country, date: LocalDate): Money?
}
