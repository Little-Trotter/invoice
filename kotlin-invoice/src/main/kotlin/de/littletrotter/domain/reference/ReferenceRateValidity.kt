package de.littletrotter.domain.reference

import de.littletrotter.shared.country.Country
import java.time.LocalDate

/**
 * R2 — shared date-range- and region-aware applicability check for reference-rate entities keyed
 * by country/region/validity window ([HotelRate], [PerDiemRate]): a rate applies when it is
 * active, matches the requested country, matches the requested region (or carries no region of
 * its own, i.e. a country-wide catch-all row), and the requested date falls within
 * [validFrom]/[validTo] (an unset bound is open-ended on that side).
 */
interface ReferenceRateValidity {
    val country: Country?
    val region: String?
    val validFrom: LocalDate?
    val validTo: LocalDate?
    val isActive: Boolean

    fun matchesRegion(requestRegion: String?): Boolean {
        val own = region?.trim()?.uppercase().orEmpty()
        val req = requestRegion?.trim()?.uppercase().orEmpty()
        return own.isBlank() || own == req
    }

    fun isApplicableFor(requestCountry: Country, requestRegion: String?, date: LocalDate): Boolean {
        val countryMatches = country == requestCountry
        val regionMatches = matchesRegion(requestRegion)
        val startsBeforeOrAt = validFrom == null || !validFrom!!.isAfter(date)
        val endsAfterOrAt = validTo == null || !validTo!!.isBefore(date)
        return isActive && countryMatches && regionMatches && startsBeforeOrAt && endsAfterOrAt
    }
}
