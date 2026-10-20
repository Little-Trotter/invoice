package de.littletrotter.application.system.port.outbound

import de.littletrotter.application.dto.ValkeyStatsStatus

/**
 * Application-layer port for querying Valkey usage by key family.
 * Used to display key usage in the system view, helping operators understand cache load.
 */
fun interface ValkeyStatsService {
    fun getStats(): ValkeyStatsStatus
}
