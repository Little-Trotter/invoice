package de.littletrotter.application.system

import de.littletrotter.application.system.port.inbound.ValkeyStatsQueryPort
import de.littletrotter.application.system.port.outbound.ValkeyStatsService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.application.dto.ValkeyStatsStatus

/** Implements [ValkeyStatsQueryPort] on top of the cache statistics adapter. */
@ApplicationScoped
class ValkeyStatsQueries @Inject constructor(
    private val statsService: ValkeyStatsService,
) : ValkeyStatsQueryPort {
    override fun getStats(): ValkeyStatsStatus = statsService.getStats()
}
