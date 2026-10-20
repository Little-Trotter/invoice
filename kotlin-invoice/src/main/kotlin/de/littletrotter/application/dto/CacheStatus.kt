package de.littletrotter.application.dto

/** Reachability of the cache and the key counts of its PDF and query databases. */
data class CacheStatus(
    val valkeyOnline: Boolean,
    val pdfCacheKeys: Long,
    val queryCacheKeys: Long,
)
