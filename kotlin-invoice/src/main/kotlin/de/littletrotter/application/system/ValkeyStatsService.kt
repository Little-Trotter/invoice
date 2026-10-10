package de.littletrotter.application.system

/**
 * Application-layer port for querying Valkey usage by key family.
 * Used to display key usage in the system view, helping operators understand cache load.
 */
fun interface ValkeyStatsService {
    fun getStats(): ValkeyStatsStatus
}
