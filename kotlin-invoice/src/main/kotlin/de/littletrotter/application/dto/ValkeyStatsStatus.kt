package de.littletrotter.application.dto

/**
 * Valkey usage statistics: key families and total count.
 * Fetched using non-blocking SCAN + MATCH patterns, not KEYS *.
 * On unreachable: all counts zero, reachable=false (exceptions not surfaced).
 */
class ValkeyStatsStatus(
    val reachable: Boolean,
    val families: List<KeyFamilyCount> = emptyList(),
    val totalKeys: Long = 0,
    val usedMemoryHuman: String? = null,
) {
    class KeyFamilyCount(
        val family: String,
        val count: Long,
    )
}
