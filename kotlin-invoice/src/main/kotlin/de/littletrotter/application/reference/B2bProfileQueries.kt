package de.littletrotter.application.reference

import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.country.Country
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.LocalDate

/** Implements [B2bProfileQueryPort] on top of [B2bProfileLifecycleService] and [B2bProfileUsageService]. */
@ApplicationScoped
class B2bProfileQueries @Inject constructor(
    private val lifecycleService: B2bProfileLifecycleService,
    private val usageService: B2bProfileUsageService,
) : B2bProfileQueryPort {

    override fun findB2bProfiles(pageIndex: Int, pageSize: Int, filterText: String?, sortDir: SortDirection): List<B2bProfileDto> =
        lifecycleService.findProfiles(pageIndex, pageSize, filterText, sortDir).map { it.toDto() }

    override fun countB2bProfiles(filterText: String?): Long = lifecycleService.countProfiles(filterText)

    override fun countInactiveB2bProfiles(filterText: String?): Long = lifecycleService.countInactiveProfiles(filterText)

    override fun findB2bProfileById(id: Long): B2bProfileDto? = lifecycleService.findProfileById(id)?.toDto()

    override fun findAllActiveB2bProfiles(): List<B2bProfileDto> = lifecycleService.findAllActiveProfiles().map { it.toDto() }

    override fun findB2bProfileUsageCounts(): Map<Long, Long> = usageService.usageCounts()

    override fun findB2bProfileUsageNames(profileId: Long): List<String> = usageService.usageNames(profileId)

    override fun allowedB2bCountryScope(today: LocalDate): Set<Country> = B2bProfile.allowedCountryScope(today)
}
