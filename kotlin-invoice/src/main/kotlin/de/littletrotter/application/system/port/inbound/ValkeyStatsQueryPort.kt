package de.littletrotter.application.system.port.inbound

import de.littletrotter.application.dto.ValkeyStatsStatus

/** Input port for the System screen's cache usage by key family. */
fun interface ValkeyStatsQueryPort {
    /** Key counts per cache family, or the unreachable state. */
    fun getStats(): ValkeyStatsStatus
}
