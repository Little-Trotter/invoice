package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.country.Country
import java.time.LocalDate

/**
 * Input port for reading B2B profiles: the catalogue tab's paged grid with usage counts and
 * "Verwendet von" list, and the profile selectors of the invoice header and the PDF-template tab.
 */
interface B2bProfileQueryPort {
    fun findB2bProfiles(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortDir: SortDirection = SortDirection.ASC,
    ): List<B2bProfileDto>

    fun countB2bProfiles(filterText: String? = null): Long

    /** The header counter's "N inaktiv" half. */
    fun countInactiveB2bProfiles(filterText: String? = null): Long

    fun findB2bProfileById(id: Long): B2bProfileDto?

    fun findAllActiveB2bProfiles(): List<B2bProfileDto>

    /** Usage counts of every profile (recipients' standing default), keyed by profile id; one
     *  grouped query, no per-row calls. */
    fun findB2bProfileUsageCounts(): Map<Long, Long>

    /** Names of every recipient that has [profileId] as standing default. */
    fun findB2bProfileUsageNames(profileId: Long): List<String>

    /** The countries a profile's country scope may name on [today]: the seller-registration
     *  countries plus Germany. */
    fun allowedB2bCountryScope(today: LocalDate): Set<Country>
}
