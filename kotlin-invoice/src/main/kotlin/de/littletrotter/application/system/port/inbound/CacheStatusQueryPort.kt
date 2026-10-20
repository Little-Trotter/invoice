package de.littletrotter.application.system.port.inbound

import de.littletrotter.application.dto.CacheStatus

/** Input port for the System screen's cache reachability and key counts. */
fun interface CacheStatusQueryPort {
    /** Whether the cache answers and how many keys the PDF and query caches hold. */
    fun fetchStatus(): CacheStatus
}
